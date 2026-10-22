## Context

The reproduction used throwaway tests in `none` and `oidc` mode against an active enrichment table, sending the admin FE's exact
request bodies:

| Step | New `table_definition_aud` row? | Snapshot n vs n-1 |
|---|---|---|
| `PATCH /schema` add column | no (TableColumn rows only) | — |
| Edit-table popup, same values (`PUT /v1/tables/{name}`) | no | — |
| Edit-table popup, new description / `tag_order` | yes | differs |
| `PUT /access`, unchanged lists | no | — |
| `PUT /access`, `[]` → `[a,b]` | yes, only `access` differs | **identical** |
| `PUT /access`, `[a,b]` → `[b,a]` | yes, only `access` differs | **identical** |

Code paths:

- **Revision read.** `TableController.snapshot` → `AuditSnapshotService.snapshot` loads the historic entity
  and passes it to the same `TableMapper.toDto` the live read uses. That method builds a `TableDto`
  without `access` or `row_policies`.
- **Audited fields.** These two are the only audited `table_definition` fields that `TableDto` does not
  serve.
- **Writers.** The only writers are `TableAccessService.replaceAccess` (`:113`) and
  `RowPolicyService.put`/`delete` (`:61`, `:79`).
- **Access lists.** `TableAccess` is dirty-checked by value (`@EqualsAndHashCode`), but `normalize`
  (`:210-222`) keeps request order, so a reorder is "dirty".
- **Row policies.** `row_policies` is a `String` on JSONB, so its dirty check always fires.

## Goals / Non-Goals

**Goals:**

- An access or row-policy change is visible in the Before/After to a caller who may read grants.
- An access save that changes no role set, or a row-policy re-save, records no activity.

**Non-Goals:**

- Showing grants to callers who cannot read them live. This includes `READ_ONLY_ADMIN`.
- Grouping or hiding activities in the feed. Each row in the bug is a real, separate save.
- Rewriting the empty revisions already in the trail.

## Decisions

### D1. Optional members on `TableDto`, filled only on the revision read

Add `access` (`AccessDto`) and `row_policies` (`Map<String, RowPolicyDto>`) to `TableDto`, populated by a
`TableMapper.toDto(view, includeGrants)` overload.

- `TableController.snapshot` passes the gate result. Every other caller passes `false`, so their JSON is
  unchanged (`TableDto` is `NON_NULL`).
- When the members are included, both are always present: `{}` and five lists, even when empty. Absent
  therefore means *withheld*, never *none*.
- A null historic `access` maps to empty lists. A pre-V45 NULL `row_policies` maps to `{}` (via
  `codec.read`).
- The `TableAccess → AccessDto` and `RowPolicy → RowPolicyDto` helpers move out of the two controllers into
  shared code, so the snapshot cannot drift from the live reads.

*Rejected:*

- **A separate `TableRevisionDto`.** The FE diffs snapshots generically, and the documented contract is
  "same shape as `GET /{name}`". A superset, visible only to callers who could read the extra members live,
  keeps that contract.
- **Mirroring the members on the live `GET`.** The FE diff reads snapshots only, so this would widen the
  live contract for nothing.

### D2. Gate on global `TABLE_GRANT`

`includeGrants = !securityMode.isEnforced() || claims.hasPrivilege(Privilege.TABLE_GRANT)`. This is the form
`ImportTargetReader:90` uses, and it mirrors the live reads:

- `GET …/access` and `GET …/row-policies` are both `@RequiresPrivilege(TABLE_GRANT)`, global-only.
- In `none` mode those reads are open, so the snapshot always carries the members.

A past grant is therefore never more visible than the present one.

*Rejected:*

- **Gating on `AUDIT_READ`.** It would hand grant contents to `READ_ONLY_ADMIN`, a privilege split that
  exists on purpose.
- **Serving `{}` or `[]` to an unprivileged caller.** It would read as "the table has no grants".

### D3. An order-only access change writes nothing

In `TableAccessService.replaceAccess`, skip `setAccess` when the merged lists equal the current ones as
sets, compared per privilege. The stored order is kept, so `GET …/access` output is unchanged.

The same comparison runs per list on a save that does change something: a list whose role set is
unchanged keeps its stored order. Otherwise a save that adds one role to `get` would also show a reordered
`write` in the before/after.

*Rejected:* sorting in `normalize`. It also works, but every table's stored order would drift to sorted on
its next write.

### D4. A re-saved identical row policy writes nothing

`RowPolicyService.put` already decodes the current map (`:59`), so it can return the stored policy without
calling `setRowPolicies` when `stored.equals(current.get(grantee))`.

- `RowPolicy` and its `FilterNode` model are records, so `equals` is structural.
- Nothing else bumps the entity: there is no `@Version`, `@PreUpdate` or listener.
- `delete` needs no guard. An absent role is already `404`, and a present role always changes the map.

## Risks / Trade-offs

- [`READ_ONLY_ADMIN` still sees an empty diff for an access change] → accepted. It cannot read grants live
  either, so showing them in the past would widen its reach. That is a product decision outside this bug.
- [A future filter-grammar change makes old `row_policies` unreadable] → the snapshot already decodes
  historic policies for `permissions.rows_restricted`, so this adds no new failure mode.
- [Empty revisions already in the trail] → they stay. Access-only revisions become readable retroactively,
  because the members are read from the existing `_aud` rows. Historic identical row-policy re-saves stay
  empty.

## Migration Plan

None. This is a plain rollout. To confirm against a running deployment, run:

`select rev, access, row_policies from table_definition_aud where name = 'test1' order by rev`

Only `access` should differ between the two revisions.

## Open Questions

_None blocking._ Found during review and left out of scope:

- `PipelineGenerationService.onTargetColumnsChanged` (~`:142`) rewrites an unchanged pipeline `spec`
  (String on JSONB), which can record an empty `Update / Pipeline`. This was found by reading the code and
  has not been reproduced.
- A snapshot resolves `permissions` from the historic access lists but filters sensitive columns using the
  caller's **live** clearance.
- FE: the Manage-access Save could be disabled when no role set changed.
