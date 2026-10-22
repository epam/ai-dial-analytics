package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.service.enrichment.model.InputBinding;
import com.epam.aidial.analytics.service.enrichment.model.MemberSelect;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.enrichment.model.ReadyWhen;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;

import java.util.List;

/**
 * The parts of a stored pipeline that name columns, read as a <b>detached projection</b> rather than as a
 * managed entity.
 *
 * <p>That distinction is the whole reason this exists. The catalog asks "which pipelines read this
 * enrichment?" from inside a table <em>delete</em> transaction, and loading {@link PipelineEntity} rows
 * there puts them in the persistence context — where their JSON-converted columns ({@code ready_when},
 * {@code member_select}) compare unequal on every dirty check, so Hibernate flushes a full-row
 * {@code UPDATE} for each one at commit. Against a row the delete has since cascaded away, that update
 * matches zero rows and the whole delete fails with a stale-state error. A constructor projection is never
 * managed, so it cannot be flushed.
 *
 * <p>Carries only what a column reference can appear in — the same fragments the governance gates resolve.
 * {@link #spec} is one of them rather than an afterthought: a {@code sql} transform's column references
 * live entirely in its output expressions, so without the spec the enrichments a Class-1 pipeline reads
 * would be invisible to the dependency graph and to the catalog's drop/rename checks. That used to need a
 * pointer to a second object; the transform is on the pipeline now, so the spec is the whole of it.
 *
 * <p>{@link #spec} is carried as the <b>raw stored JSON</b> rather than as a typed declaration, for two
 * reasons. It keeps the projection a constructor projection over columns, which is what makes it detached;
 * and the column holds a different type per kind, so a typed member would have to be one kind's and would
 * make the other's declaration unreadable through the same query. Resolve it with
 * {@link PipelineSpecCodec}, which dispatches on {@link #kind}.
 */
public record PipelineDeclaration(
        String name,
        PipelineKind kind,
        String inputTable,
        String target,
        String triggerGroupBy,
        String filterSql,
        ReadyWhen readyWhen,
        MemberSelect memberSelect,
        String spec) {

    /**
     * The projection of a pipeline row a caller <b>already holds</b>. The detachment this class exists for
     * is about not <em>loading</em> entities inside a delete transaction (see the class javadoc); a caller
     * that legitimately has one in hand — a state read resolving what its declaration references — still
     * wants the same shape the governance gates resolve, and gets it without repeating the field list.
     */
    public static PipelineDeclaration of(PipelineEntity pipeline) {
        return new PipelineDeclaration(pipeline.getName(), pipeline.getKind(), pipeline.getInputTable(),
                pipeline.getTarget(), pipeline.getTriggerGroupBy(), pipeline.getFilterSql(),
                pipeline.getReadyWhen(), pipeline.getMemberSelect(), pipeline.getSpec());
    }

    /**
     * The transform input bindings this declaration names, or an empty list for a kind that has none.
     * Reads {@link #spec} through the codec rather than storing a typed member; see the class javadoc.
     */
    public List<InputBinding> inputBindings(PipelineSpecCodec codec) {
        if (kind != PipelineKind.ENRICH) {
            return List.of();
        }
        return ((EnrichSpec) codec.read(kind, spec)).getInputBindings();
    }

    /**
     * The target columns this declaration <b>writes</b> — the derived output mapping, by physical
     * {@code source_name} — or an empty list for a kind that has none.
     *
     * <p>The mirror image of {@link #inputBindings}, and needed for the mirror-image guard: the catalog
     * refuses dropping or renaming a column an enrichment's own pipeline writes, because the mapping is
     * derived by matching the transform's output names to this table's columns and a column that stops
     * existing under that name leaves the pipeline unable to resolve one.
     */
    public List<OutputBinding> outputBindings(PipelineSpecCodec codec) {
        if (kind != PipelineKind.ENRICH) {
            return List.of();
        }
        return ((EnrichSpec) codec.read(kind, spec)).getOutputBindings();
    }

    /**
     * The transform this declaration runs, or null for a kind that has none — the shape a {@code sql}
     * declaration's output expressions live in, which is what the catalog's drop/rename guard walks.
     */
    public EnrichSpec.TransformSpec transform(PipelineSpecCodec codec) {
        if (kind != PipelineKind.ENRICH) {
            return null;
        }
        return ((EnrichSpec) codec.read(kind, spec)).getTransform();
    }
}
