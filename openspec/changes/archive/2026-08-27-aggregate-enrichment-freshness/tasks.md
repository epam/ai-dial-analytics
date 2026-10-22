## 1. Take the clamp requirement over from `aggregate-reads-entity`

Ordered first because leaving it undone puts an unbuilt requirement into the shipped spec. Both items are
edits to the *other* change's artifacts, not to code.

Verified before removing that `aggregate-reads-entity`'s only edits to *Pipeline runtime
state is readable* were the clamp paragraph and its scenario, so deleting the whole `MODIFIED` block loses
nothing; and the two places its other requirements referred to the clamp in passing were rephrased to name
this change rather than dangle.

- [x] 1.1 Remove the `A refresh window is clamped to its referenced enrichments' cursors` requirement from
      `openspec/changes/aggregate-reads-entity/specs/aggregate-pipeline/spec.md`; it is superseded by this
      change's corrected version, which clamps to a materialized position rather than a writer cursor.
- [x] 1.2 Remove that change's `MODIFIED` block for `Pipeline runtime state is readable` entirely — its
      only edit to the shipped requirement was the clamp clause and scenario, both of which move here — and
      record in its `design.md` D-6 and `tasks.md` group 5 that the work moved rather than being dropped.

## 2. Record the materialized position

- [x] 2.1 PostgreSQL migration adding four nullable columns to `rule`: the materialized position's version
      and identity halves, the drained-at instant, the history-re-derived-at instant, and the `generation`
      the record was written under. No ClickHouse migration.
- [x] 2.2 Add the fields to `RuleEntity` and a repository method resolving them for a set of enrichment
      names in one query, so the clamp costs one lookup per refresh rather than one per enrichment.
- [x] 2.3 In `MaterializeService.materialize`, record the probed window's end tuple on the
      **probed-window branch only**, monotonically (design D-1, D-2). A call that lowers the stored
      position leaves it unchanged.
- [x] 2.4 On the `window == null` branch record the drained instant instead, and explicitly **not** a
      position — that branch echoes the caller's cursor, and recording it would persist the caller's claim
      rather than ADAS's observation (design D-2, Risks).
- [x] 2.4a Stamp that drained instant **before** the probe runs, not at record time. The horizon claims
      "nothing matching below this instant minus the safety lag", and the instant it is really claiming
      about is the one the probe evaluated `now64(3)` at; reading the clock afterwards moves the horizon
      forward by the probe's duration and releases a sliver of rows the probe never looked inside. Found in
      review; on a slow probe over a large source that sliver is seconds wide.
- [x] 2.5 Where the call carries a `from`/`to` bound, record neither: record the history-re-derived instant
      instead (design D-6). This is the backfill discriminator **for a windowed backfill only** — see 2.5a.
- [x] 2.5a Also record the history-re-derived instant where a written window ends **at or below** the
      position the rule was already complete through, whether or not the call carried a bound (design D-6).
      This is what catches the *unwindowed* backfill, which carries a cursor and no window and so is
      byte-identical to a live tick on the wire. Decided in `MaterializeService` from the rule as read at
      the start of the call and passed into the writer — **not** looked up inside the writer's transaction,
      because `RuleEntity` carries `@JdbcTypeCode(SqlTypes.JSON)` columns and no `@DynamicUpdate`, so
      loading one there makes the commit flush a full-row `UPDATE` that silently overwrites the very
      columns the bulk statements just set.
- [x] 2.6 Stamp every record with the rule's current `generation`, and treat a record whose generation has
      moved as absent (design D-3).

## 3. Clamp the refresh window

- [x] 3.1 Resolve the referenced enrichments of a refresh — the set the compiler already derives to decide
      which joins to emit — to their owning rules, via `findByTargetEnrichment` (one rule per enrichment is
      enforced).
- [x] 3.2 Compute each enrichment's effective position as
      `max(materialized_through, drained_at − enrichment.scan.safety-lag)` (design D-2), and the refresh's
      bound as the earliest across the set.
