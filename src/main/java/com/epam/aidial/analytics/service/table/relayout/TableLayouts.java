package com.epam.aidial.analytics.service.table.relayout;

import com.epam.aidial.analytics.service.table.EnrichmentAlignment;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The {@code layout} {@code GET /v1/tables/{name}} reports for an enrichment: {@code migrating} while ADAS relays
 * it out, {@code aligned} once it is laid out aligned with its source, {@code unsupported} when the relayout skipped
 * it, and nothing otherwise.
 */
@Component
@RequiredArgsConstructor
public class TableLayouts {

    public static final String MIGRATING = "migrating";
    public static final String ALIGNED = "aligned";
    public static final String UNSUPPORTED = "unsupported";

    private final TableDefinitionRepository definitions;
    private final RelayoutStateStore states;

    /** {@code table}'s layout, or null when there is nothing to report. */
    public String layout(TableDefinitionEntity table) {
        if (table.getType() != TableType.ENRICHMENT) {
            return null;
        }
        final Optional<RelayoutStateStore.State> state = states.find(table.getName());
        if (state.map(s -> s.step().inProgress()).orElse(false)) {
            return MIGRATING;
        }
        if (state.map(s -> s.step() == RelayoutStep.UNSUPPORTED).orElse(false)) {
            return UNSUPPORTED;
        }
        final TableDefinitionEntity source = definitions.findById(table.getSourceTable()).orElse(null);
        return EnrichmentAlignment.alignedColumn(table, source) != null ? ALIGNED : null;
    }
}
