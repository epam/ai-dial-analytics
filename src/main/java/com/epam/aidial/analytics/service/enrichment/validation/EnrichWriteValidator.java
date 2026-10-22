package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.exception.WriteColumnNotAllowedException;
import com.epam.aidial.analytics.exception.WriteOutputColumnRequiredException;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.support.PartitionEcho;
import com.epam.aidial.analytics.service.enrichment.support.ProvenanceColumns;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates a pipeline-scoped write's column set, from both directions.
 *
 * <p><b>Nothing outside the permitted set.</b> A row may name only {the declaration's output-binding
 * columns} ∪ {the grain key} ∪ {permitted provenance columns}. System ({@code _}-prefixed) columns —
 * including the version column {@code _updated_at} — are never caller-settable (ClickHouse stamps
 * {@code _updated_at} via its DEFAULT). A disallowed column raises {@link WriteColumnNotAllowedException}
 * (422 {@code write_column_not_allowed}). The target enrichment's own physical-column check still runs in
 * the delegated write path.
 *
 * <p><b>And at least one output column, in every row.</b> A row of grain plus provenance names only
 * permitted columns, so the subset rule above passes it — and against the target's
 * {@code ReplacingMergeTree}, read at its grain, it <em>replaces</em> a label that has already been paid
 * for with nothing. It does not fail and it does not no-op. So presence of a declared output column is
 * required, and its own code says so: {@link WriteOutputColumnRequiredException} (422
 * {@code write_output_column_required}).
 *
 * <p>The requirement is on the <b>presence of the column, not its value</b>: an output column explicitly
 * carrying {@code null} is a derived label of {@code null} and is accepted, because a mapping that
 * deliberately maps a sentinel to {@code null} is a declared outcome and there is one such mapping live.
 *
 * <p>This check is <b>version-independent</b>, unlike the contract refusals on the same endpoint. It is the
 * mirror of the principle the runner's own admission gate is written from — a remote invariant is not a
 * safe guard for a local, irreversible loss — applied in the direction ADAS owns: ADAS holds the target and
 * the mapping, so this is where "this write can write no label" is decidable whichever client sent it.
 *
 * <p>It cannot lock an <b>armed</b> pipeline out. {@code EnrichSpecValidator.deriveOutputBindings} refuses
 * a declaration whose transform resolves to no target column when the pipeline is armed, and sets the
 * column on every binding it derives, on both lanes — so an armed pipeline's permitted-output set always
 * holds at least one output. A <b>disabled</b> pipeline — possibly a draft with no derived mapping at all —
 * never reaches this check: {@code EnrichWriteService} refuses it first with 409 {@code pipeline_conflict}.
 * What remains is an <b>enabled</b> pipeline whose stored row has drifted since it was armed until it lacks
 * a member: its permitted outputs could then be empty, the allow-list would still hold the provenance
 * columns and the grain key, and a row naming a label column would be refused
 * {@code write_column_not_allowed} and a grain-only row {@code write_output_column_required} — both naming
 * the row when what is missing is the declaration. {@code EnrichWriteService} therefore refuses an
 * incomplete declaration before this runs, with {@code pipeline_validation_failed}; both codes above keep
 * exactly their meaning on a complete one.
 *
 * <p><b>{@code evaluator_version} is refused outright</b>, although it is still a permitted provenance
 * column to <em>read</em>. Nothing writes it since the evaluator was folded into its pipeline, and the
 * feed offers no member to derive it from, so a row naming it is a retired spelling — refused rather than
 * absorbed, which is this repository's ordinary rule, here applied to a provenance column name. An earlier
 * draft renamed it onto {@code pipeline_generation}; that tolerance existed only to serve an unchanged
 * runner, and the runner ships in the same window.
 *
 * <p><b>It is not the deploy detector, and must not be justified as one.</b> {@code /rows} already requires
 * {@code Adas-Contract-Version} and answers a stale runner with 409 {@code runner_contract_mismatch}
 * before any validation runs, so a stale runner never reaches this check. What this covers is the caller
 * the header gate does not reach the same way: a hand-rolled request, an out-of-date {@code adas-mcp}, any
 * holder of a {@code WRITE} grant.
 */
@Component
public class EnrichWriteValidator {

    public void validate(ResolvedEnrichPipeline resolved, List<Map<String, Object>> rows) {
        validate(resolved, rows, null);
    }

