## Why

Two gaps surfaced while the enrichment runner implemented the group path against the shipped
group-enrichment capability. Both are ADAS-side: the runner cannot work around either without giving
up a property the design promised.

- **The source's version column is not on the wire.** A group rule's activity timestamp *is* the
  source's version column, and the scan already projects it under its own name — but the sync feed
  never says what that name is. The runner therefore has to configure it (`GROUP_VERSION_COLUMN`),
  which makes a per-source fact into a per-process one: one name for every source it scans. A group
  rule over a source whose version column is named differently dead-letters every scanned row at
  `input_map` until an operator notices. ADAS already has the fact — `table_definition.version_column`,
  the same value the scan resolves through `ScanSourceMetadata` — so the fix is to surface it, not to
  derive it.
- **A truncated group's label is indistinguishable from a complete one.** `groups:fetch` returns
  `truncated` precisely so a partial input stays identifiable downstream, and the runner trims further
  against its own token budget. But that fact dies at the runner: the permitted provenance set is
  `{evaluator_version, model, enriched_at}`, so there is nowhere to record it, and nowhere to record
  *which* version of the group was judged either. A reader of the enrichment sees a label and cannot
  tell whether it was formed from the whole group, from a trimmed prefix of it, or from a group that
  has since moved on. Only the runner's own `group_state` knows, in its Postgres — invisible to any
  query over the enrichment.

## What Changes

- **`version_column` on the rule DTO.** `GET /v1/rules`, `GET /v1/rules/{id}`, and rule create/update
  return the source's declared version column alongside the existing derived `source`/`grain_key`. It
  is resolved from the source's catalog scan metadata — the same fact the scan path resolves — and is
  **absent** when the source declares none, so a rule read never fails because its source is not
  scannable (the scan path remains where that becomes a 422). The sync feed resolves it in one extra
  batched fetch, keeping the feed's constant query count.
- **`group_version` and `truncated` join the permitted provenance set.** Both are ordinary un-prefixed
  user columns, optional on the target like every provenance column: permitted on
  `POST /v1/rules/{id}/rows`, silently skipped when the target does not carry them, and queryable as
  lineage when it does. `group_version` records which version of the group produced the label;
  `truncated` records whether that input was complete. The Class-1 materialize path skips both, as it
  already skips `model` — a `sql` evaluator makes no model call and governance forbids it the group
  trigger.

Un-prefixed is not a cosmetic choice: the `_` prefix is reserved for ADAS's system columns, which are
not caller-settable at all, and a user-declared enrichment column may not start with `_`. A
`_group_version` column could neither be declared on a target nor written by the runner.

Out of scope: any change to how the runner computes `group_version` (that stays `max(version)` over
the members it saw, runner-side); retiring the runner's `GROUP_VERSION_COLUMN` config key (a runner
change this unblocks); making provenance columns mandatory on a target; seeding them onto existing
enrichments.

## Capabilities

### New Capabilities

<!-- None. Both items extend existing capabilities. -->

### Modified Capabilities

- `enrichment-registry`: the rule DTO and sync feed carry the source's `version_column`.
- `enrichment-runtime`: the permitted provenance set gains `group_version` and `truncated`.
- `class1-enrichment`: the materialize path's provenance stamping skips the two new columns.

## Impact

**Code:** `ResolvedRule` gains `versionColumn` (populated by the read/sync resolves, left null by the
data-plane resolves that do not read it); `RuleService.resolve`/`sync` resolve it, the latter batched;
`RuleDto` gains `version_column` (MapStruct maps it by name); `ProvenanceColumns.NAMES` gains the two
names, which the write-back allow-list and skip logic already read from that one set;
`MaterializeService` skips them alongside `model`.

**No migration, no config, no new endpoint.** `table_definition.version_column` already exists (V13)
and is already populated for every scannable source (V13, V16).

**Compatibility:** additive on the wire. `version_column` is a new optional field; the two provenance
names widen an allow-list, so no previously-accepted write becomes invalid. A target that declares
neither new column behaves exactly as before.
