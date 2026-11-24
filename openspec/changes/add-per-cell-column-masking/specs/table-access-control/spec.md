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
is **row-unrestricted** when it has no row policy on the table. A **row-contributing** role is one the caller carries
whose row policy enters the caller's row filter (see "Row-policy evaluation by caller role") but which does not
govern, such as an entry carrying `rows` on a role with no read path. It allows a column when its own column policy
allows it **and** every governing role allows it, so it only narrows and never widens. Each of the table's columns
SHALL be, for the caller, exactly one of:

- **visible**, when some row-unrestricted governing role allows it, or when every governing role and every
  row-contributing role allows it;
- **masked**, when it is not visible and at least one governing role allows it;
- **hidden**, when no governing role allows it.

Each entry is one unit — a role's readers see that role's columns on that role's rows — and the rows a caller reads
are the union of its roles' row reach (see "Row-policy evaluation by caller role"). A visible column SHALL read its
stored value on every row the caller reads. A masked column's **mask condition** SHALL be the disjunction of the row
policies of the governing roles and the row-contributing roles that allow it, every one of which carries a row
policy, since a row-unrestricted governing role allowing the column would make it visible. On each row the caller reads, a masked column SHALL read its stored value
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
take no part, so it SHALL NOT lift a restriction and its row policy SHALL NOT enter a mask condition, unless it is a
row-contributing role. A role whose entry carries `rows` but which has no read path adds its rows to a confined caller
carrying it, by row-policy evaluation, and its `columns` then narrow what those rows show: a column its list leaves out
is masked, and absent on the rows only it adds. A rows-only entry whose `columns` is `null` narrows nothing, so it shows
the governing roles' columns on the rows it adds, as before this change. A caller with no governing role is covered
below.

A masked column SHALL be listed, named and referenced wherever a visible column is: schema discovery (which marks it,
see [query-schema-discovery](../query-schema-discovery/spec.md), "A masked field is marked"), the catalog read and
revision snapshots, `column_count`, the schema the query, translation, CSV-export and Flight SQL surfaces resolve
against, a schema change other than as a TTL column, catalog export and an import entry's `prev`. Where a
requirement elsewhere speaks of the columns "visible" to a caller — on those schema surfaces, or as the columns the
verbs `get`, `read` and `modify` act on (see "Per-table privilege grants") — a masked column is among them, its values
readable on the rows its mask admits. A TTL SHALL NOT be over a masked column: a `ttl.column` naming it is refused
`403 column_not_entitled`, and a stored TTL on it refuses every `ttl` member, because watching which rows a TTL removes
would disclose stored values of absent cells. The remaining
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

#### Scenario: Gaining a column-restricted role masks a confined role's columns to its rows

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

#### Scenario: Gaining a column-restricted role can hide a confined role's columns

- **GIVEN** a table `t` with columns `project`, `t`, `x` and `y`, so the hiding fallback applies; an entry for `A`
  with `read`, `rows` admitting `project = 'P1'` and `columns` `null`; and an entry for `B` with `read`, no `rows`
  and `columns` `["x"]`
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads every row with column `x` only, and nothing is marked masked, because masking would show `A`'s
  other columns on the rows only `B` admits and the fallback keeps the rule without masking

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
- **AND WHEN** `N`'s `columns` are `["project"]` instead
- **THEN** it reads the rows of `P1` and of `P2`; `project` carries its stored value on every row, and `a` carries its
  stored value on the rows of `P1` and is absent on the rows of `P2`, which only `N` adds

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
against the columns visible to the caller only, so naming a hidden column fails as naming an undeclared one (`422`)
and TTL validation never reveals its type or nullability; a `ttl.column` naming a column masked for the caller SHALL be
refused `403 column_not_entitled` naming it, before that validation; and while the stored TTL is on a column hidden from the
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

### Requirement: Per-role row policies

