package com.epam.aidial.analytics.service.table;

import java.util.List;

/**
 * The catalog's inbound questions of the pipeline registry: which pipelines read a column of this
 * enrichment, and which read this particular column of it?
 *
 * <p>Both are <b>declaration-parsing</b> questions, and that is why they need an interface at all rather
 * than a repository lookup. They ask which declarations name a column of an enrichment <em>through the
 * grain join</em>, which is a property of the declaration's field references rather than of the table it
 * reads — the reading pipeline's own {@code input_table} is a different table entirely. The plainer
 * question, "which pipelines declare this table as their read source", is answered directly by
 * {@code PipelineRepository#findByInputTable} in {@link TableCatalogService#delete}, and needs no
 * inversion.
 *
 * <p>An interface rather than a direct call for the reason given on {@link PipelineGenerationBump}: it
 * inverts a dependency that would otherwise make the bean graph circular. The database's foreign key on
 * {@code pipeline.input_table} is the backstop for a race that slips past the pre-check — deliberately
 * RESTRICT rather than cascade, so a table drop can never silently take governed pipelines with it.
 */
public interface PipelineInputDependents {

    /**
     * The names of the pipelines that <b>reference a column of</b> {@code enrichment} through their read
     * source's grain join, empty when none.
     *
     * <p>Not covered by the catalog's own enrichment check: that check refuses dropping a <em>source</em>
     * that has enrichments, while this one refuses dropping an <em>enrichment</em> that pipelines read.
     * Without it, dropping an enrichment would leave every pipeline naming one of its columns failing on
     * its next run, instead of failing at the moment the dependency was broken.
     */
    List<String> pipelinesReadingEnrichment(String enrichment);

    /**
     * The names of the pipelines that reference {@code column} of {@code enrichment} by its qualified flat
     * name, empty when none. Asked before a schema patch drops or renames that column.
     */
    List<String> pipelinesReadingEnrichmentColumn(String enrichment, String column);

    /**
     * The names of the pipelines that reference {@code column} of the <b>source</b> {@code source} by its
     * bare name, empty when none. Asked before a schema patch drops or renames that column, and before a
     * rename takes that name over.
     *
     * <p>The source counterpart of {@link #pipelinesReadingEnrichmentColumn}, and needed for a reason the
     * enrichment one is not: a source column has no qualified spelling, so a declaration reads it by the
     * same bare name any other column could be renamed into. That makes a rename on a source a way to
     * change <em>which physical column</em> a stored declaration reads, without touching the declaration.
     *
     * @param source the table the declaration reads — its {@code input_table}, or its target
     *               enrichment's {@code source_table} when it declares none
     */
    List<String> pipelinesReadingSourceColumn(String source, String column);

    /**
     * The <b>targets</b> of the pipelines that reference a column of {@code enrichment} through the grain
     * join, empty when none — the tables derived from it by reading it, as opposed to the tables derived
     * from it by parentage.
     *
     * <p>{@link #pipelinesReadingEnrichment} answers the same question one step earlier, in pipeline names,
     * which is what a delete guard wants. The laundering invariant wants the tables those pipelines write,
     * because that is the side of the pair whose grants it constrains.
     */
    List<String> targetsReadingEnrichment(String enrichment);

    /**
     * The enrichments the pipeline writing {@code target} reads through its read source's grain join,
     * empty when none — the inverse of {@link #targetsReadingEnrichment}.
     *
     * <p>The laundering invariant needs both directions and cannot get either from a repository lookup: an
     * enrichment column is reached by its qualified flat name in a declaration's field references, so the
     * reading pipeline's own {@code input_table} is a different table entirely, and the edge exists only
     * in the parsed declaration.
     */
    List<String> enrichmentsReadByPipelineTargeting(String target);

    /**
     * The names of the pipelines that <b>write</b> the column physically named {@code column} of
     * {@code enrichment} — i.e. whose derived output mapping binds it — empty when none.
     *
     * <p>The mirror image of {@link #pipelinesReadingEnrichmentColumn}, and asked at the same moment, for a
     * reason that is about ordering rather than symmetry. An enrich pipeline's output mapping is derived by
     * matching its transform's output <em>names</em> to its target's columns, so dropping or renaming a
     * bound column leaves that mapping unresolvable — and {@link PipelineGenerationBump#onTargetColumnsChanged}
     * refuses the catalog write when it is. But that refusal fires inside the catalog transaction, which
     * runs <b>after</b> the ClickHouse {@code ALTER} has already been applied: rolling the catalog back
     * there would leave the two disagreeing about which columns exist, permanently and with no operation
     * that repairs it. Asking here, before any DDL is rendered, is what keeps the refusal cheap and the two
     * stores in step; the later check stays as the backstop it should always have been.
     */
    List<String> pipelinesWritingEnrichmentColumn(String enrichment, String column);
}
