## Why

The structured query DSL supports aggregation and grouping but has no way to bucket a timestamp —
the only bucketing function, `width_bucket`, is numeric-only. Consumers cannot answer time-series
questions like "count events per 5-minute interval" because `group_by` accepts only fields/aliases
and no catalog function truncates a timestamp to a bucket.

## What Changes

- Add a new scalar function **`date_bin`** to the closed function catalog: the timestamp analog of
  `width_bucket`.
- Signature: `date_bin(amount, unit, timestamp)` — a positive integer `amount`, a `unit` from a
  closed enum (`second`, `minute`, `hour`, `day`, `week`), and a timestamp expression. Returns the
  bucket-start timestamp.
- Buckets are aligned to a fixed epoch origin (no origin argument), so every bucket starts on a
  clean boundary with `:00` seconds (e.g. a 5-minute bucket maps `12:03 → 12:00`).
- Renders to native ClickHouse `toStartOfInterval(ts, INTERVAL n UNIT)`.
- Non-catalog usage stays rejected; new validation covers `date_bin`'s arity, `amount`, and `unit`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `structured-query`: the **Supported function catalog** requirement gains the `date_bin` scalar
  function (catalog row + validation/rendering scenarios).

## Impact

- Code: `service/query/translate/ExprTranslator.java` (one new `case` + a private renderer + a unit
  allowlist). No changes to grouping/validation wiring — `date_bin` composes as an aliased `select`
  entry referenced by `group_by`, already supported by `StructuredQueryBuilder.resolveGroupKey`.
- Spec: `openspec/specs/structured-query/spec.md` function catalog.
- Tests: `QueryTranslatorValidationTest`, `DialUsageLogQueryRenderTest`,
  `StructuredQueryProvisionalIntegrationTest`.
- No new dependencies, config, or API surface changes.
