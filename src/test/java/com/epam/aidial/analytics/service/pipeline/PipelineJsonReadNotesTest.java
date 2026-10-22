package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.service.enrichment.EnrichResolver;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TransformVar;
import com.epam.aidial.analytics.service.enrichment.support.EntityFieldResolver;
import com.epam.aidial.analytics.service.enrichment.support.EntityFields;
import com.epam.aidial.analytics.service.enrichment.validation.ExprSqlValidator;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;
import com.epam.aidial.analytics.service.enrichment.validation.SourceScopeGuard;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.sql.CalciteQueryCatalog;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Registration reports the paths x parts form of a JSON read and never refuses it — the
 * {@code structured-query} requirement "The cost of reading a native JSON column scales with paths times
 * parts".
 *
 * <p>The two extractors that cannot use subcolumn access are the whole subject: the case-insensitive one,
 * which has a single-path alternative the note has to name, and the array one, which has none. Every other
 * {@code json_extract_*} over the same column addresses a literal path, and reporting it would be noise on
 * the declaration an author is being steered towards.
 *
 * <p>The scope guard is stubbed to a no-op for the reason {@code ExprSqlValidatorTest} stubs it: what it
 * enforces is its own subject, and admitting the qualified payload columns is what every real caller of
 * these two validators does.
 */
class PipelineJsonReadNotesTest {

    private static final String SOURCE = DialUsageLogTestModel.ENTITY;
    private static final String OBJECT_COLUMN = DialUsageLogTestModel.payload("request_tags");
    private static final String TEXT_COLUMN = DialUsageLogTestModel.payload("request_body");

    private final EnrichResolver enrichResolver = mock(EnrichResolver.class);
    private final EntityFieldResolver entityFields = mock(EntityFieldResolver.class);
    private final PipelineJsonReadNotes notes = new PipelineJsonReadNotes(
            enrichResolver, entityFields, filterValidator(), exprValidator(),
            new PipelineSpecCodec(new com.epam.aidial.analytics.configuration.JsonMapperConfiguration().jsonMapper()),
            mock(AggregateSqlReader.class));

