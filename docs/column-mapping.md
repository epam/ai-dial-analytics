# Column mapping (data-sync)

Each `data-sync` target table's structure is defined by an external YAML **mapping file**, not
hard-coded in the service. This is the authoring reference for those files.

Which mapping file a target uses is configured by `sync.targets[*].mapping-file` — see
[`configuration.md`](configuration.md#data-sync-schedule). The shipped defaults are
`src/main/resources/mapping/dial-usage-log.yaml` (match-all),
`src/main/resources/mapping/dial-usage-log-payload.yaml` (match-all, the verbatim-content columns) and
`src/main/resources/mapping/rate-analytics.yaml` (`/rate` POST records only).

For the ingest behaviour these files drive — parse order, absent/blank fallback, startup schema
reconciliation — see `openspec/specs/data-sync/spec.md`.

**A changed mapping applies to new data only.** Blobs already ingested are recorded in the ingest ledger
and are not read again by the poller, the daily reconciliation run or a default backfill. To apply the
new mapping to earlier days, run a backfill over them with `"includeIngested": true`
(`POST /v1/data-sync/backfill`); the re-ingested rows are picked up again by row-grain (`schedule`)
enrichments.

## File structure

```yaml
match:                          # optional; omit to emit for every record
  - path: request.uri
    op: ends-with
    value: /rate
  - path: request.method
    op: equals
    value: POST
    ignore-case: true

columns:
  - column: request_time         # mapped column
    source: request.time
    kind: string
    transform: iso-to-ch-datetime

  - column: rate                 # derived column
    derive: json-extract-rate
    sources: [request.body]
    args: { path: rate }
    kind: long
    nullable: true
```

`columns` is an ordered list. Only declared columns are read and written — **undeclared producer
fields are never persisted** (allowlist semantics). A table column the mapping does not populate
takes its ClickHouse default, which is how `_ingested_at` is left unmapped.

**Removing a column from the mapping is the off switch for capturing it**, and requires no migration:
the physical column can stay in place unused.

## Column keys

A column is either **mapped** (`source`) or **derived** (`derive` + `sources`). Declaring both, or
declaring `derive` without `sources`, is a fatal config error at load.

| Key            | Applies to                      | Description                                                                                                                              |
|----------------|---------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `column`       | both                            | The ClickHouse column name. Required.                                                                                                    |
| `kind`         | both                            | Extraction kind — see below. Required.                                                                                                   |
| `source`       | mapped                          | A dotted JSON path within the record, or the `$sourceFile` synthetic token (the originating blob name).                                  |
| `derive`       | derived                         | A name from the deriver registry below.                                                                                                  |
| `sources`      | derived                         | Ordered list of dotted paths handed to the deriver.                                                                                      |
| `args`         | derived                         | Static arguments for the deriver (e.g. `{ path: responseId }`).                                                                          |
| `transform`    | mapped                          | A name from the transform registry below.                                                                                                |
| `default`      | both                            | Value used when the resolved value is absent or blank.                                                                                   |
| `nullable`     | both                            | `true` emits a JSON `null` for an absent/blank value with no `default`. The table column must be `Nullable(...)`.                        |
| `inline-files` | mapped `string`, no `transform` | `{ body: request\|response, uri: <dotted path of the request URI> }` — marks a body whose inline files `SYNC_INLINE_FILES_DROP_TRUNCATED` and `SYNC_INLINE_FILES_MAX_SIZE` govern. |

`nullable` is what keeps an absent producer field distinguishable from a genuine zero. A
non-nullable column with no `default` falls back to its kind's zero value, so the insert stays valid
but "not reported" and "reported as 0" become indistinguishable.

## Extraction kinds

The authoritative column type is the table DDL; `kind` only selects how the source value is read.
From `ColumnKind.java` — six tokens, and an unrecognised one is a fatal config error:

| Kind            | Reads                                                                                                       |
|-----------------|-------------------------------------------------------------------------------------------------------------|
| `string`        | Scalar text.                                                                                                |
| `long`          | Integer.                                                                                                    |
| `double`        | Decimal, with precision preserved exactly — no binary float round-trip, so it suits `Decimal` cost columns. |
| `bool`          | Boolean.                                                                                                    |
| `array<string>` | JSON array coerced to a list of strings.                                                                    |
| `object`        | A JSON object carried verbatim into a ClickHouse `JSON` column (the payload table's two documents).         |

## Transform registry

A closed, code-defined set referenced by name — no expression language. An unknown transform name is
a fatal config error at load. From `TransformRegistry.java`:

| Transform             | Effect                                                                                                                                    |
|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| `iso-to-ch-datetime`  | ISO-like timestamp → ClickHouse `DateTime64` literal.                                                                                     |
| `string-array`        | JSON array → list of strings.                                                                                                             |
| `lowercase-json-keys` | Lower-cases the **top-level keys** of a JSON object; values are untouched. Used by `request_tags` so header names are stored in one case. |

A transform applies at ingest, so it reaches newly ingested rows only — it implies no rewrite of rows
already stored.

## Deriver registry

A closed, code-defined set referenced by a derived column's `derive` key — no expression language. An
unknown deriver name is a fatal config error at load. From `DeriverRegistry.java`:

| Deriver               | Reads                        | Produces                                                                                                                                                         |
|-----------------------|------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `hash-uuid`           | all `sources`, concatenated  | Deterministic UUIDv5, stable across re-ingests and never null (e.g. `event_id`).                                                                                 |
| `classify-event-kind` | first source (a request URI) | `llm_call` / `embedding` / `mcp` / `route`, or `""` when none match.                                                                                             |
| `json-extract-string` | `sources` + `args.path`      | The member's verbatim scalar text. Reads **any** scalar, so a boolean yields `true`/`false` and a number its literal digits; an object or array yields no value. |
| `json-extract-uint`   | `sources` + `args.path`      | The member as an unsigned integer.                                                                                                                               |
| `json-extract-rate`   | `sources` + `args.path`      | Normalized signed rating: number as-is, `true`→1, `false`→0, else null.                                                                                          |
| `json-array-length`   | `sources` + `args.path`      | Length of the array at that path.                                                                                                                                |
| `byte-length`         | first source                 | UTF-8 byte length of the string.                                                                                                                                 |
| `success-from-status` | first source                 | Whether an HTTP status is in `200..299`.                                                                                                                         |

Two notes that are load-bearing rather than incidental:

- **`hash-uuid`'s namespace is fixed forever.** It seeds every historical `event_id`; changing it
  silently re-keys all existing events, so re-ingested records would stop collapsing with rows
  already stored.
- **`json-extract-string` returning the submitted form verbatim is deliberate.**
  `rate_analytics.rate_raw` exists precisely to keep a boolean `false` distinguishable from a numeric
  `0`, both of which `json-extract-rate` normalizes to `0`. Changing that coercion re-introduces the
  ambiguity, which is why it is pinned by the data-sync spec and by `DeriverRegistryTest`.

## The `match:` predicate

A target produces a row for a record only when its `match:` block accepts it; a mapping without a
`match:` block emits for every record. A single record may therefore populate more than one table.

Each condition names a dotted `path`, an `op`, a `value`, and an optional `ignore-case` (default
`false`) that folds case on both sides. **All conditions are ANDed.** Operators are `ends-with`,
`equals` and `contains`; an unknown operator is a fatal config error at load.

## Verbatim content and PII

⚠️ The shipped `dial-usage-log-payload.yaml` declares **five** verbatim-content columns — the three
heavy bodies (`request_body`, `response_body`, and the merged streaming `assembled_response`) plus the
two documents that can carry credentials (`jwt_claims`, `request_tags`) — so raw prompt content, raw
response content, the producer's assembled answer, the caller's claims and the request headers all
**are** persisted, for 90 days by default (`SYNC_PAYLOAD_TTL_DAYS`). They are a separate target because a ClickHouse row-expiry TTL is a
property of the table, not of a column: `dial_usage_log` keeps its analytic columns for a year by default (`SYNC_USAGE_LOG_TTL_DAYS`), while
`dial_usage_log_payload` — catalogued as a system enrichment of it, keyed on `event_id` — keeps the
content for a quarter. Its mapping is match-all, so it shadows the usage log one row for one row.

`assembled_response` is captured *in addition to* `response_body`, not instead of it: on a streamed
call the raw body is a chunk transcript, while the merged form is the only shape in which an answer
can be read without reassembling that stream. It remains a deriver source as well (`response_id`,
`reasoning_tokens`); the two uses are independent. ADAS never reassembles, validates or rejects it,
so a value that is not JSON is stored rather than rejected — **parse it defensively**. It is parsed only
to find inline files, and only when one of the two settings below can change it.

All three bodies declare `inline-files`, which puts them under two independent settings:

- `SYNC_INLINE_FILES_DROP_TRUNCATED` (default `true`): a body DIAL Core cut at 4 MiB is cut back to before
  the half file the cut landed in, which cannot be decoded. Complete files are kept, and a body that was not
  cut is not parsed.
- `SYNC_INLINE_FILES_MAX_SIZE` (default unset): the payload of every inline file longer than the limit is
  removed at ingest and not kept anywhere; files at or under it are kept, and `0` removes them all. The size
  is the stored base64 text after a data URL's `data:...;base64,` prefix, so a decoded file is about three
  quarters of it. A body no longer than the limit is not parsed.

With the first `false` and the second unset, the bodies are stored as sent. Inline files are values at the
fixed JSON locations of the record's API (images, PDFs, audio, MCP tool files, base64 embedding vectors);
the data-sync spec lists the locations. Text is never removed, however it is encoded, and routes are never
scanned.

The service applies no masking. All five are seeded `sensitive`, so only callers holding
`TABLE_READ` and `TABLE_SENSITIVE` on the owning table see
them — gate them with column-level access controls (see [`column-tags.md`](column-tags.md) and
[`heavy-columns.md`](heavy-columns.md)) plus the payload table's retention. Removing a column from
the payload mapping is the off switch for capturing it; it is the only mapping declaring those five.
