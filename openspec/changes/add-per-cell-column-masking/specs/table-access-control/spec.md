## ADDED Requirements

### Requirement: Pipeline paths refuse a masked column

Every path "Pipeline and export paths refuse a hidden column" lists, catalog export excepted, SHALL refuse a column
masked for the calling identity (see "Column visibility by caller role") exactly as it refuses a hidden one: HTTP 403
with machine code `column_not_entitled`, naming the column, judged with the hidden columns before any column is judged
for the clearance, over the same columns that requirement says the path reads. Those paths are pipeline authoring
(`enrich` and `aggregate` registration, enable, patch and preview, and an aggregate's refresh on request) and the
runner's data-plane endpoints under the calling identity. They read without the row filter and without the mask, and
a pipeline writes what it derives into a table where the row a value came from no longer decides who may read it, so
a column the caller may read on some rows only SHALL NOT reach them. A fragment that parses because the masked column
is in the caller's schema SHALL still be refused for it, as a column the path reads. The re-attribution of a parse
failure to `column_not_entitled` keeps keying on a column a column policy hides: a masked column parses, so it is
never the cause of one, and a caller whose restricted columns are all masked receives a fragment's genuine parse
diagnostic, although its `columns_restricted` is `true`. For the same reason a masked column is not a protected column
for an unplannable aggregate SQL statement (see [aggregate-pipeline](../aggregate-pipeline/spec.md)): a planning
failure whose text mentions a masked column, and no hidden or unentitled sensitive one, SHALL stay the `422` planning
diagnostic. A statement that does plan and reads a masked column is refused `403 column_not_entitled` by the rule
above.

A masked column SHALL be judged at the point in each path where a hidden one is, so the refusal SHALL NOT change the
order in which a path already decides `403 row_policy_confined` (see [pipeline-registry](../pipeline-registry/spec.md),
and [enrichment-runtime](../enrichment-runtime/spec.md), "The pipeline data plane refuses a row-confined caller") and
`403 column_not_entitled`; a registration or patch that leaves the pipeline disarmed is judged when it is armed, as
for a hidden column. Where the confinement check comes first, as on an armed registration and on the runner's data
plane, a masked column reaches this refusal only for a caller that is not confined — one carrying a row-unrestricted
reading role whose column policy leaves out a column a confined role of the caller allows.

Where one of these paths withholds a value read from a column hidden from the caller instead of refusing it, it SHALL
withhold it for a masked column too. In particular, an `enrichment_behind` note SHALL carry no instant read from the
read source's `version_column` when that column is masked for the caller, exactly as when it is hidden. Those values
come from reads without the row filter, so they can come from rows on which the cell is absent for the caller. A
`lag_seconds` value and the note's gap stay returned, as for a hidden version column.

Catalog export SHALL NOT refuse a masked column: a bundle carries the column's definition, which the caller may see,
and no stored value. Work running under the system identity SHALL NOT be refused for a masked column, as for a hidden
one.

#### Scenario: An author cannot register a pipeline over a masked column

- **GIVEN** a caller holding `REGISTRY_ADMIN` whose governing roles on `dial_usage_log` are `A`, with `read`, `rows`
  admitting `project_id = 'P1'` and `columns` `null`, and `B`, with `read`, no `rows` and `columns` listing every
  column of the table but `deployment` — so it reads every row and `deployment` alone is masked
- **WHEN** it registers an armed (`enabled: true`) `enrich` pipeline whose bound input reads `deployment`
- **THEN** the response is `403 column_not_entitled` naming `deployment`, and nothing is stored

#### Scenario: A confined caller is refused for its confinement first

- **GIVEN** the same caller without `B`, so it is confined to `P1`, and a second role with `read`, `rows` admitting
  `project_id = 'P2'` and `columns` `["project_id"]`, so `deployment` is masked
- **WHEN** it registers the same armed pipeline
- **THEN** the response is `403 row_policy_confined`, as for any confined caller

#### Scenario: A runner scan over a masked column is refused

- **GIVEN** a runner identity that is not confined on a pipeline's read source but for which a column the scan returns
  is masked
- **WHEN** it calls the pipeline's scan
- **THEN** the response is `403 column_not_entitled` naming that column

#### Scenario: A masked column is refused before an unentitled sensitive one

- **GIVEN** a caller that is not confined, and a pipeline declaration that reads a column masked for it and a
  sensitive column of another table it lacks the clearance for
- **WHEN** the caller registers it armed
- **THEN** the response is `403 column_not_entitled`, whatever order the tables are visited in

#### Scenario: An unplannable aggregate statement mentioning only a masked column keeps its planning diagnostic

- **GIVEN** a caller that is not confined, for which `deployment` is masked on an aggregate pipeline's input and no
  column of it is hidden
- **WHEN** it previews an aggregate whose SQL statement mentions `deployment` and cannot be planned
- **THEN** the response is the `422` planning diagnostic
- **AND WHEN** it previews an aggregate whose SQL statement plans and reads `deployment`
- **THEN** the response is `403 column_not_entitled` naming `deployment`

#### Scenario: A preview's note omits the instants of a masked version column

- **GIVEN** a caller holding `REGISTRY_ADMIN` that is not confined on an `enrich` pipeline's read source, for which
  the source's `version_column` is masked, and an enrichment behind its source
- **WHEN** it requests a `sql` enrich preview
- **THEN** the preview runs, and its `enrichment_behind` note names the enrichment and gives the gap, but carries
  neither the newest sampled version nor the enrichment's materialized position

#### Scenario: An export carries a masked column

- **GIVEN** a caller holding `REGISTRY_READ` and `get` on a table, whose governing roles there mask one of its columns
- **WHEN** it exports the table
- **THEN** the export succeeds and the bundle's table definition lists that column

## MODIFIED Requirements

### Requirement: Column visibility by caller role

In `oidc` mode, which of a table's columns a caller sees SHALL be decided from the column policies of its **governing
roles** on that table:

- when the caller may read the table, its governing roles SHALL be those of its raw provider roles, `*` included, that
  **may read the table** as "Row-policy evaluation by caller role" defines it — named by the table's `read` grant list,
  or mapped by a configured `roles-mapping` to `TABLE_READ` globally while the caller holds `TABLE_READ` globally on the
  request;
- when the caller may not read the table, they SHALL be the roles through which it holds `TABLE_GET` there: named by
  the `get`, `read` or `modify` grant list (a `*` in `modify` excepted), or mapped to a privilege conferring `TABLE_GET`
  globally while the caller holds `TABLE_GET` globally.

A role mapped to a global privilege SHALL be judged as row-policy evaluation judges it, with the same cross-provider
approximation: a role name some configured provider maps to the privilege counts for any caller that holds the
privilege globally on the request, whichever of its roles conferred it.

A governing role **allows** a column when it has no column policy on the table, or has one that lists the column; it
is **row-unrestricted** when it has no row policy on the table. Each of the table's columns SHALL be, for the caller,
exactly one of:

- **visible**, when some row-unrestricted governing role allows it, or when every governing role allows it;
- **masked**, when it is not visible and at least one governing role allows it;
- **hidden**, when no governing role allows it.

Each entry is one unit — a role's readers see that role's columns on that role's rows — and the rows a caller reads
are the union of its roles' row reach (see "Row-policy evaluation by caller role"). A visible column SHALL read its
stored value on every row the caller reads. A masked column's **mask condition** SHALL be the disjunction of the row
policies of the governing roles that allow it, every one of which carries a row policy, since a row-unrestricted role
allowing the column would make it visible. On each row the caller reads, a masked column SHALL read its stored value
when the mask condition is true on the row and SHALL be **absent** otherwise — where it is false and where it is
unknown alike — so a masked column is never shown on a row that no governing role allowing it admits. The mask condition SHALL be
evaluated with the caller values the row filter is evaluated with on the same request (see "Caller values resolve per
read and an absent one is unknown"), so a policy whose caller value is unknown, or whose comparison is unknown on a
row because a column it reads is null there, admits that row to the mask no more than it admits it to the row filter. How each surface returns an absent cell, and how a query
reads one, is specified by that surface: see [structured-query](../structured-query/spec.md), "A masked field reads as
absent at every reference", and the CSV-export and Flight SQL capabilities. A hidden column SHALL be treated as
"Hidden columns are absent from every read surface" states; a masked column is not hidden.

When no governing role carries a row policy, no column is masked and the visible columns are the union of the
governing roles' lists.

A column that would be masked SHALL be hidden instead, as the rule without masking would decide, when any table of its
**entity** — the governed table (the column's own table, or for an enrichment column its source) and every enrichment
registered on it, whatever its status — stores a column whose physical name (`source_name`) equals the name of any
table of that entity. That covers a source column named after the source or an enrichment, and an enrichment column
named after its own enrichment. A query names the governed
table's columns, and each enrichment's, by that table's name, and the data store resolves such a qualified name to the
like-named column of the source, reading that column's subcolumns in place of the named table's columns. A mask
could then admit a row its policy does not, or return a source column's derived value as an enrichment value. Hiding
keeps the guarantee on such a table, at the cost of the columns masking would have shown there.

Gaining a role SHALL NOT remove a column from the rows the caller's other roles admit: a column a confined role allows
stays readable on that role's rows, whatever other roles the caller gains — except on a table where the hiding fallback
above applies, which keeps the rule without masking. The rule applies
unchanged to a caller that may not read the table, whose governing roles are get-governing; such a caller reads no
rows, so for it a masked column differs from a visible one in being reported masked and counted in
`columns_restricted`, in the refusals a query over a masked field meets on translation and validation (see
[structured-query](../structured-query/spec.md)), in the TTL refusal below, which a caller holding `modify` without
`read` meets too, and in the pipeline refusal (see "Pipeline paths refuse a masked column"). A role that does not
govern — an IdP group, a role conferring only `write`, a role conferring only `get` to a caller that may read — SHALL
take no part, so it SHALL NOT lift a restriction and its row policy SHALL NOT enter a mask condition. That includes a
role whose entry carries `rows` but which has no read path: its row policy adds its rows to a confined caller carrying
it, by row-policy evaluation, but its `columns` are not consulted, so on those rows a visible column reads its value and
a masked one is absent unless a governing role that allows it admits the row too. A caller with no governing role is
covered below.

A masked column SHALL be listed, named and referenced wherever a visible column is: schema discovery (which marks it,
see [query-schema-discovery](../query-schema-discovery/spec.md), "A masked field is marked"), the catalog read and
revision snapshots, `column_count`, the schema the query, translation, CSV-export and Flight SQL surfaces resolve
against, a schema change other than as a TTL column, catalog export and an import entry's `prev`. Where a
requirement elsewhere speaks of the columns "visible" to a caller — on those schema surfaces, or as the columns the
verbs `get`, `read` and `modify` act on (see "Per-table privilege grants") — a masked column is among them, its values
readable on the rows its mask admits. A TTL treats a masked column as hidden: a `ttl.column` cannot name it, and a
stored TTL on it refuses every `ttl` member, as "Hidden columns are absent from every read surface" states for a hidden
column, because watching which rows a TTL removes would disclose stored values of absent cells. The remaining
exceptions are reporting, where it
counts as restricted (see "Caller-effective privilege reporting"), and the paths that read without a row filter, which
refuse it (see "Pipeline paths refuse a masked column").

Masking discloses, row by row, whether a governing role that allows the column admits the row: a cell is present
exactly where its mask condition is true. On the rows that role itself admits, this is what its row reach already
discloses. On rows only another of the caller's roles admits, it is new, and it holds even when the policy reads a
column the caller cannot see, such as a sensitive one. That disclosure is accepted as the cost of showing each role's
columns on its own rows, as the persisted literal of a saved query is accepted (see
[saved-queries](../saved-queries/spec.md), "A sensitive value may be persisted in a saved query body").

A column policy SHALL apply to its role whatever path that role reads the table by, a global privilege included, so
confining a broadly-entitled role takes an entry on that role. A `*` entry's `columns` therefore confine only callers
that get or read the table through `*`'s grant: `*` holds no global privilege, so a `*` entry carrying `columns` and no
privileges confines no one.

A caller with **no governing role** on a table — one holding neither `TABLE_GET` nor `TABLE_READ` there, such as a
caller holding only `TABLE_LIST`, or a pipeline author holding only `REGISTRY_ADMIN` — SHALL NOT be narrowed by the
table's column policies: every column is visible to it as far as column policies go, the clearance still applies, and
its `columns_restricted` is `false`. The verb bars already decide what such a caller reaches; column policies narrow
only a caller that gets or reads the table through at least one governing role.

A computed field, which is not a catalog column and so cannot be named by a policy, SHALL be visible exactly when every
catalog column whose values it returns is visible — for the computed fields of `dial_usage_log_payload`, its
`request_body` and `request_cache_control`, named in a source entry as `dial_usage_log_payload.request_body` and
`dial_usage_log_payload.request_cache_control`. It SHALL be masked when none of those columns is hidden and at least
one is masked, and absent on every row where any of them is absent; otherwise it SHALL be hidden.

The clearance SHALL stack on top: a sensitive column SHALL be visible or masked only when the column policies make it
so **and** the caller holds the clearance on the table; without the clearance it is hidden. A column policy SHALL NOT
grant anything: whether the caller may get or read the table at all is decided by its privileges, unchanged.

For an enrichment, the governing roles and their policies SHALL be its source's, the policy naming the enrichment's
column `c` as `<enrichment>.c`; when the caller may not read the source, a role holding `get` on the enrichment through
the enrichment's own `modify` list SHALL also govern it, as a row-unrestricted role with no column policy. A masked
enrichment column's mask condition SHALL be evaluated on the source row its value is joined to, so an enrichment value
is present exactly where the source row satisfies it.

Column policies SHALL narrow what a caller sees and acts on through `get`, `read` and `modify` — the verbs the
clearance extends — and SHALL NOT narrow `write`: a row insert is judged as before. They SHALL NOT apply to the system
identity that background work runs under, so a pipeline's own reads see every column. In `none` security mode they
SHALL NOT be evaluated and every column SHALL be visible.

#### Scenario: A role sees only its listed columns

- **GIVEN** a table with columns `a`, `b` and `c`, and an entry for role `X` carrying `read` and `columns` `["a", "b"]`
- **WHEN** a caller carrying only `X` runs `SELECT * FROM t`
- **THEN** the result has columns `a` and `b` only
- **AND WHEN** it selects or filters on `c`
- **THEN** the query fails as it does for an unknown field

#### Scenario: Two roles' lists are combined when neither carries a row policy

- **GIVEN** entries for `X` with `read` and `columns` `["a"]`, and for `Y` with `read` and `columns` `["b"]`, neither
  carrying `rows`
- **WHEN** a caller carrying both requests the entity's schema
- **THEN** it carries `a` and `b`, and not `c`

#### Scenario: One role's columns are not shown on another role's rows

- **GIVEN** a table with columns `project`, `a` and `c`; an entry for `R` with `read`, `rows` admitting `project = 'P1'`
  and `columns` `["a", "project"]`; and an entry for `U` with `read`, `rows` admitting `project = 'P2'` and `columns`
  `null`
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads the rows of `P1` and of `P2` with columns `project`, `a` and `c`, where `c` carries its stored value
  on the rows of `P2` and is absent on the rows of `P1`, and its `GET /v1/tables/t` reports
  `permissions.columns_restricted = true`

#### Scenario: A role with no row policy shows the columns it allows on every row

- **GIVEN** an entry for `R` with `read`, `rows` admitting `project = 'P1'` and `columns` `["a", "project"]`, and an
  entry for `U` with `read`, no `rows` and `columns` `null`
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads every row with every column

#### Scenario: A column every confining role lists is visible

- **GIVEN** a table with columns `project`, `a`, `b`, `c` and `d`; an entry for `R` with `read`, `rows` admitting
  `project = 'P1'` and `columns` `["a", "c"]`; and an entry for `U` with `read`, `rows` admitting `project = 'P2'` and
  `columns` `["b", "c"]`
- **WHEN** a caller carrying both requests the entity's schema
- **THEN** it lists `a`, `b` and `c`, with `a` and `b` marked masked, and lists neither `project` nor `d`
- **AND WHEN** it queries the table
- **THEN** it reads the rows of `P1` and of `P2`; `c` carries its stored value on every row, `a` only on the rows of
  `P1` and `b` only on the rows of `P2`, each absent on the other project's rows

#### Scenario: Gaining a column-restricted role can hide a confined role's columns

- **GIVEN** a table with columns `project`, `x` and `y`; an entry for `A` with `read`, `rows` admitting
  `project = 'P1'` and `columns` `null`; and an entry for `B` with `read`, no `rows` and `columns` `["x"]`
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads every row with columns `project`, `x` and `y`; `x` carries its stored value on every row, and
  `project` and `y` carry theirs on the rows of `P1` and are absent on every other row, because only `A` allows them
- **AND WHEN** `B`'s entry also carries `rows`, admitting `project = 'P2'`
- **THEN** it reads the rows of `P1` and of `P2`; `x` carries its stored value on both, and `project` and `y` on the
  rows of `P1` only
- **AND WHEN** instead `B`'s `columns` list `x`, `y` and `project`
- **THEN** it reads every row with all three columns, none absent

#### Scenario: A masked column follows the caller value of the policy that allows it

- **GIVEN** a table whose `read` list is `["*", "V"]`: a `*` entry with `read`, `rows` admitting
  `user_hash = caller.user_hash()` and `columns` `null`, and an entry for `V` with `read`, `rows` admitting
  `project = 'P2'` and `columns` `["project"]`
- **WHEN** a caller carrying `V` whose own `user_hash` is `u1` queries the table
- **THEN** it reads its own rows and the rows of `P2`; `project` carries its stored value on every one of them, and
  every other column carries its stored value on the rows whose `user_hash` is `u1` and is absent on the rest
- **AND WHEN** the same caller's `user_hash` is unknown on the request
- **THEN** it reads the rows of `P2` only, with every column but `project` absent on all of them, and each such cell
  is reported absent by the surface that returns it

#### Scenario: A table with a column named after it hides instead of masking

- **GIVEN** a table `t` that stores a column named `t`, an entry for `A` with `read`, `rows` admitting `p = 'P1'` and
  `columns` `null`, and an entry for `B` with `read`, `rows` admitting `p = 'P2'` and `columns` `["p"]`
- **WHEN** a caller carrying both requests the entity's schema
- **THEN** it lists `p` only, as the intersection of the two lists, and nothing is marked masked

#### Scenario: An older revision lists a masked column

- **GIVEN** a caller whose governing roles on a non-system table mask a column under the current policies
- **WHEN** it reads an older revision snapshot of the table, recorded before the column policies existed
- **THEN** the snapshot lists the masked column, as the current catalog read does

#### Scenario: A null policy column makes the cell absent

- **GIVEN** an entry for `A` with `read`, `rows` admitting `route_name = 'r1'` and `columns` `null`, and an entry for `B`
  with `read`, `rows` admitting `project = 'P2'` and `columns` `["project"]`, and a row of `P2` whose `route_name` is
  null
- **WHEN** a caller carrying both queries the table
- **THEN** on that row every column but `project` is absent and reported absent, exactly as on a row of `P2` whose
  `route_name` is `r2`

#### Scenario: A caller that only gets the table lists a masked column

- **GIVEN** an entry for `G1` with `get`, `rows` admitting `project = 'P1'` and `columns` `["a"]`, and an entry for `G2`
  with `get`, `rows` admitting `project = 'P2'` and `columns` `["b"]`
- **WHEN** a caller carrying both and no read on the table reads `GET /v1/tables/t`
- **THEN** the definition lists `a` and `b`, and `permissions.columns_restricted` is `true`

#### Scenario: A rows-only entry without a read path adds rows but no columns

- **GIVEN** an entry for `X` with `read`, `rows` admitting `project = 'P1'` and `columns` `["a", "project"]`, and an
  entry for `N` with no privileges, `rows` admitting `project = 'P2'` and `columns` `null`, where no global privilege
  gives `N` a read
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads the rows of `P1` and of `P2`, with columns `a` and `project` only

#### Scenario: An unrestricted reading role lifts the restriction

- **GIVEN** an entry for `X` with `read` and `columns` `["a"]`, and role `Y`, mapped to `TABLE_READ` globally, with no
  entry
- **WHEN** a caller carrying both queries the table
- **THEN** it sees `a`, `b` and `c`

#### Scenario: A role that only gets the table does not widen a reader's columns

- **GIVEN** an entry for `X` with `read` and `columns` `["a"]`, and an entry for `G` with `get` only
- **WHEN** a caller carrying both requests the entity's schema and queries the table
- **THEN** both show `a` only

#### Scenario: A global reader is confined by its role's column policy

- **GIVEN** provider role `X` mapped to `READ_ONLY_ANALYTIC`, and an entry for `X` with no privileges and `columns`
  `["a"]`
- **WHEN** a caller carrying only `X` queries the table
- **THEN** it sees `a` only

#### Scenario: A caller that only gets the table is shown its listed columns

- **GIVEN** an entry for `G` with `get` and `columns` `["a"]`
- **WHEN** a caller carrying only `G` reads `GET /v1/tables/t`
- **THEN** the definition lists `a` only

#### Scenario: The clearance still applies to a listed column

- **GIVEN** a sensitive column `s` and an entry for `X` with `read` and `columns` `["a", "s"]`, and no clearance for `X`
- **WHEN** a caller carrying only `X` requests the schema
- **THEN** it carries `a` and not `s`
- **AND WHEN** `X` is granted `sensitive`
- **THEN** it carries `a` and `s`
- **AND WHEN** instead `X`'s entry also carries `rows`, and the caller carries a second reading role with `rows` and
  `columns` `["a"]`, without the clearance
- **THEN** it carries `a` and not `s`: the clearance hides `s`, which is not masked

#### Scenario: A source's allowlist hides the enrichment columns it does not name

- **GIVEN** an entry for `X` on `dial_usage_log` with `read` and `columns` `["project_id", "deployment_ref.display_name"]`
- **WHEN** a caller carrying only `X` requests the entity's schema
- **THEN** it carries `project_id` and `"deployment_ref.display_name"`, and no other column of the source or of its
  enrichments

#### Scenario: An enrichment maintainer sees the columns it maintains

- **GIVEN** a caller whose only role is in an enrichment's `modify` list and in no entry of its source
- **WHEN** it reads `GET /v1/tables/{enrichment}`
- **THEN** every non-sensitive column of the enrichment is listed

#### Scenario: A write is not narrowed

- **GIVEN** an entry for `W` with `write` and `columns` `["a"]`
- **WHEN** a caller carrying only `W` inserts a row with values for `a` and `b`
- **THEN** the insert succeeds

#### Scenario: A column policy does not grant a read

- **GIVEN** an entry for `Z` with no privileges and `columns` `["a"]`, and no global read for `Z`
- **WHEN** a caller carrying only `Z` queries the table
- **THEN** the query fails as though the table did not exist

#### Scenario: A caller with no governing role is not narrowed

- **GIVEN** a table with columns `a`, `b` and `c`, none sensitive, on which role `X`'s entry carries `read` and
  `columns` `["a"]`
- **WHEN** a caller holding `TABLE_LIST` globally and nothing else, carrying no role with an entry, lists the catalog
- **THEN** the table's list item carries `column_count: 3` and every flag in its `permissions` is `false`,
  `columns_restricted` included

#### Scenario: A computed field follows the columns it returns

- **GIVEN** an entry for `X` on `dial_usage_log` with `read` and `columns` naming `dial_usage_log_payload.request_body`
  but not `dial_usage_log_payload.request_cache_control`, and the clearance where those columns are sensitive
- **WHEN** a caller carrying only `X` requests the entity's schema
- **THEN** it carries `dial_usage_log_payload.request_body` and neither of the computed fields
  `dial_usage_log_payload.request_body_full` and `dial_usage_log_payload.request_body_rebuild`
- **AND WHEN** the list names both columns
- **THEN** it carries both computed fields
- **AND WHEN** instead `X`'s `columns` name `dial_usage_log_payload.request_body` only, as at first, its entry also
  carries `rows` admitting `project_id = 'P2'`, and the caller also carries `Y`, whose entry carries `read`, `rows`
  admitting `project_id = 'P1'` and `columns` `null`
- **THEN** both computed fields are listed and marked masked, and a query returns them on the rows of `P1` and
  absent on the rows of `P2`

#### Scenario: none mode shows every column

- **WHEN** the service runs in `none` mode and a table carries column policies
- **THEN** every query and schema read exposes every column

### Requirement: Caller-effective privilege reporting

The system SHALL report, on every table read surface (`GET /v1/tables` list items and `GET /v1/tables/{name}`), the
calling identity's effective per-table permissions as a `permissions` object with boolean `get`, `read`, `sensitive`,
`write`, `modify`, `grant`, `rows_restricted` and `columns_restricted` fields. There SHALL be no `list` flag: a caller that sees
a table at all may list it, so the flag could only ever read `true`. The reported flags SHALL agree with what per-table
evaluation, row-policy evaluation, column-visibility evaluation and system-table protection would decide for that
caller, **after** implication is applied:

- a `system` table SHALL report `write=false, modify=false` for every caller, including one holding every privilege
  (all modifying operations are refused on system tables), while its read-side flags SHALL be evaluated from the grant
  lists like any other table's;
- an enrichment SHALL report its source's `read` and `sensitive`, a `rows_restricted` that is `true` when the caller
  is confined on the source or by a policy an earlier build left on the enrichment, a `columns_restricted` decided by
  its source's column policies, and a `get` that is its source's or implied by its own `modify`; its `write` and
  `modify` SHALL be evaluated from its own grant lists;
- otherwise each of `get`, `read`, `sensitive`, `write` and `modify` SHALL be `true` if the caller holds the
  corresponding privilege globally, or if one of its raw provider roles appears in a grant list that grants or implies
  it;
- `rows_restricted` SHALL be `true` exactly when row-policy evaluation confines the caller on the table (see
  "Row-policy evaluation by caller role", and the enrichment bullet above), on `system` and non-`system` tables alike;
- `columns_restricted` SHALL be `true` exactly when column-visibility evaluation hides or masks at least one of the
  table's own columns from the caller (see "Column visibility by caller role", row policies of the governing roles
  included), on `system` and non-`system` tables alike; a
  column hidden only by the clearance does not set it, and it is `false` for a caller with no governing role on the
  table, as `rows_restricted` is for a caller that may not read it;
- `grant` SHALL be `true` exactly when the caller holds `TABLE_GRANT`, and so may call the table's access API (see
  "Role-keyed access API"). `TABLE_GRANT` is held only globally, never through an entry, so `grant` SHALL be the
  same on every table the caller sees, `system` tables and enrichments included: both accept entries. An entry
  carrying every privilege SHALL NOT set it. It SHALL agree with the rule that withholds a revision snapshot's
  entries from a caller without `TABLE_GRANT`;
- in `none` security mode `grant` SHALL be `true` on every table, every other flag but `rows_restricted` and
  `columns_restricted` SHALL be `true` for non-`system`
  tables, the read-side flags SHALL be `true` for `system` tables, and `rows_restricted` and `columns_restricted`
  SHALL be `false` (no authorization is enforced).

`sensitive` SHALL report whether the caller holds the clearance on the table, not whether it can read sensitive
values. It counts among the read-side flags. A consumer SHALL derive "reads sensitive values" as `read && sensitive`,
"sees sensitive columns" as `get && sensitive`, and "may change them or the flag" as `modify && sensitive`.
`grant` SHALL NOT change what the caller may do: the access API keeps its own `TABLE_GRANT` check.
`rows_restricted` and `columns_restricted` SHALL NOT disclose the policies themselves; those are readable only where `TABLE_GRANT`
is required: through the access API, a revision snapshot (see [audit-trail](../audit-trail/spec.md)), and a catalog export or import
called with `include_access` (see [catalog-export-import](../catalog-export-import/spec.md), "Access export" and "Access import").

The reporting SHALL share its role-matching, implication, row-policy and column-visibility evaluation with the
evaluation used for enforcement so the two cannot diverge. Reporting SHALL NOT change enforcement behaviour.

#### Scenario: Matching write grant is reported

- **GIVEN** a table on which provider role `analytics-writer`'s entry carries `write` only
- **WHEN** a caller carrying provider role `analytics-writer` and no global privilege reads the table via
  `GET /v1/tables/{name}`
- **THEN** the table carries `permissions.write = true` and `permissions.modify = false`

#### Scenario: Implication is reflected in the reported flags

- **GIVEN** a table on which provider role `analytics-viewers`' entry carries `read` only
- **WHEN** a caller carrying that role reads the table
- **THEN** the table carries `permissions.read = true` and `permissions.get = true`, and `permissions.sensitive =
  false`

#### Scenario: The clearance is reported on its own flag

- **GIVEN** a table on which provider role `finance-owners`' entry carries `modify` and `sensitive`
- **WHEN** a caller carrying that role and no global privilege reads the table
- **THEN** the table carries `permissions.get = true`, `permissions.modify = true`, `permissions.sensitive = true` and
  `permissions.read = false`

#### Scenario: A full-privilege caller sees full permissions on a non-system table

- **WHEN** a caller holding the `FULL_ADMIN` bundle, none of whose roles has an entry on the table, reads a
  non-`system` table with no entries
- **THEN** every flag in `permissions` except `rows_restricted` and `columns_restricted` is `true`, and those two are
  `false`

#### Scenario: No matching grant is reported as no permissions

- **WHEN** a caller with no global privilege, none of whose provider roles has an entry on a table, reads that table
  through a surface it can reach
- **THEN** every flag in `permissions` is `false`, `columns_restricted` included even where another role's entry on
  the table carries `columns`

#### Scenario: A system table reports no write permissions but honours read grants

- **WHEN** a caller holding the `FULL_ADMIN` bundle reads a `system` table (e.g. the seeded `dial_usage_log`)
- **THEN** the table carries `permissions.write = false` and `permissions.modify = false`, and `permissions.read =
  true`

#### Scenario: A confined caller is reported as row-restricted

- **GIVEN** an entry for role `X` on a table whose `rows` admits one project
- **WHEN** a caller carrying `X` and holding `TABLE_READ` reads the table's definition
- **THEN** the table carries `permissions.read = true` and `permissions.rows_restricted = true`
- **AND WHEN** the caller also carries another role that may read the table and has no row policy on it
- **THEN** the table carries `permissions.rows_restricted = false`

#### Scenario: A column-restricted caller is reported

- **GIVEN** an entry for role `X` with `read` and `columns` naming some but not all of the table's columns
- **WHEN** a caller carrying only `X` reads the table's definition
- **THEN** the table carries `permissions.columns_restricted = true`
- **AND WHEN** the list names every column of the table
- **THEN** the table carries `permissions.columns_restricted = false`

#### Scenario: A caller with a masked column is reported as column-restricted

- **GIVEN** an entry for `R` with `read`, `rows` and `columns` naming some of the table's columns, and an entry for `U`
  with `read`, `rows` and `columns` `null`
- **WHEN** a caller carrying both reads the table's definition
- **THEN** every column is listed and the table carries `permissions.columns_restricted = true`, because the columns
  `R` does not list are masked

#### Scenario: An enrichment reports its source's read-side flags

- **GIVEN** a source on which role `X`'s entry carries `read` and a row policy, and an enrichment of it on which `X`'s
  entry carries `write`
- **WHEN** a caller carrying `X` and no global privilege reads the enrichment's definition
- **THEN** the enrichment carries `permissions.read = true`, `permissions.rows_restricted = true`, `permissions.write
  = true` and `permissions.modify = false`

#### Scenario: An enrichment reports its source's column restriction

- **GIVEN** a source on which role `X`'s entry carries `read` and `columns` naming the source's columns and none of
  enrichment `e`'s
- **WHEN** a caller carrying only `X` reads `GET /v1/tables/e`
- **THEN** `e` carries `permissions.columns_restricted = true` and lists no column
- **AND WHEN** `X`'s `columns` names every column of `e` as `e.<column>`
- **THEN** `e` carries `permissions.columns_restricted = false`

#### Scenario: A policy left on an enrichment is reported as row-restricted

- **GIVEN** a source on which role `X`'s entry carries `read` and no row policy, and an enrichment of it that still
  stores a row policy for `X`, written by an earlier build
- **WHEN** a caller carrying only `X` and no global privilege reads the enrichment's definition
- **THEN** the enrichment carries `permissions.read = true` and `permissions.rows_restricted = true`

#### Scenario: An enrichment whose source is missing grants no read

- **GIVEN** a revision snapshot of an enrichment whose source table no longer exists
- **WHEN** a caller holding `TABLE_GET` globally and no other global privilege reads it
- **THEN** the read answers `200`, not an error, and the snapshot carries `permissions.get = true`, `permissions.read
  = false` and `permissions.sensitive = false`

#### Scenario: none mode reports full permissions

- **WHEN** the service runs in `none` security mode and any caller reads a non-`system` table
- **THEN** every flag in `permissions` except `rows_restricted` and `columns_restricted` is `true`, and those two are
  `false`

#### Scenario: The grant flag follows TABLE_GRANT

- **WHEN** a caller holding `TABLE_GET` and `TABLE_GRANT` globally, and no other privilege, reads a table
- **THEN** the table carries `permissions.grant = true` and `permissions.read = false`
- **AND WHEN** a caller holding only `TABLE_GET` globally reads it
- **THEN** the table carries `permissions.grant = false`

#### Scenario: An entry does not confer the grant flag

- **GIVEN** a table on which role `X`'s entry carries `get`, `read`, `sensitive`, `write` and `modify`
- **WHEN** a caller carrying `X` and no global privilege reads the table
- **THEN** the table carries `permissions.modify = true` and `permissions.grant = false`

#### Scenario: The grant flag is reported on system tables and enrichments

- **WHEN** a caller holding the `FULL_ADMIN` bundle reads a `system` table and an enrichment
- **THEN** both carry `permissions.grant = true`, the `system` table with `permissions.write = false`

### Requirement: Hidden columns are absent from every read surface

A column hidden from the caller SHALL be treated on every user surface exactly as a sensitive column the caller holds
no clearance for: it SHALL be absent from schema discovery, from the catalog read (`GET /v1/tables/{name}` and every
response carrying the table representation), from revision snapshots, and from the schema the SQL, structured-query,
translation, CSV-export and Flight SQL surfaces resolve against, Flight's catalog metadata and `INFORMATION_SCHEMA`
included. A star projection SHALL omit it. Referencing it anywhere in a query — projection, filter, grouping, ordering,
join or function argument — SHALL fail as an unknown field, with the status that surface gives an unknown field, and
a saved query or dashboard widget referencing it SHALL fail the same way when that caller runs it. A schema change
naming it SHALL be refused as naming an unknown column (`422`), while an `add` or `rename.to` colliding with it is
still refused `422` as a name that already exists, as for a hidden sensitive column (see
[table-management](../table-management/spec.md), "Modify table schema via PATCH"). A `ttl.column` SHALL resolve
against the columns visible to the caller only — a masked column excluded — so naming a hidden column fails as naming an undeclared one (`422`)
and TTL validation never reveals its type or nullability; and while the stored TTL is on a column hidden from the
caller by a column policy, or masked for it, any `ttl` member (set, replace or remove) SHALL be refused `403 column_not_entitled`
naming that column, and the TTL left unchanged — where the column is also sensitive and the caller lacks the
clearance, the response is `403 column_not_entitled` too. The list item's `column_count` SHALL not
count it (see [table-management](../table-management/spec.md), "Read and list table definitions"), and an import
entry's `prev` SHALL omit it (see [catalog-export-import](../catalog-export-import/spec.md), "Import entries"). No
response SHALL mark a hidden column as withheld or carry a placeholder for it; as for an unentitled sensitive column,
a definition's structural fields are returned as stored and a conflict refusal such as `409 column_in_column_policy`
names the columns it concerns. A masked column is not hidden: it is listed on these surfaces like a visible one, and
schema discovery and query results mark it and its absent cells as "Column visibility by caller role" refers to.

The same parity covers two surfaces that resolve against the writer's own view. Sessions-source validation (see
[instance-settings](../instance-settings/spec.md), "Sessions-source validation") resolves the bound fields against the
writing caller's flat schema, so a field hidden from that caller by a column policy is reported as absent, exactly as
an unentitled sensitive one is. And a saved query or inline dashboard body that binds a literal to a column a column
policy hides from other readers is stored and returned to them verbatim: that is the disclosure already accepted for a
sensitive column (see [saved-queries](../saved-queries/spec.md), "A sensitive value may be persisted in a saved query
body"), not a new one.

A revision snapshot SHALL hide a column that either the column policies the table had at that revision or the
column policies the table has **now** hide from the caller, so neither a restriction recorded then nor one added since
is bypassed by reading an older revision. A revision recorded before column policies existed is judged by the
policies it has now alone. The table's policies now SHALL be those of the **life** of the table that contains the
revision, a life running from a creation of the table to its next deletion: the live table's while that life has not
ended, and otherwise those of its state as recorded at the deletion that ended it. A table deleted since the revision
therefore keeps judging its revisions by the policies it last had, and a table created again under the same name
begins a new life whose policies SHALL NOT judge an earlier life's revisions. A column SHALL be matched by the name it carries in the revision: a current allowlist that lists only a
renamed column's new name hides it in a revision taken before the rename. An enrichment snapshot SHALL be judged by its
source's policies now. While the enrichment's own life has not ended, those are the policies of the source's life
that contains the revision, resolved the same way: the live source while that life has not ended, otherwise its state
as recorded at the deletion that ended it. Once the enrichment's life has ended, they are the source's policies as
they stood just before that deletion, so a later enrichment of the same name, granted columns on the source, does not
judge an earlier life's revisions. When no recorded state of the table or of its source can be found, every column of
the snapshot SHALL be hidden. A snapshot's `permissions.columns_restricted` SHALL be
judged over the revision's own columns, except on a system table's snapshot, which carries none and reports it `true`
exactly when a column policy, the revision's or the current one, hides or masks a column for the caller (see
[audit-trail](../audit-trail/spec.md), "Historical table snapshots").

