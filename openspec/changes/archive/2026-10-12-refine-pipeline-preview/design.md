## Context

Preview (`PipelineDataController.preview`) has two arms with unrelated shapes:

- **`aggregate`** goes to `AggregateRefreshService.preview`. It runs `AggregateCompiler.compilePreview`
  and renders `compileRefresh` without running it.
- **`enrich`** goes to `EnrichPreviewService`, which hands off to one of:
  - `MaterializeService.preview` for Class-1;
  - `ScanService.scan` for row-grain `llm`;
  - `GroupFetchService.fetch` for `group`.

A review of the code and of previews run against real pipelines found these problems:

- The rendered refresh statement is wrong in two ways: it has no window end or cap (only the safety lag
  and the seek; the cap lives in the probe's `LIMIT`), and it drops the clamp in the held-back case
  (`AggregateRefreshService.java:424-428`, `.filter(at -> !at.withholdsEverything())`).
- Samples are taken first-by-key or oldest-first.
- Payload is spent echoing what `GET /v1/pipelines/{name}` already serves.
- There are no types and no completeness flag.
- Notes are free text.
- The empty-sample note blames the filter when the clamp emptied the sample.
- The enrich preview reports budget refusals under the runner's code.
- A `group` preview without a `group_key` returns no data.
- The row-grain sample mixes filter and cursor columns in with the inputs.

The UI design for preview starts next. Its users preview right after writing a declaration and before
enabling it.

## Goals / Non-Goals

**Goals:**

- One flat response shape for both kinds that carries only what preview alone knows: rows, typed
  columns, `statement`, an aggregate-only `total_rows`, and coded notes.
- Samples that show the newest input.
- Warnings that matter before enabling: held by an enrichment, the enrichment behind, values the target
  cannot hold, groups the runner would cut.
- Correct, consistent refusal codes, and specs that match the code.

**Non-Goals:**

- **Runtime state.** Cursor, lag, next window and clamp position stay on `GET …/{name}` under `state`.
  Preview answers "is this declaration right", not "where is the pipeline".
- **Cost statistics** (rows read, bytes read, elapsed time). The executor exposes no ClickHouse
  summary, and the cost of an unwindowed sample says little about a windowed refresh.
- An opt-in `from`/`to` window for `aggregate` preview, which the "scope never narrowed" budget rule
  would need revisiting for.
- Rendered prompts and candidate declarations in the body.
- Any change to gating rules or budget limits, and any change to the runner's scan or group fetch
  contract.
- Data-quality findings on specific pipelines, for example `turns.response_id` being null where the
  root hop is filtered out.

## Decisions

**D1. Flat shape with a closed note-code enum.**

The new types:

```java
PreviewDto(statement, columns, rows, total_rows, group_key, notes)
PreviewColumnDto(name, role, type, target_type, max_bytes)
PreviewNoteDto(code, severity, subject, message)
```

- `PreviewDto` keeps `NON_NULL`; `total_rows` is a `Long` set to `0` (not null) on an empty aggregate.
- `rows`, `columns` and `notes` are always present.
- `PreviewColumnRole`, `PreviewNoteCode` and `PreviewNoteSeverity` each carry a lowercase `code`
  exposed through `@JsonValue`, as `QueryFieldType`, `PipelineKind` and `FreshnessMode` do. The
  application mapper's `WRITE_ENUMS_TO_LOWERCASE` covers only the wire: springdoc would otherwise
  publish `"enum": ["NO_MATCHING_ROWS", …]` while the wire sends lowercase, which is the drift
  `EvaluatorType` already shows in the adas-mcp snapshot.
- `EnrichPreviewDto`, `PreviewOutputDto` and the `EnrichPreview.SchemaSource` exposure are deleted.

Alternatives considered:

- Keep the sibling `enrich` member. Rejected: two shapes means two UI components for one screen.
- Keep echoing only the derived members. Rejected: the gate writes them on every declaration write, so
  the read already serves them (`GET …/{name}` for aggregate; `?view=compiled` for enrich).
- Free-text notes with a code prefix. Rejected: a UI cannot branch on prose.

**D2. Remove the refresh statement rather than fix it.**

A faithful refresh statement needs the probe, which means another query, and it describes runtime state
in any case. Removing it fixes both bugs and shrinks the payload. The one fact it carried that matters
before enabling ("would a run write nothing?") becomes the `held_by_enrichment` note, computed from the
`enrichmentBound` result the method already has.

`held_by_enrichment` covers only the no-bound case. A refresh can also write nothing because every
probed row is above a bound that exists (`AggregateRefreshService.java:248-256`). Preview reports that
as `enrichment_behind` with the gap in its message, not as held, because telling the two apart needs the
probe this decision removes.

Alternatives considered:

- Run the probe and print the real statement. Rejected: it adds cost, and it answers a runtime-state
  question.
- Keep the statement with a disclaimer. Rejected: a dangerous statement is still dangerous with a label
  on it.

**D3. Newest-first ordering, per arm.**

- **Aggregate.** `ORDER BY max(versionExpr) DESC, keys ASC`, using `CursorPredicates.versionExpr` over
  the input's cursor columns. The expression is qualified with the input table: `compilePreview` joins a
  whole enrichment table when there is no source-only filter (`EnrichmentJoins.java:228-229`), and on an
  `upsert_by_key` input versioned by `_updated_at`, the user-table enrichment also has `_updated_at`. The
  `max` is projected under an internal alias, because `enrichment_behind` needs it, and stripped before
  mapping.
- **Class-1.** `sampleQuery` flips from `ASC`/`ASC` to `DESC`/`DESC`. This costs nothing extra: final=1
  collapses keys before the sort, and both orders are a full top-N sort on a non-sort-key expression.
  The version is projected under an internal alias for `enrichment_behind` and stripped before mapping.
- **Row-grain `llm`.** The preview stops calling `ScanService.scan`, whose ascending keyset order is its
  contract with the runner. Today `buildScan` is private and builds the projection, order and limit
  inline, while the clamp and the sensitive check live in `run()`. So the change first extracts a **scan
  plan** from `run()`: filter analysis, the guarded set (projection + bound), the clamp bound, the
  source predicates and the joins. The runner's scan and a new preview read both render from that plan.
  The preview read differs only in its SELECT list (grain key + bound inputs) and its ORDER BY
  direction. The join, guard and clamp sets stay the runner's, so a filter on an enrichment column
  still joins, a sensitive filter column is still enforced, and the clamp sees the same enrichments. A
  test pins the runner scan's rendered SQL as unchanged.
  - Rejected: a descending flag on the runner's scan API, because it would widen a runner contract for
    an operator need.
- **Group auto-pick.** One statement:
  `SELECT toString(<group field>) FROM source [JOIN …] WHERE filter AND lag AND clamp AND window AND
  <group field> IS NOT NULL AND toString(<group field>) != '' ORDER BY version DESC LIMIT 1`.
  - The key is projected as `toString(...)` because `fetch` compares `toString(field) = groupKey`
    (`GroupFetchService.java:371`), and blank keys are excluded because `fetch` refuses a blank key
    (`:174-176`). Some clients send `chat_id=''`, so without the exclusion the newest row
    of a `chat_id`-grouped source is often keyless.
  - The field is resolved with `ScanSourceMetadata.projected` and `EnrichmentJoins.addReferenced`, so
    an enrichment-sourced `group_by` works.
  - `sensitiveGuard.enforceEntity` runs over the group field and the filter columns before the read.
  - The clamp is the one the runner's dirty-mark scan applies (`ScanService.java:296-302`),
    `freshness.bound(...)` over the guarded set plus `atMost`. `GroupFetchService` itself has no clamp,
    so without this the preview could choose a group the runner has not marked yet. A bound that
    `withholdsEverything` short-circuits to an empty sample with `held_by_enrichment`.
  - The first attempt passes `from = now − 24h` through `CursorPredicates.window`. That adds the
    partition floor (`request_time >= (SELECT min(...) … SETTINGS final=0)`) on a source declaring
    `partition_column`; a plain `_ingested_at` bound prunes nothing under final=1. On a source without
    one, it is a plain version predicate. If the first attempt finds nothing, it retries once with no
    window.
  - It runs under the preview budget path.

Alternative considered: a default `from = now - 1 day` for every enrich sample. Rejected: it is
arbitrary, and it empties the sample of a quiet pipeline.

**D4. An aggregate-only `total_rows`, and no "more rows" flag.**

Preview has no pagination, so a `has_more` flag gives a client nothing to act on beyond "showing N".
What an author can act on is the size of the result. On `aggregate`, the sample statement adds
`count(*) OVER ()` to the grouped result. ClickHouse evaluates it after `GROUP BY` and before
`ORDER BY`/`LIMIT`, which gives the number of groups over the whole input in the same pass. The
statement goes through `executeScan`, not the execute-sql guard, so the window function is admitted.

The cost is not free. Without a window, the grouped result feeds a top-N sort that keeps about
`preview-rows` rows. With an empty-frame `OVER ()`, the window step buffers the whole grouped result
(every key and measure) before the sort, and that step does not spill to disk as `GROUP BY` can under
`max_bytes_before_external_group_by`. A declaration with tens of millions of groups may therefore be
refused under `max_memory_usage` rather than report its count. The spec accepts this: the refusal is
the same "wrong group key" signal, and the aggregate preview-budget message says so.

The `enrich` kind gets no count. For Class-1 it would only measure the source.

The group arm still counts members, but only to decide `group_cut_by_fetch_limit` (D8).

Alternatives considered:
- A `has_more` flag from reading cap + 1. Rejected: there is no paging to act on.
- A separate `count()` statement. Rejected: it is a second read under the budget, of the same size.
- ClickHouse's `rows_before_limit_at_least`. Rejected for now: the executor exposes no query summary
  (see Non-Goals), and it needs `exact_rows_before_limit`, which costs the same full pass.
- A Class-1 total. Rejected: it is the source size.

**D5. Types and coercion checks.**

- **`type`** of an aggregate measure. The gate infers no `QueryFieldType` today: it only checks the
  catalog's coarse `fn.returns()` against the target through `RETURN_TYPE_TARGETS`, and does not check
  `SAME_AS_ARGUMENT` (min/max) at all. `ExprFieldType.of` maps `NUMERIC` to `decimal`, which would
  mislabel `sum(long)` (the engine's `Int64`). So this is **new code in the gate**, a small inference
  table next to `RETURN_TYPE_TARGETS`:

  | Measure | `type` |
  |---|---|
  | `count` | `long` |
  | `sum` over `integer`/`long` | `long` |
  | `sum` over `decimal`, `avg` | `decimal` (as `requireFractionPreserved` already forces) |
  | `min`, `max` | the input column's type |
  | `group_uniq_array` | `array` |

  The catalog's measure set is exactly these six functions; `uniq` and `arg_max` are not measures.
- **`type`** of an aggregate key: the input binding's type for a plain key. A `trunc` key takes the
  target's type when the unit's `TruncUnit.targetTypes` contains it (a day, week or month bucket into
  `date` is lossless), and `timestamp` otherwise.
- Class-1 outputs take `ExprAnalysis.type()`. Inputs take the source binding's type.
- **`target_type`** is the target entity model's binding type.
- **`target_type_narrowing`** fires when `type` ranks above `target_type` on `integer < long <
  decimal`, or, for `min`/`max` only, on `date < timestamp`. A plain key is never narrowing, because
  `requireGroupKeyTypeAccepts` already requires an exact match. The real silent narrowings this catches
  are:
  - `count` or `sum` into `integer`;
  - `min`/`max` over `decimal` into `long`/`integer` (fraction loss);
  - `min`/`max` over `timestamp` into `date`.
- **`value_out_of_target_range`** reuses the range rules the REST row-write path (`WriteValuePreparer`)
  already enforces. Pipeline INSERT…SELECT never ran them. The catalog type fixes the physical type
  (`integer` is `Int32`, `long` is `Int64`, `decimal` is `Decimal(38, 12)`). Three classes hold the
  ranges:
  - `WriteNumericRangeValidator`: `integer`, `long`, `decimal`;
  - `WriteDateValidator`: `date`, 1970–2149;
  - `WriteTimestampNormalizer.MIN/MAX`: `timestamp`, 1900–2299, the ClickHouse 26.3 clamp.

  Their per-value checks are extracted into non-throwing predicates that both paths call, so the two
  cannot drift. Today the write path throws `TableValidationException` per row and parses strings
  (`WriteDateValidator` returns early on non-`String` input). Sampled values arrive as typed Java values
  (`Long`, `BigInteger` for `UInt64`, `BigDecimal`, `LocalDate`, `OffsetDateTime`), so the predicates take
  typed values, and the write path converts and then calls them.

Alternative considered: casting in the sample `SELECT` to show the coerced value. Rejected: that hides
the problem instead of reporting it.

**D6. `statement` carries its settings textually.**

Settings go to ClickHouse as per-query JDBC settings, not as SQL. For the aggregate preview they are:
- from `FinalSettings.pipeline`, merged inside `executeScan` (`StructuredQueryExecutor.java:280-282`):
  `final=1` and `transform_null_in=0`, plus `do_not_merge_across_partitions_select_final=1` on a
  partition-local model;
- from `ClickHouseBudget.settings`: `max_execution_time`, `max_memory_usage` and
  `max_bytes_before_external_group_by`.

`StatementTag.applyTo` adds a `query_id`, which is a client option and not a setting.

To keep the rendering from drifting from what ran, the merge moves out of `executeScan` into one helper
next to `FinalSettings`. The executor and the renderer both call that helper, and the renderer emits
only the server-setting keys, as `SETTINGS k = v, …` in a stable (sorted) order. It never emits
`query_id`, and it cannot see server-profile settings. `StructuredQueryExecutor`'s public contract does
not change.

The rendered statement also projects the internal helper columns (`max(version)`, the window count, the
Class-1 version), so run by hand it returns a few columns more than `rows` does. The spec says so.

**D7. Preview budget code for enrich.**

`PipelineReadBudget.run` stays as it is. It has six call sites, and two of them (`GroupFetchService:394`
and `:600`) are private methods shared by the runner and the preview, so a surface parameter would have
to be threaded through the runner path this change promises to leave alone. Instead:
- `PipelineReadBudgetExceededException` gains a structured `exceeded` (ceiling name, configured value).
- `EnrichPreviewService` catches it around every read it issues and rethrows
  `PipelinePreviewBudgetExceededException`. That exception and its 422 handler already exist
  (`DefaultExceptionHandler.java:386-398`). Its Javadoc, which says `aggregate.preview.*` only, is
  updated.
- The message carries author-facing remedies chosen per arm: a narrower `from`/`to` (not on `group`,
  which refuses one), fewer heavy columns bound, or a raised `pipeline.budget.*`.
- The preview-budget `logRefusal` key becomes per pipeline, as the read budget's already is, so one
  noisy pipeline cannot suppress another's log line.

The limits are unchanged.

Alternatives considered:
- A surface parameter on `PipelineReadBudget.run`. Rejected for the reason above.
- Moving enrich preview onto `aggregate.preview.*`. Rejected: that changes what is admitted, and the
  2 GB ceiling would refuse every heavy-column read.

**D8. Notes computed from existing state.**

| Note | Computed from |
|---|---|
| `held_by_enrichment` (every arm) | the bound `withholdsEverything()`: the aggregate's `enrichmentBound`; the bound the row-grain scan plan resolves; the group auto-pick's clamp; and, for Class-1, `freshness.bound(referencedEnrichments(model, referenced), source)`, which `MaterializeService.preview` does not compute today (it passes `null`) and now computes for notes only. No statement is issued. |
| `held_by_enrichment` (row-grain, partial bound, empty sample) | one probe: filter AND lag AND window AND `CursorPredicates.seekPast(bound)`, projecting only the version, `LIMIT 1`, under the preview budget. `seekPast` brings the partition floor. This is the idea of `MaterializeService.clampHeldRowsBack` (`:636-649`). It is **not** the sample re-run with the clamp lifted: that would project the heavy columns (on the order of a GiB per heavy column even for one row), and with no `from` it would have no lower bound. |
| `enrichment_behind` (row-grain, partial bound, non-empty sample) | the same probe |
| `enrichment_behind` (aggregate, Class-1) | the bound's version compared with the maximum projected sample version (D3), through `compareCursors`. Both are epoch-millis of the input's own cursor column (`FOREIGN_AXIS` writers are excluded from `bound()`). Skipped when the bound is held. The message gives the gap. |
| `group_cut_by_fetch_limit` | see below |
| `output_without_description`, `unconstrained_response` | as today (`NO_DESCRIPTION_NOTE`, `schema == null`), mapped to codes, with one `output_without_description` per output instead of one listing all |
| `values_truncated` | the value capper (D9) |

`enrichment_behind` will be present on most previews of a live pipeline over a lagging enrichment,
because the sample is unwindowed and ignores even the safety lag. It is `info`, and its message gives
the gap so an author can tell a few minutes of cadence from a stalled enrichment. A threshold was
considered and rejected: no number fits every enrichment's cadence, and the gap is the fact.

**Group cap.** The runner's cap on one group is
`cap = min(enrichment.group-fetch.default-rows, member_select.limit ?: ∞)`, as `resolveLimit` takes it
(`GroupFetchService.java:745-756`). The validator admits `member_select.limit` up to `max-rows` (2000),
above `default-rows` (200), so a limit of 500 still delivers 200. The preview gets a preview-only
path on `GroupFetchService` (the runner's `fetch` is untouched):
- **Phase 1** selects members after the `prefer` ordering, with no bound columns, reading up to
  `cap + 1` rows. This bypasses `resolveLimit`, which would refuse `cap + 1` when `cap == max-rows`.
  Today phase 1 projects `response − bound`, which includes the filter columns, so a filter on a body
  column would read that body for `cap + 1` rows. The preview's phase 1 keeps the filter in `WHERE`
  but projects only the member identity and order columns.
- **Phase 2** reads the bound columns for the top 5 members by rank only.
- On the single-phase fallback (no bound columns, or a source with no ordering key), the count is a
  separate key-only read, rather than `cap + 1` heavy rows.
- The cut is reported iff the counted pool exceeds `cap` and `cap` is the configured default (no
  `member_select.limit`, or one above the default). It is never derived from `GroupPage.truncated`,
  which also fires for `prefer_sql` narrowing and byte-cap trims. The message names the cap and gives
  "delivers N of M members; showing 5". The 8 MB `max-bytes` cut cannot be predicted from five members
  and is not reported.
- Group members keep the assembly's ascending total order (`TOTAL_ORDER`, `:687-689`), not newest
  first. The group key, version, identity and filter columns the fetch returns are stripped in the
  preview mapping; the key is reported once as `group_key`.

**D9. Values are capped in Java, after the read, at a constant 2048 bytes.**

One `PreviewValueCapper` runs over the mapped rows of every arm. After `StructuredQueryExecutor.normalize`,
`object` and `map` cells are `Map`s and `array` cells are `List`s.
1. Measure each non-null `string`, `object`, `array` and `map` value's UTF-8 size. An object, array or
   map is measured as its compact JSON serialization through the application `JsonMapper`, exactly as
   the response will write it. Keep the column's maximum as `max_bytes`, absent when the column sampled
   no non-null value. `uuid` and `enum` are not measured.
2. Cut every value over 2048 bytes to at most 2045 bytes at a code point boundary (never inside a UTF-8
   sequence or a surrogate pair), and append `…` (3 bytes), so the cell is at most 2048 bytes.
3. Emit one `values_truncated` note per affected column, with the count of capped cells and the cap.

It runs **after** the coercion checker (D5) and the `enrichment_behind` comparison, so every check sees
full values.

Alternatives considered:
- `substring()` in SQL. Rejected: it would change the `statement` an author reads, and it saves nothing
  that matters. A heavy column's cost is fixed per column whatever is selected from it, and five rows of
  tens of KB is well under a megabyte on the wire.
- Dropping heavy columns from the sample. Rejected: whether the input is empty or malformed is the most
  useful thing an `llm` preview shows.
- A configurable cap. Rejected: it would need a configuration row and invite raising it back into the
  problem. It is a constant like `SAMPLE_ROWS`.
- Bytes rather than characters, deliberately: bytes are what the response weighs, and they are a
  closer proxy for tokens than a character count is for non-Latin text.

**D10. The enabled enrich preview keeps the composer's refusal.**

The schema echo goes, but `llmPreview` keeps calling `resolver.composeSchema(...)`, the throwing entry
point (`EnrichPreviewService.java:190-192`). `unconstrained_response` needs its `null` result, and
`output_without_description` needs composition to have run. So the enabled gate is, in fact, the
template check plus transform presence and type plus the composer's refusal, not "template-only". The
spec now says so, rather than this change quietly narrowing it.

## Risks / Trade-offs

- **Capped JSON is no longer JSON.**
  - Risk: a capped `object` or `array` cell is a string of cut JSON, so a client that parses it fails.
  - Mitigation: a client knows exactly which columns were capped from `values_truncated`, and it renders
    those cells as text. Every cell under the cap keeps its JSON type.

- **Breaking contract.**
  - Risk: `adas-mcp`'s `adas_preview_pipeline` and any script that reads `statement`, `enrich`, or
    sends `cursor` will break.
  - Mitigation: there is no UI yet and the runner does not call preview. Update `adas-mcp` in the same
    release, record the break in `docs/release-0.3-upgrade.md`, and refresh the adas-mcp OpenAPI
    snapshot with `--sort-keys`.
- **The window count needs memory the sort did not.** See D4. A very high-cardinality group key is
  refused instead of counted. The budget message names that cause.
- **Stale compiled read for drafts.**
  - Risk: a disabled draft whose stored `output_bindings` are stale previews fine, because the
    in-memory derivation is used, while `GET ?view=compiled` still serves the stale mapping and schema.
  - Mitigation: this window is narrow (the target schema changed after the last declaration write).
    Record it as a follow-up: re-derive on target schema patch.
- **Newest-first cost.**
  - Risk: `ORDER BY version DESC LIMIT n` cannot read in order on inputs sorted by another key, so it
    reads the version column across the source. The aggregate `max()` adds a top-N sort over all
    groups.
  - Mitigation: Class-1 and row-grain already pay a full top-N sort in ascending order today, so the
    direction adds nothing. Heavy-column cost is fixed per column whatever the order.
- **Group auto-pick on a large input.**
  - Risk: the unbounded fallback scans the group, filter and version columns of the whole source.
  - Mitigation: it runs only when the last 24 hours are empty, and runs under the preview budget.
- **Scan-plan extraction touches the runner's scan.**
  - Risk: refactoring `ScanService.run` could change the runner's SQL.
  - Mitigation: a pure extraction, with a test pinning the runner's rendered SQL unchanged.
- **Range rules shared with the write path.**
  - Risk: extracting typed predicates from the three write-path classes touches the row-write path.
  - Mitigation: a pure extraction. The write path's existing tests stay as they are and must pass
    unchanged.

## Migration Plan

This is a single ADAS release with no configuration, migration or ClickHouse change. Ship the matching
`adas-mcp` tool and doc update alongside it. Record the break in the 0.3 upgrade guide. Preview stores
nothing, so there is no state to undo.

## Open Questions

None blocking. Two were settled at the defaults:
- `enrichment_behind` is not raised on the `group` arm. The group choice is clamped like the runner's
  marking scan, and the member fetch is unclamped like the runner's fetch, so a chosen group's members
  read exactly what the runner's would.
- The enrich sample stays at five rows (five members on `group`). Body columns are heavy, and the value
  cap (D9) bounds the payload, not the read.
