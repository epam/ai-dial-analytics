package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.table.EnrichmentAlignment;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;

/**
 * The partition value an LLM pipeline's rows carry from the scan to the write-back when their target is aligned
 * (see {@link EnrichmentAlignment}). The write-back is the only writer of such a pipeline's rows and ADAS keeps no
 * per-caller state between the two calls, so the source row's alignment value travels with the row: the scan
 * returns it as {@link #MEMBER}, the runner echoes it unchanged, and the write-back writes it into the target's
 * alignment column. Looking it up at write-back instead would scan the source's identity column across every
 * partition per batch.
 *
 * <p>The scan and the write-back both ask {@link #column}, so the scan returns the member exactly when the write-back
 * requires it. Registration keeps the two cases it answers null for off an aligned target altogether (see
 * {@code EnrichSpecValidator.requireAlignableWriter}): a group-trigger pipeline, whose rows are written from an
 * assembled group rather than a scanned row, and a pipeline reading a table other than the target's own source,
 * whose rows hold no value of the target's source rows.
 */
public final class PartitionEcho {

    /** The scan-row and write-row member carrying the source row's alignment value. */
    public static final String MEMBER = "__partition_v";

    private PartitionEcho() {
    }

    /**
     * The alignment column of {@code enrichment}, computed against its <b>own</b> source — never against a pipeline's
     * read source, whose partition column may share the name and hold another table's values — or null when it is
     * not aligned.
     */
    public static String targetAlignment(TableDefinitionRepository definitions, TableDefinitionEntity enrichment) {
        if (enrichment == null || enrichment.getPartitionColumn() == null || enrichment.getSourceTable() == null) {
            return null;
        }
        return EnrichmentAlignment.alignedColumn(enrichment, definitions.findById(enrichment.getSourceTable()).orElse(null));
    }

    /**
     * The alignment column the echo fills for a {@code trigger} pipeline reading {@code readSource} into
     * {@code target}, or null when there is none: the target is not aligned, the pipeline is a group-trigger one, or
     * it reads a table other than the target's own source.
     */
    public static String column(TableDefinitionRepository definitions, TableDefinitionEntity target, TriggerKind trigger,
            String readSource) {
        if (target == null || trigger == TriggerKind.GROUP || readSource == null || !readSource.equals(target.getSourceTable())) {
            return null;
        }
        return targetAlignment(definitions, target);
    }
}
