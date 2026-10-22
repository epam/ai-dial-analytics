# query-schema-discovery Specification

## Purpose

The structured query capability (see [structured-query](../structured-query/spec.md)) is
body-delivered: a client posts an envelope naming an `entity` and flat field names in its
`filter`/`select`/`sort`/`group_by` sections. To compose a valid query the client must first discover
which entities are queryable and which flat field names each one exposes.

This capability defines the read-only discovery surface under the `/v1/queries` namespace: an entity
catalog and an instance-independent base schema, plus the discovery error contract. Queryable entities
and their flat field schemas are derived from the table-management catalog (see
[table-management](../table-management/spec.md)): each `ACTIVE` `source` table is a queryable
entity — of either write discipline, so a rollup written `upsert_by_key` is queryable like any other
source — and every attached enrichment's columns are flattened into that source's schema under
table-qualified names. Enrichment tables are not themselves queryable.
## Requirements
### Requirement: Queryable entity catalog

The system SHALL expose, at `GET /v1/queries/entities`, the list of entities that can be named as the
`entity` of a structured query. Queryable entities SHALL be derived from the table-management catalog:
each `ACTIVE` `source` table is one queryable entity, of either write discipline — an `upsert_by_key`
source (a rollup target) is listed exactly like an `append` source, with no separate table kind for
aggregation. Enrichment tables SHALL
NOT be listed as queryable entities — enrichments are surfaced only as flattened columns on their
source (see the base-schema requirement). Each catalog item SHALL carry the entity wire `name` and a
boolean `supports_final`: `true` when the entity is final-capable — its source is `upsert_by_key`, or it has
an active enrichment the caller may read (`TABLE_READ`, the bar execution resolves the entity at, whatever
bar the listing itself filters at) — and `false` otherwise, so a caller can tell where a structured
query's `"final": true` and a SQL `FINAL` are legal and where they are rejected with `illegal_final` (see
[structured-query](../structured-query/spec.md) "Read result deduplication"). `supports_final` is also what
an absent `final` resolves to. It SHALL NOT reveal an enrichment the caller cannot read, and an enrichment
the caller may list or get but not read SHALL NOT make it `true`.
Entities SHALL be listed in stable alphabetical order by name.

#### Scenario: Catalog lists queryable source entities

- **WHEN** `GET /v1/queries/entities` is called
- **THEN** the response lists each materialized `source` table as a queryable entity in alphabetical
  order, including `dial_usage_log`, and does not list enrichment tables

#### Scenario: An upsert-by-key source is listed

- **WHEN** an `upsert_by_key` source is materialized and `GET /v1/queries/entities` is called
- **THEN** it appears in the list in alphabetical order alongside the `append` sources, and
  `GET /v1/queries/entities/schema/{name}` returns its flat schema

#### Scenario: Catalog items report whether FINAL is supported

- **WHEN** `GET /v1/queries/entities` is called with `dial_usage_log` (`upsert_by_key`), an `append` source
  with no enrichment, and an `append` source with an enrichment materialized
- **THEN** the `dial_usage_log` item and the enriched `append` source's item carry `"supports_final": true`,
  and the unenriched `append` source's item carries `"supports_final": false`

#### Scenario: An enrichment the caller cannot read does not make the entity final-capable

- **WHEN** a caller holds `list` and `get` but not `read` on the only enrichment of an `append` source it may
  read, and calls `GET /v1/queries/entities`
- **THEN** that source's item carries `"supports_final": false`, matching execution, where the enrichment is
  not joined and `"final": true` is rejected with `illegal_final`

### Requirement: Instance-independent base schema

The system SHALL expose, at `GET /v1/queries/entities/schema/{name}`, the entity's flat schema sourced
from the table-management catalog. The flat schema SHALL be the union of the source table's exposed
columns and every attached enrichment's exposed columns; foreign-key (join) columns SHALL be hidden
and SHALL NOT appear. The source's columns SHALL be exposed under their bare exposed `name`, while each
enrichment column SHALL be exposed under a table-qualified flat name `<enrichment_table>.<name>` (dot
separator) so that a source column and an enrichment column — or two enrichments — may share the same
bare `name` without colliding. Because table and column names match `^[a-z][a-z0-9_]*$` (no dot),
qualified names are unambiguous and unique by construction. Each field SHALL carry its flat `name` (bare
for source columns, qualified for enrichment columns), its flat field type, for an `array`-typed field
its `element_type` (absent/null for non-array fields), the bare physical `source`
column it maps to, the column's optional user-friendly `display_name` (null when unset), the column's
optional identifying `tag` (null when unset), the column's `sensitive` flag (default false), and the
column's `heavy` flag (default false).

The `heavy` flag SHALL tell the client which fields a **wildcard** projection omits — an omitted
row-mode `select` on `POST /v1/queries/execute`, or a SQL `SELECT *` — so the cost of the default
"give me everything" query is discoverable before it is issued (see the `structured-query` capability's
"Wildcard projection excludes heavy columns"). Unlike `sensitive`, `heavy` SHALL NOT be role-filtered
and SHALL NOT hide the field: it is a transfer-cost hint, so the field is returned to every caller who
may see it, carrying the flag, and stays selectable by name.

