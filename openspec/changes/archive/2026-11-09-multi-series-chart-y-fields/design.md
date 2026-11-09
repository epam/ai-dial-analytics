## Context

The chart is a three-member record (`type`, `x_field`, `y_field`) serialized as JSON text into the
`saved_query.chart` column and, through the dashboard `inline_query`, into `dashboard.widgets`. Both are
read back through the same record, by the primary (lenient) JSON mapper. Requests are bound by a strict
copy of that mapper that only adds `FAIL_ON_UNKNOWN_PROPERTIES`.

## Goals / Non-Goals

**Goals:**
- `y_field` is an ordered list of series names on the wire, in both directions.
- Existing stored charts and existing clients that send a string keep working.

**Non-Goals:**
- Per-series styling (colour, axis, chart type per series) — out of scope: the chart stays a rendering
  hint the client owns.
- Rewriting audit snapshots: `saved_query_aud.chart` keeps what was written at the time. The revision
  endpoint maps a snapshot through the same chart record, so an old string `y_field` still reads as a
  one-element array.

## Decisions

**Keep the member name `y_field`, change its type to `List<String>`.** Renaming to `y_fields` would read
better but forces every client and every stored row to change at once; keeping the name lets a client
that sends a string keep working (the single-value tolerance) and avoids a second member for one axis.
Rejected: a second `y_fields` member alongside `y_field` — two sources of truth for one
axis, and the client must reconcile them.

**Read a string as a one-element array (`ACCEPT_SINGLE_VALUE_AS_ARRAY` on the member) instead of a data
migration.** The annotation sits on the record component, so it applies to every read path — HTTP
requests (strict mapper), stored saved queries and stored dashboard widgets (primary mapper) — without
touching mapper-wide configuration. Rejected: a Flyway migration rewriting `chart.y_field` in
`saved_query` and `dashboard.widgets`. It would still need the tolerant read for old clients, it touches
the widget JSON nested inside arrays, and the read path already normalizes on every load, so the stored
string is invisible to callers. Writes after the change store the array form, so stored rows converge as
they are edited.

**No new validation.** The element count and names stay unchecked, as the rest of the chart is; the
existing serialized-size cap on the saved-query body already bounds an oversized list. Neither mapper turns
off Jackson's scalar coercion, so a number or boolean element is read as its string form, a `null` element
is kept (content inclusion is `ALWAYS`), and an empty array is returned as `[]` (value inclusion
`NON_NULL` omits only `null`). An object, or an array holding an object or an empty array (`{}`, `[{}]`,
`[[]]`), fails binding and becomes `400 bad_request`. A non-empty nested array is not covered: the pinned
Jackson 3.1.7 `List<String>` deserializer reads `[["a"]]` as `["a"]` and stops one token early, with or
without `ACCEPT_SINGLE_VALUE_AS_ARRAY`. The leftover tokens land in the parent object. On a saved-query
request that ends as a trailing-token or unrecognized-field `400`, which can name a member the caller sent
correctly. On a dashboard body it can bind with a phantom empty widget that the validator then reports as
`422 validation_error`. The spec leaves that outcome undefined, and the tests do not pin it. Rejected: a
`CoercionConfig` or custom deserializer to refuse scalars, nulls or nested arrays. The chart is a client
hint ADAS does not interpret, so tightening it buys nothing.

## Risks / Trade-offs

- [A client that reads `y_field` as a string breaks when it receives an array] → This is the intended
  contract change; it is marked **BREAKING** in the proposal, and the Query Builder is the client that
  renders multi-series charts from it.
- [Stored rows keep the string form until next edited] → Invisible on every read path; noted in the spec
  so it is a contract, not an accident.

## Migration Plan

Deploy the service; no schema migration. A rollback is not data-safe once a chart has been saved in the
array form: an older version cannot read an array into its string `y_field`, so reading that saved query
(and listing the scope it is in), reading a dashboard whose inline query carries an array, and reading a
saved-query revision snapshot written in the array form all fail. Roll back only before clients write
arrays, or first rewrite the stored arrays back to their first element in `saved_query.chart`,
`dashboard.widgets` (the nested `inline_query.chart`) and `saved_query_aud.chart`. The upgrade notes for
the release carry this condition.
