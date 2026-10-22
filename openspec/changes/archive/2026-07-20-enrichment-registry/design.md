## Context

ADAS is the governed gate to ClickHouse. The catalog today models source/enrichment/aggregation
tables (`web/controller`, `web/dto` records + `@Schema`, `web/mapper`, `service`,
`configuration`; Postgres metadata store, migrations to `V9`). The Enrichment Runner
needs a control-plane where evaluators and rules are registered and **statically governed once**,
then synced — so filters, entitlements, type-compatibility, and write targets are enforced by
ADAS rather than trusted to the runner. This change (plan item B1 + B5 write grants) adds that
registry. Its `rule`/`enrichment_grain` records are what the sibling `enrichment-runtime` change's
rule-scoped scan/write endpoints resolve.

Reusable machinery already in the codebase: the Calcite SQL front-end (used by `/execute-sql` and
the translation endpoints) for parsing/column-extraction of `filter_sql`; the DIAL Api-Key
introspection path (`CoreApiKeyIntrospector`) for confirming a `model` is a real deployment; the
per-table JSONB `access` model owned by the in-flight `rework-uac-role-based-access` for
entitlement and B5 write grants; and the enrichment `grain`/`parent` records used to derive a
rule's source/grain.

## Goals / Non-Goals

**Goals:**
- Evaluator definitions that are **immutably versioned** (edit = new version), with `latest`
  resolution at sync time.
- Rules that bind an evaluator to a target enrichment, with **source/grain derived** (never
  re-declared), removing a whole mismatch class.
- A **static governance gate** that rejects bad rules at registration: type/column compatibility,
  bounded parseable filter, entitlement, real DIAL model, allowed trigger, parseable JSONata, and
  one-rule-per-target.
- A **sync feed** that returns enabled rules with the resolved pinned evaluator inlined plus a
  `generation` for cheap change detection.
- B5 **write grants** so the runner can write only its target enrichments — no generic table-write.

**Non-Goals:**
- The scan/write data-plane and keyset engine (→ `enrichment-runtime`).
- Any dynamic dry-run or rendered/DIAL-calling preview — deferred from both services; the JSONata
  + template rendering engine lives only in the runner. ADAS does **static parse** of JSONata,
  never evaluation.
- Multi-writer / independent-cadence-per-label on one enrichment (needs a column-coalescing
  engine — deferred).
- Triggers other than `on_ingest`/`schedule` (reject `group`/`push` at the gate).

## Decisions

**D1 — Two-table immutable evaluator versioning.** `evaluator(name PK, latest_version, …)` +
`evaluator_version(name, version, …, PK(name,version))`. Editing writes a new `evaluator_version`
row; existing rows never mutate; `latest` = `max(version)`. *Alternative rejected:* a single
mutable evaluator row — a rule pinned to an evaluator could then silently change behavior
underneath it; immutable versions make a pinned rule reproducible.

**D2 — Rules derive source/grain from the target enrichment.** A rule names only its
`target_enrichment`; the scan source and grain come from that enrichment's `parent`/`grain`
records. *Alternative rejected:* re-declaring source/grain on the rule — introduces a mismatch
class (rule source ≠ enrichment source) with no benefit.

**D3 — Static governance gate reusing existing front-ends.** `FilterSqlValidator` wraps the
existing Calcite SQL front-end (parse + column extraction; enforce boolean, bounded grammar, no
joins/subqueries/CTEs); a shared `TypeMapping` util maps JSON output-var types ↔ ClickHouse column
types; the DIAL Api-Key introspection path confirms `model`; JSONata expressions are parsed with a
pinned parser. *Alternative rejected:* a dynamic dry-run (render + call DIAL) at registration —
explicitly deferred; keeps the mapping/template engine solely in the runner and avoids drift.