    /**
     * As {@link #validate(ResolvedEnrichPipeline, List)}, for a pipeline whose target is aligned on {@code alignment}
     * (see {@code PartitionEcho}; null for none). Every row must then carry the {@link PartitionEcho#MEMBER} the scan
     * returned, non-null: it is the only value of the alignment column a write-back row may supply. The column itself
     * is admitted only as what it is to every other column — a declared output — and a row naming both must give them
     * the same value, because the member is what is written and a disagreeing output would be overwritten silently.
     */
    public void validate(ResolvedEnrichPipeline resolved, List<Map<String, Object>> rows, String alignment) {
        Set<String> outputs = new LinkedHashSet<>();
        for (OutputBinding binding : resolved.spec().getOutputBindings()) {
            if (binding.getColumn() != null) {
                outputs.add(binding.getColumn());
            }
        }
        // EVALUATOR_VERSION is deliberately NOT subtracted here. Its own refusal below runs before the
        // subset check on every path, so removing it from the allow-list could only change which message
        // a caller never sees — and a second, silent copy of the rule is how the two come to disagree.
        Set<String> allowed = new HashSet<>(ProvenanceColumns.NAMES);
        allowed.addAll(outputs);
        if (resolved.grainKey() != null) {
            allowed.add(resolved.grainKey());
        }
        int index = 0;
        for (Map<String, Object> row : rows) {
            index++;
            if (row == null || row.isEmpty()) {
                throw new TableValidationException("a row must declare at least one column");
            }
            for (String column : row.keySet()) {
                if (alignment != null && PartitionEcho.MEMBER.equals(column)) {
                    continue;
                }
                if (column.startsWith("_")) {
                    throw new WriteColumnNotAllowedException(
                            "system column '" + column + "' is not caller-settable");
                }
                // Named separately from the generic subset refusal, so the message says what replaced it
                // rather than "not permitted" — the column exists on the target and is readable, and a
                // caller sending it is running against a retired contract rather than misreading a mapping.
                if (ProvenanceColumns.EVALUATOR_VERSION.equals(column)) {
                    throw new TableValidationException("column '" + ProvenanceColumns.EVALUATOR_VERSION
                            + "' is no longer written: a row's definition is identified by '"
                            + ProvenanceColumns.PIPELINE_GENERATION
                            + "' (the pipeline generation that produced it). Already-stamped"
                            + " evaluator_version values stay readable; send pipeline_generation instead");
                }
                if (!allowed.contains(column)) {
                    throw new WriteColumnNotAllowedException("column '" + column
                            + "' is not permitted for pipeline '" + resolved.pipeline().getName() + "'");
                }
            }
            // After the subset check, so a row that names a forbidden column keeps answering
            // write_column_not_allowed — the two codes carry opposite remedies and must not swap.
            // containsKey, not get: an output column present with a null value is a derived null.
            if (outputs.stream().noneMatch(row::containsKey)) {
                throw new WriteOutputColumnRequiredException("a row for pipeline '"
                        + resolved.pipeline().getName() + "' must name at least one of its declared output "
                        + "columns " + outputs + "; a row of grain and provenance alone carries no derived "
                        + "value and would replace the label this grain already holds with nothing. The "
                        + "column need only be present — a declared output whose value is null is accepted");
            }
            if (alignment != null) {
                requireEchoedPartition(resolved, row, index, alignment);
            }
        }
    }

    /**
     * The aligned-target checks on one row: the echoed member is present and non-null, and agrees with the row's own
     * value of the alignment column when an output is bound to it. Compared as sent, before any value normalization:
     * the runner echoes the scan's value unchanged, so an equal pair is the only one it produces.
     */
    private static void requireEchoedPartition(ResolvedEnrichPipeline resolved, Map<String, Object> row, int index,
            String alignment) {
        Object echoed = row.get(PartitionEcho.MEMBER);
        if (echoed == null) {
            throw new TableValidationException("row " + index + ": member '" + PartitionEcho.MEMBER + "' is required"
                    + " and must be non-null: pipeline '" + resolved.pipeline().getName() + "' writes an enrichment"
                    + " aligned on '" + alignment + "', and each row must carry back unchanged the '"
                    + PartitionEcho.MEMBER + "' its scan row returned");
        }
        if (row.containsKey(alignment) && !Objects.equals(row.get(alignment), echoed)) {
            throw new TableValidationException("row " + index + ": column '" + alignment + "' and member '"
                    + PartitionEcho.MEMBER + "' disagree; the target is aligned on '" + alignment + "', which is"
                    + " written from '" + PartitionEcho.MEMBER + "', so the two must carry the same value");
        }
    }
}
