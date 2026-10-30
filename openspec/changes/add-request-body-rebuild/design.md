## Context

See proposal.md for the motivation. The current state that shapes the approach:

- **Storage.** Under `delta`, `dial_usage_log_payload` (ClickHouse `V10`, `ORDER BY (event_id)`, `PARTITION BY
  toYYYYMMDD(request_time)`, `ReplacingMergeTree`) carries, per chat request, `request_body` cut to its new history and
  the chain columns ClickHouse `V16` added: `request_hash`, `request_parent_hash`, `request_ancestor_hashes` (up to the
  128 nearest ancestor paths, root first — a hint) and `request_cache_control`, plus the `idx_request_hash` bloom
  filter. A chain never leaves one `(project_id, user_hash, UTC day)`, so it never leaves one partition. The data-sync
  spec's rebuild equivalence defines the result; `src/test/java/.../sync/delta/Rebuild.java` implements it for tests
  only.
- **Catalog.** `column_mapping` rows describe *physical* columns. Around twenty components read them as such
  (`CatalogQueryService`, `EntityFieldResolver`, `TableCatalogService`, `CatalogExportService`, `TableDdlRenderer`,
  `TableDataService`, `EnrichmentRelayoutService`, `LookupIndexService`, `ProvenanceReconciler`, the grant and
  sensitive-projection guards, the pipeline validators). Postgres `V50` registered `request_cache_control`.
- **Read paths.** Every caller-supplied read funnels through `StructuredQueryService` → `StructuredQueryExecutor`
  (`execute` for single-entity DSL, SQL after translation, CSV, saved queries and Flight; `executeComposite` for
  joins and derived tables). Every pipeline read (`ScanService`, `GroupFetchService`, the previews) builds its own
  jOOQ select from `QueryEntityModel.bindings()` and runs it through `StructuredQueryExecutor.executeScan`. All of them
  meet in `StructuredQueryExecutor.materialize`, and all project a binding as `binding.field()` under its flat name.