#### Scenario: Every query surface omits a hidden column

- **GIVEN** a caller from whom column `c` of table `t` is hidden
- **WHEN** it requests `t`'s schema through discovery, runs `SELECT *` over SQL, a structured query with no explicit
  fields, a CSV export, and a Flight SQL query and `INFORMATION_SCHEMA.COLUMNS` read
- **THEN** none of them carries `c`

#### Scenario: Referencing a hidden column fails as unknown

- **WHEN** the same caller translates or executes a query that filters, groups or orders on `c`
- **THEN** the request fails exactly as for a column `t` does not have

#### Scenario: A saved query over a hidden column fails for that caller

- **GIVEN** a saved query, and a dashboard widget running it, that selects `c`, authored by a caller who sees `c`
- **WHEN** the caller from whom `c` is hidden runs the saved query and the widget
- **THEN** each run fails exactly as for a column `t` does not have, while the author's runs succeed

#### Scenario: The catalog read omits a hidden column

- **WHEN** the same caller reads `GET /v1/tables/t`
- **THEN** `c` is absent from its columns and `permissions.columns_restricted` is `true`

#### Scenario: An older revision hides a column the current policies hide

- **GIVEN** a revision of `t` at which the caller's only role had no column policy, and a later entry for that role
  with `columns` `["k"]`