The system SHALL accept and store, for each catalog table other than an enrichment, at most one **row policy** per
raw provider role, as the `rows` member of the role's entry (see "Role-keyed access API"). A stored policy SHALL be
`{"filter": <filter node>}` — a predicate in the structured query's filter grammar (see
[structured-query](../structured-query/spec.md), "Filter sub-grammar (CQL2-JSON)" and "Expression grammar") over that
table's own columns. An enrichment SHALL accept none: the rows a caller reads through it are its source's (see "An
enrichment's read-side access is its source's").

There SHALL be no unrestricted policy shape: a role that may read the table and has no policy there already reads
every row (see "Row-policy evaluation by caller role"), so `rows: null` means no policy. A `rows` object carrying
neither `filter` nor `where` SHALL be refused `422 validation_error`; a `rows` member other than `filter` and `where`,
like any body that does not bind to the request type, SHALL be refused by the strict request mapper as
`400 bad_request` (see "Role-keyed access API").

A row policy SHALL **narrow** a read and SHALL NOT **grant** one. Whether a caller may read a table at all SHALL remain
decided by its global privileges and the table's grant lists, unchanged. A policy on a role with no read path to the
table SHALL grant no read: a caller carrying only that role reads nothing, while a caller confined by another of its
roles also reads that policy's rows (see "Row-policy evaluation by caller role"). Such an entry's `columns` narrow what
the rows it adds show and widen nothing: a column the list leaves out is absent on those rows (see "Column visibility
by caller role", row-contributing roles).

A policy's role SHALL follow the grantee rules: a raw provider role name, trimmed, non-blank, and not a bundle or
privilege name. `*` SHALL be a valid policy subject, with the meaning it has in a grant: its policy is one every
authenticated caller carries. Row policies SHALL be storable on `system` tables other than enrichments, which carry
the shared data a policy most often narrows. Deleting a table SHALL remove its row policies.

The filter SHALL be validated against the table's **own** stored columns — every one of them, sensitive columns and
columns a column policy hides included, independent of what the writing caller may see — and SHALL be refused when
it:

- references a column the table does not store, including a dotted enrichment field (a policy narrows the table it is
  stored on);
- uses a `param` expression other than a caller value (see "Caller values in row policies");
- calls a function outside the function catalog's scalar group, or one the catalog declares as calling a service
  outside the data store;
- fails any validation the structured query surface would apply to the same filter; or
- renders to a predicate longer than 16 KiB.

A filter MAY call the relative time functions (`now`, `date_add`, `date_sub`): a policy is evaluated on a read, where
a moving window is meaningful, never on a derivation path.

A stored filter SHALL be returned in the structured query's stable wire serialization, so a policy read back is a
filter the query surface accepts.

#### Scenario: A policy narrows a role to one project

- **WHEN** a caller holding `TABLE_GRANT` stores for role `X` on `dial_usage_log` an entry whose `rows` is
  `{"filter": {"op": "in", "args": [{"type": "field", "name": "project_id"}, {"type": "array", "items": [{"type": "value", "value_type": "string", "value": "X-PRJ-1"}]}]}}`
- **THEN** the entry is stored and reading `X`'s entry returns that policy under `rows`

#### Scenario: A policy names a column the table does not store

- **WHEN** a policy's filter references a field the table does not store, or a dotted enrichment field
- **THEN** the request is refused `422 validation_error` naming the field, and nothing is stored

#### Scenario: A policy filter may not take a parameter or aggregate

- **WHEN** a policy's filter contains a `param` expression that is not a caller value, an aggregate function, or a
  window expression
- **THEN** the request is refused `422 validation_error`, and nothing is stored

#### Scenario: A policy without a filter is refused

- **WHEN** an entry's `rows` is `{}`
- **THEN** the request is refused `422 validation_error`

#### Scenario: An unknown member does not bind

- **WHEN** an entry's `rows` carries a member other than `filter` and `where`, e.g. `{"all_rows": true}`
- **THEN** the request is refused `400 bad_request`, and nothing is stored

#### Scenario: A sensitive column may constrain a policy

- **WHEN** a caller holding `TABLE_GRANT` but no clearance stores a policy filtering on a sensitive column of the table
- **THEN** the policy is accepted, because it is validated against the table's stored columns rather than the caller's
  view

#### Scenario: A relative time window is a valid policy

- **WHEN** a policy's filter is `request_time >= date_sub('day', 90, now())`
- **THEN** the policy is accepted

### Requirement: Role-keyed access API

The system SHALL expose a table's whole access configuration as one resource per raw provider role, under
`/v1/tables/{name}/access`. Every method SHALL require `TABLE_GRANT`, because an entry discloses which provider roles
exist and what each may do; a caller without it SHALL receive `403 forbidden`.

An **entry** SHALL be `{"privileges": [...], "rows": ..., "columns": ...}`: `privileges` a subset of `get`, `read`,
`sensitive`, `write` and `modify` (see "Per-table privilege grants"), `rows` either `null` or the role's row policy
(see "Per-role row policies"), and `columns` either `null` or the role's column policy (see "Per-role column
policies"). A stored entry SHALL be returned with `privileges` de-duplicated in the order `get`, `read`, `sensitive`,
`write`, `modify`, `rows` as `{"filter": <filter node>}` or `null`, and `columns` as a sorted list or `null`.

- `GET /v1/tables/{name}/access`, with no `role` parameter, SHALL return `{"generation": <integer>, "roles":
  {"<role>": <entry>, ...}, "ineffective_grants": [...]}`, the members of `roles` sorted by role, with an empty
  `roles` object when the table has no entry, and the report described below covering every stored entry (see
  "Ineffective grants are reported on read").
- `GET /v1/tables/{name}/access?role=<role>` SHALL return `{"generation": <integer>, "role": "<role>", ...entry,
  "ineffective_grants": [...]}`, the report covering that entry only, and `404 not_found` when the role has no entry
  on the table.
- `PUT /v1/tables/{name}/access?role=<role>` SHALL take `{"generation": <integer>, "privileges": [...], "rows": ...,
  "columns": ...}` and SHALL **replace the role's whole entry**: an absent or `null` `privileges` SHALL mean `[]`, and
  an absent `rows` or `columns` SHALL mean `null`. Nothing about another role's entry SHALL change. The response SHALL be `200`
  with `{"generation", "role", "privileges", "rows", "columns", "ineffective_grants"}`: the entry as stored, the
  table's generation after the request, and the report described below. It SHALL NOT carry a `permissions` member; the caller's own permissions
  are reported by `GET /v1/tables/{name}`.
- `DELETE /v1/tables/{name}/access?role=<role>` SHALL remove the role's entry and answer `204`, or `404 not_found` when
  the role has no entry on the table. It SHALL carry no body; it MAY carry a `generation` query parameter, which makes
  the removal conditional (see "Access configuration generation").

An entry whose `privileges` is empty and whose `rows` and `columns` are both `null` SHALL NOT be stored: a `PUT` of one
SHALL remove the role's entry if it has one, and SHALL answer `200` with that empty entry, after which a
`GET .../access?role=<role>` answers `404`. An entry MAY carry `rows`, `columns` or both with an empty `privileges`: it then
grants nothing. Its `columns` narrow a caller where the role governs the caller's access to the table (see
"Column visibility by caller role"), a global read privilege included, and where its `rows` add rows to a confined
caller as a row-contributing role, narrowing what those rows show; they are ignored otherwise. Its `rows` take part
in "Row-policy evaluation by caller role", which combines the policy of every role a confined caller carries whether
or not that role may read the table: on a role with no read path to the table, `rows` therefore **adds** its rows to a
confined caller that also carries the role, rather than narrowing anything, and gives a caller carrying only that
role no read.

The role SHALL be carried as the `role` query parameter: the raw provider role as an ordinary URL-encoded query value,
decoded once. Any character SHALL be allowed, so every role is addressable: role `/analytics/viewers` is addressed as
`.../access?role=%2Fanalytics%2Fviewers`, `team%ops` as `?role=team%25ops` and `a;b` as `?role=a%3Bb`, and each is
listed under its decoded name. The decoded value SHALL be trimmed on every method, so `?role=%20X` names `X`. A `PUT`,
a `DELETE` or a one-role `GET` whose `role` is missing, or blank once trimmed, SHALL be refused `400 bad_request`
before any other check, and so SHALL one carrying `role` more than once, rather than joining the values into one role.
On a `PUT` the role SHALL follow the grantee rules: a bundle or privilege name SHALL be refused
`422 validation_error`. `*` SHALL be an ordinary key with the meaning given in "Per-table privilege grants". `GET`
and `DELETE` naming a role that has no entry, however spelled, SHALL answer `404`. The role SHALL NOT be accepted as a
path segment: `/v1/tables/{name}/access/<anything>` is an unknown path (`404 not_found`).

A `rows` member in a request SHALL be `{"filter": <filter node>}` or `{"where": "<condition>"}` (see "A row policy may
be written as a SQL condition"); it is stored and returned as `{"filter": ...}`.

`ineffective_grants` SHALL report, without failing the request, each privilege in the entry that changes nothing, and
each standing warning below, as an object carrying `role`, `key` (the privilege), a stable `reason` and a
human-readable `message`. It SHALL be an array, empty when nothing is reported. Items SHALL follow the entry's
`privileges` order, and for one privilege the order `lifts_row_policies`, `lifts_column_policies`,
`everyone_reads_all_rows`, `already_global`, `sensitive_without_verb`. The reasons are:

- `already_global` — the role already holds that privilege globally;
- `sensitive_without_verb` — `sensitive` for a role that holds none of `TABLE_GET`, `TABLE_READ` or `TABLE_MODIFY` on
  the table, globally or by any entry, nor `modify` on any enrichment of it (a source's clearance does change
  something for a role holding an enrichment's `modify`, because it extends that `modify` to the enrichment's
  sensitive columns);
- `lifts_row_policies` — `read` in the entry of `*` with `rows` `null`, on a table where another role's entry carries
  `rows`: every caller then carries a role that reads with no policy, so by "Row-policy evaluation by caller role"
  those policies confine no one;
- `lifts_column_policies` — `read` in the entry of `*` with `rows` and `columns` both `null`, on a table where another
  role's entry carries `columns`: every caller then carries a reading role with neither a row nor a column
  restriction, so by "Column visibility by caller role" those restrictions hide nothing;
- `everyone_reads_all_rows` — `read` in the entry of `*` with `rows` `null`, on a table that is not an enrichment,
  whatever the entry's `columns`: every authenticated caller then reads every row of the table. This is a standing
  warning, not an ineffective grant: it is reported for as long as the configuration stays, a table meant to be
  readable by everyone keeps carrying it, and it is reported beside `lifts_row_policies` and
  `lifts_column_policies` when those apply, so this one reason identifies a table open to everyone with no row
  restriction. It SHALL NOT suppress any other reason.

Stored state no `PUT` can produce any more — a `*` entry carrying `write` or `modify`, or an enrichment's leftover
`get`, `read` or `sensitive` — SHALL be judged by these same rules only; no reason reports that such a privilege
confers nothing. The report SHALL be built the same way in `none` security mode.

A `PUT` against a `system` table SHALL be refused `409 table_is_system` when the entry carries `write` or `modify`; its
other privileges, `rows` and `columns` SHALL be accepted (an enrichment, `system` or not, is governed by the
enrichment refusal below).

A `PUT` against an enrichment SHALL be refused `409 enrichment_follows_source`, naming the enrichment's source as the
table to configure instead, when the entry **as sent** carries `get`, `read` or `sensitive`, or a `rows` or `columns`
that is not `null` — so `"columns": []` and `"rows": {}` are `409`, not `422`. Nothing SHALL change. `GET` and `DELETE`
naming an enrichment SHALL behave as on any table, so state an earlier build stored there can be read and removed.

A `PUT`'s checks SHALL run in this order, the first failing one deciding the response:

1. a missing, blank or repeated `role` (see above), or a body that does not bind — malformed JSON, a member other than
   `generation`, `privileges`, `rows` and `columns`, a `rows` member other than `filter` and `where`, or a member of the
   wrong JSON type, including an item of `privileges` or `columns` that is not a JSON string (a number, `null` or a
   nested array) (`400 bad_request`). `generation` binds any JSON value and is judged by step 5;
2. a caller without `TABLE_GRANT` (`403 forbidden`);
3. an unknown table (`404 not_found`);
4. the enrichment refusal (`409 enrichment_follows_source`);
5. a `generation` that is absent, `null`, not a JSON integer, or outside the range of a signed 64-bit integer
   (`422 validation_error`);
6. an entry that passes every check below and equals, as it would be stored, the role's stored entry (an absent
   entry being the empty one): the request SHALL answer `200` with that entry and the table's current generation and
   SHALL change nothing, whatever `generation` it carries (see "Access configuration generation");
7. a conflicting `generation` (`409 access_conflict`, see "Access configuration generation");
8. the role (`422 validation_error`);
9. `write` or `modify` on a `system` table (`409 table_is_system`);
10. the remaining checks, each `422 validation_error` and in no specified order among themselves: an unknown privilege
   value, `*` with `write` or `modify`, a `rows` carrying both or neither of `filter` and `where`, the translation of a
   `where`, the row policy's own validation, and the column policy's validation.

A `DELETE`'s checks SHALL run in the order: a missing, blank or repeated `role` (`400 bad_request`), `403 forbidden`, unknown
table (`404 not_found`), a `generation` parameter that is not an integer within the signed 64-bit range
(`422 validation_error`), no entry for the role (`404 not_found`), and a conflicting `generation`
(`409 access_conflict`); a `DELETE` without a `generation` SHALL NOT answer `409 access_conflict`. A `GET` of one
role's entry SHALL check a blank or repeated `role` (`400`), `403`, then the table (`404`), then the entry (`404`);
`GET .../access` SHALL check `403`, then the table (`404`).

These rules constrain what may be stored, so they SHALL apply in `none` security mode too. Every `PUT` or `DELETE`
that changes the stored entry SHALL be recorded in the audit trail as one change to the table definition (see
[audit-trail](../audit-trail/spec.md)); a `PUT` that leaves the entry as it was SHALL record none.

#### Scenario: Create, read and remove an entry

- **WHEN** a caller holding `TABLE_GRANT` reads `GET /v1/tables/t/access` at `generation` 4, `PUT`s
  `{"generation": 4, "privileges": ["read"], "rows": {"where": "project_id = 'P-1'"}}` for role `X`, reads
  `GET /v1/tables/t/access?role=X`, then `DELETE`s `X`'s entry
- **THEN** the `PUT` answers `200` with `generation` 5, `privileges` `["read"]`, `rows` carrying the equivalent
  `filter` and `columns` `null`; the `GET` returns the same entry with `"role": "X"`; the `DELETE` answers `204`; and a
  further `GET /v1/tables/t/access` returns `"roles": {}` at `generation` 6

#### Scenario: A PUT replaces the whole entry

- **GIVEN** role `X`'s entry carries `read`, a `rows` policy and `columns` `["a", "b"]`
- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"generation": <current>, "privileges": ["read"]}` for `X`
- **THEN** the stored entry is `{"privileges": ["read"], "rows": null, "columns": null}`, and no other role's entry
  changed

#### Scenario: Privileges are de-duplicated and ordered

- **WHEN** a caller `PUT`s `"privileges": ["modify", "get", "modify"]` for a role
- **THEN** the response and every later read carry `["get", "modify"]`

#### Scenario: An empty entry removes the role

- **GIVEN** role `X` has an entry carrying `write`
- **WHEN** a caller `PUT`s `{"generation": <current>, "privileges": []}` for `X`
- **THEN** the response is `200` with an empty entry, `GET .../access?role=X` answers `404 not_found`, and `X` is
  absent from `GET .../access`
- **AND WHEN** a caller `PUT`s `{"generation": <current>, "privileges": []}` for role `Z`, which has no entry
- **THEN** the response is `200` with an empty entry and the generation unchanged, and no revision or activity is
  recorded for the table

#### Scenario: Reading one role that has no entry

- **WHEN** a caller holding `TABLE_GRANT` reads `GET /v1/tables/t/access?role=nobody`
- **THEN** the response is `404 not_found`

#### Scenario: Removing an absent entry is not found

- **WHEN** a caller holding `TABLE_GRANT` `DELETE`s the entry of a role that has none on the table
- **THEN** the response is `404 not_found`

#### Scenario: An unknown table is not found on reading or removing entries

- **WHEN** a caller holding `TABLE_GRANT` reads `GET /v1/tables/nope/access` or `GET /v1/tables/nope/access?role=X`,
  or `DELETE`s `/v1/tables/nope/access?role=X`, for a table that does not exist
- **THEN** each response is `404 not_found`

#### Scenario: A role containing a slash, percent sign or semicolon is an ordinary query value

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"generation": <current>, "privileges": ["read"]}` to
  `/v1/tables/t/access?role=%2Fanalytics%2Fviewers`, then to `?role=team%25ops`, then to `?role=a%3Bb`, in either
  security mode
