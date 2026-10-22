# Design

## Scope

One mapping entry, plus the spec/doc statements that currently promise the column is unpopulated. The
design work is entirely in what is *not* done: no migration, no nullability change, no clamp transform.
Each of those was considered; the reasoning is below so the next reader does not re-open it blind.

## Nullability: rejected, deliberately

The instinct from `2026-08-06-fix-cache-creation-tokens-mapping` is to make the column
`Nullable(UInt32)` so "the producer reported nothing" stops looking like a measured zero. That reasoning
does not transfer here, for four reasons:

1. **The producer always reports it.** The core's append is unconditional — no setting gates it, and the
   value is computed for every path including the ones with no response body. "Absent" therefore means
   only "this record came from a core older than `8b1634e2`", not "this request had no duration". That is
   a version-skew condition with a version-skew answer (upgrade the core), not a per-record unknown.
2. **A reported `0` is legitimately observable**, so NULL cannot separate the cases anyway. The core
   clamps with `Math.max(0, …)`, and a request rejected before any upstream work — auth failure, a
   validation error — completes inside a millisecond. `0` will appear in the data as a real measurement,
   which means a reader must already treat small values as "too fast to measure" rather than as sentinel.
   Adding NULL splits the *absent* case out of `0` while leaving the ambiguity that actually bites.
3. **It would invalidate already-registered aggregate declarations without failing them.**
   `AggregateSpecValidator.requireNullabilityPreserved` rejects a nullable measure bound to a
   non-nullable target column, and that check runs at pipeline registration. Flipping the input column's
   flag after the fact leaves an existing `conversations_rollup` — whose `duration_ms`/`avg_duration_ms`
   measures target non-nullable columns — as a declaration the validator would now refuse, still
   running, still storing `0` for an all-NULL group. Fixing that means recreating the pipeline's target
   columns; the benefit bought is item 1's version-skew case.
4. **It cannot repair the past.** The historical rows already hold `0`. `T` → `Nullable(T)` adds a
   null-map that is all-zero for existing parts, so every stored `0` stays a `0`. The migration would
   change the meaning of *future* absent values only — the same set item 1 covers.

Accepted cost, now written into the spec instead of living in a mapping comment: on a pre-`8b1634e2`
core the column reads `0`, indistinguishable from a sub-millisecond operation.

## Why non-nullable is safe against the absent case

`MappingEngine.applyFallback` sends an absent/blank value to the kind's zero — `0L` for `long` — which
the `UInt32` column accepts. Crucially `MappingEngine.isBlank` treats only `null`, blank strings, and
empty collections as blank, so a **present** `0` survives as `0` rather than being re-derived through the
fallback. Real zeros and absent fields converge on the same stored value, but neither can break the
insert, and no record is dropped for lacking the field.

## The `UInt32` ceiling: accepted, not guarded

`UInt32` tops out at 4,294,967,295 ms ≈ 49.7 days. A value above that would be rejected by ClickHouse at
insert time, and because `RowBatchSink` flushes batches, one poison row would fail the whole flush and the
run would retry it indefinitely. The exposure is real but not new: every `long`-kind column on this table
(`prompt_tokens`, `reasoning_tokens`, `request_body_bytes`, …) inserts a producer-supplied `long` into a
`UInt32` with no clamp, and the values here are wall-clock deltas of a live request or a polled background
job — bounded by the core's own timeouts. Adding a `clamp-uint32` transform to the closed transform
registry for this one column would be the first such guard on the table and would still leave the sibling
columns unguarded; if the ceiling is ever worth defending, it is worth defending table-wide, as its own
change.

## Column position

The entry goes between `deployment_price` and `response_status`, matching
`V1__create_dial_usage_log.sql`. Position is cosmetic to the insert — `JSONEachRow` is keyed by name, and
`MappingEngine.toRow` returns a `LinkedHashMap` whose order only reaches the serialized JSON — but the
mapping file's header states that its order matches the DDL, and `MappingEngineTest`'s
`writesOnlyDeclaredColumnsInOrder` pins it. Keeping the invariant costs nothing and keeps the two files
diffable side by side.

## Retiring "blocked column"

With this entry the shipped mapping declares all 37 user columns of `dial_usage_log`; `_ingested_at` (a
table-side `DEFAULT now64(3)`) is the only column it leaves out. The spec's
"Table columns that the mapping does not populate … MAY be absent from the mapping" **requirement** stays
— it is a property of the validator, not of this mapping, and an operator's overridden mapping may
legitimately omit more. What goes away is the promise that `operation_duration_ms` specifically is one of
them, including the `Blocked column exists but is unpopulated` scenario, which this change turns false.
`SchemaValidatorTest.unmappedBlockedColumnsArePermitted` correspondingly loses its subject and is
re-pointed at `_ingested_at`, the remaining table-default column.