- [x] 3.3 Clamp the probed window's end to that bound **only when the probed window would end past it**, so
      a refresh already behind its enrichments is untouched (design Q-2). Let the refresh proceed over the
      clamped window rather than skipping it.
- [x] 3.4 Treat an enrichment whose owning rule no longer exists as imposing no clamp, distinctly from a
      rule that exists but has recorded nothing, which clamps (design D-4).
- [x] 3.5 Leave a refresh that references no enrichment entirely unaffected — same window, same predicates,
      same rendered SQL.

## 4. Refuse what cannot be clamped

- [x] 4.1 In `AggregateSpecValidator`, refuse with 422 a field reference to a column of an enrichment whose
      rule uses the **group** trigger, naming the enrichment and stating that a group-derived value has no
      settled position (design D-5).
- [x] 4.2 Refuse on the same rule an enrichment whose rule ADAS does not execute itself — anything that is
      not Class-1 (`evaluator.type = sql`) — since no materialized position is recorded for it (design D-7).
- [x] 4.3 Confirm both refusals are re-applied at execution, not only at registration, so a rule changed to
      the group trigger after a pipeline was registered fails loudly rather than writing provisional values.
- [x] 4.4 **Q-3 resolved the other way — see design D-8.** An enrichment whose owning rule declares a read
      source other than the pipeline's input is **admitted, imposes no clamp, and is reported** in pipeline
      state as `unclamped_reads[].reason = foreign_axis`, rather than refused. The comparison is not left
      unguarded, which was the requirement: the position is never compared across axes at all, because such
      an enrichment contributes no bound. Refusing was rejected because it removes the only means ADAS has
      of relating two tables (a rollup at a shared grain plus a pass-through rule — `turn_feedback` on
      `turns`, derived from `response_ratings`, is exactly that shape), to guard a case where the
      clamp would be near-inert anyway: such an enrichment is typically sparse, so its rule drains on nearly
      every tick and the drained horizon releases the clamp regardless, and its absences are permanent
      rather than late. Reporting it is what keeps this from being the silent gap the change exists to close.

## 5. Surface it in state

- [x] 5.1 Extend the pipeline state DTO and `PipelineStateWriter` with the clamp: that the window was
      clamped, which enrichment bounded it, and the position it was bounded to.
- [x] 5.2 Report the rebuild-required flag for a pipeline whose enrichment's history was re-derived since
      the pipeline last read it, naming the enrichment. It holds no window back.
- [x] 5.3 Report no clamp when none applied, so the indicator carries information when present.

## 6. Documentation

- [x] 6.1 Document in `docs/configuration.md`, in the aggregate section, that a rollup is only as fresh as
      the slowest enrichment it reads, what the clamp does, and how the two state indicators differ — one
      is progress, one is an instruction. No config property is added by this change, so the section gains
      guidance and no property row.
- [x] 6.2 Replace the rebuild guidance added by `aggregate-reads-entity` where it says the mutation case is
      undetectable: for a Class-1 enrichment it is now detected and reported. Keep the operator rebuild
      shapes unchanged — they are still the remedy.
- [x] 6.2a **Replace `aggregate-reads-entity`'s freshness-gap paragraph, which this change falsifies.** In
      `docs/configuration.md` it is headed *"A rollup does not wait for its enrichments, and rows the
      enrichment has not reached are lost"* and ends *"Until a freshness clamp exists there is no setting
      that fixes this"* — actively wrong once the clamp ships. Replace it with what the clamp guarantees,
      and keep the one case it does **not** close: rows a rule permanently excludes are never enriched, pass
      the clamp via the drained horizon, and are excluded from the rollup by the pipeline's own filter. That
      is correct rather than lossy, and saying so is what stops the next reader re-opening this.
- [x] 6.3 In `README.md` prose, **replace** the sentence `aggregate-reads-entity` added — *"A refresh does
      not wait for its enrichments: rows an enrichment has not reached yet are aggregated without it…"* —
      with the clamped behaviour, linking to the guidance. It is a replacement, not an addition: leaving both
      makes the README contradict itself. No env-var table.
