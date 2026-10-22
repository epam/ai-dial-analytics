package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.service.enrichment.model.TransformOutput;
import com.epam.aidial.analytics.service.enrichment.model.TransformVar;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single reconciliation of the <b>two stored output shapes</b>: the current {@code outputs} array,
 * keyed by the target column each entry writes, and the legacy {@code output_vars} list a declaration
 * carried before that shape existed. Both coexist in storage indefinitely — no stored row is ever
 * rewritten to migrate it — so every consumer resolves them here rather than reading either raw field.
 *
 * <p>The holder is {@code EnrichSpec.TransformSpec}, the authored transform on a pipeline; the resolution is
 * kept here so it has exactly one implementation. A second implementation of it is the defect this class
 * exists to make impossible.
 *
 * <p><b>Why it must not be skipped at any call site.</b> One consumer is not a validation path:
 * {@code EnrichSpecValidator#outputExpressionTokens} feeds the catalog's drop-and-rename dependency
 * guard. A {@code sql} transform in the new shape keeps its expressions in {@code outputs} and carries an
 * empty {@code output_vars}, so a guard reading the raw field would contribute <b>zero</b> column
 * references and let an operator drop or rename a column the pipeline's {@code INSERT … SELECT} joins —
 * with the failure arriving at the next materialize rather than at the DDL. That is data loss, not a
 * cosmetic gap.
 *
 * <p>The projection carries <b>no logical type</b>, by construction: a new-shape output declares none. Every
 * path that receives the projected var either ignores {@code type} (the runner's LLM output mapping) or
 * checks the expression's <em>inferred</em> type against the target column instead (the declaration gate
 * and the materialize call), so the absence is inert.
 */
public final class OutputShapes {

    private OutputShapes() {
    }

    /**
     * The outputs as {@link TransformVar}s, whichever shape they are stored in: projected from
     * {@code outputs} when it has any, and {@code outputVars} verbatim otherwise.
     *
     * <p>An <b>empty</b> {@code outputs} reads as old-shape, not as "declares nothing" — which is the test
     * the fold migration has to match, and why it folds {@code outputs} only when it is both non-null and
     * not an empty array.
     */
    public static List<TransformVar> effectiveOutputVars(
            List<TransformOutput> outputs, List<TransformVar> outputVars) {
        if (outputs == null || outputs.isEmpty()) {
            return outputVars == null ? List.of() : outputVars;
        }
        final List<TransformVar> projected = new ArrayList<>(outputs.size());
        for (TransformOutput output : outputs) {
            final TransformVar var = new TransformVar();
            var.setName(output.getName());
            var.setJsonata(output.getJsonata());
            var.setSql(output.getSql());
            projected.add(var);
        }
        return projected;
    }

    /**
     * The response transform per output name, in declaration order, resolved through
     * {@link #effectiveOutputVars} so both stored shapes answer identically.
     *
     * <p>A blank transform maps to <b>null</b>, because absent is what "read the response property named by
     * this output" means and an identity expression is refused at declaration. Two consumers need exactly
     * this join — the compiled projection's {@code outputs} and the enrich preview's — and both key it by
     * the binding's {@code var}, so it is built once here rather than as a stream lookup at each site,
     * where the shape of the lookup is easy to get subtly wrong (a {@code findFirst} mapped before it is
     * unwrapped turns the ordinary no-transform case into a {@code NullPointerException}).
     */
    public static Map<String, String> jsonataByOutputName(
            List<TransformOutput> outputs, List<TransformVar> outputVars) {
        final Map<String, String> byName = new LinkedHashMap<>();
        for (TransformVar var : effectiveOutputVars(outputs, outputVars)) {
            if (var.getName() != null) {
                byName.put(var.getName(), StringUtils.trimToNull(var.getJsonata()));
            }
        }
        return byName;
    }
}