- **Joins.** `EnrichmentJoins` emits the payload join bare, keyed, or as a derived table projecting
  `binding.field()` for each referenced binding; late materialization ("Projection-only heavy enrichment columns are
  fetched for the returned page only") already reads heavy payload columns for the returned page only.
- **Lookup precedent.** `ClickHouseParentLookup` (ingest) looks paths up per partition in chunks of 1000, binding
  each list as one server-side string parameter because the client sends parameters in the URI.

## Goals / Non-Goals

**Goals:**

- One rebuild, implemented once in production code, reachable from every row-read surface and from the pipeline
  data plane, with the same governance as `request_body`.
- Cost proportional to the rows returned and their chains, never to the window a query filters, and zero extra
  statements for rows stored whole.
- No change to `request_body`, to ingest, or to any query that does not name the new fields.

**Non-Goals:**

- Using the rebuilt body inside SQL (filters, functions, aggregates, joins). It exists after the statement.
- A rebuild inside ClickHouse (UDF, dictionary, materialized column).
- Caching rebuilt bodies across reads.
- Everything the proposal lists as out of scope.

## Decisions

### D1. The two fields are service-defined computed fields, not catalog rows

A small closed registry in production code (`ComputedFields`, beside `CatalogQueryService`) defines
`request_body_full` and `request_body_rebuild` for the table `dial_usage_log_payload`. It produces, per field, a
**transient** `ColumnMappingEntity` descriptor (never persisted; `sensitive` when the `request_body` or the
`request_cache_control` row is, since the rebuilt body returns the values of both; `heavy` true, `nullable` true,
fixed description and display name) so the places that build a flat field set from catalog rows —
`CatalogQueryService.buildModel` (query model and discovery), `EntityFieldResolver` (pipeline declarations) and
`SensitiveProjectionGuard` (the pipeline data plane's 403, through `ComputedFields.withComputed`) — append them after
the enrichment's catalog columns through their existing per-column code paths, and only when the `request_body` row
is present. Nothing else sees them.

Alternatives rejected:

- **A `column_mapping` row with a new `computed` marker (Postgres `V51`).** Every physical consumer listed above would
  have to learn to skip it; one that does not (export, relayout, lookup index, row-policy validation) fails late, as a
  ClickHouse "unknown column", on a code path unrelated to the feature. It would also make an operator-editable catalog
  row the switch for service code. The only gains — catalog-stored description, listing on `GET /v1/tables` — are not
  worth that; the table read surface describes stored columns, and a computed field has none of the operations that
  surface governs.
- **A ClickHouse `ALIAS` / `MATERIALIZED` column or UDF.** A rebuild reads other rows, round after round; an alias
  cannot, and an executable UDF runs one process per block per call site.
- **A dedicated endpoint (`GET …/request-body/{event_id}`).** Pipelines bind fields through `transform.inputs` and the
  hop inspector already reads through `/v1/queries/execute`; a field keeps one governance, one late-materialization
  path and one discovery entry for both consumers.

No Postgres or ClickHouse migration is added (no `V51`).

### D2. A computed binding renders a tuple stand-in and declares its physical reads

`QueryFieldBinding` gains two members: `computed` (the registry descriptor, `null` for every catalog binding) and
`reads` (the physical columns the binding reads; for a catalog binding, `[field]`). A computed binding's `field` is a
**stand-in** expression over the payload table's own columns, of a dedicated jOOQ field type the executor can
recognize after unwrapping an alias:

- `request_body_full` → `tuple(request_body, request_parent_hash, request_ancestor_hashes, request_cache_control,
  toYYYYMMDD(request_time))`
- `request_body_rebuild` → `tuple(toUInt8(event_id = zero-UUID OR request_body.null = 1), request_parent_hash,
  request_ancestor_hashes, toUInt8(request_cache_control.null = 0), toYYYYMMDD(request_time))`

The status stand-in names no body: it reads the two text columns only through their `.null` subcolumns. The first draft
used `isNull(request_body)` and `isNotNull(request_cache_control)`; measured in the integration test on the supported
server, `isNull` over a column read `FINAL` (which every payload read is) reads the whole column, while the explicit
`.null` subcolumn reads the null map alone. A subcolumn of an unmatched `LEFT JOIN` row reads its type default (`0`),
not `NULL`, so the stand-in also reads the grain key, which is the all-zero UUID exactly on an unmatched row, and
treats that row as having no body. Under `join_use_nulls = 1`, which a server or user profile can set, an unmatched
row's elements read `NULL` instead; the completer decides "no body" before it parses anything else, and treats a
`NULL` body, flag, parent or day the same way, so such a row reads both fields `NULL`. The paths are cast to text and the day is a number in both stand-ins.

Every projection site already projects `binding.field()` under the output name, so a stand-in travels through the DSL
builder, the late-materialized outer statement, the scan, the group projection and the previews with **one cell per
field and no helper columns**: output names, order and count are unchanged, and no site can forget a companion column.
`EnrichmentJoins.derived` projects `binding.reads()` instead of `binding.field()`, so a derived payload table carries
the physical columns the stand-in references — five for the body, the grain key and two `.null` subcolumns among six
for the status — each once (they are qualified by the enrichment's name, which is also the derived table's alias). Paths that inspect a binding as a column (`getUnqualifiedName`, object-path pruning, the
"scalar call over a heavy text column" shaping) skip computed bindings, which is safe because no expression over
them is accepted (D6).

Rejected: helper columns (`__adas_rb_*`) appended next to the field — every site would have to add and strip them,
and a site that forgot would silently return the stored body as if it were the rebuilt one.

### D3. Completion happens once, in `StructuredQueryExecutor.materialize`

After a statement's rows are materialized, `materialize` hands the rows and the select's output columns to a new
`ComputedFieldCompleter` when the projection carries a stand-in. The completer replaces each stand-in cell with its
value (D4, D5) and counts it. This is the single choke point every supported surface reaches, so the user reads
(`execute`, and through it SQL, CSV, saved queries and Flight) and the pipeline reads (`executeScan`) share one
implementation. `executeComposite` never sees a stand-in because translation refuses it (D6); the completer asserts
that.

The completer runs the rebuild statements on the **reader** client (`ClickHouseClientWrapper`), under the settings
the triggering statement ran with (its budget, its row-policy filters) with the wall-clock ceiling reduced to what is
left, plus `final = 1` and `do_not_merge_across_partitions_select_final = 1` — the payload table is partition-local.
The settings are asked for anew before each statement (`ClickHouseBudget.remainingTimeIfAny`), which answers nothing
once less than a second is left: the statement is then not issued and the rows of its day end `partial` (`failed`).
`remainingTime`'s one-second floor suits a statement that must run, but granted per rebuild statement it would bound
the rebuild by its statement count rather than by the budget. For the same reason the completion runs after the read's
own statements: in `execute` after the `include_total` count, which fails the read when it times out, where a rebuild
short of time only degrades rows. The `surface` tag is `query` for `execute`, `pipeline` for `executeScan`.

The settings never carry the triggering statement's query id (`remainingTime` drops it). A pipeline read hands its
`StatementTag` to `executeScan`, and each rebuild statement then carries a fresh id minted under the stage
`<stage>-rebuild` (`adas-p1-group-project-rebuild-<uuid>`), so the statements a computed field costs can be attributed
to the pipeline in `system.query_log`. The id is set on the statement's settings only, not published to the MDC: the
call's outcome line keeps describing the triggering read. User reads stay untagged, as their own statements are.

### D4. Two-phase, partition-local chain read

For each stand-in cell whose `request_parent_hash` is non-zero (cells with an all-zero parent need no read; one with
markers is rebuilt from its own body):

**Phase 1 — resolve the chain from small columns.** Seed the path set from every target's
`request_ancestor_hashes` (plus its parent, defensively). Look the paths up, per partition day of the targets:

```sql
SELECT toString(request_hash) AS path, toString(request_parent_hash) AS parent,
       arrayStringConcat(arrayMap(x -> toString(x), request_ancestor_hashes), ',') AS ancestors,
       toString(event_id) AS event_id
FROM dial_usage_log_payload FINAL
WHERE request_time >= {from:DateTime64(3, 'UTC')} AND request_time < {to:DateTime64(3, 'UTC')}
  AND toYYYYMMDD(request_time) IN (SELECT toUInt32(arrayJoin(splitByChar(',', {days:String}))))
  AND request_hash IN (SELECT toUUID(arrayJoin(splitByChar(',', {paths:String}))))
LIMIT 1 BY request_hash
```

`LIMIT 1 BY request_hash` is sound because any row carrying a path yields the same history (and a path already
folds in its day). Walk each target's chain over the found rows; for each chain stopped at a missing path, the next
round's seeds are that path and the `ancestors` hint of the row whose parent it is. Stop when every chain reaches a
root, a round adds nothing, or a bound is reached (D7).

**Phase 2 — read the bodies by key.** Read `event_id, request_hash, request_parent_hash, request_body` for exactly
the `event_id`s the walks visited, same day bounds, `event_id IN (…)` — the table's sort key, so the read prunes to
the granules holding them. Re-walk using **phase 2's** links: a row whose path or parent changed between the phases (a
re-ingest) is re-resolved in the remaining rounds or ends `partial`, so a body is never combined with another
version's link. Only the target's own markers are restored (markers are not inherited), so phase 2 reads no
`request_cache_control`.

Both phases bind values as server-side parameters, one comma-separated string per list, in chunks of 1000 (the
`ClickHouseParentLookup.CHUNK` rationale: parameters travel in the URI). Both read `FINAL` whatever `FORCE_FINAL` says,
because the walk's correctness depends on each row's body and link coming from one version.

**Why two phases.** A spike over one full day of ingested usage logs compared three shapes. Reading a page of insights-style
members through the two-phase rebuild cost about the same as, or less than, reading those members' whole bodies
today; a single-row rebuild was a few times slower than a whole-body read but stayed well under a second; chains
deeper than the 128-path hint converged in very few rounds. The naive shape — reading bodies inside the hash lookup —
cost several times more, because the bloom-filter lookup reads whole granules and a body column multiplies what each
granule costs. Phase 1 therefore touches only the small chain columns, and phase 2 touches bodies only through the
primary key.

**Status only.** A projection of `request_body_rebuild` without `request_body_full` runs phase 1 only. When both are
projected they are completed together and always agree. The group projection, which otherwise reads each heavy column
in a statement of its own, therefore reads the computed fields of one enrichment in one statement: read apart, the
status read would walk phase 1 only and could say `rebuilt` beside a body the other read returned `partial`.

### D5. The rebuild moves into production code

`sync/delta/RequestBodyRebuild` (package-private helpers of `ChatRequest` reused: `MESSAGE_ARRAY_KEYS`,
`SYSTEM_KEYS`, `TOOLS`, `parse`, `canonicalHistory`, `canonical`) implements the equivalence over in-memory rows with no
I/O: iterative walk (no recursion), history = root history + each descendant's message elements, system keys and
`tools` from the root, own markers restored, written with `ChatRequest.canonical` (sorted keys, exact decimals, lone
surrogates escaped — the same writer the stored body uses, so the output is always valid JSON text). Any exception
for one row (unparseable body on the chain, a marker pointing outside the history) yields `partial` with reason
`invalid` for that row only. The test `Rebuild` becomes a thin adapter over it, and `RequestDeltaEncoderTest` /
`RequestDeltaIntegrationTest` keep their assertions, so ingest and read are checked against one implementation.

A body completed as `stored` or `partial` is the stored text, byte for byte — it is never re-serialized.

### D6. Projection-only enforcement points

One message, one rule, enforced where each surface already validates:

- **DSL** — `StructuredQueryBuilder` (and therefore execute, CSV, saved queries, translate): a computed binding may
  appear only as a bare `select` field in row mode, not in a `distinct` query; anything else, including a `sort` on its
  output name, a subquery reference, and an output name beginning with `__adas_`, is a `QueryValidationException`
  (400 `bad_request`).
- **SQL** — single-entity statements translate to the DSL and hit the same check; composite statements
  (`CompositeQueryBuilder` / `SqlCompositeTranslation`) refuse any computed binding anywhere with the same 400, since an
  enclosing block would consume a stand-in tuple the completer never sees.
- **Pipelines** — `EntityFields.Field` gains `computed`; `transform.inputs` resolution accepts it; `FilterSqlValidator`
  (filter, `ready_when.signal`, `prefer_sql`, `sql` outputs), `member_select.order_by`, `trigger.group_by` and the
  aggregate declaration validators refuse it with `PipelineValidationException` (422 `pipeline_validation_failed`).
  Those run at arming and preview only, and a disabled pipeline — every aggregate registration among them — is a draft
  the data plane still reads as stored, so `DeclaredComputedFields` also refuses the same names on **every**
  declaration write (`PipelineService.apply`, beside the trigger contradictions): by name, needing no entitlement or
  parse, with fragments scanned outside their literals and comments. It therefore precedes the entitlement check,
  which only the accepted place, `transform.inputs`, reaches. `ScanService` and `GroupFetchService` refuse a stored
  `order_by` or `group_by` naming one as well, for a declaration stored before that check; their fragments are
  already refused by name when parsed.

### D7. Bounds, failure and observability

`RequestBodyRebuildProperties` (`@ConfigurationProperties("query.request-body-rebuild")`, `@Validated`, each `@Min(1)`):
`max-rounds` (default `8`) and `max-ancestors` (default `20000`, distinct ancestor rows per read). Eight rounds of a
128-path hint reach chains of on the order of a thousand turns in one day; twenty thousand ancestors cover a full
1000-row page of typical conversations. The memory a read holds is bounded by the requests it returns: a delta row
was a parseable request when stored, so its rebuilt body is no larger than what the request carried, and the ancestor
bodies are disjoint slices of it.

The chain hints are not covered by that argument. Each resolved link keeps its row's `request_ancestor_hashes` (up to
128 paths) for the life of the read, and so does each target, so the hint memory is bounded by
`(max-ancestors + rows returned) × 128` paths: on the order of a hundred MB at the default bound and a full Flight
page, before any body is read. It is reached only by a read whose rows mostly sit deep in long chains; the bound that
governs it is `max-ancestors`, and lowering it trades that memory for `partial` (`limit`) rows.

