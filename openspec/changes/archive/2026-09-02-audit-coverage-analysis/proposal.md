## Why

`audit-tables-and-rules` shipped the `audit-trail` capability covering only `table_definition` and
`column_mapping`, and explicitly deferred rules, evaluators, aggregate pipelines, and saved queries
as later increments once those surfaces stabilized. Since then the enrichment registry/runtime and
rate-analytics pipeline capabilities have kept moving, and nobody has re-assessed which of those
control-plane mutations are still unattributable today, or whether the deferred surfaces have in
fact stabilized enough to audit. Before proposing a second audit increment, we need a single
document that inventories every mutable control-plane entity, states whether it is currently
audited, and — where it is not — judges whether it is stable enough to justify auditing next.

This is an analysis change: it produces a report, not a code or spec change. No runtime behavior,
endpoint, schema, or capability changes as a result.

## What Changes

- Produce an audit-coverage report (`design.md`) that inventories every mutable entity behind the
  catalog and control-plane surfaces (`table_definition`, `column_mapping`, enrichment rules,
  evaluators, aggregate pipeline configuration, saved queries, and any others found in
  `service/*/`), and for each states: current audit status (audited / not audited), the mutation
  paths that can change it, and — for unaudited entities — a stability judgment on whether it is
  ready to be brought into `audit-trail` next.
- No code changes, no migrations, no new dependencies, no endpoint or DTO changes.
- No spec deltas: this change does not alter any shipped capability's requirements. It is scoped
  under `skip_specs: true`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. `audit-trail`'s requirements are unchanged; this change only assesses candidates for a future
increment.

## Impact

- **Code**: none touched or added.
- **Docs**: this change's own `design.md` is the deliverable (the coverage report). No changes to
  `README.md` or `docs/configuration.md`.
- **Follow-up**: findings here are expected to seed a future `audit-*` change proposal (e.g. a
  successor to `audit-tables-and-rules`) for whichever entities are judged ready.
