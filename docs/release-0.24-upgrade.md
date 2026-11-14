# ADAS 0.24 — upgrade notes

What an environment has to do to move from **0.23** to **0.24**. An environment on an earlier build first
follows [`release-0.23-upgrade.md`](release-0.23-upgrade.md).

## Multi-series charts

A chart's `y_field` — on a saved query and on a dashboard widget's `inline_query` — is now an array of result
columns, one per series (see [`saved-queries`](../openspec/specs/saved-queries/spec.md)). Responses always carry the
array, so a client that reads `y_field` as a string must move to the array; a one-element array is the old
single-series chart. Requests may still send a string, and charts stored before the upgrade are returned as a
one-element array without a migration.

- **Rollback.** A build older than 0.24 cannot read a chart stored as an array: reading that saved query, the
  scope list it appears in, its revision snapshot, or a dashboard holding such a widget fails. Before rolling back,
  rewrite each stored `y_field` array to its first element in `saved_query.chart`, `saved_query_aud.chart` and
  `dashboard.widgets` (the nested `inline_query.chart`).

## The `*` grantee

`*` in a table's `get`, `read` or `sensitive` grant list, or as the role of a row policy, now stands for every
authenticated caller (see [`row-level-security.md`](row-level-security.md)). Earlier builds stored `*` as an
ordinary role name that matched only a caller carrying a provider role spelled `*`.

- **Before upgrading, look for a stored `*`.** A table that already names `*` in a read-side list becomes readable
  by every authenticated caller, with its sensitive columns when the list is `sensitive`. Remove the entry
  (`PUT /v1/tables/{name}/access`) unless that is what you want. After the upgrade, the startup log lists every
  table that names `*` and where. It skips an enrichment's `get`, `read` and `sensitive` lists, which confer nothing
  any more (and which the `V55` migration clears, a `*` included; see below); a `*` row policy on one is still listed.
- A `*` stored in `write` or `modify` grants nothing, and a later edit of that list is refused while it keeps it.
- **Flight SQL admits every authenticated caller.** The connection gate used to turn away a caller holding no
  privilege and no provider role; such a caller can now be reached by a `*` grant. Each call is still decided per
  table.
- **Rollback.** A build older than 0.24 reads `*` as an ordinary role name again. Callers who read a table only
  through a `*` grant lose access to it, and a `*` row policy confines nobody, so a caller it confined who reads the
  table another way sees every row. Delete `*` policies, or the read paths they narrow, before rolling back if a
  rollback must not widen reads.

## Row policies on the caller's own rows