- [x] 6.4 Correct the disabled-rule guidance, which understated the consequence. A disabled Class-1 rule
      does not hold a dependent rollup *at its current position*: disabling is a mutation, so it bumps the
      rule's `generation` and discards the recorded mark, and only a materialize re-establishes one — which
      a disabled rule never gets. The rollup is held at **nothing**, writing zero rows every tick with
      `state.clamp` naming the enrichment and carrying no `cursor_version`. Fixed in `docs/configuration.md`
      and in this change's proposal.

## 7. Tests

- [x] 7.1 `MaterializeService`: a written window records its end position; a second window advances it; a
      replay at an earlier cursor does not lower it.
- [x] 7.2 `MaterializeService`: an empty window records the drained instant and **not** a position — the
      assertion that the echoed caller cursor was not persisted is the point of the test.
- [x] 7.3 `MaterializeService`: a `from`/`to` call records the re-derivation and neither position.
- [x] 7.3a `MaterializeService`: an **unwindowed replay** — a cursor and no `from`/`to`, the default backfill
      shape — records the re-derivation too, recognised by writing a window that ends at or below what the
      rule was already complete through; a forward live tick does not, and a rule with no usable mark does
      not. Added after live verification found the wire discriminator alone missed the full-replay case.
- [x] 7.4 A rule mutation, and separately a `version_column` repoint on the read source, both make the
      record read as absent.
- [x] 7.5 `AggregateRefreshService`: a refresh whose probed window ends past the bound is clamped to it and
      still writes; one that ends before it is untouched.
- [x] 7.6 Regression: a refresh referencing no enrichment produces byte-identical SQL and an unclamped
      window.
- [x] 7.7 The drained horizon releases the clamp for a filtered rule whose last matching row is old — the
      case a materialized position alone would stall forever.
- [x] 7.8 An enrichment whose rule has been deleted imposes no clamp; one whose rule exists but has
      recorded nothing does.
- [x] 7.9 Negative: a group-grain enrichment column → 422 naming the enrichment; a non-Class-1 enrichment
      column → 422; both re-applied at execution on a stored pipeline.
- [x] 7.10 Integration: source rows ingested ahead of their enrichment produce **no** wrong row — the window
      clamps, and the group is written correctly on a later tick once the enrichment catches up.
- [x] 7.11 Integration: the clamp and the rebuild flag are both reported by `GET /v1/pipelines/{name}`,
      naming the enrichment.

## 8. Verification

- [x] 8.1 Confirm the delta specs in `openspec/changes/aggregate-enrichment-freshness/specs/` match the
      shipped behaviour, and that no requirement was implemented differently from how it is written.
      Re-confirmed after the 2.4a / 2.5a corrections; `openspec validate` passes for both
      changes.
- [x] 8.2 Confirm `aggregate-reads-entity` no longer carries an unimplemented requirement, so it can be
      archived. Checked by grep: no `SHALL` in its delta mentions the clamp, and the two passing references
      it retains name this change as the owner.
- [x] 8.3 `./gradlew clean build` passes — compile, checkstyle and the full test suite.

## 9. Live verification

Run against a local ADAS (`:5000`) and enrichment runner (`:5100`) on real ClickHouse and
Postgres, with a local `dial_usage_log`, the Class-1 rule `request-shape-live` →
`usage_request_shape` (filtered `deployment = 'e2e-class1'`, 8 rows), and a purpose-built rollup. Two
defects came out of it — 2.4a and 2.5a — and both were re-verified live afterwards. Environment restored to
its prior state on completion.

- [x] 9.1 **Happy path.** A rollup grouped by `usage_request_shape.model_name` measuring
      `avg(usage_request_shape.body_chars)` compiles with the grain join emitted, previews, refreshes, and
      writes 5 groups whose values match the same aggregation read through `POST /v1/queries/execute`.
- [x] 9.2 **The clamp trims.** With the rule's mark set mid-history, a refresh wrote exactly the 3 groups
      below the bound, advanced its cursor to the bound tuple rather than to the probed window end, and
      reported `state.clamp` naming the enrichment and the position.
