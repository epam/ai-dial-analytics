## Context

`AggregateSpecValidator` already resolves, for every measure, the input column's binding and the target
column's declaration — that is how the existing fractional-measure and result-type rules work. Both sides'
nullability are therefore already in hand at the point the new rule needs them; nothing new has to be
looked up.

What the validator has in hand is the **catalog's** answer, and for `dial_usage_log` the catalog is wrong:
`column_mapping.nullable` shipped with a `FALSE` default, the seed predates it, and eleven physically
`Nullable(...)` columns are recorded as non-nullable. So the rule as first designed would have been
correct, tested, and inert on the table that produced the bug report. Fixing the catalog is not a
side-quest here — it is the difference between the rule working and the rule looking like it works.

The capability is not deployed anywhere yet, so this lands before any pipeline exists to be affected by
it. There is nothing to audit, nothing to migrate, and no stored rollup carrying the zeros the rule
prevents — which is the cheapest possible moment to add a governance rule, and the reason this design is
short.

What still needs deciding is what the rule *does* when it fires: refuse, or silently substitute a zero.

## Goals / Non-Goals

**Goals:**

- A declaration that would store type defaults in place of "no data" is refused, with a message that names
  the fix.
- Existing pipelines of that shape stop writing rather than continuing to accumulate silent zeros.
- The rule is derived from the input column, so it neither over-refuses (`count`, non-nullable inputs) nor
  under-refuses (`sum` looks safe by function alone).

**Non-Goals:**

- Extending the rule to group keys. A key is the input value itself and the existing exact-type rule
  governs it; a nullable key column is a separate question about whether NULL is a valid grain, which this
  change does not open.
- Auto-coercing with `ifNull(...)`. Considered and rejected below.

## Decisions

### Refuse the declaration rather than compiling `ifNull(measure, 0)`

Wrapping the measure so the zero becomes intentional would make every existing pipeline keep working, with
no migration. Rejected: it hard-codes an answer to a question only the declarer can answer. For a token
count, zero may be a reasonable stand-in for absent; for a price, or a `min` latency, it is a fabricated
data point that will be averaged and charted. Refusing puts the choice where the knowledge is — and a
declarer who genuinely wants zeros can express that by making the target column non-nullable *and* the
measure total-safe, deliberately.

This also keeps the capability internally consistent: the fractional-measure rule refuses rather than
rounding, for the same reason.

### Derive nullability from the input column, not the function

`sum` over `UInt32` cannot be NULL; `sum` over `Nullable(UInt32)` can. Keying the rule on the function
would refuse the first and is therefore both wrong and annoying. Keying it on the input column matches
what the engine actually does and is information the validator already has.

`count` is special-cased as never-nullable rather than falling out of the input rule, because `count` may
have no input column at all.

### Fix the catalog rather than reading nullability from ClickHouse

The alternative to backfilling the seed is to key the rule on `system.tables` — authoritative by
construction, and immune to a stale catalog. Rejected. It costs a lookup per validation, it introduces a
failure mode when the two disagree, and above all it treats the catalog's inaccuracy as a fact to route
around rather than a defect. The catalog is what schema discovery publishes; leaving it wrong means every
consumer keeps being told these columns cannot be null while reads return nulls from them. Correcting the
data fixes both the rule and the surface, and keeps one source of truth.

Nullability is fixed at DDL, so the backfill is a `column_mapping` update and never an `ALTER`. Unlike the
seeded `sensitive` flag — which an operator may legitimately change — this value is not a choice: the
physical column decides it, and any disagreement is a bug in the catalog, not a preference.

### Enforce at registration and re-apply at execution

Consistent with every other rule on this path. The execution-time re-application is what catches a column
that becomes nullable after registration — a schema change that would otherwise silently change the
meaning of every zero the pipeline has ever written, with no error anywhere.

## Risks / Trade-offs

- **Making a target column nullable requires re-materializing the table.** Column nullability is fixed at
  DDL. → The remedy is a table change, not a pipeline edit, and the error message should say so rather
  than implying the pipeline alone can be fixed. This is the rule's main ergonomic cost, and it is why the
  message wording is a task in its own right.
- **A declarer who wanted zeros now has to say so.** → Small friction, deliberately placed: it converts an
  invisible default into a recorded decision.
- **The rule refuses at execution too, so a schema change can stop a running pipeline.** → Intended, and
  the standard behaviour for every rule on this path: a refresh that fails is visible in `last_error` and
  in the failure metric, whereas a silently changed zero is not.

## Migration Plan

None required. The capability is undeployed, so there are no registered pipelines to audit and no
materialised rollups holding the zeros this rule prevents. Rollback is a code revert.

## Open Questions

None.
