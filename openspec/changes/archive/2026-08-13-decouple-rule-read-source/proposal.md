## Why

A rule's **read** source is not declarable — it is derived as `target_enrichment.source_table`
(`RuleService#sourceOf`, `RuleGovernanceValidator#validate`), and `SourceScopeGuard` then pins every
governed SQL fragment the rule carries (`filter_sql`, `ready_when.signal`, a `sql` evaluator's output
expressions) to that one table. Because an enrichment's `source_table` is also the **read-side**
attachment — it is what builds the auto `LEFT JOIN` on the shared grain key — the two meanings are
conflated, and an enrichment can only ever be derived from the table it hangs off.

That forbids a shape the catalog already supports everywhere else: `conversations_feedback` is an
enrichment of `conversations` (grain `chat_id`), but the values that fill it live in `rate_analytics`
(which carries `chat_id` too). Today registering that rule fails the gate with 422
`rule_validation_failed` — "filter must read only its rule's source 'conversations'" — with no way to
express the intent. The same wall blocks any enrichment whose grain sits on one table while its
evidence sits on another.

## What Changes

- A rule MAY declare its own **read source** (`source` on `POST`/`PUT /v1/rules`), naming an existing
  `source`-kind table. Omitted, it defaults to `target_enrichment.source_table` — so every rule
  registered today keeps its exact current behavior, and this is **not** a breaking change.
- The read source, declared or derived, becomes the single table the rule reads: `filter_sql`,
  `ready_when.signal` and Class-1 `sql` expressions validate against it, and scan / group-input
  assembly / materialize page it.
- `SourceScopeGuard` is **kept, not removed** — only re-pointed at the read source. Both of its checks
  (translated entity equals the source; every referenced column is a declared column of it) stay, so
  the sensitive-column entitlement gate stays total and a fragment that substitutes its own `FROM`
  is still rejected at registration rather than at execution.
- The invariant the change gives up (read source == target's `source_table`) is replaced by two
  narrower ones, enforced at register and enable:
  - the read source SHALL declare the target enrichment's **grain-key column**, at a type compatible
    with the one the target is keyed at, so derived rows can be keyed by grain and the read-side join
    still resolves;
  - the read source SHALL declare **scan metadata** (`version_column`/`identity_column`), which the
    incremental scan already requires — checked at registration instead of at the first scan.
- `GET /v1/rules` (runner sync) and rule reads report the rule's read source in the existing `source`
  field, and `version_column` becomes that source's version column. The generation bump on a
  `version_column` repoint follows the read source, so a rule is marked changed by the table it
  actually scans.
- An enrichment's `source_table` keeps its meaning untouched: immutable, read-side, driving the grain
  auto-join, schema flattening, source-delete blocking, and grain-key validation.
- Class-1 (`sql`) rules keep the row-grain rule unchanged in substance — the target's grain key must
  equal the read source's `identity_column` — now resolved against the read source. A conversation-grain
  enrichment fed from `rate_analytics` therefore remains a `group`/LLM rule, not a Class-1 rule, because
  one value per conversation from many rate rows is an aggregation this capability excludes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `enrichment-registry`: rule declaration gains an optional `source`; the governance gate's
  source-scoping requirement is re-pointed at the rule's read source and gains the grain-key-present
  and scan-metadata-present checks; the sync feed's `source`/`version_column` describe the read source.
- `enrichment-runtime`: the scan, cursor, and group-input assembly are defined over the rule's read
  source rather than the target enrichment's `source_table`; the `version_column` repoint bump follows
  the read source.
- `class1-enrichment`: expression governance and the row-grain-target check resolve against the rule's
  read source.
- `table-management`: deleting a source a rule declares as its read source is blocked with HTTP 409,
  the protection an enrichment-attached source already has.

## Impact

**API** (additive, backward compatible)
- `POST /v1/rules`, `PUT /v1/rules/{id}`: new optional `source` field (`CreateRuleRequest`).
- `GET /v1/rules`, `GET /v1/rules/{id}`: `source` and `version_column` now describe the read source
  (identical values for every rule that omits `source`).

**Catalog schema**
- New nullable `rule.source_table` column (PostgreSQL migration `V26`), with an FK/validation to an
  existing `source`-kind table. Null = derive from the target enrichment, which is what every existing
  row means.

**Code**
- `service/enrichment/`: `RuleEntity`, `RuleService` (`sourceOf`, `resolve`, `resolveForScan`, `sync`,
  `versionColumnsOf`, `apply`), `RuleGenerationService` (bump by read source),
  `repository/RuleRepository` (query rules by declared read source), `model/RuleRegistration`.
- `service/enrichment/validation/`: `RuleGovernanceValidator` (source resolution + the two new checks);
  `SourceScopeGuard` and `FilterSqlValidator`/`ExprSqlValidator` need no logic change — they are already
  parameterized by source.
- `web/dto/enrichment/` + `web/mapper/`: `CreateRuleRequest`, `RuleDto` doc, rule mapper.
- `service/table/TableCatalogService#delete`: refuse a source a rule declares as its read source (409),
  alongside the existing enrichment and pipeline-input checks.
- `ScanService`, `GroupFetchService`, `MaterializeService`: no logic change — all three already read
  `ResolvedRule#source()`; they inherit the new resolution.

**Docs**
- `docs/` rule/enrichment documentation describing when to declare `source`, and the two invariants
  that replace the old one.

**Not affected**
- The rule-scoped write-back (`POST /v1/rules/{id}/rows`) resolves grain only.
- Table management: `source_table` semantics, immutability, and grain-key validation are unchanged.
- The credential split, the sensitive-column chokepoint, and the one-rule-per-target invariant.