Every failure is per row and ends `partial` with the stored body: `missing` (no row carries a path), `limit` (a bound
reached), `failed` (a rebuild statement of the row's day threw or was not issued for lack of time, budget breach
included — the triggering statement already succeeded, so its rows are returned; the other days keep resolving),
`invalid` (D5). Metrics `query.request.rebuild.rows{outcome,surface}` and
`query.request.rebuild.partial{reason}`; one WARN line per read with partial rows (counts per reason, rounds; never a
body or a hash); a DEBUG line per read with rounds, ancestors and statement count.

### D8. Supported surfaces

| Surface | Decision | Why |
|---|---|---|
| `POST /v1/queries/execute`, `execute-sql` (single-entity) | supported | the two consumers' paths |
| `execute-csv`, `execute-sql-csv` | supported | same pipeline; the cell is the JSON text |
| Saved-query execution | supported | executes the stored DSL through `execute` |
| Flight SQL | supported | same pipeline; `Utf8`; large statements degrade to `partial` under the per-read bounds rather than raising them |
| `translate`, `translate-sql` | pass-through by name, projection-only rule applied | validation-only; round trip must hold |
| Composite SQL (joins, derived tables, CTEs, set operations, subqueries) | refused, 400 | an enclosing block would read the stand-in |
| `…/scan`, `…/group-rows`, previews | supported | `transform.inputs` binding is the insights consumer's path |
| Governed fragments, `member_select.order_by`, `group_by`, aggregate pipelines | refused, 422 | the value does not exist inside SQL |
| `INFORMATION_SCHEMA` / Flight metadata | listed like any column | the field is selectable |

