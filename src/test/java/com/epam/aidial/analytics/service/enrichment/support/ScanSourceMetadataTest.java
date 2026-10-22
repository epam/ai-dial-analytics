package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * The two model accessors differ in exactly one way, and that difference is load-bearing: a rule-scoped
 * read must not see enrichment joins, while the aggregate pipeline path must. Both resolve the same
 * bindings, so nothing else distinguishes them — which is why the split is pinned here rather than left to
 * the reading of two callers.
 */
@ExtendWith(MockitoExtension.class)
class ScanSourceMetadataTest {

    private static final String SOURCE = "dial_usage_log";

    @Mock
    private TableDefinitionRepository definitions;
    @Mock
    private CatalogQueryService catalog;

    private ScanSourceMetadata metadata() {
        return new ScanSourceMetadata(definitions, catalog);
    }

    /** A source carrying one enrichment that exposes a single qualified column. */
    private static QueryEntityModel modelWithEnrichment() {
        final String flatName = "usage_client_identity.client_session_id";
        final Map<String, QueryFieldBinding> bindings = Map.of(
                "event_id",
                new QueryFieldBinding("event_id", DSL.field(DSL.name(SOURCE, "event_id")), QueryFieldType.UUID, null, false),
                flatName,
                new QueryFieldBinding(flatName, DSL.field(DSL.name("usage_client_identity", "client_session_id")),
                        QueryFieldType.STRING, null, false));
        final EnrichmentJoin join = new EnrichmentJoin(
                DSL.table(DSL.name("usage_client_identity")),
                DSL.field(DSL.name(SOURCE, "event_id")).eq(DSL.field(DSL.name("usage_client_identity", "event_id"))), null, null, null, null,
                Set.of(flatName), null);
        return new QueryEntityModel(SOURCE, DSL.table(DSL.name(SOURCE)), bindings, List.of(join), List.of());
    }

    @Test
    void entityModelKeepsEnrichmentJoins() {
        given(catalog.findModel(eq(SOURCE), any())).willReturn(Optional.of(modelWithEnrichment()));

        assertThat(metadata().entityModel(SOURCE).enrichments())
                .singleElement()
                .satisfies(join -> assertThat(join.exposedNames())
                        .containsExactly("usage_client_identity.client_session_id"));
    }

    @Test
    void anUnqueryableSourceIsRejected() {
        given(catalog.findModel(eq("nope"), any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> metadata().entityModel("nope"))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("nope");
    }

    /**
     * The raw-name fallback exists for a base column the role-filtered model omitted. A qualified name has
     * no base-table spelling, so rendering it would emit {@code "<source>"."<enrichment>.<column>"} — a
     * column no FROM clause carries — and the read would fail at execution pointing nowhere near the cause.
     */
    @Test
    void qualifiedNameAbsentFromTheBindingsIsRefusedRatherThanRendered() {
        final QueryEntityModel model = modelWithEnrichment();

        assertThatThrownBy(() -> ScanSourceMetadata.projected(model, Map.of(), "usage_client_identity.missing"))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("usage_client_identity.missing");
    }

    /**
     * A bare name still falls back, which is what an entitled caller's sensitive column relies on: a
     * sensitive column is absent from the model entirely, so the projection cannot find it in the
     * bindings and must treat it as a column of the source. Deliberately a bare name — a qualified one
     * names an enrichment and is rejected rather than falling back (asserted above).
     */
    @Test
    void bareNameAbsentFromTheBindingsStillFallsBackToTheSourceColumn() {
        final QueryEntityModel model = modelWithEnrichment();

        assertThat(ScanSourceMetadata.projected(model, Map.of(), "a_sensitive_source_column").toString())
                .contains("a_sensitive_source_column");
    }

    private static TableDefinitionEntity scannable(String partitionColumn) {
        final TableDefinitionEntity definition = new TableDefinitionEntity();
        definition.setName(SOURCE);
        definition.setVersionColumn("_ingested_at");
        definition.setIdentityColumn("event_id");
        definition.setPartitionColumn(partitionColumn);
        return definition;
    }

    /** The partition bound is keyed on the catalog's own declaration, so the cursor carries it from there. */
    @Test
    void cursorColumnsCarryTheSourceAndItsPartitionColumn() {
        given(definitions.findById(SOURCE)).willReturn(Optional.of(scannable("request_time")));

        assertThat(metadata().cursorColumns(SOURCE))
                .isEqualTo(new CursorColumns("_ingested_at", "event_id", SOURCE, "request_time"))
                .satisfies(columns -> assertThat(columns.partitioned()).isTrue());
    }

    private static TableDefinitionEntity upsertPartitionedOutsideKey(String version) {
        final TableDefinitionEntity definition = scannable("event_day");
        definition.setWriteMode(TableWriteMode.UPSERT_BY_KEY);
        definition.setVersionColumn(version);
        definition.setOrderingKey(List.of("event_id"));
        return definition;
    }

    /**
     * Pruning happens before FINAL collapses a key, so on an upsert source partitioned outside its key a pruned
     * old-cursor-version row may be the key's winner; if the cursor does not order by the collapse version, the
     * floor would resurrect the row it superseded.
     */
    @Test
    void anUpsertSourcePartitionedOutsideItsKeyGetsNoBoundWhenTheCursorIsNotTheCollapseVersion() {
        given(definitions.findById(SOURCE)).willReturn(Optional.of(upsertPartitionedOutsideKey("event_time")));

        assertThat(metadata().cursorColumns(SOURCE).partitioned()).isFalse();
    }

    @Test
    void anUpsertSourcePartitionedOutsideItsKeyKeepsTheBoundWhenTheCursorIsTheCollapseVersion() {
        given(definitions.findById(SOURCE)).willReturn(Optional.of(upsertPartitionedOutsideKey(TableDdlRenderer.UPDATED_AT)));

        assertThat(metadata().cursorColumns(SOURCE).partitionColumn()).isEqualTo("event_day");
    }

    @Test
    void anUpsertSourcePartitionedInsideItsKeyKeepsTheBound() {
        final TableDefinitionEntity definition = upsertPartitionedOutsideKey("event_time");
        definition.setOrderingKey(List.of("event_day", "event_id"));
        given(definitions.findById(SOURCE)).willReturn(Optional.of(definition));

        assertThat(metadata().cursorColumns(SOURCE).partitionColumn()).isEqualTo("event_day");
    }

    @Test
    void anUnpartitionedSourceYieldsNoPartitionBound() {
        given(definitions.findById(SOURCE)).willReturn(Optional.of(scannable(null)));

        assertThat(metadata().cursorColumns(SOURCE).partitioned()).isFalse();
    }
}