    private static SqlQueryFrontEnd frontEnd() {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(SOURCE));
        when(catalog.findModel(anyString(), any())).thenReturn(Optional.empty());
        when(catalog.findModel(eq(SOURCE), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));
    }

    private static FilterSqlValidator filterValidator() {
        return new FilterSqlValidator(frontEnd(), mock(SourceScopeGuard.class));
    }

    private static ExprSqlValidator exprValidator() {
        return new ExprSqlValidator(frontEnd(), mock(SourceScopeGuard.class));
    }

    /** The read source's flat field view: enough of it that a type lookup answers for each column used here. */
    private static EntityFields entity() {
        final Map<String, EntityFields.Field> byName = new LinkedHashMap<>();
        byName.put("event_id", field(SOURCE, "event_id", QueryFieldType.UUID, false));
        byName.put(OBJECT_COLUMN,
                field(DialUsageLogTestModel.PAYLOAD, "request_tags", QueryFieldType.OBJECT, true));
        byName.put(DialUsageLogTestModel.payload("jwt_claims"),
                field(DialUsageLogTestModel.PAYLOAD, "jwt_claims", QueryFieldType.OBJECT, true));
        byName.put(TEXT_COLUMN,
                field(DialUsageLogTestModel.PAYLOAD, "request_body", QueryFieldType.STRING, true));
        return new EntityFields(byName);
    }

    private static EntityFields.Field field(
            String owner, String name, QueryFieldType type, boolean enrichment) {
        final ColumnMappingEntity column = new ColumnMappingEntity();
        column.setTableName(owner);
        column.setName(name);
        column.setSourceName(name);
        column.setType(type);
        column.setNullable(false);
        return new EntityFields.Field(owner, column, enrichment);
    }

    private PipelineEntity enrichPipeline(String filterSql) {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName("usage-request-baggage-live");
        pipeline.setKind(PipelineKind.ENRICH);
        pipeline.setTarget("usage_request_baggage");
        pipeline.setFilterSql(filterSql);
        return pipeline;
    }

    /**
     * Wires the enrich resolve this pipeline's notes go through, with an optional Class-1 expression.
     *
     * <p>The expression is carried in the declaration's own transform now, under the <b>legacy</b>
     * {@code output_vars} shape on purpose: the notes read it through the output-shape accessor, so a
     * fixture in the shape nothing exercises by default is the one worth pinning here.
     */
    private PipelineEntity resolving(PipelineEntity pipeline, String outputSql) {
        final EnrichSpec spec = new EnrichSpec();
        spec.mutableTransform().setType(TransformType.SQL);
        if (outputSql != null) {
            final TransformVar var = new TransformVar();
            var.setName("baggage");
            var.setSql(outputSql);
            spec.mutableTransform().setOutputVars(List.of(var));
        }
        when(enrichResolver.resolve(any(PipelineEntity.class))).thenReturn(new ResolvedEnrichPipeline(
                pipeline, spec, SOURCE, "event_id", "request_time"));
        when(entityFields.enrichEntityOf(SOURCE)).thenReturn(entity());
        return pipeline;
    }

    @Test
    void caseInsensitiveFilterOverAnObjectColumnIsReported() {
        final PipelineEntity pipeline = resolving(enrichPipeline(
                "json_extract_string_ci(\"" + OBJECT_COLUMN + "\", 'baggage') <> ''"), null);

        assertThat(notes.of(pipeline)).singleElement().asString()
                .contains("filter")
                .contains(OBJECT_COLUMN)
                .contains("json_extract_string_ci")
                .contains("one read stream per JSON path per part")
                // The alternative is what makes the note actionable rather than a complaint.
                .contains("json_extract_string");
    }

    @Test
    void singlePathFilterOverAnObjectColumnIsNotReported() {
        // Same column, same selected rows, and the form the author is being steered towards: it
        // addresses one literal path, so it reads one subcolumn and there is nothing to report.
        final PipelineEntity pipeline = resolving(enrichPipeline(
                "json_extract_string(\"" + OBJECT_COLUMN + "\", 'baggage') <> ''"), null);

        assertThat(notes.of(pipeline)).isEmpty();
    }

    @Test
    void caseInsensitiveReadOverPlainTextIsNotReported() {
        // The cost this note exists for is native-JSON storage, not the function: over a String column
        // json_extract_string_ci reads one column like any other, so reporting it would be noise.
        final PipelineEntity pipeline = resolving(enrichPipeline(
                "json_extract_string_ci(\"" + TEXT_COLUMN + "\", 'baggage') <> ''"), null);

        assertThat(notes.of(pipeline)).isEmpty();
    }

    @Test
    void arrayExtractionIsNotReported() {
        // It was, while it shared the serialized-text rendering. It addresses the subcolumn now — one
        // stream rather than one per path — so there is nothing to warn the author about, and a note here
        // would send them looking for a cheaper spelling that does not exist because this is it.
        final PipelineEntity pipeline = resolving(enrichPipeline(null),
                "json_extract_array(\"" + DialUsageLogTestModel.payload("jwt_claims") + "\", 'roles')");

        assertThat(notes.of(pipeline)).isEmpty();
    }

    @Test
    void filterAndOutputExpressionAreReportedSeparately() {
        final PipelineEntity pipeline = resolving(enrichPipeline(
                        "json_extract_string_ci(\"" + OBJECT_COLUMN + "\", 'baggage') <> ''"),
                "json_extract_string_ci(\"" + DialUsageLogTestModel.payload("jwt_claims") + "\", 'sub')");

        assertThat(notes.of(pipeline)).hasSize(2);
    }

    @Test
    void nothingIsRefusedOnThisGround() {
        // The whole point of D3: the declaration that exceeded the read budget is still accepted, and the
        // resource budget — not registration — is what bounds it.
        final PipelineEntity pipeline = resolving(enrichPipeline(
                "json_extract_string_ci(\"" + OBJECT_COLUMN + "\", 'baggage') <> ''"), null);

        assertThatCode(() -> notes.of(pipeline)).doesNotThrowAnyException();
        assertThat(notes.of(pipeline)).isNotEmpty();
    }

    @Test
    void anAggregateFilterIsReportedToo() {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName("usage_daily_rollup");
        pipeline.setKind(PipelineKind.AGGREGATE);
        pipeline.setInputTable(SOURCE);
        pipeline.setFilterSql("json_extract_string_ci(\"" + OBJECT_COLUMN + "\", 'baggage') <> ''");
        when(entityFields.entityOf(SOURCE)).thenReturn(entity());

        assertThat(notes.of(pipeline)).singleElement().asString().contains("json_extract_string_ci");
    }

    @Test
    void anUnresolvableDeclarationDegradesToNoNotes() {
        // A write that succeeded must not be answered with an error raised by advice about it.
        final PipelineEntity pipeline = enrichPipeline("this is not sql");
        when(enrichResolver.resolve(any(PipelineEntity.class)))
                .thenThrow(new IllegalStateException("source gone"));

        assertThat(notes.of(pipeline)).isEmpty();
    }
}
