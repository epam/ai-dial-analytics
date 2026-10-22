## Context

Both items are follow-ups the enrichment runner raised while implementing the group path. Neither adds
a mechanism — the facts exist, they are simply not on the wire — so the design question in each case is
*where the fact belongs* and *how tolerant its absence should be*.

## Decisions

### D-1: `version_column` is derived, not declared

The rule does not gain a `version_column` field. The value is derived from the source's catalog scan
metadata exactly as `source` and `grain_key` are derived from the target enrichment, and for the same
reason: a re-declarable copy could disagree with the column the scan actually orders by, and the scan's
exactly-once guarantee rests on that column's physical properties. The DTO reports what the catalog
says; nothing writes it through the rule API.

### D-2: an absent version column is a null field on a read, and a 422 only on a scan

`ScanSourceMetadata.cursorColumns` rejects a source without scan metadata with 422 — correct there,
because an unscannable source cannot be scanned. A *read* of the rule is different: the rule is valid,
its registration was accepted, and failing `GET /v1/rules/{id}` because the source is not scannable
would make the registry unusable for rules whose data plane is not the row scan. So the resolve is
tolerant (null → field omitted under `@JsonInclude(NON_NULL)`), mirroring how `source` is already null
when the target has no parent. The runner reads a rule with no `version_column` as "this source is not
scannable", which is exactly the truth.

### D-3: the sync feed stays at a constant query count

`sync` resolves in a fixed number of batched fetches by design — the per-rule alternative was measured
against a feed the runner polls. Version columns are resolved the same way: collect the distinct source
tables of the already-fetched target definitions, one `findAllById`, one map lookup per rule. The
single-rule `resolve` path takes the extra indexed lookup, which is negligible against the ~4 it
already issues.

### D-4: the group pair are provenance, not a new concept

`group_version` and `truncated` go into `ProvenanceColumns.NAMES` rather than getting their own
allow-list. Everything provenance already promises is what these two need: permitted on a rule-scoped
write, optional on the target, silently skipped when absent, never read back by the pipeline. Adding a
second category would duplicate the skip logic and the write-back allow-list for no behavioural
difference. They are permitted on a row-grain rule's write too — the allow-list is not the place to
re-derive a rule's grain, and a row rule simply has nothing to put in them.

### D-5: un-prefixed names, forced by the write path

The earlier plan sketched `_group_version`. That cannot work: `RuleWriteValidator` rejects any
`_`-prefixed column as not caller-settable (the rule that keeps `_updated_at` a ClickHouse-stamped
system column), and `Identifiers.requireUserIdentifier` forbids a leading `_` on a user-declared
column, so the column could not be declared on the target either. Provenance is declared and written
like any user column, so it lives in the user namespace. The two names are therefore `group_version`
and `truncated`.

### D-6: Class-1 skips both

`MaterializeService` stamps provenance itself inside its `INSERT … SELECT` and already skips `model`
with a debug log. `group_version` and `truncated` join it: a `sql` evaluator makes no model call, may
not use the group trigger (governance rejects it), and materialize never trims its input. The three are
now one list rather than three branches, so a future provenance column that Class-1 cannot describe is
one entry rather than another `if`.

## Risks / Trade-offs

- **Un-prefixed provenance shares the user namespace.** A target that wants a business column literally
  named `truncated` now has a name collision with a provenance name it may not have intended. Accepted:
  the allow-list only *permits* the name, and the target declares the column either way — the write
  writes what the caller sends. The same is already true of `model` and `enriched_at`.
- **`version_column` exposes a physical column name to the runner.** The original design deliberately
  kept the runner from naming tables and columns. This is the narrow exception the group path needs: the
  runner must read one value out of a row it already receives, and the alternative is a
  hand-configured name that is silently wrong for any second source. It exposes a name the runner
  already sees in every scan row.
- **The runner still needs a change to use it.** Until it reads `version_column` off the rule, its
  `GROUP_VERSION_COLUMN` key remains authoritative. This change is additive, so the two can ship
  independently in either order.

## Migration Plan

None. `table_definition.version_column` exists (V13) and is populated for every scannable source (V13,
V16). No ClickHouse DDL: a target that wants the group provenance columns adds them through the
existing `PATCH /v1/tables/{name}/schema` like any other column.

## Open Questions

- Should `group_version`/`truncated` become *required* on a group-rule target, so a group label is
  never unattributable? Deferred: provenance is optional everywhere else, and making it mandatory for
  one trigger kind would fail registration for existing targets.
