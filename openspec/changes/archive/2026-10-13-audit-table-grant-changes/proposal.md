## Why

QA bug 7. On an active enrichment table, after adding a column and then editing the table, the Audit
tab shows two `Update / Table` rows 15 s apart, and each one's Before/After
(`GET /v1/tables/{name}/revision/{n}` vs `{n-1}`) marks nothing as changed.

A reproduction of the admin FE's exact requests shows that each row is a **Manage access** save
(`PUT /v1/tables/{name}/access`). Both are real changes: in `table_definition_aud` the two rows differ in
the `access` column and nothing else. The revision snapshot, however, is a plain `TableDto`, and that has
no member for the grant lists. A full admin's computed `permissions` are all `true` whatever the lists
say, so Before and After come back byte-identical. The "Edit table" popup, the column add and an
unchanged access save write no table revision at all.

This also contradicts the shipped `audit-trail` scenario "Access-list change is tracked", which says
"the snapshot carries the new lists". That has never been true, and no test covers it.

## What Changes

- `GET /v1/tables/{name}/revision/{revision}` gains `access` (the shape of `GET …/access`) and
  `row_policies` (the `policies` map of `GET …/row-policies`), as they stood at that revision.
  - Both are served only to a caller holding `TABLE_GRANT`, which is what the live reads of those
    lists require, and in `none` security mode.
  - For any other caller both members are absent.
  - Live `GET /v1/tables/{name}`, the list and the mutation responses are unchanged.
  - The FE diff compares every member, so the change becomes visible with no FE work.
  - `row_policies` is included because it is the only other audited table field with the same blind
    spot.
- `PUT /v1/tables/{name}/access` that only **reorders** roles within a list writes no revision. Today the
  multi-select can produce an `Update` whose sole difference is list order.
- `PUT /v1/tables/{name}/row-policies/{role}` with the policy the role already has writes no revision.
  Today it always does, because the JSONB text read back never equals the codec's output. That
  contradicts the spec's "idempotent: repeating it changes nothing".

No new endpoint, config key, migration or error code. The change is not breaking: the new members are
optional and appear only on the revision read.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `audit-trail`:
  - "Audited resources": row policies are named, the access-list scenario is corrected, and new
    scenarios cover the row-policy change and the reorder/re-save no-ops.
  - "Historical table snapshots": the grant members and their gate.
- `table-access-control`: "Row-policy management API", where the idempotency statement now holds for the
  audit trail.

## Impact

- **Code:**
  - `TableDto` gains two members.
  - `TableMapper` gains a snapshot overload and `RowPolicyCodec`.
  - `TableController.snapshot` applies the gate.
  - `TableAccessService.replaceAccess` gets a guard for order-only changes.
  - `RowPolicyService.put` gets a no-op guard.
  - The private `AccessDto`/`RowPolicyDto` converters move to shared code.
- **API:** the OpenAPI `TableDto` gains two optional members, documented as revision-only.
- **Tests:** two hand-built `TableMapper` constructors change, and OIDC integration coverage is added.
- **Not covered:** `READ_ONLY_ADMIN` (`AUDIT_READ` without `TABLE_GRANT`) still sees an empty diff for an
  access change, because it may not read grants live either (see design).
