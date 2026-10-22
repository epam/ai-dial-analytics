## Why

A rule resolves every column it names against the **declared columns of one source table**, while a
pipeline — since `aggregate-reads-entity` — resolves against the **flat entity**: the source plus its
ACTIVE enrichments, the same namespace `GET /v1/queries/entities/{name}` publishes. That asymmetry
means a value an enrichment computes is available to a rollup and invisible to a rule.

Against a running deployment, `POST /v1/rules` with `source: dial_usage_log`:

```
422 rule_validation_failed
source 'dial_usage_log' does not declare the target enrichment's grain key
'client_session_id'; a rule's derived rows are keyed by that column
```

`client_session_id` is computed by the `usage_client_identity` enrichment; `dial_usage_log` is a
system table, so it cannot simply gain the column. This blocks session-level labelling (`session_insights`,
whose table and evaluator are already registered and waiting) — we can build the session spine but
not label it — and it blocks every future rule that would derive a value from one already derived.

The mechanism is already built: `ScanSourceMetadata` carries both models side by side, and the
validators that had to admit enrichment columns for the pipeline path already take the flag to do so.
Only the rule paths still pass the narrow one.

## What Changes

A rule's declaration resolves against the flat entity of its read source. Four reference surfaces,
one resolver:

- **Grain key / `group_by`** — may name an enrichment column by its qualified flat name
  (`"usage_client_identity.client_session_id"`). The scan projects it under **both** that name and the
  bare grain key: the qualified one is the spelling the Enrichment Runner addresses the key column by
  (`row.get(rule.groupBy())`, a miss there being a silent skip), the plain one is what the target is
  physically keyed on. Only a **group** rule may take its grain key from an enrichment — a row-grain rule
  has no `group_by` to declare it with, and would collapse many source rows onto one target key.
- **Governed SQL fragments** — `filter_sql`, `ready_when.signal`, `member_select.prefer_sql` and
  `member_select.order_by` may reference enrichment columns.
- **`input_bindings`** — may bind an enrichment column. Its qualified name is the projection alias;
  the binding's `var` is what the evaluator template sees, so no aliasing is needed here.
- **Class-1 output `sql` expressions** — may reference enrichment columns of the read source, so a
  Class-1 enrichment can derive from a sibling enrichment at the same grain.

Also:

- **No opt-in flag.** Widening is monotonic — enrichment columns are published only *qualified*, so
  they cannot shadow a source column, and `EnrichmentJoins.addReferenced` emits a join only for an
  enrichment a statement actually references, so a rule naming none renders identical SQL.
  `ScanSourceMetadata#sourceModel` is **removed**; it has no callers left.
- **The freshness clamp is reused on both rule windows.** `EnrichmentFreshness.bound(...)` bounds the
  scan window and the Class-1 materialize window to the materialized position of every enrichment the
  statement joins. This is the single correctness mechanism for all four surfaces: a row that does
  not enter the window cannot contribute a wrong group key, a wrong readiness verdict, a wrong prompt
  value, or a wrong derived value.
- **Registration refuses an enrichment read whose position cannot be established** — one written by
  a group-trigger rule or by a rule ADAS does not execute itself. Without an establishable position
  there is no clamp, and the loss is unrecoverable in both directions: a group rule's source cursor
  advances past an unmarked row, and a Class-1 materialize stores a wrong value once and moves on.
- **Registration refuses a dependency cycle** across rules' enrichment references, which would
  otherwise clamp two windows against each other and freeze both silently.
- **An enrichment whose history re-derived is reported, not cascaded.** A rule read gains the same
  freshness diagnostics a pipeline carries (`unclamped_reads`, `rebuild`), derived on read. ADAS does
  not re-derive downstream automatically: a cascade over an LLM rule's history spends money no
  operator approved.
- **An enrichment column feeding a group key or a Class-1 output must be declared `nullable`** —
  ClickHouse's `join_use_nulls` is 0, so an unmatched outer join on a non-nullable column renders its
  type default, indistinguishable from a derived value. Same rule the pipeline path already enforces.
- **ADAS emits the non-empty guard itself** for an enrichment-sourced group key
  (`<key> IS NOT NULL AND <key> <> ''`), rather than requiring the author to declare it in
  `filter_sql`. A declared requirement cannot be verified — any check is satisfied by `... OR true` —
  while an unguarded empty key collects every session-less row into one junk group.
- **Sensitivity crosses the join.** Reading a `sensitive` enrichment column is entitled exactly as a
  sensitive source column is, and a non-entitled caller's parse failure over a hidden enrichment
  column re-attributes to `403 sensitive_column_not_entitled` rather than surfacing as a malformed
  rule.
- **Drop and rename safety extends to a rule's enrichment references**, as it already does for a
  rule's declared read source.

No wire shape changes and no field is added: `group_by`, `filter_sql` and `input_bindings[].column`
simply accept a spelling they previously rejected. **No BREAKING changes** — every registered rule
validates and renders identically.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `enrichment-registry`: the read-source requirement gains the entity case — the grain key may come
  from an enrichment; the governed-SQL, `input_bindings` and `member_select` membership checks resolve
  against the flat entity; new refusals for an unclampable enrichment read, a dependency cycle, and a
  non-`nullable` enrichment column behind a group key; sensitive-column entitlement extends across
  the grain join.
- `enrichment-runtime`: which model each rule-scoped read resolves against and which joins it emits;
  the group key's projection alias and the emitted non-empty guard; the scan window's freshness clamp;
  the freshness diagnostics on a rule's state.
- `class1-enrichment`: expression governance resolves against the flat entity; the materialize window
  carries the same freshness clamp; the `nullable` requirement for an enrichment-sourced output.

## Impact

**Endpoints** — behavior only, no shape change: `POST`/`PATCH /v1/rules` (validation and the new
refusals), `GET /v1/rules/{id}/scan`, `GET /v1/rules/{id}/groups/{groupKey}`,
`POST /v1/rules/{id}/materialize`. `GET`/`POST`/`PATCH /v1/rules` responses gain optional freshness
diagnostics on the rule DTO, following `PipelineDto`'s existing fields.

**Code** — `service/enrichment/`: `ScanService`, `GroupFetchService`, `MaterializeService`,
`RuleGovernanceValidator`, `ExprSqlValidator`, `ScanSourceMetadata` (remove `sourceModel`).
`service/pipeline/AggregateSpecValidator`: its flat-entity field resolver is extracted to
`service/enrichment/support/` so rule and pipeline validation decide membership once — a second copy
is how a rule and a rollup start disagreeing about what a column means.

**No catalog schema change, no migration, and no new config key.**

**Deployment — one new writer grant.** The Class-1 `INSERT … SELECT` now joins the enrichments its
expressions reference, so the writer credential needs `SELECT` on any **enrichment** table a rule
references, not only on its read source. This mirrors the grant an aggregate pipeline already
requires and must be provisioned before such a rule is enabled.

**Runner (`analytics-enrichment-runner`) — no change required**, and verified end to end against a live
runner rather than by reading its code. `GroupMarkBuilder` reads the key column as
`row.get(rule.groupBy())` and `GroupWorkerService` writes it back under the same name, so the scan page
must carry the **qualified** spelling — publishing only the plain grain key made the runner scan, advance
its cursor and mark zero groups with nothing logged. With both names present it marked every group, and an
input binding over an enrichment column resolved through `row.get(binding.column())` as designed.

**Unblocks** session-level labelling: registering `session-insights-live` on `dial_usage_log` grouped by
`usage_client_identity.client_session_id`.