- **WHEN** the caller requests `t`'s snapshot at that revision
- **THEN** only `k` is listed and `permissions.columns_restricted` is `true`

#### Scenario: A schema change cannot name a hidden column

- **GIVEN** a caller holding `modify` on `t` from whom `c` is hidden
- **WHEN** it patches `c`'s description
- **THEN** the request is refused as naming an unknown column

#### Scenario: A TTL cannot reach a policy-hidden column

- **GIVEN** a table with a `timestamp` column `c` hidden by a column policy from a caller holding `modify` on it
- **WHEN** the caller `PATCH`es `{"ttl":{"column":"c","interval":1,"unit":"day"}}`
- **THEN** the request is refused `422` as naming an undeclared column, and no `ALTER` is issued
- **AND WHEN** the table's stored TTL is on `c` and the same caller sends `{"ttl":{}}`
- **THEN** the response is `403 column_not_entitled` and the TTL is unchanged

#### Scenario: A deleted table's revisions are judged by the policies it last had

- **GIVEN** a revision `R` of table `t` recorded before column policies existed, a later entry for role `X` with
  `columns` `["k"]`, and then the deletion of `t`
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `t`'s snapshot at `R`
- **THEN** it shows column `k` only and `permissions.columns_restricted = true`

#### Scenario: A table created again does not judge an earlier life's revisions