- **THEN** each answers `200` with `role` `"/analytics/viewers"`, `"team%ops"` and `"a;b"` respectively,
  `GET /v1/tables/t/access` lists all three under those names, `GET` with each `role` value returns its entry, and a
  `DELETE` with each answers `204`

#### Scenario: A missing or blank role is a bad request

- **WHEN** a caller `PUT`s an entry to, or `DELETE`s, `/v1/tables/t/access` with no `role` parameter, or with
  `?role=%20`
- **THEN** the response is `400 bad_request`, before the privilege check, and nothing is stored
- **AND WHEN** a caller `PUT`s an entry to, `DELETE`s, or reads `/v1/tables/t/access?role=a&role=b`
- **THEN** each response is `400 bad_request`, and no entry for a role `a,b` or any other is stored

#### Scenario: The role is not a path segment

- **WHEN** a caller holding `TABLE_GRANT` reads `GET /v1/tables/t/access/X`
- **THEN** the response is `404 not_found`, as for any unknown path

#### Scenario: A role sent as a path segment meets the request firewall

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s an entry for role `a;b` as a path segment,
  `/v1/tables/t/access/a%3Bb`, instead of as `?role=a%3Bb`
- **THEN** the response is `400 bad_request` as an `ErrorView`, because the request firewall refuses an encoded `;` in
  any path, and nothing is stored