A row policy may compare a `string` column with a value of the caller reading the table, `caller.user_hash` or
`caller.project`, and `PUT /v1/tables/{name}/row-policies/{role}` accepts the policy as a SQL condition in `where`
instead of `filter` (see [`row-level-security.md`](row-level-security.md#own-rows-comparing-a-column-with-the-caller)).
Nothing changes for stored policies.

- **`caller.user_hash` needs new settings.** It is derived the way DIAL Core derives its `user.id` log field, from
  `providers.<name>.logging-key`, `.logging-salt` and `.obfuscate-user-email` for an identity provider
  (see [`configuration.md`](configuration.md#identity-providers)), and from `API_KEY_LOGGING_KEY`,
  `API_KEY_LOGGING_SALT` and `API_KEY_OBFUSCATE_USER_EMAIL` for a per-user API key
  (see [`configuration.md`](configuration.md#api-key-authentication)). Set them to the values DIAL Core uses for
  the same provider; leave the API-key settings unset when Core's providers use different salts. The salt is a
  credential: keep it in the deployment's secret store. Without the settings a caller has no user hash, and a
  policy matching on `caller.user_hash` gives that caller no rows.
- **Rollback.** A build older than 0.24 refuses a policy that uses a caller value, so every read that policy
  confines fails. Replace or delete such policies before rolling back.

## An enrichment's read-side access is its source's

An enrichment is never queried on its own: every read of its rows has its source as `FROM`. Its `get`, `read` and
`sensitive` access is now its `source_table`'s, and it stores no row policy (see
[`table-access-control`](../openspec/specs/table-access-control/spec.md)). `write` and `modify` stay the
enrichment's own, and its own `modify` still lets a role read its definition. The Postgres migration `V55` removes
every enrichment's `get`, `read` and `sensitive` lists and its row policies, the seeded `dial_usage_log_payload`
included.

- **API.** `PUT /v1/tables/{enrichment}/access` with a non-empty `get`, `read` or `sensitive` list (`[" "]` counts),
  and every `PUT /v1/tables/{enrichment}/row-policies/{role}`, are refused `409 enrichment_follows_source`, in `none`
  mode too; the message names the source to grant instead. Empty lists are accepted, so a body echoed from `GET`
  still succeeds. The `enrichment_read_inert` reason is gone from `ineffective_grants`, and a source `sensitive`
  grant to a role holding `modify` on one of its enrichments is no longer reported `sensitive_without_verb`. On
  every table, a row-policy `PUT` resolves the table first, so one carrying both `filter` and `where` for a table
  that does not exist answers `404`, not `422`.
- **Who gains access.** A per-table reader of a source reads its enrichments' non-sensitive columns, and a holder
  of the source's clearance their sensitive columns, whatever the enrichment's own lists said: the payload
  enrichment and `usage_client_identity` now follow `dial_usage_log` (their body and personal-data columns are
  `sensitive`). A caller that may get a source, through its `get`, `read` or `modify` list, gets every enrichment
  of it: the listing, `GET /v1/tables/{enrichment}`, its revision snapshots, its inclusion in an export, and its
  non-sensitive columns in schema discovery and translation. A holder of an enrichment's `modify` and the source's
  clearance gains schema authority over the enrichment's sensitive columns. Until column-level grants exist, a
  column not meant for every reader of the source is hidden by marking it `sensitive`.
- **Who loses access.** A role granted `get`, `read` or `sensitive` on an enrichment but not on its source loses
  the enrichment's listing and definition, its columns, or its sensitive columns: on query surfaces they become
  unknown fields (`400`), schema edits of them answer `422` unknown column, and pipeline authoring and runner scans
  that reference them answer `403 sensitive_column_not_entitled`. A runner cleared only on
  `dial_usage_log_payload` stops scanning until it is cleared on `dial_usage_log`.
- **Enrichment row policies have no equivalent.** A policy that blanked an enrichment's values for a role is
  removed, and those values become visible on the source rows that role reads. A source policy can name only the
  source's own columns, so a condition on an enrichment column is refused `422`, and it hides whole rows rather
  than the enrichment's values on them. For each policy the first query below finds, decide before upgrading:
  mark the enrichment's columns `sensitive`, which hides them on every row from callers without the source's
  clearance, or write a source policy on source columns, which hides those rows entirely. A caller confined only
  by a removed enrichment policy is no longer refused `403 row_policy_confined` by preview, arming or the pipeline
  data plane over that enrichment.
- **The sensitive-grant subset rule is removed**, for enrichment and aggregate targets alike. A derived table's
  sensitive columns are read by its own clearance holders (for an enrichment, its source's), whether or not they
  are cleared on the table it derives from. `PUT /v1/tables/{name}/access` and both schema endpoints no longer
  answer `422 sensitive_grant_widens`; pipeline registration, enable and aggregate refresh no longer answer
  `422 pipeline_validation_failed` for a widening role; and grant edits, schema edits that mark a column
  sensitive, and `enrich` registration, enable and armed patch no longer answer
  `409 pipeline_declaration_unresolvable`. A pipeline's author must still be entitled to every sensitive column it
  reads, and a rollup column derived from a `sensitive` input column must still be declared `sensitive`.
- **`supports_final` changes** for a per-table reader of an `append` source that could not read its enrichment:
  `GET /v1/queries/entities` now reports `supports_final: true`, an absent `final` reads final, and `"final": true`
  or SQL `FINAL`, which answered `400 illegal_final`, now succeed.
- **Exports of everything** now include the enrichments of every source the caller may get, and are refused
  `403 sensitive_column_not_entitled` when one carries a sensitive column the caller lacks the source's clearance
  for, where the enrichment used to be left out. An imported enrichment of a source this environment already
  grants is readable at once by that source's readers.
- **Revision snapshots lag the migration.** `V55` writes no audit revision, so an enrichment's latest snapshot keeps
  showing the removed lists and policies until its next change.

**Before upgrading**, run both queries against the catalog database.

Who gains access: per enrichment, the source's `read` and `sensitive` grantees it does not name, and every policy
it stores.

```sql
SELECT e.name AS enrichment, e.source_table,
       ARRAY(SELECT jsonb_array_elements_text(s.access -> 'read')
             EXCEPT SELECT jsonb_array_elements_text(e.access -> 'read')) AS gains_read,
       ARRAY(SELECT jsonb_array_elements_text(s.access -> 'sensitive')
             EXCEPT SELECT jsonb_array_elements_text(e.access -> 'sensitive')) AS gains_sensitive,
       e.row_policies AS policies_removed
  FROM table_definition e
  JOIN table_definition s ON s.name = e.source_table
 WHERE e.type = 'ENRICHMENT';
```

Who loses access, per key; expected empty. Move any role it finds to the same key on the source. A role that
`roles-mapping` maps to the privilege globally keeps it whatever the lists say, and can be ignored.

```sql
WITH pair AS (
  SELECT e.name AS enrichment, e.access AS ea, s.access AS sa
    FROM table_definition e
    JOIN table_definition s ON s.name = e.source_table
   WHERE e.type = 'ENRICHMENT')
SELECT enrichment, 'sensitive' AS key, role
  FROM pair, jsonb_array_elements_text(ea -> 'sensitive') AS role
 WHERE NOT coalesce(sa -> 'sensitive', '[]'::jsonb) ?| ARRAY[role, '*']
UNION ALL
SELECT enrichment, 'read', role
  FROM pair, jsonb_array_elements_text(ea -> 'read') AS role
 WHERE NOT coalesce(sa -> 'read', '[]'::jsonb) ?| ARRAY[role, '*']
UNION ALL
SELECT enrichment, 'get', role
  FROM pair, jsonb_array_elements_text(ea -> 'get') AS role
 WHERE NOT coalesce(sa -> 'get', '[]'::jsonb) ?| ARRAY[role, '*']
   AND NOT coalesce(sa -> 'read', '[]'::jsonb) ?| ARRAY[role, '*']
   -- a '*' in a modify list confers nothing
   AND NOT (role <> '*' AND coalesce(sa -> 'modify', '[]'::jsonb) ? role)
   AND NOT (role <> '*' AND coalesce(ea -> 'modify', '[]'::jsonb) ? role);
```

- **During the rollout**, make no grant, row-policy or pipeline edits. Until every replica runs the new build, old
  replicas judge enrichments by their own, now empty, lists and still apply the subset rule: they may refuse
  enrichment columns to per-table readers of the source and to runner scans, and fail refreshes of rollups that
  read enrichment columns. These failures are expected, need no action, and stop when the rollout finishes.
- **Rollback.** `V55` has no undo, and an older build reads the cleared lists as the enrichments' own empty grants:
  per-table readers of a source and runner scans lose the enrichment columns, rollups that read them fail every
  refresh, and arming or patching the `enrich` pipeline filling an enrichment is refused. Before upgrading, save
  every enrichment's read-side lists and row policies (`GET /v1/tables/{enrichment}/access` and
  `.../row-policies`, or `SELECT name, access, row_policies FROM table_definition WHERE type = 'ENRICHMENT'`).
  After redeploying the previous release, re-apply them through its `PUT /v1/tables/{enrichment}/access`, sending
  only `get`, `read` and `sensitive`, and `PUT /v1/tables/{enrichment}/row-policies/{role}` with each saved filter.
  If a restore is refused `422 sensitive_grant_widens`, or a rollup still fails its refresh, a table was cleared
  after the upgrade for a role not cleared on a table it reads: narrow that table's `read` or `sensitive` list, or
  grant the role on the table it reads. Grant enrichments created after the upgrade the roles of their source's
  readers that should read them. Upgrading again does not re-run `V55`: then send each enrichment's `get`, `read`
  and `sensitive` empty and `DELETE` each of its policies, since the restored lists confer nothing and the
  restored policies keep narrowing.

| Change | Effect | Action |
|---|---|---|
| `*` means every authenticated caller in read-side grant lists and row policies; Flight SQL admits every authenticated caller | A `*` stored earlier as a role name opens that table to all callers | Remove unintended `*` entries before upgrading; check the startup log after; before a rollback, delete `*` policies that must keep confining |
| Chart `y_field` is an array (breaking response shape) | Saved-query and dashboard responses return `y_field` as an array; a string is still accepted on write | Move clients that read `y_field` to the array; before a rollback, rewrite stored arrays as above |
| Row policies may use `caller.user_hash` / `caller.project` | `caller.user_hash` matches no row until the `logging-*` settings are set | Set the `logging-*` settings to DIAL Core's values where own-rows policies are used; before a rollback, remove caller-value policies |
| An enrichment's `get`, `read`, `sensitive` and rows follow its source; `V55` clears its read-side lists and row policies | New `409 enrichment_follows_source` on `PUT .../access` (non-empty read-side list) and `PUT .../row-policies/{role}` on an enrichment; source readers and clearance holders gain its columns; enrichment-only grantees lose them; `supports_final` can turn true | Run the two queries above before upgrading; grant enrichment readers on the source; decide each enrichment policy's replacement; save the lists and policies for a rollback |
| `enrichment_read_inert` removed from `ineffective_grants` | A client matching on the code never sees it | Drop the code from clients |
| Row-policy `PUT` resolves the table first | A body with both `filter` and `where` for a missing table answers `404`, not `422` | None |
| Sensitive-grant subset rule removed | No more `422 sensitive_grant_widens` (access and schema endpoints), `422 pipeline_validation_failed` for a widening role (pipeline registration, enable, refresh), or `409 pipeline_declaration_unresolvable` (grant edits, schema edits marking a column sensitive, `enrich` registration and enable) | Review who holds each derived table's clearance: it no longer has to be cleared on what the table derives from |
