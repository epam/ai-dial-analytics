package com.epam.aidial.analytics.web.mapper.pipeline;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.service.enrichment.model.InputBinding;
import com.epam.aidial.analytics.service.enrichment.model.TransformPreset;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TransformVar;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.ProvenanceColumns;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.pipeline.model.PipelineRegistration;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.ColumnInput;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.CreateTableInput;
import com.epam.aidial.analytics.service.table.DefineSchemaInput;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.service.table.grant.TableCallerPermissions;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyCodec;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest;
import com.epam.aidial.analytics.web.dto.table.DefineSchemaRequest;
import com.epam.aidial.analytics.web.mapper.table.TableMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The catalog bundle's inverse mappers: a stored table or pipeline exported, serialized, read back with the
 * strict request mapper and mapped to the service inputs comes back as the declaration it was stored from.
 */
class CatalogBundleMappingTest {

    private final JsonMapper lenient = new JsonMapperConfiguration().jsonMapper();
    private final JsonMapper strict = lenient.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final PipelineSpecCodec specCodec = new PipelineSpecCodec(lenient);
    private final TableMapper tableMapper = new TableMapper(mock(TableCallerPermissions.class),
            mock(SensitiveColumnAccess.class), new RowPolicyCodec(lenient), mock(com.epam.aidial.analytics.service.table.relayout.TableLayouts.class));
    private final PipelineMapper pipelineMapper = pipelineMapper();

    private PipelineMapper pipelineMapper() {
        final PipelineMapper instance = new PipelineMapperImpl();
        ReflectionTestUtils.setField(instance, "specCodec", specCodec);
        return instance;
    }

    @Test
    void sourceRoundTripsWithStoredWriteModeAndScanMetadata() {
        final TableDefinitionEntity source = definition("usage_base", TableType.SOURCE, null);
        source.setWriteMode(TableWriteMode.UPSERT_BY_KEY);
        source.setOrderingKey(new ArrayList<>(List.of("id")));
        source.setIdentityColumn("id");
        source.setVersionColumn("_updated_at");
        source.setTagOrder(new ArrayList<>(List.of("ids")));
        final List<ColumnMappingEntity> columns = List.of(column("id", 0, false, false), column("secret", 1, true, true));

        final CreateTableInput create = tableMapper.toCreateInput(
                roundTrip(tableMapper.toCreateRequest(source), CreateTableRequest.class));
        final DefineSchemaInput define = tableMapper.toDefineInput(
                roundTrip(tableMapper.toDefineSchemaRequest(source, columns), DefineSchemaRequest.class));

        assertThat(create).isEqualTo(new CreateTableInput("usage_base", "desc", TableType.SOURCE, null,
                TableWriteMode.UPSERT_BY_KEY, List.of("ids")));
        assertThat(define.orderingKey()).containsExactly("id");
        assertThat(define.identityColumn()).isEqualTo("id");
        assertThat(define.versionColumn()).isEqualTo("_updated_at");
        assertThat(define.columns()).extracting(ColumnInput::name).containsExactly("id", "secret");
        assertThat(define.columns().get(1).sensitive()).isTrue();
        assertThat(define.columns().get(1).heavy()).isTrue();
        assertThat(define.columns().get(0).nullable()).isFalse();
    }

    @Test
    void enrichmentWithDeclaredProvenanceRoundTripsUnchanged() {
        final TableDefinitionEntity enrichment = definition("usage_sentiment", TableType.ENRICHMENT, "usage_base");
        enrichment.setGrainKey("id");
        enrichment.setOrderingKey(new ArrayList<>(List.of("id")));
        final List<ColumnMappingEntity> columns = new ArrayList<>();
        columns.add(column("sentiment", 0, false, false));
        int ordinal = 1;
        for (final String name : ProvenanceColumns.DECLARED_ORDER) {
            final ColumnMappingEntity col = column(name, ordinal++, false, false);
            col.setDescription("custom " + name);
            col.setTag("prov");
            columns.add(col);
        }

        final DefineSchemaRequest request = tableMapper.toDefineSchemaRequest(enrichment, columns);
        final DefineSchemaInput define = tableMapper.toDefineInput(roundTrip(request, DefineSchemaRequest.class));

        assertThat(request.orderingKey()).isNull();
        assertThat(request.partitionBy()).isNull();
        assertThat(request.grainKey()).isEqualTo("id");
        assertThat(tableMapper.toCreateRequest(enrichment).write()).isNull();
        assertThat(TableSchemaCreationService.withInjectedProvenance(TableType.ENRICHMENT, define)).isSameAs(define);
        assertThat(define.columns()).extracting(ColumnInput::description)
                .containsExactly(null, "custom " + ProvenanceColumns.DECLARED_ORDER.get(0),
                        "custom " + ProvenanceColumns.DECLARED_ORDER.get(1),
                        "custom " + ProvenanceColumns.DECLARED_ORDER.get(2),
                        "custom " + ProvenanceColumns.DECLARED_ORDER.get(3),
                        "custom " + ProvenanceColumns.DECLARED_ORDER.get(4));
    }

