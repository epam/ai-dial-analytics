package com.epam.aidial.analytics.service.pipeline.preview;

import java.util.List;
import java.util.Map;

/**
 * A dry-run preview of either kind, in the one shape both answer in: the sample, its typed columns, the
 * statement that produced it where one did, the number of groups an {@code aggregate} target holds once caught
 * up, the group a {@code group} sample describes, and coded notes.
 *
 * <p>It carries nothing the pipeline read already serves — no group keys, measures, schema or mapping — so a
 * client renders it beside the declaration it holds rather than reconciling two copies of it.
 *
 * @param statement the {@code SELECT} that produced {@code rows}, with its {@code SETTINGS}; null when no single
 *                  statement did (an {@code llm} sample)
 * @param totalRows the {@code aggregate} group count over the whole input, {@code 0} when there is none; null
 *                  for {@code enrich}
 * @param groupKey  the group a {@code group} sample describes; null otherwise
 */
public record PipelinePreview(
        String statement,
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