#### Scenario: The role is trimmed on read and removal

- **GIVEN** role `X` has an entry on `t`
- **WHEN** a caller holding `TABLE_GRANT` reads `GET /v1/tables/t/access?role=%20X%20`
- **THEN** the response is `X`'s entry with `"role": "X"`

#### Scenario: A stale generation is reported before an invalid role

- **GIVEN** an entry an earlier build stored for role `READ_ONLY_ANALYTIC`, removed after a client read the access
- **WHEN** that client `PUT`s an entry for `READ_ONLY_ANALYTIC` carrying the generation it read
- **THEN** the response is `409 access_conflict`, not `422`

#### Scenario: Two concurrent deletes of one entry

- **WHEN** two callers holding `TABLE_GRANT` `DELETE` the same role's entry concurrently
- **THEN** one receives `204` and the other `404 not_found`

#### Scenario: A DELETE does not conflict with a write to another role

- **GIVEN** entries for roles `X` and `Y`
- **WHEN** a caller's `PUT` for `Y`, carrying the current generation, locks the table's row first and a `DELETE` of
  `X`'s entry waits for it
- **THEN** the `PUT` answers `200`, the `DELETE` then answers `204`, and the generation has moved by two
- **AND WHEN** instead the `DELETE` commits first and the `PUT` carries the generation read before it
- **THEN** the `DELETE` answers `204`, the `PUT` answers `200`, because `Y`'s entry did not change after that read,
  and the generation has moved by two