The returned schema SHALL be filtered per owning table: for a caller holding no
`TABLE_SENSITIVE` on the table that owns a given column,
fields whose column is `sensitive` SHALL be omitted from the response entirely (hidden, not merely
flagged), so such a caller cannot tell a sensitive field from a non-existent one. For an entitled
caller — or when security is disabled (`security.mode=none`) — all fields SHALL be returned, each
carrying its `sensitive` flag so an admin can see which columns are restricted.

#### Scenario: Base schema lists the entity's flat fields

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log` is called
- **THEN** the response lists the entity's fields with their mapped types — e.g. `project_id` typed
  `string`, `total_tokens` typed `long`, `request_time` typed `timestamp`, and `execution_path` typed
  `array`

#### Scenario: Array fields carry their element type

- **WHEN** `GET /v1/queries/entities/schema/{source}` is called for an entity with an `array`-typed
  column (e.g. `dial_usage_log.execution_path`)
- **THEN** the corresponding flat field carries `type: "array"` and its `element_type` (e.g.
  `"string"`), while non-array fields carry a null/absent `element_type`

#### Scenario: Flat fields carry their column display name

- **WHEN** a source (or one of its enrichments) has a column with a `display_name` and
  `GET /v1/queries/entities/schema/{source}` is called
- **THEN** the corresponding flat field carries that `display_name`, while fields whose columns have no
  display name carry a null `display_name`

#### Scenario: Flat fields carry their column tag

- **WHEN** a source (or one of its enrichments) has a column with an identifying `tag` and
  `GET /v1/queries/entities/schema/{source}` is called
- **THEN** the corresponding flat field carries that `tag`, while fields whose columns have no tag carry
  a null `tag`

#### Scenario: Sensitive fields are hidden from non-admin callers

- **WHEN** a caller without `TABLE_SENSITIVE` on the owning table calls
  `GET /v1/queries/entities/schema/{source}` for an
  entity that has one or more sensitive columns
- **THEN** the sensitive fields are absent from the returned field list, while all non-sensitive fields
  are present

#### Scenario: Sensitive fields are visible to FULL_ADMIN and carry the flag

- **WHEN** a caller holding `TABLE_SENSITIVE` on the owning table (or any caller when
  `security.mode=none`) calls
  `GET /v1/queries/entities/schema/{source}`
- **THEN** the response includes the sensitive fields, each carrying `sensitive: true`, and
  non-sensitive fields carry `sensitive: false`

#### Scenario: Flat fields carry their column heavy flag

- **WHEN** a source (or one of its enrichments) has a column marked `heavy` and
  `GET /v1/queries/entities/schema/{source}` is called
- **THEN** the corresponding flat field carries `heavy: true`, while every other field carries
  `heavy: false`

#### Scenario: Heavy fields are visible to non-admin callers

- **WHEN** a caller without `TABLE_SENSITIVE` on the owning table calls
  `GET /v1/queries/entities/schema/{source}` for an entity with a non-sensitive `heavy` column
- **THEN** the field is present in the response with `heavy: true` — heaviness never hides a field —
  and the caller may select it by name

#### Scenario: Enrichment columns are flattened into the source schema

- **WHEN** a source has one or more attached enrichments and
  `GET /v1/queries/entities/schema/{source}` is called
- **THEN** the response lists the source's exposed columns under their bare `name` plus every
  enrichment's exposed columns under their table-qualified flat name `<enrichment_table>.<name>` as a
  single flat field list, excluding the enrichment foreign-key columns, and each enrichment field's
  `source` is the bare physical column name

#### Scenario: Two enrichments exposing the same column name are both surfaced

- **WHEN** a source has two enrichments that each expose a column with the same bare `name` and
  `GET /v1/queries/entities/schema/{source}` is called
- **THEN** the response lists both as distinct qualified flat fields (`<enrichment_a>.<name>` and
  `<enrichment_b>.<name>`) rather than rejecting the second enrichment

### Requirement: Supported function catalog discovery

The system SHALL expose, at `GET /v1/queries/functions`, the closed catalog of functions accepted in
structured query expressions (see [structured-query](../structured-query/spec.md), "Supported
function catalog" and "Window-only functions extend the catalog"). The response SHALL be a JSON array with
one entry per supported function, in stable alphabetical order by function name. Each entry SHALL carry:

- `name` — the function's wire name as accepted in a `fn` expression;
- `group` — one of `scalar`, `aggregate`, `ordered_set_aggregate`, `window`; `window` marks a function
  accepted only as the `fn` of a `window` expression;
- `signature` — a human-readable signature string (e.g. `date_bin(amount, unit, timestamp)`);
- `returns` — one of `string`, `integer`, `long`, `numeric`, `boolean`, `timestamp`, `array`,
  `same_as_argument`, `element_of_argument`; `array` names the shape only — the element type follows
  the operand, as `same_as_argument` does for the scalar case. `element_of_argument` is the inverse of
  `array`: the result is the **element** type of the function's first argument, which is what
  `array_join` returns, and neither `array` nor `same_as_argument` can name it;
- `distinct_supported` — whether the `distinct` flag is meaningful for the function. Where it is
  `false` for an aggregate, the entry's `description` SHALL state **why** the quantifier does not apply
  (a no-op for `min`/`max`, a different statistic for the percentiles, already-distinct for
  `group_uniq_array`, operand-detaching for `arg_max`), so a client can distinguish a deliberate
  exclusion from an unimplemented one;
- `deterministic` — whether two evaluations of the function over the same input rows are guaranteed to
  produce the same value. It SHALL be present on **every** entry rather than only where it is `false`,
  because a client reading a catalog that omits it cannot tell "deterministic" from "an older service
  that did not say". Where it is `false`, the entry's `description` SHALL say what the value depends
  on. The property is what the derivation paths refuse a function by (see
  [structured-query](../structured-query/spec.md), "The catalog declares which functions are
  non-deterministic"), so publishing it lets a client predict a pipeline rejection from discovery
  alone instead of discovering it on submission;
- `description` — a short human-readable description;
- `args` — the ordered argument list; each argument SHALL carry its `name`, its `kind` (one of
  `expression`, `integer_literal`, `numeric_literal`, `string_literal`, `lambda`), an `optional` flag when the
  argument may be omitted (e.g. `count`'s single argument), a `variadic` flag when the argument may
  be repeated (e.g. a `json_extract_*` path key), and — only when applicable — a `constraints` object
  with `allowed_values` (closed value set) and/or `min`/`max` (inclusive numeric bounds). Inapplicable
  members SHALL be omitted from the JSON rather than serialized as null.

The advertised catalog SHALL match what the query translator accepts: every listed function with a
conforming argument list SHALL be accepted by query validation, and the advertised constraint values
(e.g. `date_bin` units, `percentile_cont`/`percentile_disc` fraction bounds) SHALL equal the values
the translator enforces.

#### Scenario: Function catalog lists all supported functions

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** the response lists exactly the functions of the structured-query supported function
  catalog — including `date_bin`, `width_bucket`, `count`, `sum`, `avg`, `min`, `max`,
  `arg_max`, `group_uniq_array`, `percentile_cont`, `percentile_disc`, `lower`, `upper`, `length`, `trim`,
  `abs`, `json_extract_string`, `json_extract_long`, `json_extract_double`, `json_extract_bool`,
  `json_extract_string_ci`, `json_extract_array`, `if`, `not_empty`, `starts_with`, `contains`,
  `equals`, `plus`, `minus`, `multiply`, `div`, `modulo`, `round`, `floor`, `ceil`, `map_value`,
  `map_contains`, `map_keys`, `array_has`,
  `array_has_any`, `array_has_all`, `array_join`, `array_length`, `array_slice`, `array_to_string`,
  `concat`, `to_string`, `split_string`, `now`, `date_add`, `date_sub`, `date_trunc`, `match`, `arrayExists`,
  `arrayAll`, `arrayCount`, `arrayFilter`, `arrayMap`, `groupUniqArrayArray`, `groupArrayArray`,
  `row_number`, `rank`
  and `dense_rank` —
  in alphabetical order, each with its `group`, `signature`, `returns`, `distinct_supported`,
  `deterministic`, `description`, and `args`

#### Scenario: The window-only functions are discoverable

- **WHEN** a client lists the function catalog
- **THEN** `row_number`, `rank` and `dense_rank` each appear in the `window` group with no arguments, a
  `returns` of `long`, `distinct_supported` false, and a `description` stating that the function is
  accepted only inside a `window` expression (an `OVER` clause in SQL)

#### Scenario: The arithmetic scalars are discoverable

- **WHEN** a client lists the function catalog
- **THEN** `plus`, `minus`, `multiply`, `div` and `modulo` each appear in the scalar group with two
  required expression arguments and a `returns` of `numeric`, and the `description` of `div` and of
  `modulo` each state that a zero or null right operand yields null rather than an error, so a client
  can tell the contract from the catalog alone. `modulo`'s `description` additionally states that the
  result takes the sign of the left operand
- **AND** each of the five `description`s states that the name is **not callable in SQL**, names the
  operator to write there instead (`+`, `-`, `*`, `/`, `%`), and points at `GET /v1/queries/sql-functions`
  for the SQL spellings — none of them says the name is "also" spelled with the operator, which would
  imply the function name works in SQL too

#### Scenario: The rounding scalars advertise their optional precision

- **WHEN** a client lists the function catalog
- **THEN** `round` declares a second `digits` argument of kind `integer_literal` marked `optional`,
  while `floor` and `ceil` each declare a single required expression argument, all three with a
  `returns` of `numeric`

#### Scenario: The map accessors advertise a single literal key

- **WHEN** a client lists the function catalog
- **THEN** `map_value` (`returns` `string`) and `map_contains` (`returns` `boolean`) each appear in the
  scalar group declaring `map` as an `expression` and `key` as a `string_literal` that is **not** marked
  `variadic` — distinguishing them from the `json_extract_*` entries, whose path keys are variadic —
  and each `description` states that the key is matched literally rather than as a path

#### Scenario: The map key lister advertises an array return and single arity

- **WHEN** a client lists the function catalog
- **THEN** `map_keys` appears in the scalar group with a `returns` of `array` and exactly one
  `expression` argument, so a client can tell from the catalog alone that its result is projection-only
  as `json_extract_array`'s is

#### Scenario: The array predicates advertise a boolean return

- **WHEN** a client lists the function catalog
- **THEN** `array_has`, `array_has_any` and `array_has_all` each appear in the scalar group with a
  fixed `returns` of `boolean`, so a client can tell they may supply `if`'s condition, and
  `array_length` appears with a `returns` of `integer`

#### Scenario: The string splitter advertises an array return

- **WHEN** a client lists the function catalog
- **THEN** `split_string` appears in the scalar group with a `returns` of `array`, declaring `text` as
  an `expression` and `delimiter` as a `string_literal`

#### Scenario: A refused distinct quantifier carries its reason

- **WHEN** a client lists the function catalog
- **THEN** each aggregate carrying `distinct_supported` false — `min`, `max`, `percentile_cont`,
  `percentile_disc`, `group_uniq_array`, `arg_max` — carries a `description` naming why the quantifier
  does not apply to it

#### Scenario: The conditional and its predicate producers are discoverable

- **WHEN** a client lists the function catalog
- **THEN** `if` appears in the scalar group with signature `if(condition, then, else)` and a return
  type of same-as-argument, and `not_empty`, `starts_with`, `contains` and `equals` each appear in
  the scalar group with a fixed `returns` of `boolean`, so a client can tell from the catalog alone
  which functions may supply `if`'s condition

#### Scenario: The case-insensitive extractor advertises its fixed arity

- **WHEN** a client lists the function catalog
- **THEN** `json_extract_string_ci` declares `json` as an `expression` and `key` as a
  `string_literal` that is **not** marked `variadic`, distinguishing it from the other
  `json_extract_*` entries, and carries a fixed `returns` of `string`

#### Scenario: The array extractor advertises an array return

- **WHEN** a client lists the function catalog
- **THEN** `json_extract_array` appears in the scalar group with a `returns` of `array`, declaring
  `json` as an `expression` and `key` as a `variadic` `string_literal`

#### Scenario: date_bin entry advertises its literal constraints

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** the `date_bin` entry's `args` declare `amount` as an `integer_literal` with `min` 1,
  `unit` as a `string_literal` whose `allowed_values` are exactly the units accepted by the
  translator (`second`, `minute`, `hour`, `day`, `week`), and `timestamp` as an `expression`

#### Scenario: Aggregate entries advertise distinct support

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** `count`, `sum`, and `avg` carry `distinct_supported: true`, and `count`'s single argument
  is marked `optional` (arity 0 or 1), while scalar functions carry `distinct_supported: false`

#### Scenario: The path-addressable json_extract entries advertise a variadic literal path

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** each of `json_extract_string`, `json_extract_long`, `json_extract_double` and
  `json_extract_bool` declares `json` as an `expression` and `key` as a `string_literal` marked
  `variadic`, and carries a fixed `returns` of `string`, `long`, `numeric`, or `boolean` respectively —
  the two later members of the family differ on both counts and are covered by their own scenarios
  above (`json_extract_string_ci`'s `key` is not variadic; `json_extract_array` returns `array`)

#### Scenario: arg_max is discoverable with its two-argument signature
- **WHEN** a client lists the function catalog
- **THEN** `arg_max` appears in the aggregate group with signature `arg_max(value, order)`, two required
  expression arguments, a return type of same-as-argument, and `distinct_supported` false

#### Scenario: The array expansion function is discoverable

- **WHEN** a client lists the function catalog
- **THEN** `array_join` appears in the scalar group with the signature `array_join(array)`, a
  `returns` of `element_of_argument`, `distinct_supported` false, and exactly one required `array`
  expression argument — with a `description` stating that the call produces one row per element and
  therefore changes the number of rows the query returns

#### Scenario: The text composition functions are discoverable

- **WHEN** a client lists the function catalog
- **THEN** `concat` appears in the scalar group with the signature
  `concat(value, value[, value…])`, a `returns` of `string`, `distinct_supported` false, and a
  `variadic` second argument marking that further operands may be repeated; and `to_string` appears
  with the signature `to_string(value)`, a `returns` of `string` and one required expression argument

#### Scenario: The array text and slicing functions are discoverable

- **WHEN** a client lists the function catalog
- **THEN** `array_to_string` appears in the scalar group with the signature
  `array_to_string(array, delimiter)`, a `returns` of `string`, and a `string_literal` second
  argument; and `array_slice` appears with the signature `array_slice(array, offset, length)`, a
  `returns` of `array`, and two `integer_literal` bound arguments

#### Scenario: The clock is discoverable and marked non-deterministic

- **WHEN** a client lists the function catalog
- **THEN** `now` appears in the scalar group with the signature `now()`, a `returns` of `timestamp`,
  an empty `args` list, `distinct_supported` false, and `deterministic` **false** — with a
  `description` stating that its value is the data store's clock at execution, in UTC, and that it is
  refused where a derivation must be reproducible

#### Scenario: The offset functions advertise their literal constraints

- **WHEN** a client lists the function catalog
- **THEN** `date_add` and `date_sub` each appear in the scalar group with a `returns` of `timestamp`,
  declaring `unit` as a `string_literal` whose `allowed_values` are exactly the units the translator
  accepts (`second`, `minute`, `hour`, `day`, `month`, `year`), `amount` as an `integer_literal` with
  `min` 1, and `timestamp` as an `expression`

#### Scenario: Every entry states its determinism

- **WHEN** a client lists the function catalog
- **THEN** every entry carries a `deterministic` member, and `now` is the only entry whose value is
  `false`

#### Scenario: A higher-order entry declares its lambda argument

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** the `arrayExists` entry's `group` is `scalar`, its `returns` is `boolean`, and its `args`
  declare the first argument with `kind` `lambda` and the second as an `expression` — so a client can
  tell from discovery alone that the call takes a lambda, and in which position

#### Scenario: A higher-order array result is advertised as an array

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** the `arrayFilter` and `arrayMap` entries' `returns` is `array`, and the
  `groupUniqArrayArray` and `groupArrayArray` entries are `group` `aggregate` with `returns` `array`
  and `distinct_supported` `false`, each `description` stating why the quantifier does not apply

#### Scenario: The cost of a higher-order call is documented

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** each higher-order entry's `description` states that it reads every element and cannot use a
  data-skipping index, and each array-valued aggregate's `description` states that it holds an
  accumulator per group — the same way `json_extract_string_ci`'s cost is already documented

### Requirement: Schema discovery error contract

The system SHALL reject invalid discovery requests with specific HTTP statuses: an unknown entity name
SHALL return 404. Errors SHALL use the shared `ErrorView` contract and MUST NOT leak internals.

#### Scenario: Unknown entity

- **WHEN** any schema endpoint is called with an entity name that is not registered as a queryable
  source
- **THEN** the response status is 404 with error code `not_found`

### Requirement: Schema-level tag order

The base-schema response at `GET /v1/queries/entities/schema/{name}` SHALL carry a top-level
`tag_order` member surfacing the source table's per-table tag ordering from the table-management
catalog (see the `table-management` capability's "Per-table tag ordering"): the ordered array of
tag names when an ordering is set, or (consistent with the service's `NON_NULL` serialization) an
absent/null member when the table has no ordering. The server SHALL
return the stored ordering verbatim — entries are NOT filtered against the tags present on the
returned fields. `tag_order` SHALL NOT be role-filtered: the same value is returned to admin and
non-admin callers, because ordering entries are display labels that may reference tags with no
columns at all, so no sensitive-column existence can be inferred from them.

#### Scenario: Schema carries the table's tag order

- **WHEN** a source table has a tag ordering set and `GET /v1/queries/entities/schema/{source}` is
  called
- **THEN** the response carries `tag_order` with exactly that ordered list of tag names

#### Scenario: Schema without a tag order omits tag_order

- **WHEN** `GET /v1/queries/entities/schema/{source}` is called for a source with no tag ordering
  set
- **THEN** the response omits the `tag_order` member (absent, read as null by consumers)

#### Scenario: Tag order is returned verbatim even when tags have no fields

- **WHEN** a source's tag ordering includes a tag name that no returned field carries (e.g. the
  ordering was set before a retag, or the caller is a non-admin for whom all fields of that tag
  are sensitive-hidden)
- **THEN** the response still carries the full stored ordering, unfiltered

### Requirement: Tag-ordered field sorting

When the source table has a tag ordering set, the system SHALL return the base-schema `fields`
array already sorted by tag group: fields whose `tag` appears in `tag_order` come first, grouped
in `tag_order` list order; fields whose `tag` is not listed come next, their groups ordered
alphabetically by tag name; untagged fields (null `tag`) come last. Within each group — including
the untagged group — fields SHALL be sorted alphabetically by their flat `name` (bare or
table-qualified). The sort SHALL be applied after role-based sensitive-field filtering and SHALL
only change field order, never field content or presence.

When the source table has no tag ordering set (`tag_order` is `null`), the `fields` order SHALL
remain the pre-existing behavior — the source's columns in column-ordinal order followed by
enrichment fields — unchanged, so tables without an ordering behave exactly as before this
capability existed.

#### Scenario: Fields are grouped and sorted per the tag order

- **WHEN** a source has `tag_order: ["identifier", "cost"]` and columns tagged `cost`,
  `identifier`, and `token-usage` (unlisted), plus an untagged column, and
  `GET /v1/queries/entities/schema/{source}` is called
- **THEN** the response lists all `identifier` fields first, then all `cost` fields, then the
  `token-usage` fields, then the untagged fields — each group sorted alphabetically by flat
  field `name`

#### Scenario: Unlisted tag groups follow listed groups alphabetically

- **WHEN** a source's ordering lists none of `beta` and `alpha`, both of which appear as tags on
  its columns alongside listed tags, and the schema is fetched
- **THEN** the `alpha` group precedes the `beta` group, and both come after every listed-tag
  group and before the untagged fields

#### Scenario: New tag from churn lands after ordered groups without breaking

- **WHEN** a column is added with (or retagged to) a brand-new tag not present in the table's
  `tag_order`, and the schema is fetched
- **THEN** the new tag's fields appear grouped after the listed-tag groups (alphabetically among
  unlisted tags) and before untagged fields, with no error and no change to the stored ordering

#### Scenario: Without an ordering the field order is unchanged

- **WHEN** `GET /v1/queries/entities/schema/{source}` is called for a source with no tag ordering
- **THEN** the `fields` array is in the pre-existing column-ordinal order (enrichment fields
  appended), byte-for-byte as before this capability

### Requirement: Map fields are discoverable

Schema discovery SHALL report a `map`-typed catalog column as a field whose `type` is `map`, with no
`element_type`. The field SHALL appear in the entity's schema like any other column — subject to the same
sensitivity, tag ordering and heavy-column metadata — so a client can see that the field exists and that
it is a map before attempting to project it.

#### Scenario: Discovery reports a map field

- **WHEN** `GET /v1/queries/entities/schema/{name}` is read for an entity whose source table carries a
  `map` column
- **THEN** that field is listed with `type` `map` and carries no `element_type`

### Requirement: Enum fields carry their declared domain

`GET /v1/queries/entities/schema/{name}` SHALL list a flat field backed by a catalog column of type
`enum` (see [table-management](../table-management/spec.md), "Enum column type") with
`type: "enum"`, and SHALL carry that column's declared domain as `enum_values` — a JSON array of
strings in declared order. A field backed by a column of any other type SHALL carry an absent or
null `enum_values`.

Publishing the domain is the point of the type on this surface: it lets a consumer build a value
picker or validate a filter from discovery alone, rather than sampling the data with a
`SELECT DISTINCT`.

`enum_values` SHALL follow the same visibility rules as the rest of a field's metadata: a field
hidden from the caller (a `sensitive` column read without `TABLE_SENSITIVE` on its owning table)
does not appear at
all, and therefore neither does its domain.

#### Scenario: Enum field lists its domain

- **WHEN** a table has an `enum` column `status` declaring `enum_values: ["pending", "running"]`
  and the caller requests that entity's schema
- **THEN** the corresponding flat field carries `type: "enum"` and
  `enum_values: ["pending", "running"]` in that order

#### Scenario: Non-enum field carries no domain

- **WHEN** the entity's schema is requested for a `string` column
- **THEN** that field carries `type: "string"` and a null or absent `enum_values`

#### Scenario: A hidden sensitive enum column exposes no domain

- **WHEN** a caller without `TABLE_SENSITIVE` on the owning table requests the schema of an entity
  holding a `sensitive` `enum`
  column
- **THEN** the field is absent from the response entirely, along with its domain

### Requirement: Discovery is authorized per entity

`GET /v1/queries/entities` SHALL require `TABLE_LIST` and SHALL list only entities whose source the
caller may see, because the response carries entity names alone. `GET /v1/queries/entities/schema/{name}`
SHALL require `TABLE_GET` on the entity's source and SHALL respond `404 not_found` when the caller
lacks it — the same door as the catalog read of the same table, so neither surface is the weaker one.

`GET /v1/queries/functions` and `GET /v1/queries/sql-functions` SHALL require authentication only:
they describe the query languages and carry no tenant data.

Where an entity is listed, its schema SHALL contain exactly the fields that caller may reference, with
enrichments' columns the caller cannot clear the same bar for, and unentitled sensitive columns, absent
(see `structured-query`).

HTTP discovery deliberately **may** name an entity a subsequent query would refuse. The three surfaces
are gated at three rungs — the listing at `TABLE_LIST`, the per-entity schema at `TABLE_GET`, execution
at `TABLE_READ` — so a caller holding `TABLE_LIST` alone sees every name, then `404` from the schema
read and unknown-entity from execute. `TABLE_LIST` is a global privilege with no per-table grant, so
on a single entity the listing and the schema read are opened by the same `get` grant; the
name-without-schema state exists only for an enumerate-everything caller, which is what it was
granted. The Flight surface resolves the same tension the other way
and filters `GetTables` at `TABLE_READ`, because Flight is an execution protocol with no separately
gated follow-up: a name it advertises is a name `DoGet` must serve (see `flight-sql-endpoint`).

#### Scenario: A TABLE_LIST-only caller sees the name and not the schema

- **GIVEN** a caller holding `TABLE_LIST` globally and no other table privilege
- **WHEN** the caller lists entities and then requests an entity's schema
- **THEN** the listing contains the entity and the schema read is `404 not_found`

#### Scenario: The entity list is caller-filtered

- **GIVEN** two sources, one of which grants the caller's provider role a read-side privilege
- **WHEN** a caller holding no global read privilege lists entities
- **THEN** the response contains only the granted entity

#### Scenario: An unreadable entity's schema is not found

- **WHEN** that caller requests the other entity's schema
- **THEN** the response is `404 not_found` and does not reveal that the entity exists

#### Scenario: The function catalog needs no table privilege

- **WHEN** an authenticated caller holding no table privilege requests the function catalog
- **THEN** the response is `200` with the supported functions

#### Scenario: The SQL function catalog needs no table privilege

- **WHEN** an authenticated caller holding no table privilege requests `GET /v1/queries/sql-functions`
- **THEN** the response is `200` with the SQL function catalog

### Requirement: SQL function catalog discovery

The system SHALL expose, at `GET /v1/queries/sql-functions`, the catalog of function-like spellings
that the ad-hoc SQL surface accepts — `POST /v1/queries/execute-sql`, `validate-sql`, `translate-sql`
and Flight SQL — so that a caller who writes SQL learns what they may write from discovery alone rather
than from the DSL catalog, whose names and signatures differ on this surface. The response SHALL be a
JSON **object** `{"functions": [...]}`, with the entries in stable order by name compared
case-insensitively.

Each entry SHALL carry:

- `name` — the primary SQL spelling, lowercase (`length`, `mod`, `coalesce`, `+`, `like`, `case`);
- `form` — `function` for a `name(args)` call, `operator` for an infix or prefix operator (`+`, `||`,
  `LIKE`, `ILIKE`), or `syntax` for a keyword construct (`CASE … END`, `CAST(x AS VARCHAR)`);
- `aliases` — the other spellings accepted with the same meaning, lowercase, possibly empty (for
  example `char_length` and `character_length` for `length`, `ceiling` for `ceil`, `current_timestamp` and
  `localtimestamp` for `now`, `not like` for `like`);
- `group`, `returns`, `distinct_supported`, `deterministic` and `args` — with the meanings and value sets
  of the DSL catalog (see "Supported function catalog discovery"); an entry lowering onto a DSL catalog
  function SHALL carry that function's values for all five, except that an `operator` entry whose
  operand count is fixed by its syntax MAY declare its own `args` — `||` is binary, so it lists two
  `expression` operands `a` and `b` where `concat` declares a variadic `value`;
- `signature` — the spelling **as written in SQL**, which SHALL differ from the DSL signature wherever
  SQL requires a different form: `percentile_cont(fraction) WITHIN GROUP (ORDER BY value)`,
  `count(*)` alongside `count([DISTINCT] value)`, `array_has_any(array, ARRAY[value, ...])`, and an `OVER`
  clause on the window-only functions;
- `description` — a short human-readable description, stating the SQL-specific rules where they differ
  from the DSL function it lowers onto;
- `dsl_function` — the name of the DSL catalog function the spelling lowers onto, or `null` where the
  lowering target is not a catalog function (`coalesce`, `nullif` and `case` lower onto the DSL `case`
  expression; `like` and `ilike` onto the filter grammar's contains comparisons);
- `example` — one complete `SELECT` statement using the entry, which the SQL surface accepts.

The catalog SHALL be exact in both directions:

- every entry, written as its `example` shows and under each of its `aliases`, SHALL be accepted by the
  SQL surface;
- every DSL catalog function SHALL be reachable from at least one entry's `dsl_function`, so every DSL
  function has a discoverable SQL spelling;
- every caller-facing function name the SQL surface registers SHALL be an entry `name` or alias —
  internal markers the surface refuses when a caller spells them (`to_string_cast`, `__lambdaparam`)
  SHALL NOT be listed;
- the five DSL arithmetic names `plus`, `minus`, `multiply`, `div` and `modulo`, which the SQL surface
  does not accept as function names, SHALL NOT appear as a `name` or alias. Their SQL spellings are the
  operator entries `+`, `-`, `*`, `/`, `%` (and `mod` for `%`).

The catalog lists spellings that compute a value. The predicate and statement grammar — comparisons,
`IN`, `BETWEEN`, `IS NULL`, `AND`/`OR`/`NOT`, joins and set operations — is not listed. It is
documented in `docs/sql-reference.md`.

The endpoint SHALL require authentication only, like `GET /v1/queries/functions` (see "Discovery is
authorized per entity"). The published
API description of `GET /v1/queries/functions` SHALL state that it lists the DSL's `fn` names, and
SHALL point SQL callers at this endpoint, so that a caller who knows only one of the two can find the
other.

#### Scenario: SQL catalog is an object envelope in stable order

- **WHEN** `GET /v1/queries/sql-functions` is called
- **THEN** the response is a JSON object whose `functions` member is an array ordered by `name`
  compared case-insensitively, and every entry carries `name`, `form`, `aliases`, `group`, `signature`,
  `returns`, `distinct_supported`, `deterministic`, `description`, `dsl_function`, `example` and `args`

#### Scenario: DSL-only arithmetic names are absent and their operators present

- **WHEN** a client lists the SQL function catalog
- **THEN** no entry's `name` or alias is `plus`, `minus`, `multiply`, `div` or `modulo`
- **AND** operator entries `+`, `-`, `*`, `/` and `%` exist with `form` `operator` and `dsl_function`
  `plus`, `minus`, `multiply`, `div` and `modulo` respectively, and a `function` entry `mod` has
  `dsl_function` `modulo`

#### Scenario: SQL-only spellings are discoverable

- **WHEN** a client lists the SQL function catalog
- **THEN** `coalesce` and `nullif` appear as `function` entries, and `case` and `cast` as `syntax`
  entries, with `cast`'s `dsl_function` `to_string`
- **AND** `like` and `ilike` appear as `operator` entries returning `boolean`, each `description` stating
  that only the `'%text%'` contains pattern is accepted, with `not like` and `not ilike` as their aliases
- **AND** `||` appears as an `operator` entry with `dsl_function` `concat`, carrying `concat`'s
  `group`, `returns`, `distinct_supported` and `deterministic`, and two `expression` args `a` and `b`

#### Scenario: Synonyms are listed as aliases of one entry

- **WHEN** a client lists the SQL function catalog
- **THEN** the `length` entry lists `char_length` and `character_length` as aliases, `ceil` lists
  `ceiling`, and `now` lists `current_timestamp` and `localtimestamp`, and the `now` entry keeps
  `deterministic` false

#### Scenario: SQL signatures are written the way SQL requires

- **WHEN** a client lists the SQL function catalog
- **THEN** `percentile_cont` and `percentile_disc` carry signatures containing
  `WITHIN GROUP (ORDER BY`, `count`'s signature includes `count(*)`, `array_has_any` and
  `array_has_all` carry signatures using `ARRAY[`, and `row_number`, `rank` and `dense_rank` carry
  signatures containing `OVER (`

#### Scenario: Every example is accepted by the SQL surface

- **WHEN** each entry's `example` is submitted to `POST /v1/queries/translate-sql`
- **THEN** translation succeeds for every entry

#### Scenario: A new DSL function without a SQL spelling fails the build

- **WHEN** a DSL catalog function is added with no SQL catalog entry lowering onto it
- **THEN** the parity test between the two catalogs fails, naming the function

## Implementation notes

- Controller: `web.controller.query.QuerySchemaController` (`/v1/queries`).
- Catalog-backed entity/schema source: `service.query.CatalogQueryService` derives queryable entities
  and their flat schemas from the table-management catalog (`TableDefinitionRepository`,
  `ColumnMappingRepository`), surfacing each `ACTIVE` `source` plus its
  `ACTIVE` enrichments' columns under table-qualified flat names. `dial_usage_log` is seeded by the
  catalog migration (`src/main/resources/db/migration/postgres/V1__create_table_catalog.sql`).
  DTOs: `web.dto.query.{QueryEntityDto, QueryEntitySchemaDto, QuerySchemaFieldDto, QueryFieldType}`.
- Function catalog: `service.query.QueryFunctionCatalog` serves `GET /v1/queries/functions` as static
  descriptor data (one entry per `ExprTranslator#toFunction` branch), advertising the `date_bin` unit
  set as `DATE_BIN_ALLOWED_UNITS`. DTOs: `web.dto.query.{QueryFunctionDto, QueryFunctionArgDto,
  QueryFunctionArgConstraintsDto, QueryFunctionGroup, QueryFunctionReturnType, QueryFunctionArgKind}`.
  Parity with the translator is enforced by `QueryFunctionCatalogParityTest` (every descriptor
  translates; advertised units equal `ExprTranslator.DATE_BIN_UNITS` keys); serialization and ordering
  by the `QuerySchemaControllerTest` function-catalog cases.
- SQL function catalog: `service.query.sql.SqlFunctionCatalog` serves `GET /v1/queries/sql-functions`,
  derived from `QueryFunctionCatalog` minus its `DSL_ONLY` arithmetic names, plus explicit SQL
  overrides (aliases, signatures, notes, one example per entry) and the SQL-only entries. DTOs:
  `web.dto.query.{SqlFunctionCatalogDto, SqlFunctionDto, SqlFunctionForm}`. Parity in both directions is
  enforced by `SqlFunctionCatalogParityTest` (every example and alias translates through
  `SqlQueryFrontEnd`, every DSL function is some entry's `dsl_function`, every caller-facing name in
  `SqlQueryOperators.TABLE` is listed, the `DSL_ONLY` names are refused); the envelope and entries by
  the `QuerySchemaControllerTest` sql-functions cases.
- Error mapping (via `web/handler/DefaultExceptionHandler` + `ErrorView`):
  `QueryEntityNotFoundException` → 404 `not_found`; `QueryValidationException` → 400 `bad_request`.
- Tests: `QuerySchemaControllerTest` (catalog, base schema, unknown-entity 404);
  `QuerySchemaDiscoveryIntegrationTest` and `EnrichmentJoinIntegrationTest` (catalog-backed discovery
  and enrichment flattening).
