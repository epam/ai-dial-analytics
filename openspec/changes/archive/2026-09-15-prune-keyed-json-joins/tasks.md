## 1. The object-path usage seam

- [x] 1.1 Add a value type carrying, per exposed column name, either a set of literal JSON paths or
      `WHOLE_COLUMN` — built once per statement from the analyzed expressions (filter plus outputs, or the
      group declaration's bound columns) rather than re-derived per join.
- [x] 1.2 Expose from `ExprTranslator` the rule that decides which of the two a reference yields, reusing
      `pathKey`'s literal-only, backslash-rejecting validation rather than duplicating it: a literal path
      for the four path-addressable extractors over an `object` field; `WHOLE_COLUMN` for
      `json_extract_string_ci`, `json_extract_array`, and any use of the column as a value in its own right
      (bare projection, comparison, argument to a whole-document function).
- [x] 1.3 Add the shared helper that builds the derived-table alias from (column, path) as a single quoted
      identifier spelling the dotted path (`request_tags.baggage`), and use it from both sides so the two
      cannot disagree.

## 2. Pruning the keyed join's projection

- [x] 2.1 Thread the usage map through `EnrichmentJoins.addReferenced` into `keyed()`; project the aliased
      subcolumns for a path set and `binding.field()` for `WHOLE_COLUMN`, deciding per column so one
      unprunable reference does not cost another column its pruning.
- [x] 2.2 Make `ExprTranslator.subcolumnExtract` address the alias when its column was pruned, and the
      column itself when it was not, so the pruned and unpruned renderings return the same value for the
      same row — including for a path the document does not carry.
- [x] 2.3 Pass the usage map from every caller that reaches `keyed()`. Wired: `MaterializeService.probeWindow`
      and its write, and `ScanService`. **Not wired, with reasons rather than omissions:**
      `GroupFetchService.projectMembers` binds whole columns by construction — a `vars` entry binds a
      column, never a path — so its usage is empty and pruning it would render exactly what it renders
      today. The aggregate refresh probe reaches `keyed()` only with an already-compiled
      `AggregateCompiler.CompiledFilter`, which carries a jOOQ `Condition` and its column names but not the
      parse tree the collector needs; pruning it means threading the `FilterNode` through `CompiledFilter`,
      which no current declaration would benefit from. Both keep today's unpruned behaviour, which is safe.
      The query surface likewise stays unpruned for now: it renders expressions through call sites that do
      not yet carry the effective model back from `addReferenced`, and an unkeyed join — its common
      shape — is never pruned anyway.
- [x] 2.4 Render tests, plus the cases two adversarial reviews found missing — a multi-segment path, a
      dotted key falling back to the whole column, and the alias-collision pair that motivated it: a keyed single-path read projects the aliased subcolumn and not the whole column;
      a `json_extract_string_ci` read still projects the whole column; a statement mixing both prunes only
      the prunable column.

## 3. Closing the budget asymmetry

- [x] 3.1 Put the Class-1 materialize `INSERT … SELECT` under `pipeline.budget.*` alongside the six reads,
      through the same `PipelineReadBudget` pairing so the refusal translation cannot be attached without
      the ceiling.
- [x] 3.2 On a budget refusal, halve the write's window and retry down to a floor of one source row;
      report the narrowed window through the returned cursor and force `hasMore` true whenever the write
      narrowed, whatever the probe reported. Surface `422 pipeline_read_budget_exceeded` only at the floor.
- [x] 3.4 **Added during implementation.** Bound the narrowing *sequence* by the budget's wall-clock
      ceiling, not each attempt: every retry runs on `ClickHouseBudget.remaining(budget, elapsed)`, and a
      spent budget surfaces the refusal instead of starting an attempt it cannot finish. Without this the
      loop can occupy a multiple of the configured timeout, outlast the runner's own `ADAS_READ_TIMEOUT_MS`,
      and finish into a closed socket — which the runner reports as a transport failure and retries. That is
      the same livelock already seen on the group-fetch path (`HttpMessageNotWritable:
      Broken pipe` on ADAS, `TransientChunkException` on the runner, ~0 progress), so shipping a retry loop
      without a shared deadline would have reproduced it on the materialize path.
- [x] 3.3 Tests: a refused write narrows and reports `hasMore: true` with a cursor at the narrowed end; a
      refusal at one row surfaces 422 with the cursor unmoved; two pipelines reading the same enrichment
      over the same window, one with a filter naming an enrichment column and one without, are both
      bounded.

## 4. Reporting the expensive JSON form

- [x] 4.1 At pipeline registration and patch, report — never refuse — a `filter` or output expression
      reading an `object` column through `json_extract_string_ci` or `json_extract_array`, naming the
      single-path alternative where the function has one.
- [x] 4.2 Tests: the `_ci` declaration is accepted and reported; the single-path declaration is accepted
      and not reported; no declaration is refused on this ground.

## 5. Specs and docs

- [x] 5.1 Confirm the three delta specs still match what shipped —
      `structured-query` (keyed-join projection, plus the added paths × parts cost requirement),
      `enrichment-runtime` (the budget boundary and the narrowing write), `class1-enrichment` (what a
      joined probe projects, and the narrowed window as a prefix) — and correct them if implementation
      moved.
- [x] 5.2 No `docs/configuration.md` change: this change adds and renames no configuration property. Record
      that explicitly rather than leaving it to inference.
- [x] 5.3 Correct the `json_extract_string_ci` cost wording where it survives as "reads the whole document
      per row" without the part-count factor. **Done** in the two places an author actually reads it:
      `QueryFunctionCatalog` (the text `/v1/queries/functions` serves, which is where the function is
      chosen) and `ExprTranslator`'s rendering javadoc. **Deliberately not done** in
      `openspec/specs/structured-query/spec.md`'s shipped sub-grammar requirement: restating it there means
      carrying that whole requirement into this delta as MODIFIED for one clause, and the ADDED
      paths x parts requirement in this same delta already states the rule authoritatively. The old
      sentence understates the cost but is not false. Flagged rather than silently skipped.

## 6. Verification

- [x] 6.1 `openspec validate prune-keyed-json-joins` passes.
- [ ] 6.2 `./gradlew clean build` passes (Docker required for the integration tests).

## 7. Acceptance against a running deployment

- [ ] 7.1 Deploy. `usage-request-baggage-live` is held at `enabled: false` with its filter already patched
      to the case-sensitive form and evaluator `usage-request-baggage` pinned to nothing, so it floats to
      the latest version — the case-sensitive expression. Leave the earlier versions in place; evaluator
      versions are immutable.
- [ ] 7.2 Before re-enabling, confirm through `POST /v1/pipelines/usage-request-baggage-live/preview` with a
      narrow `from` that the probe path no longer exceeds the ceiling — the pre-change symptom was a 422 at
      every window width including a four-minute one.
- [ ] 7.3 Re-enable the pipeline and watch it drain. Its `materialized_through_version` is discarded on the
      first materialize after the generation bump, so it re-derives from the start of history; the
      unbounded read through the cheap path returns tens of thousands of rows instantly, and the
      write is idempotent by grain.
- [ ] 7.4 Confirm `usage-client-identity-live`'s materialize write now runs under the ceiling, and check
      whether it narrows — while unbounded it can be the node's largest consumer.
- [ ] 7.5 Archive the change once the drain is green.
