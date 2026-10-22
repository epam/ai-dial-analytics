## Why

When a SQL statement or a filter predicate fails to parse, the response body contains the Calcite
parser's full expectation set — roughly fifty lines of grammar tokens — inside `ErrorView.message`.

An actual response to a one-character mistake in a pipeline filter (`success = = true OR`):

```
"message": "filter is invalid: SQL parse error: Encountered \"= =\" at line 1, column 44.\nWas expecting one of:\n    <EOF> \n    \"ORDER\" ...\n    \"LIMIT\" ...\n    \"OFFSET\" ...\n    [ ~45 more lines of grammar ]"
```

The first line is the whole diagnosis: the offending token and its position. Everything after it is the
parser's internal grammar, which tells a caller nothing they can act on, buries the part that does, and
makes the error unreadable in any UI, log line, or terminal that does not wrap fifty lines gracefully.

This originates in `SqlQueryFrontEnd.rootMessage()` and therefore reaches **four** surfaces at once:
`POST /v1/queries/execute-sql`, `POST /v1/queries/translate-sql`, enrichment rule filters, and aggregate
pipeline filters. The capability's own spec already insists elsewhere that errors name the offending
thing and do not surface raw engine text; a grammar dump is the same failure of hygiene from the other
direction — not too little information, but so much that the useful part is lost.

## What Changes

- A parse failure's client-facing message is reduced to its diagnostic head: the offending token and its
  position. The grammar expectation set is dropped from the response.
- The full parser output is retained in the service log at debug level, so nothing an operator might want
  is actually lost — it moves from the place that must stay legible to the place that can afford verbosity.
- No change to status codes, error codes, or which statements are rejected. This is presentation only.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sql-query-translation`: gains a requirement on the **shape** of a parse-failure message, alongside its
  existing requirements about what errors must name. It owns the Calcite front end, so the rule belongs
  here once rather than in each of the four capabilities that inherit it.

## Impact

- **Code**: `SqlQueryFrontEnd.rootMessage()`, plus a debug log of the full text at the same site. The
  method is shared by four call sites — the parse in `translate`, the parse in `screen`, and the
  validation and plan-conversion failures — but only a parse message carries the expectation marker, so
  the other two are unaffected by construction rather than by a special case. The class has no logger
  today and gains one.
- **API**: `message` becomes shorter for parse failures on `/v1/queries/execute-sql`,
  `/v1/queries/translate-sql`, `POST /v1/rules`, and `POST /v1/pipelines`. `status` and `error` are
  unchanged, so any client switching on the stable code is unaffected. A client parsing the grammar dump
  out of `message` would break — no such client should exist, since `message` is documented as
  human-facing prose.
- **Not affected**: validation errors (unknown column, unsupported construct, projection-only violations)
  already produce a single targeted sentence and keep it. Only the *parser's* expectation dump is trimmed.
