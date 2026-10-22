## Why

Every enrichment ADAS can express today is **row-grain**: one source row in, one label out. A large
class of analytics questions is not about a row but about a *set* of rows sharing a key — was this
conversation resolved, what was it about, how would you summarise it. Answering those with row-grain
rules is not merely inefficient; it is wrong, because the unit of judgement is the group.

The pieces are almost all present. Enrichment tables are keyed on an arbitrary declared source column
with `1:0..1` cardinality, and the auto-join is a **pure keyed lookup** — `sourceKey.eq(enrichmentKey)`
with no date or partition condition — so an enrichment registered with `grain_key = chat_id` over
`dial_usage_log` already joins correctly with **zero code change**. What is missing is on the control
and read side:

- **The registry has no way to say "fire per group, once the group is done."** `TriggerKind` is
  `{ON_INGEST, SCHEDULE}`; a group rule needs a third kind plus a declaration of the group key and of
  what makes a group *ready* (a terminating signal, an idle period, a staleness ceiling).
- **The runner cannot discover which groups changed, nor read a group's members.** The scan is
  row-oriented and projects only what a row-grain evaluator needs, and there is no endpoint that
  assembles a group. Without one the runner would have to name a table and write its own SQL, which
  the original design deliberately made impossible.

Readiness governance has to live server-side for the same reason filters do: the runner is never
trusted to self-govern. And group assembly has to live server-side because a group's members include
`sensitive` body columns, so the entitlement check must be applied at the point the rows are read.

## What Changes

- **`group` trigger in the rule registry.** `TriggerKind` gains `GROUP`. A group rule declares
  `group_by` (the group key) and `ready_when` (`{signal?, idle?, max_staleness?, cost_ceiling?}`).
  Governance enforces, each → HTTP 422 `rule_validation_failed`: `group_by` is present and **equal to
  the target enrichment's grain**; `group_by`/`ready_when` are rejected on a non-group trigger (which
  is how "`on_ingest` stays row-grain" is actually enforceable — a grain carries no row-vs-group
  marker); `ready_when.signal` parses under the same bounded Calcite grammar as a filter, boolean over
  the source's declared columns, with its referenced columns entitlement-checked; the duration fields
  parse; and at least one readiness condition is present, since a `ready_when` with none would never
  fire. A group rule carries no cron — the existing cron-iff-schedule constraint already ensures that.
  The sync feed inlines `group_by` and `ready_when` alongside the resolved evaluator.
- **Group-aware dirty-mark scan — the *existing* endpoint, not a new one.** For a group rule the scan
  additionally projects `group_by` and the `ready_when.signal` columns. Everything else — keyset,
  `final=1`, safety-lag, opaque cursor, sensitive re-check — is unchanged, and the activity timestamp
  the runner needs is already projected (the scan projects its version and identity columns under
  their own names). The runner learns which groups changed at a cost proportional to **new rows**, not
  to the number of groups.
- **Group-input assembly — `POST /v1/rules/{id}/groups:fetch`.** Body `{groupKey, limit?}` →
  `{rows[], truncated}`. ADAS resolves the rule's source and membership filter, orders by the source's
  total order, applies row **and response-byte** caps, and returns the assembled rows. The runner
  never names a table, a column set, or a filter, and never learns which table the group lives in.
- **Sensitive handling is entitlement, not stripping.** An entitled caller receives body columns; a
  non-entitled caller is rejected with HTTP 403 `sensitive_column_not_entitled`. The scan's egress
  check is extracted into one shared guard so the scan and the group fetch cannot drift apart.
- **A byte cap, not just a row cap.** A group whose members carry request/response bodies can be
  megabytes; `truncated` tells the runner its input was trimmed so a partial result is identifiable
  downstream.

Out of scope (locked): the conversations/turns system content (a separate change — the capability
needs no spine); the runner-side dirty-group state, readiness sweep, and per-group cost ceiling
enforcement, all of which live in the runner; `1:N` group values; map-reduce over long groups; any
seeded rule or system table. The verification fixture is a `chat_id`-grained enrichment created by
tests, not a migration seed.

## Capabilities

### New Capabilities

<!-- None. Both items extend the existing enrichment-registry and enrichment-runtime capabilities. -->

### Modified Capabilities

- `enrichment-registry`: a third trigger kind with its group key and server-validated readiness
  declaration, and the governance rules that bound it.
- `enrichment-runtime`: the scan's projection becomes group-aware for a group rule, and a new
  rule-scoped group-input assembly endpoint joins the scan and write-back on the data plane.

## Impact

**New code:** `service/enrichment/GroupFetchService`, `service/enrichment/model/ReadyWhen`,
`web/dto/enrichment/GroupFetch{Request,Response}`, `configuration/GroupFetchProperties`.
`support/SensitiveProjectionGuard` is extracted from `ScanService` and shared.

**Migration:** Postgres `V15__group_trigger.sql` — `group_by TEXT` and `ready_when JSONB` on `rule`,
the `trigger_kind` CHECK relaxed to include `'GROUP'`, and a `group_by`-iff-`GROUP` CHECK. No
ClickHouse DDL: a group enrichment is an ordinary `ReplacingMergeTree` keyed on its grain.

**Reuses:** the enrichment table and auto-join machinery unchanged, the rule-scoped scan, `CursorCodec`,
`FilterSqlValidator` (for `ready_when.signal`), `RuleAuthorizer`, `RuleWriteValidator`, and the
`ProvenanceColumns` write-back path — a group rule writes one row through the existing
`POST /v1/rules/{id}/rows`.

**Config:** `enrichment.group-fetch.default-rows`, `max-rows`, `max-bytes`, documented in
`docs/configuration.md`.

**Cross-change dependency:** independent of `class1-sql-enrichment`; both share only the rule registry.
The conversations/turns change consumes this capability and additionally needs the catalog-configured
cursor columns from `enrichment-scan-generalization`.
