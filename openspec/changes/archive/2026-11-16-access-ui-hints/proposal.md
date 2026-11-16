## Why

A client building an access editor has to guess two things today. It learns that the caller may not manage access
only from a `403` after the user presses save. And the warnings about a risky configuration, such as a read for
everyone that lifts another role's row policy, come only in the response to the `PUT` that caused them: after a
reload they are gone while the configuration stays, so the client recomputes them itself.

## What Changes

- The `permissions` object on table responses gains a boolean `grant`: whether the caller may use the table's access
  API, which is whether it holds `TABLE_GRANT`. It is present on every surface that carries `permissions` today.
- `GET /v1/tables/{name}/access` and `GET /v1/tables/{name}/access?role=<role>` gain `ineffective_grants`, the report a
  `PUT` already returns, computed over the entries as they are stored at the read.
- The report gains the reason `everyone_reads_all_rows`: `read` for `*` with no row policy, on a table that is not an
  enrichment. It is a standing warning rather than an ineffective grant, reported on `PUT` and on both reads for as
  long as the configuration stays, so a client detects "open to everyone, no row restriction" from one code.
- None of this changes what any caller may do. The fields are additive and the reason is new; no existing field or
  reason changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `table-access-control`: "Caller-effective privilege reporting" gains the `grant` flag; a new requirement puts
  `ineffective_grants` on both reads of the access API and adds the `everyone_reads_all_rows` reason.
- `table-management`: "Table responses carry caller-effective permissions" lists eight fields instead of seven.

## Impact

- `/v1/tables` list and get, create, description update and schema `PATCH` responses: one more boolean in
  `permissions`. Revision snapshots carry it too, since they reuse the table representation.
- `GET /v1/tables/{name}/access` with and without `role`: one more member. A `PUT` of `read` for `*` without `rows`
  reports one more item. `DELETE` is unchanged.
- Code: `EffectivePermissions`, `TableCallerPermissions`, the table DTO and mapper, the access DTOs, and the access
  service's read methods, which reuse the report the `PUT` path already builds.
- Docs: `docs/row-level-security.md` and the access section of `docs/release-0.24-upgrade.md`.
- No catalog schema change, no migration, no configuration key.
