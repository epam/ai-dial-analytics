## Context

The structured query DSL translates a closed catalog of functions to ClickHouse SQL through a
render-only jOOQ model (`ExprTranslator`). The catalog covers scalar (`lower`, `abs`,
`width_bucket`…), aggregate (`count`, `sum`…), and ordered-set aggregate (`percentile_cont`/`_disc`)
functions, but nothing that buckets a timestamp. Time-series aggregation ("per N minutes/hours/days")
is therefore impossible today. Timestamp columns are `DateTime64(3)`; DSL `timestamp` literals are
epoch-millis wrapped in `fromUnixTimestamp64Milli(...)` for filter comparisons.

## Goals / Non-Goals

**Goals:**
- Add one scalar function `date_bin(amount, unit, timestamp)` that returns a bucket-start timestamp.
- Fixed-origin (epoch) alignment so buckets start on clean boundaries (`:00` seconds).
- Compose with existing aggregate-mode grouping (aliased `select` + `group_by` alias) with no
  changes to the grouping/validation layers.

**Non-Goals:**
- No calendar-period truncation (`date_trunc` for month/quarter/year) — deferred to a future
  companion change; variable-width units are explicitly excluded here.
- No configurable origin argument (data-relative alignment). Fixed epoch origin only.
- No new value types or response-shape changes.

## Decisions

**Function name `date_bin`.** Matches core Postgres (14+), keeping the catalog consistent with its
Postgres-standard names (`width_bucket`, `percentile_cont`). Rejected `time_bucket` (TimescaleDB
extension only) and `toStartOfInterval` (ClickHouse-internal name leaking into the public DSL).

**Signature `date_bin(amount, unit, timestamp)` (numeric amount + unit enum).** Chosen over a single
Postgres-style interval string (`'5 minutes'`) to avoid parsing a compound literal and to validate
`unit` against a closed enum. `amount` is a positive integer literal; `unit` ∈
{`second`,`minute`,`hour`,`day`,`week`}; `timestamp` is any resolvable expression.

**Fixed epoch-origin alignment.** ClickHouse `toStartOfInterval` anchors buckets to
`1970-01-01 00:00:00` by default; for any fixed-width unit that divides evenly, boundaries land on
clean `:00`-second marks (`12:03 → 12:00` for a 5-minute stride). No origin argument is exposed.

**Units restricted to fixed-width (second…week).** Month/quarter/year are variable-width; Postgres
`date_bin` rejects them for the same reason. Calendar periods belong to a future `date_trunc`
companion, so excluding them keeps `date_bin`'s semantics unambiguous (all bins are equal-width and
epoch-aligned).

**Render the native ClickHouse function by name.** Emit
`toStartOfInterval({operand}, INTERVAL <amount> <UNIT>)` via a `DSL.field(...)` template — the same
technique already used for `fromUnixTimestamp64Milli`. `amount` is inlined as a validated integer and
`UNIT` as a validated enum keyword, so there is no injection surface. Unlike `width_bucket`/
percentiles (flagged "provisional under jOOQ's experimental ClickHouse dialect"), this rendering is
deterministic and can be asserted directly in the render test.

## Risks / Trade-offs

- **Return timestamp serialization** → `toStartOfInterval` returns a ClickHouse `DateTime`/
  `DateTime64` that serializes through the HTTP client as the response cell; verified by the
  live-ClickHouse integration test rather than assumed.
- **No sub-second / month+ strides** → sub-second bins and calendar months are out of scope by
  design; documented so consumers reach for a future `date_trunc` for calendar periods.
- **String-inlined `amount`/`unit`** → mitigated by validating `amount` as a positive integer and
  `unit` against a closed allowlist before inlining; anything else is rejected as HTTP 400.
