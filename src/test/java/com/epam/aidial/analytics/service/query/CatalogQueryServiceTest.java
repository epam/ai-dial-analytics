package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.PartitionGranularity;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.SecurityMode;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** Unit coverage for {@link CatalogQueryService} model assembly, mocking the catalog repositories. */
@ExtendWith(MockitoExtension.class)
class CatalogQueryServiceTest {

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();

    @Mock
    private TableDefinitionRepository definitions;
    @Mock
    private ColumnMappingRepository columns;

    private CatalogQueryService service() {
        // security.mode=none → both filters fail open, so these model-assembly tests see every table and
        // every column.
        final SensitiveColumnAccess access = new SensitiveColumnAccess(
                new TableAccessEvaluator(definitions, new SecurityClaimsExtractor()),
                new SecurityModeResolver(SecurityMode.NONE.code()));
        return new CatalogQueryService(definitions, columns, access);
    }

    /** Every table readable, with sensitivity answered uniformly — the pre-per-table-grant policy shape. */
    private CatalogQueryService service(boolean maySeeSensitive) {
        final SensitiveColumnAccess access = mock(SensitiveColumnAccess.class);
        lenient().when(access.mayList(anyString())).thenReturn(true);
        lenient().when(access.mayGet(anyString())).thenReturn(true);
        lenient().when(access.mayRead(anyString())).thenReturn(true);
        lenient().when(access.maySeeSensitive(anyString())).thenReturn(maySeeSensitive);
        return new CatalogQueryService(definitions, columns, access);
    }

    private static TableDefinitionEntity definition(
            String name, TableType type, String sourceTable, String partitionColumn) {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(name);
        entity.setType(type);
        entity.setSourceTable(sourceTable);
        entity.setStatus(TableStatus.ACTIVE);
        if (partitionColumn != null) {
            entity.setPartitionColumn(partitionColumn);
            entity.setPartitionGranularity(PartitionGranularity.DAY);
        }
        return entity;
    }

    private static ColumnMappingEntity column(String table, String sourceName, QueryFieldType type, int ordinal) {
        final ColumnMappingEntity col = new ColumnMappingEntity();
        col.setTableName(table);
        col.setSourceName(sourceName);
        col.setName(sourceName);
        col.setType(type);
        col.setOrdinal(ordinal);
        return col;
    }

    private static ColumnMappingEntity sensitiveColumn(String table, String sourceName, QueryFieldType type, int ordinal) {
        final ColumnMappingEntity col = column(table, sourceName, type, ordinal);
        col.setSensitive(true);
        return col;
    }

    private static ColumnMappingEntity tagged(
            String table, String sourceName, QueryFieldType type, int ordinal, String tag) {
        final ColumnMappingEntity col = column(table, sourceName, type, ordinal);
        col.setTag(tag);
        return col;
    }

    private static ColumnMappingEntity heavyColumn(String table, String sourceName, QueryFieldType type, int ordinal) {
        final ColumnMappingEntity col = column(table, sourceName, type, ordinal);
        col.setHeavy(true);
        return col;
    }