**D4 — JSONata parser pinned to the runner's implementation/version.** Add `com.dashjoin:jsonata`
at the **same version the runner uses**, so an expression that parses at registration cannot fail
on a dialect difference at runtime. *Alternative rejected:* a different/looser parser — would let
registration-time validation diverge from runtime behavior.

**D5 — One rule per `target_enrichment`, enforced in the DB.** `UNIQUE(target_enrichment)`;
second rule → 409. Enrichment tables are RMT with full-row replace by grain, so two rules writing
one grain clobber each other; multi-label is one evaluator with multiple output columns.
*Alternative rejected:* a column-coalescing multi-writer engine — deferred (real complexity, no
current need).

**D6 — Sync feed carries a `generation` ETag and resolves the pinned evaluator inline.**
`GET /v1/rules[?updated_since=…]` (enabled-only) inlines the concrete evaluator version (resolving
`latest` at sync time) so the runner has everything to execute without a second round-trip; the
per-rule `generation` (bumped on every mutation) drives cheap change detection.
*Alternative rejected:* returning evaluator references only — forces N extra fetches per sync.

**D7 — Entitlement and B5 write grants reuse the `rework-uac` `access` model.** Sensitive-column
entitlement and the runner's per-target `WRITE` grant (via existing
`PUT /v1/tables/{name}/access`) both build on that model rather than a new permission type. No
requirement change to `table-access-control` is authored here to avoid colliding with the in-flight
change; land after `rework-uac-role-based-access` merges. *Fallback:* if the model isn't ready,
gate entitlement on `FULL_ADMIN` temporarily and tighten later.

## Risks / Trade-offs

- **Coordinating with `rework-uac-role-based-access`** → build on its `access` model, don't fork
  it; land after it merges. Migration `V11` may be claimed by it → re-check number at apply and
  renumber.
- **JSONata dialect drift between ADAS and the runner** → pin the exact same
  `com.dashjoin:jsonata` version in both.
- **Calcite grammar drift vs the structured-query allowlist** → `FilterSqlValidator` reuses the
  existing front-end and enforces the same bounded grammar, so filter validation can't diverge
  from what the scan later runs.
- **Type-mapping mismatch (JSON ↔ ClickHouse)** → single shared `TypeMapping` util used by both
  the output-binding check here and the runtime write validation.
- **Immutable versions accumulate** → acceptable; versions are cheap rows and enable reproducible
  pinned rules; pruning can come later.
- **DIAL model existence check depends on Api-Key introspection availability** → reuse the
  existing path; treat introspection failure as a 422 with a clear message rather than a hard 500.

## Migration Plan

1. Land after `rework-uac-role-based-access` merges (shared `access` model).
2. Add `com.dashjoin:jsonata` (runner-pinned version) to `build.gradle`.
3. Add Postgres `V11__enrichment_registry.sql` (evaluator, evaluator_version, rule;
   `UNIQUE(target_enrichment)`, `UNIQUE(rule.name)`, indexes on `updated_at`/`enabled` for sync).
   Confirm `V11` still free at apply.
4. Implement services (`EvaluatorService`, `RuleService`, `RuleGovernanceValidator`,
   `FilterSqlValidator`, `TypeMapping`), controllers, DTOs, mappers, `@ExceptionHandler`s.
5. Wire B5 write grants on top of the existing per-table access endpoint.
- **Rollback:** the registry is new surface (tables + endpoints); drop the migration / remove the
  endpoints without affecting existing catalog or query paths.

## Open Questions

- Exact `com.dashjoin:jsonata` version the runner pins — must match before merge.
- Confirm `V11` (Postgres) is still free at apply (rework-uac may claim it).
- The precise seam shared with `enrichment-runtime` for reading `rule`/`enrichment_grain` (shared
  read repository vs. runtime depending on these entities) — resolved when the two changes
  integrate.
- Whether `sampling`/`cadence`/`rate_rpm` need any static bounds validation at the gate, or are
  purely runner-interpreted knobs (leaning: store-and-forward, minimal validation).
