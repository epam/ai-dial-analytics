package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.observability.trace.LogExecution;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.PartitionEcho;
import com.epam.aidial.analytics.service.enrichment.support.PipelineAuthorizer;
import com.epam.aidial.analytics.service.enrichment.support.ProvenanceColumns;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichDeclarationCompleteness;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichWriteValidator;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.table.TableDataService;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pipeline-scoped versioned write-back (B3), symmetric with {@link ScanService}. Resolves the
 * pipeline's target enrichment, authorizes by its WRITE grant, validates the row column-set against the
 * declaration's outputs (plus grain + provenance), then delegates to the existing
 * {@link TableDataService#write} (which enforces the target's physical-column set, ACTIVE status,
 * and batch limit). {@code _updated_at} is stamped by ClickHouse via its DEFAULT — never by callers.
 *
 * <p>A group pipeline's key column is normalized before validation: a row naming the declaration's
 * {@code trigger.group_by} is rewritten to name the target's plain grain key. The two spellings differ only when the group key is
 * reachable through an enrichment of the read source, where {@code group_by} carries the qualified flat
 * name the reads project it under — and the reads deliberately hand the caller both spellings, so the one
 * it echoes back must not decide whether the write is accepted. Registration has already validated that
 * the group key resolves to the grain, so this renames rather than widens: no column outside the
 * declaration's own set becomes writable, and every other pipeline is untouched.
 *
 * <p>Provenance columns ({@link ProvenanceColumns}) are informational lineage the runner always
 * stamps but that are <em>optional</em> on the target: any provenance column the target does not
 * physically carry is silently dropped from the write here, so an enrichment created without them
 * still enriches successfully instead of the write failing. Non-provenance columns outside the
 * declaration's own set are still rejected by {@link EnrichWriteValidator}
 * (422 {@code write_column_not_allowed}) before this point.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class EnrichWriteService {

    private final PipelineService pipelines;
    private final PipelineAuthorizer authorizer;
    private final EnrichWriteValidator validator;
    private final TableDataService tableData;
    private final TableDefinitionRepository definitions;

    public int write(String pipelineName, List<Map<String, Object>> rows) {
        // 404 unknown_pipeline if absent, 422 if the pipeline is not of the enrich kind
        ResolvedEnrichPipeline resolved = pipelines.resolveForWrite(pipelineName);
        String target = resolved.pipeline().getTarget();
        // No read source: this endpoint carries the caller's own rows and reads nothing.
        authorizer.authorize(null, target);  // 403 pipeline_access_denied (none-mode bypass)
        PipelineService.requireEnabledToWrite(resolved.pipeline(), "accept rows");   // 409 pipeline_conflict
        // Only an ENABLED pipeline gets here, so an incomplete declaration is an armed one whose stored row
        // drifted, never a draft. Raised ahead of the row checks: on a declaration with no derived output
        // mapping the allow-list holds only grain and provenance, so the row checks would refuse the
        // operator's own label column (write_column_not_allowed) or a grain-only row
        // (write_output_column_required) — naming the row when what is missing is the declaration.
        EnrichDeclarationCompleteness.requireRunnable(resolved, "accept rows");
        if (rows == null || rows.isEmpty()) {
            throw new TableValidationException("at least one row is required");
        }
        // Resolved once and handed down: the read source, the echo rule and the provenance reconciliation all ask
        // about the same target, and each would otherwise read its definition again.
        TableDefinitionEntity definition = definitions.findById(target).orElse(null);
        // The write resolve carries no read source; the echo needs only which table the scan read.
        String readSource = StringUtils.isNotBlank(resolved.pipeline().getInputTable())
                ? resolved.pipeline().getInputTable()
                : definition == null ? null : definition.getSourceTable();
        // The scan's own rule, so the write-back requires the member exactly when the scan returned it.
        String alignment = PartitionEcho.column(definitions, definition, resolved.pipeline().getTriggerKind(), readSource);
        List<Map<String, Object>> keyed = normalizeGroupKey(resolved, rows);
        validator.validate(resolved, keyed, alignment);     // 422 write_column_not_allowed / validation_error
        List<Map<String, Object>> echoed = echoedPartition(keyed, alignment);
        Set<String> physical = definition == null ? tableData.physicalColumns(target) : tableData.physicalColumns(definition);
        return tableData.write(target, dropAbsentProvenance(target, physical, echoed));
    }

    /**
     * Moves each row's echoed {@link PartitionEcho#MEMBER} onto the target's alignment column, when the target has
     * one. The validator has already required the member on every row, and refused a row whose own value of the
     * column (an output bound to it) disagrees with it; for an unaligned target the validator has refused the
     * member as the system column it is, so there is nothing to move.
     */
    private static List<Map<String, Object>> echoedPartition(List<Map<String, Object>> rows, String alignment) {
        if (alignment == null) {
            return rows;
        }
        List<Map<String, Object>> moved = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put(alignment, copy.remove(PartitionEcho.MEMBER));
            moved.add(copy);
        }
        return moved;
    }

    /**
     * Rewrites a group pipeline's key column from the spelling the declaration uses to the plain grain key
     * the target is physically keyed on, when the two differ — which happens exactly when the group key is
     * an enrichment column of the read source and the declared key is therefore its qualified flat name.
     * Nothing else in the row is touched, and a pipeline whose group key already is the grain key returns
     * its rows unchanged.
     *
     * <p>A row naming both spellings is accepted only when they agree; disagreeing values name two different
     * grains for one row, and silently keeping either would write a label against the wrong key.
     */
    private List<Map<String, Object>> normalizeGroupKey(
            ResolvedEnrichPipeline resolved, List<Map<String, Object>> rows) {
        String groupBy = resolved.pipeline().getTriggerGroupBy();
        String grainKey = resolved.grainKey();
        if (resolved.pipeline().getTriggerKind() != TriggerKind.GROUP || StringUtils.isBlank(groupBy)
                || StringUtils.isBlank(grainKey) || groupBy.equals(grainKey)) {
            return rows;
        }
        List<Map<String, Object>> renamed = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (row == null || !row.containsKey(groupBy)) {
                renamed.add(row);
                continue;
            }
            Object key = row.get(groupBy);
            if (row.containsKey(grainKey) && !Objects.equals(key, row.get(grainKey))) {
                throw new TableValidationException("a row names the group key twice, as '" + groupBy
                        + "' and as '" + grainKey + "', with different values; both spell the target's grain"
                        + " key and must agree");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            row.forEach((column, value) -> copy.put(groupBy.equals(column) ? grainKey : column, value));
            renamed.add(copy);
        }
        return renamed;
    }

    /**
     * Drops permitted provenance columns the target does not physically carry, so a target created
     * without them still writes (best-effort lineage). Only provenance names are ever dropped; every
     * other column has already passed {@link EnrichWriteValidator}, and the target's physical-column
     * check still applies to what remains. Rows carrying only present columns pass through untouched.
     */
    private static List<Map<String, Object>> dropAbsentProvenance(String target, Set<String> physical,
            List<Map<String, Object>> rows) {
        Set<String> absent = ProvenanceColumns.NAMES.stream()
                .filter(name -> !physical.contains(name))
                .collect(Collectors.toSet());
        if (absent.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> filtered = new ArrayList<>(rows.size());
        boolean dropped = false;
        for (Map<String, Object> row : rows) {
            if (row != null && !Collections.disjoint(row.keySet(), absent)) {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                copy.keySet().removeAll(absent);
                filtered.add(copy);
                dropped = true;
            } else {
                filtered.add(row);
            }
        }
        if (dropped) {
            log.debug("Pipeline-scoped write to enrichment '{}' skipped provenance column(s) not present"
                            + " on the target: {}",
                    target, absent);
        }
        return filtered;
    }
}
