## MODIFIED Requirements

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
