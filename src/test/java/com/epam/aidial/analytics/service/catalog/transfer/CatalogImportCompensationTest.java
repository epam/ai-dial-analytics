package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.service.audit.AuditImportScope;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.TableAccessService;
import com.epam.aidial.analytics.service.table.TableCatalogService;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import com.epam.aidial.analytics.web.dto.catalog.ImportResult;
import com.epam.aidial.analytics.web.dto.catalog.PlannedChange;
import com.epam.aidial.analytics.web.dto.catalog.ResolutionPolicy;
import com.epam.aidial.analytics.web.mapper.pipeline.PipelineMapper;
import com.epam.aidial.analytics.web.mapper.table.TableMapper;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The order {@link CatalogImportService} undoes an {@code OVERWRITE} in, against mocked services. */
class CatalogImportCompensationTest {

    private final CatalogImportPlanner planner = mock(CatalogImportPlanner.class);
    private final ObjectUpdater updater = mock(ObjectUpdater.class);
    private final PipelineService pipelineService = mock(PipelineService.class);
    private final PipelineMapper pipelineMapper = mock(PipelineMapper.class);
    private final CatalogImportService service = new CatalogImportService(mock(CatalogBundleParser.class), planner,
            mock(CatalogImportValidator.class), mock(ImportTargetReader.class), mock(TableCatalogService.class),
            mock(TableSchemaCreationService.class), pipelineService, mock(PipelineRepository.class), mock(TableMapper.class),
            pipelineMapper, mock(SecurityModeResolver.class), mock(SecurityClaimsExtractor.class), mock(AuditImportScope.class),
            mock(TableAccessService.class), updater, mock(TableAccessEvaluator.class));

    /**
     * An enabled aggregate the import disabled is re-enabled only after the tables are reverted: arming runs the gate
     * against its target, which a column the same import added fails until the column is dropped again.
     */
    @Test
    void disabledPipelineIsReEnabledAfterTheTablesAreReverted() {
        final PlannedObject table = updated(PlannedObject.Kind.TABLE, "sessions",
                List.of(new PlannedChange(PlannedChange.Op.ADD_COLUMN, "cost", List.of())));
        final PlannedObject rollup = updated(PlannedObject.Kind.PIPELINE, "sessions_rollup",
                List.of(new PlannedChange(PlannedChange.Op.DISABLE, null, List.of("enabled")),
                        new PlannedChange(PlannedChange.Op.REDECLARE, null, List.of("measures"))));
        final PlannedObject failing = new PlannedObject(PlannedObject.Kind.PIPELINE, "later", null);
        when(planner.plan(any(), anyBoolean(), any(), any(), anyBoolean())).thenReturn(new ImportPlan(List.of(),
                List.of(table), List.of(rollup, failing), List.of(), new ArrayList<>(), new ArrayList<>()));
        when(pipelineService.create(any())).thenThrow(new IllegalStateException("refused"));

        final ImportResult result = service.apply(new byte[0], false, ResolutionPolicy.OVERWRITE, false);

        assertThat(result.outcome()).isEqualTo(CatalogImportService.ROLLED_BACK);
        final InOrder order = inOrder(updater);
        order.verify(updater).revertDeclaration(rollup);
        order.verify(updater).revertTable(table);
        order.verify(updater).restoreEnabled(rollup);
    }

    private static PlannedObject updated(PlannedObject.Kind kind, String name, List<PlannedChange> changes) {
        final PlannedObject planned = new PlannedObject(kind, name, null);
        planned.update(new ObjectDiff.Result(changes, List.of(), List.of(), List.of(), null));
        return planned;
    }
}