- [x] 9.3 **The clamp withholds everything.** With the mark reading as absent, the refresh wrote nothing,
      left its cursor alone, and reported `state.clamp` with the enrichment and no `cursor_version`.
- [x] 9.4 **Every probed row above the bound.** A second refresh at the clamped cursor wrote nothing,
      executed no `INSERT`, left the cursor alone, and still reported the clamp.
- [x] 9.5 **Regression.** A rollup referencing no enrichment renders with no join and no clamp predicate,
      and reports no clamp — confirming the pre-existing path is untouched in the live renderer, not only
      in the unit test.
- [x] 9.6 **Refusals, at the HTTP surface.** A non-Class-1 enrichment column → 422 naming the evaluator
      type; an unaliased enrichment group key → 422; an unknown column of a known enrichment, and an
      unknown enrichment owner → 422 with the two distinct messages; a non-nullable enrichment column in a
      group key and in a measure → 422; **and the same column in a `filter` accepted**, which is the
      asymmetry the rule rests on.
- [x] 9.7 **`unclamped_reads`.** A rollup reading an enrichment with no owning rule is admitted and reports
      `reason = no_rule`.
- [x] 9.8 **Windowed backfill → rebuild.** A `from`/`to` backfill driven through the runner recorded
      `history_rederived_at`, and `GET /v1/pipelines/{name}` reported `rebuild_required` naming the
      enrichment. Re-declaring the pipeline cleared it, confirming `rebuilt_at` is the anchor.
- [x] 9.9 **Unwindowed backfill → rebuild.** The runner's *default* backfill (`POST /v1/rules/{id}/backfill`
      with no body, `BackfillWindow.UNBOUNDED`) records the re-derivation and leaves the position unchanged.
      This is the defect 2.5a fixes: before the fix the same call recorded a position and no re-derivation,
      so `rebuild_required` never fired for the shape that re-applies an evaluator-expression bump to
      history.
- [x] 9.10 **Forward progress is not mistaken for one.** A materialize carrying a real cursor that writes
      one new row forward leaves `history_rederived_at` untouched. Two earlier attempts at this check were
      invalid and are recorded because the reasons are instructive: calling materialize with *no* cursor is
      itself a replay, and backdating a row's `_ingested_at` past the safety lag places it beneath an
      already-recorded drained horizon, where flagging it is correct rather than a false positive.
- [x] 9.11 **Not covered live, and covered by test instead.** No group-trigger rule and no cross-table rule
      exist in the local catalog, so the `GROUP_TRIGGER` refusal (4.1) and the `foreign_axis` report (4.4)
      rest on 7.9 and `EnrichmentFreshnessTest`. The catch-up half of 7.10 — the group being written
      correctly on a later tick once the enrichment advances — likewise rests on
      `AggregateEnrichmentIntegrationTest` rather than on a live run.

## 10. Known limitations, accepted

- [x] 10.1 **One false positive on the re-derivation signal.** A live tick whose response is lost re-executes
      an identical window on the next fire (`Class1MaterializeExecutor` advances its cursor only on success),
      which 2.5a reports as a re-derivation even though it rewrites the same values from the same
      expression. Nothing on the wire or in the catalog separates it from a genuine replay of one page. A
      rare spurious rebuild instruction is the safe direction against a systematic miss; stated in the
      `class1-enrichment` delta rather than left for someone to rediscover.
- [x] 10.2 **The V32 comment on `history_rederived_at` still describes `from`/`to` as the whole
      discriminator.** Deliberately not corrected: the migration may already be applied, and
      editing an applied migration's comment changes its Flyway checksum and aborts startup. The delta spec,
      `design.md` D-6 and the code javadoc carry the corrected account. Correcting the comment needs a
      `flyway repair`, which a comment does not justify.
- [x] 10.3 **Preview shows an unclamped statement when the bound withholds everything.** `preview` filters
      out the withholding bound rather than rendering an unsatisfiable predicate, so for a rollup held back
      entirely it shows a statement the refresh would not run. `state.clamp` already reports that condition,
      and the alternative renders a bound that reads like a bug when copied out of the response.