#### Scenario: A caller without TABLE_GRANT is refused

- **WHEN** a caller holding `TABLE_READ` but not `TABLE_GRANT` reads `GET .../access`, reads `GET .../access?role=X`,
  `PUT`s or `DELETE`s an entry
- **THEN** each response is `403 forbidden`

#### Scenario: The privilege check precedes the table lookup

- **WHEN** a caller without `TABLE_GRANT` `PUT`s an entry for a table that does not exist
- **THEN** the response is `403 forbidden`

#### Scenario: An unknown table is reported before the generation and the body

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s, for a table that does not exist, a body with no `generation` whose
  `rows` carries both `filter` and `where`
- **THEN** the response is `404 not_found`

#### Scenario: A body that does not bind is refused first

- **WHEN** a caller `PUT`s `{"generation": 1, "list": ["x"]}`, or `{"generation": 1, "rows": {"all_rows": true}}`, or
  `{"generation": 1, "privileges": "read"}`, or `privileges` or `columns` given as `[["read"]]`, `[null]` or `[1]`
- **THEN** each response is `400 bad_request` naming the offending member where it is unknown, and nothing is stored

#### Scenario: A null privileges list means none

- **WHEN** a caller `PUT`s `{"generation": <current>, "privileges": null, "columns": ["a"]}` for a role
- **THEN** the response is `200` and the entry carries `privileges` `[]` and `columns` `["a"]`

