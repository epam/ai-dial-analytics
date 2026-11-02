package com.epam.aidial.analytics.service.pipeline.preview;

import com.epam.aidial.analytics.service.query.model.StructuredQuery;

import java.util.List;
import java.util.Map;

/**
 * A dry-run preview of either kind, in the one shape both answer in: the sample, its typed columns, the
 * declaration as a query the caller can run through the query API where there is one, the number of groups an
 * {@code aggregate} target holds once caught up, the group a {@code group} sample describes, and coded notes.
 *
 * <p>It carries nothing the pipeline read already serves — no group keys, measures, schema or mapping — so a
 * client renders it beside the declaration it holds rather than reconciling two copies of it.
 *
 * @param statement the declaration's portable {@code SELECT} in the external SQL dialect — not the physical
 *                  statement that produced {@code rows}; null for an {@code llm} sample and for a declaration
 *                  the dialect cannot carry (a {@code statement_not_portable} note says why)
 * @param query     the structured-query document {@code statement} renders; null exactly when it is
 * @param totalRows the {@code aggregate} group count over the whole input, {@code 0} when there is none; null
 *                  for {@code enrich}
 * @param groupKey  the group a {@code group} sample describes; null otherwise
 */
public record PipelinePreview(
        String statement,
        StructuredQuery query,
        List<PreviewColumn> columns,
        List<Map<String, Object>> rows,
        Long totalRows,
        String groupKey,
        List<PreviewNote> notes) {

    public PipelinePreview {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        notes = List.copyOf(notes);
    }
}