### D9. Discovery

`QuerySchemaFieldDto` gains `projection_only` (`@JsonProperty("projection_only")`, false for catalog columns). A
computed field's `source` is its own bare name. `request_body_rebuild` is typed `enum` with `enum_values` `stored`,
`rebuilt`, `partial`, so a client can build its warning from discovery alone.

## Risks / Trade-offs

- [The ClickHouse client's Java mapping of a `Tuple` cell (array vs list, UUID vs string)] → the completer reads the
  tuple positionally through one adapter, unit-tested against the client's actual mapping and covered by the
  integration test; the stand-in casts UUIDs and the day to stable types. The client's reader declares a `Tuple` as an `Object[]`;
  the adapter accepts an array, a list or the client's array wrapper for both the tuple and its ancestor array, and a
  number or a boolean for the flags, so the end-to-end tests pass through whichever the client produces.
- [A projection site that does not go through `binding.field()` would return a raw tuple] → every pipeline path
  already does; the integration tests cover execute, SQL, CSV, Flight, scan, group-rows and preview end to end, and
  the completer fails loudly (500, logged) on a stand-in cell it cannot read rather than passing it through.
- [Extra statements add latency to the hop inspector] → partition-local, primary-key phase 2, zero statements for
  rows stored whole; the spike's single-row cost stays well under a second.
- [A pathological chain or a huge Flight pull] → the per-read bounds turn excess into `partial` rows, counted and
  logged, never into a failed read or an unbounded one.
- [The budget runs out in the middle of a rebuild] → no further statement is issued and the remaining rows are
  `partial` (`failed`); the read the caller paid for, its total count included, is still returned.
- [Row policies on the payload table can hide an ancestor] → the rebuild runs under the same settings as the read, so
  a hidden ancestor makes the row `partial` rather than leaking; today no catalog-visible payload column carries a
  scope, so this is theoretical.
- [Consumers keep reading `request_body` after `delta` is enabled] → the release notes and the data-sync spec tell
  operators to re-point first; ADAS does not rewrite pipelines.

## Migration Plan

1. Deploy the build. No migration runs; `request_body` is unchanged, so nothing changes for existing readers.
2. Re-point every pipeline whose `transform.inputs` binds `dial_usage_log_payload.request_body` to
   `dial_usage_log_payload.request_body_full` (and optionally bind `request_body_rebuild`), and move other consumers
   that need a turn's whole request. Under `whole` the new field returns the stored body (`stored`), so re-pointing
   first is safe.
3. Enable `SYNC_REQUEST_BODY_MODE=delta`.

Rollback: an older build does not know the fields, so a pipeline bound to `request_body_full` fails validation there.
Re-point such pipelines back to `request_body` and switch ingest to `whole` before rolling back.

## Open Questions

- The default bounds (`8` rounds, `20000` ancestors) are chosen from the spike's shape; they can be retuned from the
  `query.request.rebuild.partial{reason=limit}` counter after release without changing the design.