#### Scenario: A bundle name is not a role

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s an entry with the current generation for role `READ_ONLY_ANALYTIC`
- **THEN** the response is `422 validation_error`

#### Scenario: The role is checked before a system table's write refusal

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"generation": <current>, "privileges": ["write"]}` for role
  `READ_ONLY_ADMIN` on `dial_usage_log`
- **THEN** the response is `422 validation_error`

#### Scenario: Read grants on a system table are accepted

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"generation": <current>, "privileges": ["read"], "columns":
  ["project_id", "model"]}` for `analytics-viewers` on `dial_usage_log`
- **THEN** the response is `200` and the entry is stored

#### Scenario: A write grant on a system table is refused

- **WHEN** a caller `PUT`s an entry carrying `write` for any role on a `system` table
- **THEN** the response is `409 table_is_system` and nothing is stored

#### Scenario: The system refusal precedes the other body checks

- **WHEN** a caller `PUT`s `{"generation": <current>, "privileges": ["write"]}` for `*` on `dial_usage_log`
- **THEN** the response is `409 table_is_system`, not `422`

#### Scenario: A read-side entry on an enrichment is refused

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s, for an enrichment, an entry carrying `get`, `read` or `sensitive`, a
  non-`null` `rows`, or a non-`null` `columns`, including `"columns": []`
