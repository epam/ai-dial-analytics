## Context

`MappingEngine.coerce` reads a present JSON node as its column's `ColumnKind` through Jackson 3's lenient
accessors:
- `long` uses `asLong(0)`;
- `bool` uses `asBoolean(false)`;
- `double` uses `asString("")` followed by `new BigDecimal(…)`, with `null` on failure;
- `array<string>` reads each element with `asString("")` and maps a non-array value to an empty list.

The fallback (`applyFallback`) then replaces only a **blank** result: `null`, an empty string or an empty
collection. A `0L` or `false` that the accessor invented is not blank, so it is stored as if it were the
producer's value.

Probing Jackson 3.1.7, the version the build resolves, confirms what `asLong(0)` and `asBoolean(false)` do:

| Producer node              | `asLong(0)` | `asBoolean(false)` |
|----------------------------|-------------|--------------------|
| `12.7`                     | `12`        | `false`            |
| `"12.7"`, `"abc"`, `""`    | `0`         | `false`            |
| `true`                     | `0`         | `true`             |
| an object or array         | `0`         | `false`            |
| `99999999999999999999`     | `0`         | `true`             |
| `2`                        | `2`         | `true`             |

`DeriverRegistry` repeats the pattern in `json-extract-uint` (`asLong(0)`) and `success-from-status`
(`asLong(0)` on the status). The rate normalizer's text branch returns `null` for unparseable text, so the
value falls back, but nothing counts it.

Engines are built in `MappingConfiguration`, which already passes each engine its target table and the
`MeterRegistry`. The engine used them only for the inline-file counters.

## Goals / Non-Goals

**Goals:**
- No invented value: a present value the kind cannot represent falls back exactly as an absent one does.
- Every such failure is countable per table and column, and visible in the log without flooding it.
- The numeric and boolean derivers are held to the same rule, and counted against the column they fill.

**Non-Goals:**
- A per-column strict mode that rejects the record. It is deferred; the counter tells an operator where
  one would be needed.
- Changing what a deriver yields for an **absent** input, or the semantics of transforms.
- Rewriting stored history. That is what an `includeIngested` backfill is for.

## Decisions

### 1. A `Coercion` helper with a failure marker, shared by the engine and the derivers

A new `sync/mapping/Coercion` (public, since `DeriverRegistry` and the engine share it) holds the strict readers: `toLong(node)`, `toDecimal(node)`,
`toBool(node)` and `toStringArray(node)`. Each returns the value, `null` for blank text, or the singleton
`Coercion.FAILED`. The engine's `coerce` delegates to them. A numeric deriver returns `Coercion.FAILED` from
`derive` instead of `0`.

In `resolve`, the engine treats a `FAILED` result from either source as a failure for that column: it counts
it, logs it through the limiter, and hands `null` to `applyFallback`. A failure does not run the column's
transform: the transform gets `null`, as it does for an absent value.

Why a marker rather than an exception: coercion runs per column per record, on the hot path. An exception per
bad field costs a stack trace, and turning it into a fallback would need a `try` around every column. A marker
keeps `Deriver.derive(…) → Object` unchanged, and the engine is the one place that knows the table and column
to count against.

Rejected: counting inside `DeriverRegistry`. The registry is shared across targets and columns, so it would
need its own tags passed in, and every deriver signature would change.

### 2. The strict rules

- **`long`.** An integral `NumericNode` is accepted when it fits a `long`. A floating-point node is accepted
  only when its value is integral and in range, so `1e3` gives `1000`. Text is trimmed, then parsed with
  `new BigDecimal(text)` and required to be integral and to fit through `longValueExact()`. This accepts
  `"1000"` and `"1e3"`, but not `"12.7"`.
- **`double`.** The existing `BigDecimal`-from-text path is kept, so precision is preserved exactly. The only
  change is that a boolean or a container is now a failure rather than an uncounted `null`.
- **`bool`.** A `BooleanNode` is accepted, as is an integral number equal to `0` or `1`. Text is accepted
  when, trimmed and compared case-insensitively, it is `true`, `false`, `0` or `1`.
- **`array<string>`.** Elements use the `string` kind's reading: a container is serialized as its JSON text,
  and a scalar is read as its text. That fixes an object element being stored as `""`, the one silent-loss
  case in a kind that otherwise had no failure mode. A non-array value is a failure.
- **Blank text.** For every non-string kind, blank text is absent, not a failure. This keeps the existing
  "absent or blank" contract: `""` in a nullable `long` column becomes `null`, where `asLong` used to give
  `0`.

### 3. A per-column log limiter, held by the compiled column

Each `CompiledColumn` carries a small `CoercionFailures`. Its state is an `AtomicLong` holding the nanosecond
time of the last admitted line, and an `AtomicLong` counting the lines suppressed since. A failure is admitted
when a minute has passed since the last admitted one, swapped in by compare-and-set. An admitted line carries
the suppressed count and resets it.

The keys are the compiled columns, a fixed set built at startup, so the limiter needs no map and no bound.
Rejected: reusing `web/handler/RefusalLogLimiter`. It is package-private to the web layer and keyed by request
path, and the column set here is fixed, so a map would add nothing.

The counter is a `Counter` per column, registered at construction with the tags `table`, `column` and `kind`,
like the inline-file counters. Its cost on the hot path is one `increment()` per failure.

The three-argument test constructor `MappingEngine(config, transforms, derivers)` has no table or registry. It
uses a private `SimpleMeterRegistry` and the table name `""`, so the existing unit tests are unaffected.

### 4. What is logged

The WARN line names the table, the column, the kind, the producer node's JSON type (`STRING`, `NUMBER`,
`BOOLEAN`, `OBJECT` or `ARRAY`) and the blob key. It does not include the value. A field mapped as a number can
carry arbitrary producer text, and the maintainers chose locator-only for record failures in the companion
change, for the same reason. The blob key plus the column is enough to find the value in the source blob.

## Risks / Trade-offs

- **Nullable numeric columns now hold `NULL` where they held `0`, for rows ingested after deploy.** That is the
  point: aggregates such as `sum` ignore `NULL`, and `count(column)` stops counting invented zeros. A reader that
  assumed the columns never hold `NULL` was already wrong, since they are declared nullable and absent values
  have always become `NULL`.
- **Some values a column used to store are now refused.** This affects only `long` and `bool` columns; a
  `string` or `double` column stores `"12.7"` exactly as before. In a `long` column, a fractional number such as
  `12.7` used to be truncated to `12`, and the text `"12.7"` used to become `0`. In a `bool` column, the number
  `2` used to become `true`. A producer legitimately sending fractional milliseconds
  would see `operation_duration_ms` fall to `0` with a counted failure, where it used to be truncated. The
  counter makes that visible at once, and the mapping can be changed to `double` if the table allows it.
- **A WARN storm.** This is bounded to one line per column per minute, and the column set is fixed.
- **Per-field cost.** The strict readers parse only what `asLong`, `asBoolean` and `BigDecimal` already parsed.
  The marker check is a reference comparison, so there is no measurable change on the hot path.

## Migration Plan

Deploy. No schema, config or API change. Rows ingested from then on use the strict reading. To apply it to a
past range, run an operator backfill with `includeIngested: true`. Rollback restores the lenient reading for
new rows. Rows written in between keep their `NULL`s.

## Open Questions

None.