- **GIVEN** a revision `R` of table `t` at which role `X` had no column policy, a later entry for `X` with `columns`
  `["k"]`, the deletion of `t`, and a new table `t` created with no entry
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `t`'s snapshot at `R`
- **THEN** it shows column `k` only
- **AND WHEN** the new `t` gains an entry for `X` with `columns` `["m"]` and the caller requests a snapshot of the new
  `t` recorded after that entry
- **THEN** that snapshot is judged by `["m"]`, and the snapshot at `R` still shows `k` only

#### Scenario: An enrichment snapshot is judged by its deleted source's last policies

- **GIVEN** an enrichment `e` of source `s`, a revision `R` of `e`, an entry on `s` for role `X` with `read` and
  `columns` naming `s`'s columns and none of `e`'s, and then the deletion of `e` and of `s`
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `e`'s snapshot at `R`
- **THEN** it lists no column
- **AND WHEN** `X`'s entry on `s` instead carried `read` with no `columns` before both deletions
- **THEN** the snapshot lists every column of `e` at `R`

#### Scenario: An enrichment created again does not judge an earlier life's revisions

- **GIVEN** a revision `R` of enrichment `e` of a live source `s`, at which role `X`'s entry on `s` carries `read` and
  `columns` `["k"]`; then `e` is deleted, created again with a column `label`, and `X`'s `columns` become
  `["k", "e.label"]`
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `e`'s snapshot at `R`
- **THEN** it does not show `label`

#### Scenario: Each life of a table recreated twice is judged by its own deletion

- **GIVEN** table `t` with a revision `R1` in its first life, which ends with role `X`'s `columns` `["k"]`; a second
  life with a revision `R2`, which ends with `X`'s `columns` `["m"]`; and a third, live life with no entry
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `t`'s snapshots at `R1` and at `R2`
- **THEN** the snapshot at `R1` is judged by `["k"]` and the one at `R2` by `["m"]`
