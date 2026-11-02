## Why

A producer value that cannot be read as its column's kind is silently replaced, with no log line and no
metric. For numeric and boolean columns the replacement is not even marked as missing. Jackson's lenient
readers turn it into a plausible value:
- a `long` column stores `0` for `"abc"`, `""`, `"12.7"`, `true`, an object, or a number too large for a
  `long`, even when the column is nullable;
- a fractional number in a `long` column is truncated: `12.7` becomes `12`;
- a `bool` column stores `false` for `"yes"` or an object.

Token counts and durations understated this way are indistinguishable from real zeros in every report built on
them. This is the third of three changes that close silent data loss on the ingest path. The first made failed
blobs and days durable and retried. The second isolates rejected rows and records skipped records.

## What Changes

- **Strict reading per kind**, replacing Jackson's lenient `asLong(0)` and `asBoolean(false)`:
  - `long` accepts an integral number within range, or text that parses as one;
  - `double` accepts a number, or decimal text;
  - `bool` accepts a boolean, `0`/`1`, or `true`/`false` text in any case;
  - `array<string>` accepts an array;
  - blank text is **absent** for every non-string kind.
- **A value that fails to coerce falls back like an absent one.** It takes the column's existing chain:
  the default, else `null` if the column is nullable, else the kind's zero. A nullable token-count column now
  stores `NULL` rather than an invented `0`. A non-nullable column still stores its zero, but no longer
  silently.
- **Every coercion failure is counted** on a new counter `sync.values.coercion.failures{table,column,kind}`.
  A rate-limited WARN names the table, column, kind, the JSON type the producer sent and the blob. It logs at
  most one line per column per minute, plus a count of the suppressed ones, and never the value itself.
- **Derivers that read a number or boolean follow the same rule.** These are `json-extract-uint`, the
  `response.status` read of `success-from-status`, and the rate normalizer's text branch. A present but
  unreadable value is counted against the derived column and falls back the same way.
- **`array<string>` keeps structured elements.** An object or array element is stored as its JSON text,
  matching the `string` kind, instead of as `""`.

**Behavior change (not breaking the schema):** rows ingested after deploy can carry `NULL` where earlier rows
carried `0` for the same unreadable producer value, in nullable `long` columns (the token counts, `rate`) and
`double` columns. Rows already stored are unchanged. An operator backfill with `includeIngested: true` applies
the new reading to history.

Out of scope:
- a per-column strict mode that rejects the record instead of falling back; it is deferred;
- coercion inside transforms, and the "absent means 0" defaults of the length and byte-count derivers, which
  are defined behavior, not failures.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `data-sync`: adds the requirement "A value that fails to coerce to its column's kind is counted and falls
  back like an absent one", which defines the strict reading per kind, the fallback, the counter, the
  rate-limited log, and the numeric derivers. "Log parsing and transformation" stays as it is; the new
  requirement refines what "coerce to the declared kind" means.

## Impact

- **Code**:
  - `sync/mapping/MappingEngine`: strict `coerce`, a coercion-failure marker, per-column counters and log
    limiter. Every engine gets its table and meter registry, which only inline-file rewriting needed before.
  - `sync/mapping/DeriverRegistry`: the numeric reads return the marker instead of `0`.
  - `configuration/MappingConfiguration`: no signature change; it already passes the table and registry.
- **API / schema / config**: none. No endpoint, migration or property changes, so `docs/configuration.md` is
  untouched.
- **Data**: as described under the behavior change above. `docs/column-mapping.md` gets a short note on the
  strict reading per kind, since it is the operator guide for writing mappings.
