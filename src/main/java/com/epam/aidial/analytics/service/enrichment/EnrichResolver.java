package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.support.ResponseSchemaComposer;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves an ENRICH pipeline into everything a caller needs to act on it without a second round-trip:
 * its typed declaration, and the read source, grain key and source version column derived from its target
 * enrichment.
 *
 * <p>Separate from {@code PipelineService}, which owns the registry: what is resolved here is
 * enrichment-domain knowledge — which table an enrichment hangs off, what its grain is — and the registry
 * has no business restating it. Every ENRICH consumer goes through this one component so the scan, the
 * group assembly, the materialize and the read surface cannot disagree about which table a pipeline reads.
 *
 * <p>The transform is no longer resolved at all: it is authored on the pipeline and arrives inside the
 * stored {@code spec}, so what used to be a lookup per pipeline (or a batched fetch plus an in-memory
 * version pick for the feed) is a field read. What is left to resolve is catalog knowledge about the
 * target and the source.
 *
 * <p>Each entry point resolves only what its own path reads, because each lookup is a Postgres round trip
 * on a hot path: the scan resolve omits the version column (it reads the source's cursor columns through
 * {@code ScanSourceMetadata}, where a source without them is a 422 rather than an absent field), and the
 * write resolve omits the source and the version column too.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichResolver {

    private final TableDefinitionRepository definitions;
    private final PipelineSpecCodec specCodec;
    private final ColumnMappingRepository columns;
    private final ResponseSchemaComposer schemaComposer;

    /** The read/sync-path resolve: every field populated. */
    @Transactional(readOnly = true)
    public ResolvedEnrichPipeline resolve(PipelineEntity pipeline) {
        final String source = sourceOf(pipeline);
        final EnrichSpec spec = specCodec.readEnrich(pipeline);
        return new ResolvedEnrichPipeline(pipeline, spec, source, grainKeyOf(pipeline),
                versionColumnOf(source), composedSchemaOf(pipeline, spec.getTransform()));
    }

    /**
     * The response schema composed from this pipeline's target and its transform's {@code outputs} — the
     * <b>full</b> resolve only, which is what the registry read and the sync feed use.
     *
     * <p>Composed here rather than in the web mapper because it needs the target's columns, and the
     * resolver is where a per-pipeline catalog lookup already belongs. Skipped entirely for a declaration
     * that stores its own schema: composition is not a faithful reproduction of a stored one, so the
     * stored schema wins while it is present and there is nothing to compute. Also skipped for a
     * {@code sql} transform, which has no model response to constrain.
     *
     * <p>A composition failure is <b>swallowed</b>, deliberately. This runs on a read, and a target whose
     * columns have drifted under a stored declaration must not make {@code GET /v1/pipelines} fail for
     * every other pipeline in the listing — the registration gate and the materialize call are where such a
     * declaration is refused, with a message and a status. Here the schema is simply absent, which the
     * feed's own empty-mapping guard already treats as unservable.
     */
    private Map<String, Object> composedSchemaOf(
            PipelineEntity pipeline, EnrichSpec.TransformSpec transform) {
        return composedSchemaOf(pipeline, transform, null);
    }

    /**
     * As above, over columns already in hand. {@code targetColumns} null means "fetch them" — the
     * single-pipeline path; a list means the batched feed already loaded them for every target at once.
     */
    private Map<String, Object> composedSchemaOf(PipelineEntity pipeline,
            EnrichSpec.TransformSpec transform, List<ColumnMappingEntity> targetColumns) {
        try {
            return composeSchema(pipeline, transform, targetColumns);
        } catch (RuntimeException ex) {
            // WARN for an armed pipeline only. A disabled one is a draft whose outputs need not fit its target
            // yet — that is the expected state while authoring, not drift — and this runs on every GET and
            // listing, so warning for it would log once per draft per read.
            if (pipeline.isEnabled()) {
                log.warn("cannot compose the response schema for pipeline '{}': {}",
                        pipeline.getName(), ex.getMessage());
            } else {
                log.debug("cannot compose the response schema for disabled pipeline '{}': {}",
                        pipeline.getName(), ex.getMessage());
            }
            return null;
        }
    }

    /**
     * The composition itself, <b>letting a failure out</b> — the same computation {@link #composedSchemaOf}
     * performs and swallows, exposed for the dry-run preview.
     *
     * <p>The two error postures are the point of the split rather than an accident of reuse. On a listing
     * read a target whose columns have drifted under a stored declaration must not fail
     * {@code GET /v1/pipelines} for every other pipeline in the response, so the schema is simply absent
     * there. The preview is the opposite case: it is the checkpoint an author has before arming, and the
     * composer's refusal — which names the output, the column and the spelling it expected — is precisely
     * what the author came for. Reporting "no schema" there would hand back the least useful half of what
     * ADAS knows.
     *
     * @return null when there is nothing to compose: no transform, a {@code sql} transform (no model
     *         response to constrain), a declaration that stores its own schema (which wins while present),
     *         or one that declares no {@code outputs}
     */
    public Map<String, Object> composeSchema(
            PipelineEntity pipeline, EnrichSpec.TransformSpec transform) {
        return composeSchema(pipeline, transform, null);
    }

    /**
     * As above, over columns already in hand; null means fetch them for this one target.
     *
     * <p><b>This reads {@code outputs} only, and deliberately not through the output-shape accessor.</b>
     * Every other consumer resolves {@code outputs}-else-{@code output_vars}; this one must not. A folded
     * declaration carrying {@code output_vars} and no stored {@code response_schema} calls the model
     * <em>unconstrained</em> today, and the feed serves it that way on purpose. Composing a schema for it
     * would send a {@code response_format} where none was sent before — a silent change to a live prompt,
     * made by a read path, on a declaration nobody edited.
     */
    private Map<String, Object> composeSchema(PipelineEntity pipeline,
            EnrichSpec.TransformSpec transform, List<ColumnMappingEntity> targetColumns) {
        if (transform == null || transform.getType() != TransformType.LLM
                || transform.getResponseSchema() != null
                || transform.getOutputs() == null || transform.getOutputs().isEmpty()) {
            return null;
        }
        return schemaComposer.compose(transform.getOutputs(),
                targetColumns != null ? targetColumns
                        : columns.findByTableNameOrderByOrdinal(pipeline.getTarget()),
                pipeline.getTarget());
    }

    /**
    /**
     * Scan-path resolve: source + grain. The transform rides along in the declaration, which the scan
     * needs in order to refuse a Class-1 ({@code transform.type: sql}) pipeline — its values are computed
     * in the database via the materialize path and it has no per-row payload to scan, so the two execution
     * paths are not interchangeable. That check used to cost an indexed Postgres lookup per page and now
     * costs nothing.
     */
    @Transactional(readOnly = true)
    public ResolvedEnrichPipeline resolveForScan(PipelineEntity pipeline) {
        return new ResolvedEnrichPipeline(pipeline, specCodec.readEnrich(pipeline),
                sourceOf(pipeline), grainKeyOf(pipeline), null);
    }

    /**
     * Write-path resolve: grain and declaration only, without the source or the version column (the write
     * reads neither).
     */
    @Transactional(readOnly = true)
    public ResolvedEnrichPipeline resolveForWrite(PipelineEntity pipeline) {
        return new ResolvedEnrichPipeline(
                pipeline, specCodec.readEnrich(pipeline), null, grainKeyOf(pipeline), null);
    }

    /**
     * Resolves many pipelines in a constant number of queries — one batched fetch each for target
     * definitions, the sources' version columns and the targets' columns — rather than the per-pipeline
     * lookups {@link #resolve} would issue. The grain rides along on the target definition.
     *
     * <p>This is what keeps the runner sync feed's cost independent of how many pipelines it syncs.
     */
    @Transactional(readOnly = true)
    public List<ResolvedEnrichPipeline> resolveAll(List<PipelineEntity> selected) {
        if (selected.isEmpty()) {
            return List.of();
        }
        final Set<String> targets = selected.stream()
                .map(PipelineEntity::getTarget).collect(Collectors.toSet());
        final Map<String, TableDefinitionEntity> definitionsByName = definitions.findAllById(targets).stream()
                .collect(Collectors.toMap(TableDefinitionEntity::getName, Function.identity()));
        // Batch the version columns over the pipelines' READ sources, not over the targets' source_table:
        // the latter would miss the source a pipeline declares for itself and fetch one it never reads.
        final Map<String, String> versionColumnsBySource = versionColumnsOf(
                selected.stream().map(pipeline -> readSource(pipeline, definitionsByName)).toList());
        // Batched for the same reason as everything above it: this is the runner's sync feed, and the
        // schema has to be composed HERE rather than left to the caller. Omitting it is not a missing
        // convenience — a declaration in the `outputs` shape stores no `response_schema`, so an absent
        // composed one reaches the runner as null, `response_format` is never sent, the model answers
        // free-form and every row lands in the DLQ at `validate`. That is silent: the feed looks healthy,
        // the call is made and charged, and no label is ever written.
        final Map<String, List<ColumnMappingEntity>> columnsByTable =
                columns.findByTableNameInOrderByTableNameAscOrdinalAsc(targets).stream()
                        .collect(Collectors.groupingBy(ColumnMappingEntity::getTableName));

        return selected.stream().map(pipeline -> {
            final TableDefinitionEntity target = definitionsByName.get(pipeline.getTarget());
            final String source = readSource(pipeline, definitionsByName);
            final EnrichSpec spec = specCodec.readEnrich(pipeline);
            return new ResolvedEnrichPipeline(
                    pipeline,
                    spec,
                    source,
                    target == null ? null : target.getGrainKey(),
                    source == null ? null : versionColumnsBySource.get(source),
                    composedSchemaOf(pipeline, spec.getTransform(),
                            columnsByTable.getOrDefault(pipeline.getTarget(), List.of())));
        }).toList();
    }

    /**
     * The pipeline's <b>read source</b>: the single table it scans to derive its values — its declared
     * input when it has one, otherwise its target enrichment's parent. This is the one place the resolution
     * lives; the scan, the group assembly and the materialize path all consume it through
     * {@link ResolvedEnrichPipeline#source()}, and the governance gate validates every SQL fragment against
     * it, so they cannot disagree about which table a pipeline reads.
     */
    @Transactional(readOnly = true)
    public String sourceOf(PipelineEntity pipeline) {
        if (StringUtils.isNotBlank(pipeline.getInputTable())) {
            return pipeline.getInputTable();
        }
        return definitions.findById(pipeline.getTarget())
                .map(TableDefinitionEntity::getSourceTable)
                .orElse(null);
    }

    /**
     * {@link #sourceOf} over an already-batched definition map. Kept beside it so the batched feed and the
     * per-pipeline reads cannot resolve one pipeline to different tables.
     */
    private static String readSource(
            PipelineEntity pipeline, Map<String, TableDefinitionEntity> definitionsByName) {
        if (StringUtils.isNotBlank(pipeline.getInputTable())) {
            return pipeline.getInputTable();
        }
        final TableDefinitionEntity target = definitionsByName.get(pipeline.getTarget());
        return target == null ? null : target.getSourceTable();
    }

    /**
     * The declared version column of every distinct read source among the given ones, in one batched
     * fetch — keeping the feed at a constant number of queries rather than adding a per-pipeline lookup.
     * Sources without scan metadata are simply absent from the map.
     */
    private Map<String, String> versionColumnsOf(Collection<String> readSources) {
        final Set<String> sources = readSources.stream()
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        if (sources.isEmpty()) {
            return Map.of();
        }
        return definitions.findAllById(sources).stream()
                .filter(definition -> StringUtils.isNotBlank(definition.getVersionColumn()))
                .collect(Collectors.toMap(
                        TableDefinitionEntity::getName, definition -> definition.getVersionColumn().trim()));
    }

    private String grainKeyOf(PipelineEntity pipeline) {
        return definitions.findById(pipeline.getTarget())
                .map(TableDefinitionEntity::getGrainKey)
                .orElse(null);
    }

    /**
     * The source's declared version column, or null when the source is unknown or declares no scan
     * metadata. Tolerant by design: a pipeline read must not fail because its source is not scannable —
     * the scan path itself rejects that case with the 422 it deserves.
     */
    private String versionColumnOf(String source) {
        return source == null ? null : definitions.findById(source)
                .map(TableDefinitionEntity::getVersionColumn)
                .map(StringUtils::trimToNull)
                .orElse(null);
    }
}
