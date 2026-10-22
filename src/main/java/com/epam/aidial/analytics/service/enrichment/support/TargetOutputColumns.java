package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;

import java.util.List;
import java.util.Optional;

/**
 * Resolves one transform output to the target enrichment column that receives it.
 *
 * <p>One rule, shared by the two places that apply it — the schema composer (which needs the column's type,
 * enum domain and enum order) and the registration gate (which needs its physical {@code source_name} for
 * the derived output mapping). They must agree exactly: a composer that resolved an output the gate refuses
 * would put a property in the prompt that nothing can write, and a gate that resolved one the composer
 * refuses would derive a binding for a column the model is never asked to fill.
 *
 * <p>Matched against the column's <b>exposed {@code name} only</b> — deliberately not through the catalog's
 * usual name-or-{@code source_name} lookup, which would make an output name ambiguous in principle and
 * could resolve two outputs onto one physical column. Both live enrichment targets have
 * {@code name == source_name} on every column, so pinning it costs nothing today and forecloses the
 * ambiguity. A name that matches only a {@code source_name} is refused naming the exposed spelling to write
 * instead, which is a better answer than "no such column" for the one caller who hits it.
 *
 * <p>Name-matching couples an output's name to a column's name. A transform belongs to exactly one pipeline and
 * one target, so that coupling costs nothing; the refusal still names the mismatch rather than only reporting an
 * unknown column.
 */
public final class TargetOutputColumns {

    private TargetOutputColumns() {
    }

    /**
     * The target column named by {@code output}.
     *
     * @throws PipelineValidationException when no column carries that exposed name
     */
    public static ColumnMappingEntity requireExposed(
            String output, List<ColumnMappingEntity> targetColumns, String targetName) {
        final Optional<ColumnMappingEntity> exposed = targetColumns.stream()
                .filter(column -> output.equals(column.getName()))
                .findFirst();
        if (exposed.isPresent()) {
            return exposed.get();
        }
        final Optional<ColumnMappingEntity> physical = targetColumns.stream()
                .filter(column -> output.equals(column.getSourceName()))
                .findFirst();
        if (physical.isPresent()) {
            throw new PipelineValidationException("transform output '" + output + "' matches only the"
                    + " physical name of column '" + physical.get().getName() + "' on '" + targetName
                    + "'; declare the output under the exposed name '" + physical.get().getName() + "'");
        }
        throw new PipelineValidationException("transform output '" + output + "' resolves to no column on"
                + " target '" + targetName + "'; an output is written to the target column of the same"
                + " name, so either add that column or rename the output");
    }
}
