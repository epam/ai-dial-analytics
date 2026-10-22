# Writing rows into a custom table

Two small examples of the **table-management surface**, standard library only — Python 3.11+, no
`pip install`:

- **`create_table.py`** — creates the custom `source` table and materializes it in ClickHouse
  (`--drop` to remove it again). Run once.
- **`write_custom_table.py`** — the write example: one hardcoded row, one `POST`.

Unlike [`../../seed/`](../../seed/), which inserts into ClickHouse directly because `dial_usage_log` is a
`system` table that refuses row writes through the API, everything here goes **through ADAS**:

| Step | Call | Who does it |
| --- | --- | --- |
| Record the table's identity (`PENDING`, no DDL) | `POST /v1/tables` | `create_table.py` |
| Define the physical schema and materialize it (`ACTIVE`) | `POST /v1/tables/{name}/schema` | `create_table.py` |
| Insert rows | `POST /v1/tables/{name}/rows` | `write_custom_table.py` |
| Read them back | `POST /v1/queries/execute-sql` or `/v1/queries/execute` | you |
| Drop the table | `DELETE /v1/tables/{name}` | `create_table.py --drop` |

## Authentication

Both scripts send a DIAL API key as the `Api-Key` header when one is set, so the same examples run
against an endpoint secured with `security.mode=oidc`. It is only needed there — the local
`docker-compose` default is `security.mode=none`, where the header is ignored, so leave
`ADAS_API_KEY` unset for open access.

```bash
export ADAS_API_KEY=dial_xxx                                    # both scripts pick it up
python local_env/python-examples/tables/create_table.py --api-key dial_xxx    # or pass it explicitly
```

Both scripts take the key as `--api-key`, defaulting to `ADAS_API_KEY`. In `write_custom_table.py`
the thing meant to be edited is `ROW`, the literal it writes.

## Prerequisites

1. Local data stores: `docker compose -f local_env/docker-compose.yml up -d --wait` (repo root).
2. ADAS: `./gradlew bootRun`.
3. Python ≥ 3.11 on PATH.
4. The target table: `python local_env/python-examples/tables/create_table.py`.

## Create the table (once)

```bash
python local_env/python-examples/tables/create_table.py                     # create it
python local_env/python-examples/tables/create_table.py --table my_metrics  # under a different name
python local_env/python-examples/tables/create_table.py --drop              # drop it when you are done
```

```
POST /v1/tables -> status=pending (identity recorded, no DDL issued)
POST /v1/tables/demo_widget_metrics/schema -> status=active (8 columns, CREATE TABLE issued)
```

Two calls, on purpose: the first fixes what the table keeps for life (`name`, `type`, and the `write`
discipline that picks its ClickHouse engine), the second gives it a physical schema and issues the
`CREATE TABLE`. A table accepts rows only once it is `ACTIVE` — writing to a `PENDING` or `FAILED`
one is rejected with `409`.

The schema the script sends (`SCHEMA` in `create_table.py`) lands in ClickHouse as
`MergeTree PARTITION BY toYYYYMM(recorded_at) ORDER BY (recorded_at, event_id)`:

| Column | Type | ClickHouse |
| --- | --- | --- |
| `event_id` | `uuid` | `UUID` |
| `recorded_at` | `timestamp` | `DateTime64(3, 'UTC')` |
| `widget_id`, `region` | `string` | `String` |
| `views` | `long` | `Int64` |
| `score` | `decimal` | `Decimal(38, 12)` |
| `healthy` | `boolean` | `Bool` |
| `tags` | `array` of `string` | `Array(String)` |

`write: "append"` keeps every row; `upsert_by_key` would collapse to the latest row per
`ordering_key` (`ReplacingMergeTree`). It is immutable, because it picks the engine.
`identity_column`/`version_column` are optional — they are the pair the governed incremental scan
pages by.

## Write a row

```bash
python local_env/python-examples/tables/write_custom_table.py                    # -> inserted=1
python local_env/python-examples/tables/write_custom_table.py --table my_metrics # a different table
```

The row itself is a literal, hardcoded above `main()`:

```python
ROW = {
    "event_id": "8f14e45f-ceea-4d1b-a1c0-9c1e6d3b7a52",
    "recorded_at": "2026-08-11T10:00:00Z",
    "widget_id": "gauge",
    "region": "emea",
    "views": 1280,
    "score": "94.25",
    "healthy": True,
    "tags": ["beta", "pinned"],
}
```

Edit `ROW` and run it again — `--table` picks the target, `--base-url`/`--api-key` the endpoint. It
writes the same row every time; on this `append` table that means a second identical row, since
`append` keeps every write.

## Read the row back

```bash
curl -s -X POST localhost:5000/v1/queries/execute-sql -H 'Content-Type: application/json' \
     -d '{"sql":"SELECT event_id, recorded_at, score, healthy, tags FROM demo_widget_metrics"}'
```

```json
{"rows":[{"event_id":"8f14e45f-ceea-4d1b-a1c0-9c1e6d3b7a52","recorded_at":"2026-08-11T10:00:00Z",
          "score":94.250000000000,"healthy":true,"tags":["beta","pinned"]}]}
```

Timestamps come back as ISO-8601, `decimal` with its full scale, and an `array` column as a JSON
array.

## Three things that bite when writing rows

**Rows are keyed by _physical_ column name.** The write side uses each column's `source_name`, not
the exposed `name` the query surface projects. They are identical in this example; when they differ,
a row keyed by the exposed name is rejected as an unknown column (`422`).

**Timestamps must be ISO-8601 with an explicit zone.** A `timestamp` column takes either a `Z`
designator or an explicit offset — the same form the read path hands back, so a row you read can be
written straight back. Seconds are optional and fractional seconds are accepted at any precision
(stored to milliseconds). Anything else is rejected with `422 validation_error` and a message naming
the column and what to send instead:

```jsonc
{"recorded_at": "2026-08-11T10:00:00Z"}      // accepted
{"recorded_at": "2026-08-11T13:00:00+03:00"} // accepted — stored as 10:00 UTC
{"recorded_at": "2026-08-11T10:00Z"}         // accepted — seconds optional

{"recorded_at": "2026-08-11 10:00:00.000"}   // rejected: space separator, ISO-8601 wants 'T'
{"recorded_at": "2026-08-11T10:00:00"}       // rejected: no time zone
{"recorded_at": "2026-08-11"}                // rejected: a date, not a timestamp
{"recorded_at": 1786514400000}               // rejected: epoch numbers are ambiguous
```

A zone-less value is refused rather than assumed to be UTC: guessing would store a local wall clock
as the wrong instant with no error. Only columns actually declared `timestamp` are checked — a
`string` column stores a timestamp-shaped value verbatim.

**`_`-prefixed columns are not yours to set.** They belong to the platform (an `upsert_by_key` table's
`_updated_at`, for instance), and naming one in a row is rejected with `422`; ClickHouse stamps their
defaults.

`decimal` columns are sent as **strings** here (`"94.25"`) so the digits reach ClickHouse intact; a
JSON number works too, at float precision. `array` columns take a JSON array and are always
non-nullable — ClickHouse has no `Nullable(Array(...))`.

## Limits worth knowing

- `rows` is a list, so one `POST` can carry many rows — at most **10000**
  (`TABLES_MAX_WRITE_BATCH_ROWS`). Chunk anything larger.
- A query returns at most **1000 rows**, and one with no `LIMIT` is truncated to the **default 100** —
  so add an explicit `LIMIT` once the table holds more than that, or you will quietly read back a
  page rather than the table.
- In `oidc` mode a row write needs `FULL_ADMIN` or a matching `write` access role on the table, while
  create and delete are `FULL_ADMIN`-only. See the `table-access-control` capability, and
  "Authentication" above.
- `WHERE event_id IN (...)` on `/v1/queries/execute-sql` works up to **19 values**: Calcite normalizes
  a short `IN` to a `SEARCH` the translator expands back into the DSL's `in`, but at its
  `inSubQueryThreshold` (20) the list becomes a sub-query, which is rejected with
  `SQL construct 'IN' cannot be expressed in the structured query DSL`. For longer lists use
  `/v1/queries/execute`, which has `in` natively and no such ceiling:
  `{"op": "in", "args": [{"type": "field", "name": "event_id"}, {"type": "array",
  "items": [{"type": "value", "value_type": "uuid", "value": "…"}]}]}`.
