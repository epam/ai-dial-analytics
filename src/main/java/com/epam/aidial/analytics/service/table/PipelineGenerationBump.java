package com.epam.aidial.analytics.service.table;

/**
 * The catalog's outbound notifications to the pipeline registry: facts a stored declaration derives from,
 * which the catalog can change under it.
 *
 * <p>There are two, and they share one mechanism. A source's {@code version_column} now names a different
 * column; and an enrichment that is a pipeline's <b>target</b> has gained, lost or renamed a column.
 *
 * <p>It exists because that name is on the pipeline wire. An ENRICH pipeline carries its source's version
 * column ({@code GET /v1/pipelines/{name}} and the sync feed), a scan page projects that column under its
 * own name, and a runner detects declaration changes by {@code generation} — which otherwise moves only
 * when a pipeline itself is mutated. A rename on the table API would therefore change the key a page
 * carries with no signal to the runners reading it, so the rename bumps the generation of every ENRICH
 * pipeline reading that source.
 *
 * <p>An interface rather than a direct call because it inverts a dependency that would otherwise close a
 * loop: {@code service.pipeline} and {@code service.enrichment} both depend on {@code service.table} for
 * the catalog they validate against, and a direct call back would make the bean graph circular as well as
 * the package graph. (The older reason given here — that {@code service.table} never depends on the
 * registry at all — is no longer true: {@link TableCatalogService} reads {@code PipelineRepository}
 * directly for the delete guard. The inversion is kept for the bean cycle, which is the part that still
 * bites.)
 *
 * <p>The bump must share {@link CatalogSchemaWriter#applyPatch}'s transaction — a repoint committed without
 * its bump, or a bump without its repoint, is exactly the silent staleness this closes — so the
 * implementation joins the caller's transaction rather than opening its own.
 *
 * <p>Only the version column notifies. The identity column is neither published on the pipeline wire nor
 * projected under its own name, so a runner holds nothing about it that could go stale.
 */
public interface PipelineGenerationBump {

    /**
     * Bumps {@code generation} (and advances {@code updated_at}) on every ENRICH pipeline that reads
     * {@code source} — one that declares it as its read source, or one that derives it through an
     * enrichment of it. A pipeline whose target hangs off {@code source} but which declares another read
     * source is not affected: it never sees this column. Called only when a repoint actually happened.
     *
     * <p><b>Scoped to ENRICH deliberately.</b> An aggregate pipeline reading the same source publishes
     * nothing about its version column and has no out-of-process consumer diffing its {@code generation},
     * so bumping it would move its {@code updated_at} — and therefore its position in every listing — for
     * a signal nothing reads.
     */
    void onVersionColumnRepointed(String source);

    /**
     * Bumps {@code generation} and advances {@code updated_at} on the pipeline whose <b>target</b> is
     * {@code enrichment}, and re-derives its stored output mapping — called when a column is added to,
     * dropped from, or renamed on that enrichment.
     *
     * <p>It exists because the target's column list became a live input to two things a consumer cannot
     * re-derive for itself: the composed response schema (an output's type, enum domain and enum order all
     * come from its column) and the derived output mapping. Before this change, adding a column to an
     * enrichment target required a pipeline patch to declare its {@code output_binding}, and that patch
     * carried the bump along with it. With the mapping derived there is no patch — so without this
     * notification the runner would keep the old compiled form indefinitely and simply never write the new
     * column, silently and with nothing to notice it. {@code generation} is the only change-detection token
     * on a feed with no {@code ETag}, and {@code updated_at} must move with it because the feed selects on
     * {@code updated_at}.
     *
     * <p><b>Scoped to a pipeline whose transform is {@code llm}</b>, and the scoping is not an optimization.
     * On a Class-1 pipeline the bump is unnecessary — ADAS re-resolves the bindings and re-analyzes every
     * expression on <em>each</em> materialize call, deliberately not trusting the registration gate, so a
     * new target column is picked up on the next tick with no signal at all — and it is expensive, because
     * any generation change nulls that pipeline's {@code materialized_through_version},
     * {@code materialized_through_identity} and {@code drained_at}, and a rollup reading an enrichment whose
     * pipeline has no usable position is held back <em>entirely</em> — zero rows per tick — until the next
     * materialize re-records one. Nothing is re-scanned or re-derived: the materialize cursor is
     * caller-held, in the runner, and no bump moves it. But without the scoping, adding one column would
     * cost every dependent rollup a clamp blackout it did not cost before, which inverts the point of
     * removing the binding patch.
     *
     * <p>A <b>metadata-only</b> change notifies nothing: under composition an output's prose comes from the
     * transform rather than the column, and a column's type and {@code enum_values} are immutable already.
     */
    void onTargetColumnsChanged(String enrichment);
}
