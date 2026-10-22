## Why

`sensitive` is modelled as the top rung of the read ladder (`TABLE_READ_SENSITIVE`), but it
qualifies other verbs rather than being one. The consequence surfaced as a delegation gap: an
admin can hand a department a table with `get`, `read`, `read_sensitive`, `write` and `modify`,
yet the department still cannot mark or unmark a column `sensitive`. That authority sits in
global-only `TABLE_GRANT`, because under the ladder model letting `modify` touch the flag would let
a schema author declassify a column and read it. The fix is to model sensitivity as an
**orthogonal clearance** that modifies `GET`, `READ` and `MODIFY`, not to add
`GET_SENSITIVE`/`MODIFY_SENSITIVE` duplicates of each verb. The privilege model has not been
released yet, so it can be renamed without a compatibility path.

## What Changes

- **BREAKING** Remove `TABLE_READ_SENSITIVE`. Add `TABLE_SENSITIVE`, a table-scoped **clearance**
  that confers nothing on its own and implies nothing. The implication DAG becomes
  `TABLE_GET → TABLE_LIST`, `TABLE_READ → TABLE_GET`, `TABLE_MODIFY → TABLE_GET`.
- A verb reaches sensitive columns only when the caller holds **that verb and `TABLE_SENSITIVE` on
  the same table**. Each is resolved separately as the union of global and per-table:
  - `READ` + clearance: sensitive values are readable. Without the clearance they are filtered, as today.
  - `GET` + clearance: sensitive columns appear in the catalog read surface.
    **BREAKING**: without the clearance, `GET /v1/tables/{name}` and `GET /v1/tables` now omit
    sensitive columns and do not count them in `column_count`.
  - `MODIFY` + clearance: the caller may set or clear the `sensitive` flag, declare a column
    `sensitive` (on `add` or `POST .../schema`), and rename, drop or update a
    sensitive column. Without the clearance a sensitive column does not exist for that caller
    (`422 unknown column`), and declaring or changing a column's `sensitive` flag is `403 forbidden`.
  - `WRITE` is unchanged, because it is a blind insert.
- **BREAKING** `TABLE_GRANT` no longer covers the `sensitive` flag. It only reads and replaces grant
  lists. The "self-elevation gated at the grant level" requirement is replaced by the clearance rule.
- **BREAKING** The grant-list key `read_sensitive` becomes `sensitive`. This applies to
  `GET`/`PUT /v1/tables/{name}/access` and to the `permissions` object on table responses
  (`permissions.sensitive`). No data migration: no stored grant list uses the key yet.
- **BREAKING** `roles-mapping` no longer accepts `TABLE_READ_SENSITIVE` (unknown value → startup
  failure, as for any unknown token). `READ_ONLY_SENSITIVE_ANALYTIC` now declares `TABLE_READ` +
  `TABLE_SENSITIVE`, and so confers exactly what it did before.
- A new `ineffective_grants` reason `sensitive_without_verb` is reported when a `sensitive` grantee
  holds none of `get`, `read` or `modify` on the table.
- The laundering subset rule is restated over the new lists. A derived table's `sensitive` list must
  be contained in the roles its read source lists under **both** `read` and `sensitive`. The `modify`
  list never needs checking, because without the clearance it reaches no sensitive column.
- Every requirement that names `TABLE_READ_SENSITIVE` as a query-surface entitlement is restated in
  terms of the clearance. Where the verb bar is already enforced this is a rename. Where the
  requirement means the full entitlement, it becomes "`TABLE_READ` and `TABLE_SENSITIVE`" (runner
  identity, scheduler system identity, pipeline author checks, saved-query publishing).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `authentication`: privilege set and bundles, implication DAG (the ladder loses its top rung), the
  sensitive-column authorization rule, and the replacement of the grant-level flag gate with the
  clearance rule.
- `table-access-control`: the `read_sensitive` key is renamed `sensitive`, clearance semantics, the
  `permissions.sensitive` flag, and the `sensitive_without_verb` ineffective-grant reason.
- `table-management`: the catalog read surface hides sensitive columns without the clearance; flag
  changes and sensitive declarations require `MODIFY` + clearance instead of `TABLE_GRANT`; hidden
  columns are unknown to schema mutations.
- `enrichment-runtime`: the laundering subset rule is restated over `read` ∩ `sensitive`; scan and
  group-assembly re-checks are renamed.
- `enrichment-registry`: sensitive-column entitlement and the runner identity posture are restated
  as `TABLE_READ` + `TABLE_SENSITIVE`.
- `aggregate-pipeline`: the scheduler's system identity privileges and the rollup-target subset rule.
- `class1-enrichment`: governance and materialize entitlement wording.
- `query-schema-discovery`, `structured-query`, `rest-sql-endpoint`, `query-translation-endpoint`,
  `sql-query-translation`, `flight-sql-endpoint`, `otel-sink`, `saved-queries`: the sensitive
  filter is renamed to the clearance. Behaviour is unchanged, because each surface already enforces
  its verb bar.

## Impact

- **API**: `/v1/tables/{name}/access` request and response keys; `permissions` on
  `GET /v1/tables[/{name}]`; the column inventory and `column_count` on those reads; new `403`/`422`
  answers on `PATCH .../schema` and `POST .../schema`; `ineffective_grants.reason`
  gains a value.
- **Config**: `roles-mapping` values (`TABLE_READ_SENSITIVE` becomes `TABLE_SENSITIVE`). No new
  property. `docs/configuration.md` changes only in its example mapping and its privilege list.
- **Catalog**: no migration (no stored grant list uses `read_sensitive`).
- **Code**: `Privilege`, `PrivilegeImplications`, `RoleBundle`, `PrivilegeScope`,
  `EffectivePermissions`, `TableAccess*`, `SensitiveColumnAccess`, `SystemIdentity`,
  `SensitiveGrantInvariant`, `TableSchemaService` / `TableSchemaCreationService` (gate replaced, and
  hidden-column resolution), `TableMapper` (inventory filtering), and error messages in the
  enrichment and aggregate validators and guards.
- **Consumers**: MCP `adas_*` table tools, the bundled tables demo page (since removed), the Grafana and Power BI
  provisioning docs, and `openspec/config.yaml` context (privilege list and ladder text).