    @Test
    void enrichmentWithoutProvenanceGainsTheInjectedColumns() {
        final TableDefinitionEntity enrichment = definition("usage_client_identity", TableType.ENRICHMENT, "usage_base");
        enrichment.setGrainKey("id");
        final DefineSchemaInput define = tableMapper.toDefineInput(roundTrip(
                tableMapper.toDefineSchemaRequest(enrichment, List.of(column("client", 0, false, false))),
                DefineSchemaRequest.class));

        final DefineSchemaInput injected = TableSchemaCreationService.withInjectedProvenance(TableType.ENRICHMENT, define);

        assertThat(injected.columns()).hasSize(1 + ProvenanceColumns.canonicalColumns().size());
        assertThat(injected.columns().getFirst().name()).isEqualTo("client");
    }

    @Test
    void legacyOutputVarsPipelineKeepsOutputVars() {
        final EnrichSpec spec = new EnrichSpec();
        final EnrichSpec.TransformSpec transform = spec.mutableTransform();
        transform.setType(TransformType.LLM);
        transform.setModel("gemini-2.5-flash-lite");
        transform.setPreset(TransformPreset.CHAT_COMPLETION);
        transform.setRequestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"{{text}}\"}]}");
        final InputBinding binding = new InputBinding();
        binding.setVar("text");
        binding.setColumn("prompt");
        transform.setInputBindings(new ArrayList<>(List.of(binding)));
        final TransformVar verdict = new TransformVar();
        verdict.setName("verdict");
        verdict.setType("string");
        verdict.setJsonata("verdict");
        transform.setOutputVars(new ArrayList<>(List.of(verdict)));
        spec.setRateRpm(75);
        final PipelineEntity stored = new PipelineEntity();
        stored.setName("usage_sentiment_llm");
        stored.setKind(PipelineKind.ENRICH);
        stored.setTarget("usage_sentiment");
        stored.setTriggerKind(TriggerKind.SCHEDULE);
        stored.setTriggerCron("0 * * * * *");
        stored.setEnabled(true);
        stored.setSpec(specCodec.write(spec));

        final BundlePipeline bundled = roundTrip(pipelineMapper.toBundle(stored), BundlePipeline.class);
        final PipelineRegistration registration = pipelineMapper.toRegistration(bundled);

        assertThat(bundled.exportedEnabled()).isTrue();
        assertThat(bundled.pipeline().inputs()).isNull();
        assertThat(registration.enabled()).isFalse();
        assertThat(registration.inputTable()).isNull();
        assertThat(registration.triggerCron()).isEqualTo("0 * * * * *");
        final EnrichSpec.TransformSpec imported = ((EnrichSpec) registration.spec()).getTransform();
        assertThat(imported.getOutputs()).isNull();
        assertThat(imported.getOutputVars()).extracting(TransformVar::getName).containsExactly("verdict");
        assertThat(imported.getInputBindings()).extracting(InputBinding::getColumn).containsExactly("prompt");
        assertThat(((EnrichSpec) registration.spec()).getRateRpm()).isEqualTo(75);
        assertThat(specCodec.write(registration.spec())).isEqualTo(specCodec.write(spec));
    }

    private <T> T roundTrip(T value, Class<T> type) {
        return strict.readValue(lenient.writeValueAsString(value), type);
    }

    private static TableDefinitionEntity definition(String name, TableType type, String sourceTable) {
        final TableDefinitionEntity definition = new TableDefinitionEntity();
        definition.setName(name);
        definition.setDescription("desc");
        definition.setType(type);
        definition.setSourceTable(sourceTable);
        definition.setStatus(TableStatus.ACTIVE);
        return definition;
    }

    private static ColumnMappingEntity column(String name, int ordinal, boolean sensitive, boolean heavy) {
        final ColumnMappingEntity col = new ColumnMappingEntity();
        col.setName(name);
        col.setSourceName(name);
        col.setType(QueryFieldType.STRING);
        col.setOrdinal(ordinal);
        col.setSensitive(sensitive);
        col.setHeavy(heavy);
        return col;
    }
}