- **THEN** each response is `409 enrichment_follows_source`, the message names the enrichment's source, and nothing is
  stored

#### Scenario: The enrichment refusal precedes the generation and the body

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s for an enrichment `{"privileges": ["read"]}` with no `generation`, or
  a body with a stale `generation` and `rows` carrying both `filter` and `where`, or an entry for role
  `READ_ONLY_ANALYTIC` carrying `rows`
- **THEN** each response is `409 enrichment_follows_source`

#### Scenario: An enrichment's write and modify are still granted

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"generation": <current>, "privileges": ["write"]}` for `labeler` on
  an enrichment
- **THEN** the response is `200` and the entry carries `["write"]`

#### Scenario: State left on an enrichment is read and removed

- **GIVEN** an enrichment on which an earlier build stored, for role `R`, a `read` grant and a row policy
- **WHEN** a caller holding `TABLE_GRANT` reads `GET .../access?role=R` on it and then `DELETE`s that entry
- **THEN** the read returns the entry as stored, and the `DELETE` answers `204`

#### Scenario: The response carries no permissions member

- **WHEN** a `PUT` succeeds
- **THEN** its body carries `generation`, `role`, `privileges`, `rows`, `columns` and `ineffective_grants`, and no
  `permissions`

#### Scenario: An ineffective grant is reported

- **WHEN** a caller `PUT`s `read` for a role that already holds `TABLE_READ` globally
- **THEN** the response is `200` and `ineffective_grants` carries `{"role": <that role>, "key": "read", "reason":
  "already_global", ...}`

#### Scenario: A clearance without a verb is reported

- **WHEN** a caller `PUT`s `{"generation": <current>, "privileges": ["sensitive"]}` for `pii-analysts` on a table on
  which that role holds no `get`, `read` or `modify`, globally or by any entry
- **THEN** the response is `200`, the entry is stored, and `ineffective_grants` carries an entry with `key:
  "sensitive"` and `reason: "sensitive_without_verb"`

#### Scenario: A source clearance used by an enrichment maintainer is not reported

- **GIVEN** a role holding nothing on a source and `modify` on one of its enrichments
- **WHEN** a caller `PUT`s `sensitive` for that role on the source
- **THEN** `ineffective_grants` carries no `sensitive_without_verb` entry for it

#### Scenario: A read for everyone that lifts column policies is reported

- **GIVEN** a table on which role `X`'s entry carries `columns` and `*` has no entry
- **WHEN** a caller `PUT`s `{"generation": <current>, "privileges": ["read"]}` for `*`
- **THEN** the entry is stored and `ineffective_grants` carries an entry for `*` with `key: "read"` and `reason:
  "lifts_column_policies"`
- **AND WHEN** the same `PUT` also carries `columns`, or `rows`
- **THEN** no such entry is reported

#### Scenario: Repeating a PUT records no revision

- **WHEN** a caller `PUT`s for a role exactly the entry it already has, with the current generation or an older one
- **THEN** the response is `200` with the entry and the generation unchanged, and no revision or activity is recorded
  for the table
