## Why

Analysts who pull a result out of ADAS to open in a spreadsheet or load into a notebook have to
convert the `{"rows": [...]}` JSON envelope themselves. A CSV variant of the two execute endpoints
hands them a file directly, with no client-side transformation and no change to what the query
means, which rows it may return, or who may run it.

## What Changes

- New `POST /v1/queries/execute-csv`: accepts exactly the `/v1/queries/execute` request body
  (a structured-query DSL document) and returns the same result as an RFC 4180 CSV attachment.
- New `POST /v1/queries/execute-sql-csv`: accepts exactly the `/v1/queries/execute-sql` request
  body (`{"sql": "..."}`), including `INFORMATION_SCHEMA` statements, and returns the result as CSV.
- Both endpoints reuse the JSON endpoints' pipeline unchanged: same validation, catalog governance,
  sensitive-column filtering, default limit 100 / maximum limit 1000, query budget, and `ErrorView`
  error contract. This is a **download of the same result**, not a bulk export: a result larger
  than the maximum limit is rejected exactly as on the JSON endpoints.
- The CSV always begins with a header row of the projected column names, **even when the result
  has no rows**.
- Cells render the same value the JSON body carries: null → empty field, scalars as text,
  arrays/objects/maps as compact JSON text. Values are never rewritten (no spreadsheet-formula
  escaping).
- `totalCount` (DSL offset paging with `include_total`) is carried in an `X-Total-Count` response
  header, since a CSV body has no place for it.
- Internal: the query result page carries the ordered list of projected column names, so a
  header can be emitted for an empty result. The JSON response envelope is unchanged.

## Capabilities

### New Capabilities

- `query-csv-export`: the CSV response form of query execution — the two `*-csv` endpoints, their
  inheritance of the JSON endpoints' pipeline and governance, the CSV dialect, cell rendering, the
  header-row guarantee, `X-Total-Count`, and error responses staying `ErrorView` JSON regardless of
  the request's `Accept` header.

### Modified Capabilities

None. The JSON endpoints' requirements (`structured-query`, `rest-sql-endpoint`) are unchanged;
the new capability references them rather than restating them.

## Impact

- **API**: two new `/v1/queries/*` POST endpoints (OpenAPI-documented, `text/csv` response). No
  existing endpoint changes shape.
- **Code**: new controller(s) and a CSV writer under `web/`; `QueryResultPage` gains a `columns`
  component populated by `StructuredQueryExecutor`; request handling that keeps error bodies JSON
  when the client asks for `text/csv`.
- **Security**: none new. The paths fall under the existing `/v1/**` application-role rule and the
  sensitive-column chokepoint in the query model.
- **Config / dependencies**: no new properties (nothing added to `docs/configuration.md`), no new
  library.
- **Docs**: `docs/sql-reference.md` gains a short note on the CSV variant of `execute-sql`.
