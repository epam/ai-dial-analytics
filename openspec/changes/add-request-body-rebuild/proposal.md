## Why

Under `SYNC_REQUEST_BODY_MODE=delta` a chat request's `dial_usage_log_payload.request_body` holds only the history
its parent lacked, without the system keys and tools it shares with its chain. Ingest stores everything a rebuild
needs, but nothing reads it back: every consumer that reads `request_body` gets the cut body. Two consumers need the
whole request — LLM enrichment pipelines that bind the column through `transform.inputs` (and whose
`member_select` typically picks a session's deepest hop, the most-cut row), and the admin hop inspector, which
renders a single hop's messages, role counts and tools from one structured-query row. Until ADAS rebuilds on read,
enabling `delta` silently degrades both.

## What Changes

- **New projection-only fields on the payload enrichment.** `dial_usage_log_payload.request_body_full` returns a
  row's request body rebuilt to the document the request carried; `dial_usage_log_payload.request_body_rebuild`
  returns what `request_body_full` holds: `stored`, `rebuilt` or `partial` (`NULL` when the row has no stored body).
  Both are sensitive and heavy like `request_body`. `request_body` itself keeps returning exactly what is stored.
- **Rebuild on read, two-phase and partition-local.** After a read returns its rows, ADAS resolves each
  delta row's chain from the small chain columns, then reads only the ancestor bodies it needs by `event_id`, and
  assembles the rebuilt body by the data-sync capability's rebuild equivalence. A chain that cannot be completed
  returns the stored delta unchanged, marked `partial` — never `NULL` and never an error.
- **Bounded.** Two new env-driven knobs, `QUERY_REQUEST_BODY_REBUILD_MAX_ROUNDS` and
  `QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS`, cap the chain resolution per read; past them the affected rows come
  back `partial`. Partial rebuilds are counted and logged.
- **Supported surfaces.** Row reads on `POST /v1/queries/execute`, `POST /v1/queries/execute-sql`, their CSV twins
  `execute-csv` / `execute-sql-csv`, saved-query execution and the Arrow Flight SQL endpoint; the pipeline data plane
  (`…/scan`, `…/group-rows` and the dry-run preview), so a `transform.inputs` entry can bind
  `request_body_full`. The translation endpoints (`translate`, `translate-sql`) accept and render the fields by
  name, never executing them.
- **Projection only.** Naming either field anywhere but as a plain row-mode projection — in a filter, sort,
  group-by, having, measure, `distinct` query, any expression or function argument, a composite (join, derived
  table, subquery, set operation) SQL statement, or a pipeline's governed SQL fragment, `member_select.order_by`,
  group key or aggregate declaration — is rejected: `400 bad_request` on the query surfaces, `422
  pipeline_validation_failed` on pipeline declarations.
- **Discovery** lists the two fields under `dial_usage_log`'s schema for a caller cleared for the payload table's
  sensitive columns, with a new `projection_only: true` flag. They are not catalog rows: `GET
  /v1/tables/dial_usage_log_payload` does not list them, and no Postgres or ClickHouse migration is added.
- **Docs.** `docs/release-0.4-upgrade.md` tells operators to re-point pipelines that bind
  `dial_usage_log_payload.request_body` to `request_body_full` before enabling `delta`;
  `docs/heavy-columns.md` and `docs/configuration.md` gain the matching entries.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`: the rebuild equivalence now has a reader — the "SHALL NOT itself expose a rebuilt body" sentence is
  replaced, and a new requirement specifies the rebuilt body, its status values, the two-phase read, the caps and
  the partial-rebuild metrics.
- `table-management`: the seeded payload enrichment's entity schema additionally carries the two rebuild fields,
  which are not catalog rows and are not listed by the table read surface.
- `structured-query`: the two fields are projection-only on the DSL; their values are completed after the statement
  runs; late materialization fetches them for the page only.
- `sql-query-translation`: the two fields are projection-only on the SQL surface, and refused in composite
  statements.
- `query-schema-discovery`: discovery fields carry a `projection_only` flag.
- `query-csv-export`: CSV cells of the two fields carry the completed value.
- `flight-sql-endpoint`: Flight streams the completed value; the two fields type as Utf8.
- `query-translation-endpoint`: the translation endpoints pass the fields through by name and apply the
  projection-only rule.
- `enrichment-runtime`: the scan, group-assembly and preview reads complete the two fields; the group byte cap
  measures the rebuilt body.
- `enrichment-registry`: a `transform.inputs` entry may bind the two fields; every governed fragment and
  `member_select.order_by` refuses them.
- `aggregate-pipeline`: an aggregate declaration refuses them.

## Impact

- **Code:** `sync/delta` (the rebuild moves from the test-only `Rebuild` into production code), a new rebuild reader
  over the payload table, `CatalogQueryService` / `QueryFieldBinding` (virtual bindings), `EnrichmentJoins`
  (derived tables project a virtual binding's physical columns), `StructuredQueryBuilder` and the SQL front end
  (projection-only checks), `StructuredQueryExecutor` (completion after materialization), `EntityFieldResolver`
  and the pipeline validators, `QuerySchemaFieldDto`.
- **APIs:** additive — two new discoverable fields and a new `projection_only` member on every discovery field. No
  endpoint is added or removed; `request_body` is unchanged.
- **Config:** two new properties under `query.request-body-rebuild.*`.
- **Data / migrations:** none. The fields are computed from the columns ClickHouse `V16` already added.
- **Cost:** a read that projects `request_body_full` over delta rows issues a bounded number of extra
  partition-local statements under the caller's remaining budget; a read of rows stored whole issues none.
- **Out of scope:** the admin frontend (it consumes the contract in the structured-query delta), a "what this turn
  added" view, a cheaper deduplicated insights input, rebuilding rows ingested before delta storage, and restricting
  ancestors by deployment or model inside a project.