    /**
     * The heavy flag reaches both halves of the model — the binding the query path resolves and the
     * discovery field — and, unlike {@code sensitive}, it is published to a caller who may not see
     * sensitive columns: it is a transfer-cost hint, so it hides nothing.
     */
    @Test
    void heavyFlagReachesBindingsAndSchemaForEveryCaller() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, null);
        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                column("events", "widget_id", QueryFieldType.STRING, 0),
                heavyColumn("events", "payload", QueryFieldType.STRING, 1)));
        given(definitions.findBySourceTable("events")).willReturn(List.of());

        final QueryEntityModel model = service(false).findModel("events", Privilege.TABLE_READ).orElseThrow();

        assertThat(model.bindings().get("payload").heavy()).isTrue();
        assertThat(model.bindings().get("widget_id").heavy()).isFalse();
        assertThat(model.baseSchema()).extracting(QuerySchemaFieldDto::name, QuerySchemaFieldDto::heavy)
                .containsExactly(tuple("widget_id", false), tuple("payload", true));
        // Present in the model, but not in what a wildcard projects.
        assertThat(WildcardProjection.sourceFields(model)).containsExactly("widget_id");
    }

    /** Bindings iterate in catalog order, because that order is the observable wildcard column order. */
    @Test
    void bindingsKeepCatalogOrder() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, null);
        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                column("events", "zeta", QueryFieldType.STRING, 0),
                column("events", "alpha", QueryFieldType.STRING, 1),
                column("events", "mid", QueryFieldType.STRING, 2)));
        given(definitions.findBySourceTable("events")).willReturn(List.of());

        final QueryEntityModel model = service(true).findModel("events", Privilege.TABLE_READ).orElseThrow();

        assertThat(model.bindings().keySet()).containsExactly("zeta", "alpha", "mid");
        assertThat(WildcardProjection.sourceFields(model)).containsExactly("zeta", "alpha", "mid");
    }

    /**
     * When the source declares a tag ordering, discovery returns the fields grouped by it — listed tags
     * in list order, then unlisted tags alphabetically, then untagged last, alphabetical by name within
     * each group — while {@code tag_order} itself is echoed verbatim.
     */
    @Test
    void baseSchemaIsSortedByTagOrder() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, null);
        source.setTagOrder(List.of("identifier", "cost"));
        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                tagged("events", "widget_id", QueryFieldType.STRING, 0, "identifier"),
                tagged("events", "price", QueryFieldType.LONG, 1, "cost"),
                tagged("events", "amount", QueryFieldType.LONG, 2, "cost"),
                tagged("events", "beta_col", QueryFieldType.STRING, 3, "beta"),
                tagged("events", "alpha_col", QueryFieldType.STRING, 4, "alpha"),
                column("events", "note", QueryFieldType.STRING, 5)));
        given(definitions.findBySourceTable("events")).willReturn(List.of());

        final var dto = service(true).getBaseSchema("events");
        assertThat(dto.tagOrder()).containsExactly("identifier", "cost");
        assertThat(dto.fields()).extracting(QuerySchemaFieldDto::name)
                .containsExactly("widget_id", "amount", "price", "alpha_col", "beta_col", "note");
    }

    /** Without a tag ordering the discovery fields stay in declared (ordinal) order and tag_order is null. */
    @Test
    void baseSchemaKeepsDeclaredOrderWhenNoTagOrder() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, null);
        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                tagged("events", "zeta", QueryFieldType.STRING, 0, "cost"),
                tagged("events", "alpha", QueryFieldType.STRING, 1, "cost")));
        given(definitions.findBySourceTable("events")).willReturn(List.of());

        final var dto = service(true).getBaseSchema("events");
        assertThat(dto.tagOrder()).isNull();
        assertThat(dto.fields()).extracting(QuerySchemaFieldDto::name).containsExactly("zeta", "alpha");
    }

    /** The sort runs after sensitive-field filtering, and tag_order is echoed unfiltered to a non-admin. */
    @Test
    void baseSchemaSortsAfterSensitiveHidingAndEchoesTagOrder() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, null);
        source.setTagOrder(List.of("identifier"));
        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                tagged("events", "widget_id", QueryFieldType.STRING, 0, "identifier"),
                taggedSensitive("events", "secret", QueryFieldType.STRING, 1, "identifier"),
                tagged("events", "aaa", QueryFieldType.STRING, 2, "identifier")));
        given(definitions.findBySourceTable("events")).willReturn(List.of());

        final var dto = service(false).getBaseSchema("events");
        // secret is hidden for the non-admin; the remaining identifier fields are alphabetical.
        assertThat(dto.fields()).extracting(QuerySchemaFieldDto::name).containsExactly("aaa", "widget_id");
        assertThat(dto.tagOrder()).containsExactly("identifier");
    }

    private static ColumnMappingEntity taggedSensitive(
            String table, String sourceName, QueryFieldType type, int ordinal, String tag) {
        final ColumnMappingEntity col = tagged(table, sourceName, type, ordinal, tag);
        col.setSensitive(true);
        return col;
    }

    private static ColumnMappingEntity describedColumn(
            String table, String sourceName, QueryFieldType type, int ordinal, String description) {
        final ColumnMappingEntity col = column(table, sourceName, type, ordinal);
        col.setDescription(description);
        return col;
    }

    /**
     * A column's {@code description} flows into the flat discovery schema; a column without one carries a
     * null description. A sensitive column's description is never emitted to a non-admin caller because the
     * whole field is hidden (descriptions ride through the same access chokepoint).
     */
    @Test
    void columnDescriptionIsCarriedIntoBaseSchemaAndHiddenWithSensitiveColumns() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, null);
        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                describedColumn("events", "project_id", QueryFieldType.STRING, 0, "The project."),
                column("events", "chat_id", QueryFieldType.STRING, 1),
                sensitiveDescribedColumn("events", "jwt_claims", QueryFieldType.OBJECT, 2, "Sensitive claims.")));
        given(definitions.findBySourceTable("events")).willReturn(List.of());

        assertThat(service(true).findModel("events", Privilege.TABLE_READ).orElseThrow().baseSchema())
                .filteredOn(field -> field.name().equals("project_id"))
                .singleElement()
                .extracting(QuerySchemaFieldDto::description)
                .isEqualTo("The project.");
        assertThat(service(true).findModel("events", Privilege.TABLE_READ).orElseThrow().baseSchema())
                .filteredOn(field -> field.name().equals("chat_id"))
                .singleElement()
                .extracting(QuerySchemaFieldDto::description)
                .isNull();

        // Non-admin: the sensitive column (and therefore its description) is absent entirely.
        assertThat(service(false).findModel("events", Privilege.TABLE_READ).orElseThrow().baseSchema())
                .extracting(QuerySchemaFieldDto::name)
                .containsExactly("project_id", "chat_id");
    }

    private static ColumnMappingEntity sensitiveDescribedColumn(
            String table, String sourceName, QueryFieldType type, int ordinal, String description) {
        final ColumnMappingEntity col = sensitiveColumn(table, sourceName, type, ordinal);
        col.setDescription(description);
        return col;
    }

    /**
     * Column-level access control chokepoint: a non-admin caller's model excludes sensitive columns from
     * both {@code bindings} (so a reference is an unknown field) and {@code baseSchema} (so discovery
     * hides them); an admin sees them, flagged {@code sensitive}.
     */
    @Test
    void sensitiveColumnsAreFilteredForNonAdminButVisibleToAdmin() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, null);
        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                column("events", "project_id", QueryFieldType.STRING, 0),
                sensitiveColumn("events", "jwt_claims", QueryFieldType.OBJECT, 1)));
        given(definitions.findBySourceTable("events")).willReturn(List.of());

        final QueryEntityModel hidden = service(false).findModel("events", Privilege.TABLE_READ).orElseThrow();
        assertThat(hidden.bindings()).containsKey("project_id").doesNotContainKey("jwt_claims");
        assertThat(hidden.baseSchema()).extracting(QuerySchemaFieldDto::name).containsExactly("project_id");

        final QueryEntityModel visible = service(true).findModel("events", Privilege.TABLE_READ).orElseThrow();
        assertThat(visible.bindings()).containsKeys("project_id", "jwt_claims");
        assertThat(visible.baseSchema())
                .filteredOn(field -> field.name().equals("jwt_claims"))
                .singleElement()
                .extracting(QuerySchemaFieldDto::sensitive)
                .isEqualTo(true);
    }

    /**
     * Regression guard: even when the source is partitioned and the enrichment co-stores a column whose
     * name matches the source's partition column, the enrichment join is the plain single-key grain
     * lookup {@code source.grain_key = enrichment.grain_key} — no second equality on the partition
     * column is ANDed in. (An inferred partition-aligned variant was removed because, under a LEFT JOIN
     * with nullable enrichment columns, a non-aligned/null partition value silently dropped all
     * enrichment columns to NULL.)
     */
    @Test
    void enrichmentJoinIsGrainKeyOnlyEvenWhenPartitionColumnIsCoStored() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, "day");
        final TableDefinitionEntity enrichment = definition("facts", TableType.ENRICHMENT, "events", null);
        enrichment.setGrainKey("widget_id");

        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                column("events", "widget_id", QueryFieldType.STRING, 0),
                column("events", "day", QueryFieldType.DATE, 1),
                column("events", "amount", QueryFieldType.LONG, 2)));
        given(definitions.findBySourceTable("events")).willReturn(List.of(enrichment));
        // The enrichment co-stores a column named "day" — the same name as the source's partition column.
        given(columns.findByTableNameOrderByOrdinal("facts")).willReturn(List.of(
                column("facts", "day", QueryFieldType.DATE, 0),
                column("facts", "label", QueryFieldType.STRING, 1)));

        final QueryEntityModel model = service().findModel("events", Privilege.TABLE_READ).orElseThrow();
        assertThat(model.enrichments()).hasSize(1);

        final String condition = ctx.renderInlined(model.enrichments().getFirst().condition());
        assertThat(condition)
                .contains("widget_id")
                .doesNotContain("day")
                .doesNotContainIgnoringCase(" and ");
        // Co-storing a column of the same name is not a claim that the two hold the same value: the
        // enrichment declares no partition column, so the join carries no alignment to bound it by.
        assertThat(model.enrichments().getFirst().partitionAligned()).isFalse();
    }

    /**
     * Both definitions declare the same partition column, which is the catalog's way of saying the two hold
     * the same value for the same grain key — the shape {@code dial_usage_log} and
     * {@code dial_usage_log_payload} have on {@code request_time}. The join then carries the pair, and
     * {@code EnrichmentJoins} can bound the enrichment to the partitions the source predicates select
     * instead of reading a candidate granule from every part.
     */
    @Test
    void matchingDeclaredPartitionColumnsAlignTheJoin() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, "day");
        final TableDefinitionEntity enrichment = definition("facts", TableType.ENRICHMENT, "events", "day");
        enrichment.setGrainKey("widget_id");

        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                column("events", "widget_id", QueryFieldType.STRING, 0),
                column("events", "day", QueryFieldType.DATE, 1)));
        given(definitions.findBySourceTable("events")).willReturn(List.of(enrichment));
        given(columns.findByTableNameOrderByOrdinal("facts")).willReturn(List.of(
                column("facts", "label", QueryFieldType.STRING, 0)));

        final QueryEntityModel model = service().findModel("events", Privilege.TABLE_READ).orElseThrow();
        final EnrichmentJoin join = model.enrichments().getFirst();

        assertThat(join.partitionAligned()).isTrue();
        assertThat(ctx.renderInlined(join.sourcePartition())).isEqualTo("\"events\".\"day\"");
        assertThat(ctx.renderInlined(join.enrichmentPartition())).isEqualTo("\"facts\".\"day\"");
        // An aligned join compares the pair: each source row matches only the enrichment row written from it, so a
        // grain key repeated across partitions keeps its 0..1 cardinality per source row.
        assertThat(ctx.renderInlined(join.condition()))
                .isEqualTo("((\"events\".\"widget_id\" = \"facts\".\"widget_id\") and (\"events\".\"day\" = \"facts\".\"day\"))");
        assertThat(join.partitionGranularity()).isEqualTo(source.getPartitionGranularity());
    }

    /**
     * Differently-named partition columns are not aligned. Partitioning an enrichment by its own timestamp
     * while its source partitions by another is an ordinary pair of declarations, and bounding one table by
     * the other's values would silently restrict the join to partitions that need not hold the row — which
     * a LEFT JOIN reports as the column's type default, indistinguishable from an unmatched row.
     */
    @Test
    void differentlyNamedPartitionColumnsAreNotAligned() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, "day");
        final TableDefinitionEntity enrichment = definition("facts", TableType.ENRICHMENT, "events", "created_on");
        enrichment.setGrainKey("widget_id");

        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                column("events", "widget_id", QueryFieldType.STRING, 0),
                column("events", "day", QueryFieldType.DATE, 1)));
        given(definitions.findBySourceTable("events")).willReturn(List.of(enrichment));
        given(columns.findByTableNameOrderByOrdinal("facts")).willReturn(List.of(
                column("facts", "label", QueryFieldType.STRING, 0)));

        final EnrichmentJoin join = service().findModel("events", Privilege.TABLE_READ).orElseThrow().enrichments().getFirst();

        assertThat(join.partitionAligned()).isFalse();
        assertThat(join.keyable()).isTrue();
    }

    /** A source that declares no partitioning has no values to bound an enrichment by, however the latter is laid out. */
    @Test
    void unpartitionedSourceAlignsNothing() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, null);
        final TableDefinitionEntity enrichment = definition("facts", TableType.ENRICHMENT, "events", "day");
        enrichment.setGrainKey("widget_id");

        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                column("events", "widget_id", QueryFieldType.STRING, 0)));
        given(definitions.findBySourceTable("events")).willReturn(List.of(enrichment));
        given(columns.findByTableNameOrderByOrdinal("facts")).willReturn(List.of(
                column("facts", "label", QueryFieldType.STRING, 0)));

        assertThat(service().findModel("events", Privilege.TABLE_READ).orElseThrow().enrichments().getFirst().partitionAligned())
                .isFalse();
    }

    /**
     * The entity-level partition-locality claim the executor keys its deduplication scope on: an upsert source
     * partitioned on a sort-key column, enriched by a table partitioned on the same aligned column, is
     * partition-local — the {@code dial_usage_log} + {@code dial_usage_log_payload} shape.
     */
    @Test
    void alignedUpsertSourceAndEnrichmentMakeTheEntityPartitionLocal() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, "day");
        source.setWriteMode(TableWriteMode.UPSERT_BY_KEY);
        source.setOrderingKey(List.of("widget_id", "day"));
        final TableDefinitionEntity enrichment = definition("facts", TableType.ENRICHMENT, "events", "day");
        enrichment.setGrainKey("widget_id");
        enrichment.setOrderingKey(List.of("widget_id"));

        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                column("events", "widget_id", QueryFieldType.STRING, 0),
                column("events", "day", QueryFieldType.DATE, 1)));
        given(definitions.findBySourceTable("events")).willReturn(List.of(enrichment));
        given(columns.findByTableNameOrderByOrdinal("facts")).willReturn(List.of(
                column("facts", "label", QueryFieldType.STRING, 0)));

        assertThat(service().findModel("events", Privilege.TABLE_READ).orElseThrow().partitionLocal()).isTrue();
    }

    /**
     * One enrichment whose partition column is neither in its ordering key nor aligned with the source's
     * withdraws the claim for the whole entity — every read of it, not only the ones naming that enrichment.
     */
    @Test
    void nonAlignedPartitionedEnrichmentMakesTheEntityCrossPartition() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, "day");
        source.setWriteMode(TableWriteMode.UPSERT_BY_KEY);
        source.setOrderingKey(List.of("widget_id", "day"));
        final TableDefinitionEntity enrichment = definition("facts", TableType.ENRICHMENT, "events", "created_on");
        enrichment.setGrainKey("widget_id");
        enrichment.setOrderingKey(List.of("widget_id"));

        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("events")).willReturn(List.of(
                column("events", "widget_id", QueryFieldType.STRING, 0),
                column("events", "day", QueryFieldType.DATE, 1)));
        given(definitions.findBySourceTable("events")).willReturn(List.of(enrichment));
        given(columns.findByTableNameOrderByOrdinal("facts")).willReturn(List.of(
                column("facts", "created_on", QueryFieldType.DATE, 0),
                column("facts", "label", QueryFieldType.STRING, 1)));

        assertThat(service().findModel("events", Privilege.TABLE_READ).orElseThrow().partitionLocal()).isFalse();
    }

    /**
     * Final-capability is decided at {@code TABLE_READ} on every surface — the bar execution resolves at. A
     * caller who may list and get an {@code append} source's enrichment but not read it is handed a model
     * without it at execution, so neither discovery ({@code TABLE_LIST}) nor translation ({@code TABLE_GET})
     * may call the entity final-capable: {@code FINAL} there would translate and then fail as illegal_final.
     */
    @Test
    void enrichmentTheCallerCannotReadDoesNotMakeTheEntityFinalCapable() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, null);
        final TableDefinitionEntity enrichment = definition("facts", TableType.ENRICHMENT, "events", null);
        enrichment.setGrainKey("widget_id");
        final SensitiveColumnAccess access = mock(SensitiveColumnAccess.class);
        lenient().when(access.mayList(anyString())).thenReturn(true);
        lenient().when(access.mayGet(anyString())).thenReturn(true);
        lenient().when(access.mayRead("events")).thenReturn(true);
        lenient().when(access.mayRead("facts")).thenReturn(false);
        final CatalogQueryService service = new CatalogQueryService(definitions, columns, access);

        given(definitions.findByTypeAndStatus(TableType.SOURCE, TableStatus.ACTIVE)).willReturn(List.of(source));
        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(definitions.findBySourceTable("events")).willReturn(List.of(enrichment));
        lenient().when(columns.findByTableNameOrderByOrdinal("events")).thenReturn(List.of(
                column("events", "widget_id", QueryFieldType.STRING, 0)));
        lenient().when(columns.findByTableNameOrderByOrdinal("facts")).thenReturn(List.of(
                column("facts", "label", QueryFieldType.STRING, 0)));

        assertThat(service.listEntities()).singleElement()
                .satisfies(dto -> assertThat(dto.supportsFinal()).isFalse());
        final QueryEntityModel translated = service.findModel("events", Privilege.TABLE_GET).orElseThrow();
        assertThat(translated.enrichments()).hasSize(1);
        assertThat(FinalDecision.finalCapable(translated)).isFalse();
        final QueryEntityModel executed = service.findModel("events", Privilege.TABLE_READ).orElseThrow();
        assertThat(executed.enrichments()).isEmpty();
        assertThat(FinalDecision.finalCapable(executed)).isFalse();
    }

    /** The same shape with the enrichment readable is final-capable on every surface. */
    @Test
    void readableEnrichmentMakesTheEntityFinalCapableOnEverySurface() {
        final TableDefinitionEntity source = definition("events", TableType.SOURCE, null, null);
        final TableDefinitionEntity enrichment = definition("facts", TableType.ENRICHMENT, "events", null);
        enrichment.setGrainKey("widget_id");
        final CatalogQueryService service = service(true);

        given(definitions.findByTypeAndStatus(TableType.SOURCE, TableStatus.ACTIVE)).willReturn(List.of(source));
        given(definitions.findById("events")).willReturn(Optional.of(source));
        given(definitions.findBySourceTable("events")).willReturn(List.of(enrichment));
        lenient().when(columns.findByTableNameOrderByOrdinal("events")).thenReturn(List.of(
                column("events", "widget_id", QueryFieldType.STRING, 0)));
        lenient().when(columns.findByTableNameOrderByOrdinal("facts")).thenReturn(List.of(
                column("facts", "label", QueryFieldType.STRING, 0)));

        assertThat(service.listEntities()).singleElement()
                .satisfies(dto -> assertThat(dto.supportsFinal()).isTrue());
        assertThat(FinalDecision.finalCapable(service.findModel("events", Privilege.TABLE_GET).orElseThrow())).isTrue();
        assertThat(FinalDecision.finalCapable(service.findModel("events", Privilege.TABLE_READ).orElseThrow())).isTrue();
    }

    /** The payload enrichment's computed fields follow its catalog columns, as projection-only stand-in bindings. */
    @Test
    void computedFieldsFollowThePayloadColumnsForCallerClearedForThem() {
        stubPayloadEntity(sensitiveColumn("dial_usage_log_payload", "request_body", QueryFieldType.STRING, 0));

        final QueryEntityModel model = service(true).findModel("dial_usage_log", Privilege.TABLE_READ).orElseThrow();

        assertThat(model.bindings().keySet()).containsExactly("trace_id", "dial_usage_log_payload.request_body",
                "dial_usage_log_payload.request_body_full", "dial_usage_log_payload.request_body_rebuild");
        final QueryFieldBinding full = model.bindings().get("dial_usage_log_payload.request_body_full");
        assertThat(full.isComputed()).isTrue();
        assertThat(full.heavy()).isTrue();
        assertThat(ComputedField.standInOf(full.field().as("x"))).isNotNull();
        assertThat(full.reads()).extracting(field -> field.getUnqualifiedName().last())
                .containsExactly("request_body", "request_parent_hash", "request_ancestor_hashes", "request_cache_control", "request_time");
        assertThat(model.ownerOf("dial_usage_log_payload.request_body_rebuild")).isNotNull();
        assertThat(model.bindings().get("dial_usage_log_payload.request_body").isComputed()).isFalse();
        assertThat(model.baseSchema())
                .extracting(QuerySchemaFieldDto::name, QuerySchemaFieldDto::type, QuerySchemaFieldDto::source, QuerySchemaFieldDto::sensitive,
                        QuerySchemaFieldDto::heavy, QuerySchemaFieldDto::projectionOnly)
                .containsExactly(
                        tuple("trace_id", QueryFieldType.STRING, "trace_id", false, false, false),
                        tuple("dial_usage_log_payload.request_body", QueryFieldType.STRING, "request_body", true, false, false),
                        tuple("dial_usage_log_payload.request_body_full", QueryFieldType.STRING, "request_body_full", true, true, true),
                        tuple("dial_usage_log_payload.request_body_rebuild", QueryFieldType.ENUM, "request_body_rebuild", true, true, true));
        assertThat(model.baseSchema().getLast().enumValues()).containsExactly("stored", "rebuilt", "partial");
    }

    @Test
    void computedFieldsAreHiddenFromCallerNotClearedForTheBody() {
        stubPayloadEntity(sensitiveColumn("dial_usage_log_payload", "request_body", QueryFieldType.STRING, 0));

        final QueryEntityModel model = service(false).findModel("dial_usage_log", Privilege.TABLE_READ).orElseThrow();

        assertThat(model.bindings().keySet()).containsExactly("trace_id");
        assertThat(model.baseSchema()).extracting(QuerySchemaFieldDto::name).containsExactly("trace_id");
    }

    @Test
    void computedFieldsNeedTheRequestBodyRowAndStayOutOfTableBindings() {
        stubPayloadEntity(column("dial_usage_log_payload", "response_body", QueryFieldType.STRING, 0));
        final CatalogQueryService service = service(true);

        assertThat(service.findModel("dial_usage_log", Privilege.TABLE_READ).orElseThrow().bindings().keySet())
                .containsExactly("trace_id", "dial_usage_log_payload.response_body");
        given(columns.findByTableNameOrderByOrdinal("dial_usage_log_payload")).willReturn(List.of(
                sensitiveColumn("dial_usage_log_payload", "request_body", QueryFieldType.STRING, 0)));
        assertThat(service.tableBindings("dial_usage_log_payload")).containsOnlyKeys("request_body");
    }

    /** The rebuilt body returns the cache markers too, so a sensitive markers row makes the computed fields sensitive. */
    @Test
    void computedFieldsCarryTheMarkersRowsSensitiveFlagToo() {
        stubPayloadEntity(column("dial_usage_log_payload", "request_body", QueryFieldType.STRING, 0),
                sensitiveColumn("dial_usage_log_payload", "request_cache_control", QueryFieldType.STRING, 1));

        assertThat(service(false).findModel("dial_usage_log", Privilege.TABLE_READ).orElseThrow().bindings().keySet())
                .containsExactly("trace_id", "dial_usage_log_payload.request_body");
        assertThat(service(true).findModel("dial_usage_log", Privilege.TABLE_READ).orElseThrow().baseSchema())
                .filteredOn(QuerySchemaFieldDto::projectionOnly)
                .extracting(QuerySchemaFieldDto::sensitive)
                .containsExactly(true, true);
    }

    private void stubPayloadEntity(ColumnMappingEntity... payloadColumns) {
        final TableDefinitionEntity source = definition("dial_usage_log", TableType.SOURCE, null, null);
        final TableDefinitionEntity payload = definition("dial_usage_log_payload", TableType.ENRICHMENT, "dial_usage_log", null);
        payload.setGrainKey("event_id");
        lenient().when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(source));
        lenient().when(definitions.findBySourceTable("dial_usage_log")).thenReturn(List.of(payload));
        lenient().when(columns.findByTableNameOrderByOrdinal("dial_usage_log")).thenReturn(List.of(
                column("dial_usage_log", "trace_id", QueryFieldType.STRING, 0)));
        lenient().when(columns.findByTableNameOrderByOrdinal("dial_usage_log_payload")).thenReturn(List.of(payloadColumns));
    }
}
