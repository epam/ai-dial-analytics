package com.epam.aidial.analytics.service.audit;

import com.epam.aidial.analytics.service.audit.AuditResourceMapper.AuditResource;
import com.epam.aidial.analytics.service.otel.OtelSinkEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.query.saved.SavedQueryEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingId;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import org.hibernate.envers.RevisionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Unit coverage for {@link AuditResourceMapper}: the audited entities map to their resource type and id
 * (a composite id rendering as {@code <parent>:<local>}), and anything else is dropped rather than
 * guessed at.
 *
 * <p>The "dropped" cases are asserted against the concrete entity classes the proposal excludes, so this
 * test doubles as the executable statement of the coverage boundary: annotating one of them {@code
 * @Audited} without extending the mapper would leave its changes silently absent from the feed, and
 * extending the mapper without deciding to audit it fails here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditResourceMapperTest {

    @Mock
    private PipelineRepository pipelines;

    private AuditResourceMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AuditResourceMapper(pipelines);
    }

    @Test
    void mapsTableDefinitionToTableResource() {
        final Optional<AuditResource> resource = mapper.resolve(TableDefinitionEntity.class, "conversations");

        assertThat(resource).contains(new AuditResource(ActivityResourceType.Table, "conversations"));
    }

    @Test
    void mapsColumnMappingToCompoundResourceId() {
        final Optional<AuditResource> resource =
                mapper.resolve(ColumnMappingEntity.class, new ColumnMappingId("conversations", "chat_id"));

        assertThat(resource)
                .contains(new AuditResource(ActivityResourceType.TableColumn, "conversations:chat_id"));
    }

    @Test
    void mapsPipelineToPipelineResource() {
        final Optional<AuditResource> resource = mapper.resolve(PipelineEntity.class, "some-pipeline");

        assertThat(resource).contains(new AuditResource(ActivityResourceType.Pipeline, "some-pipeline"));
    }

    @Test
    void mapsSavedQueryToSavedQueryResource() {
        final Optional<AuditResource> resource = mapper.resolve(SavedQueryEntity.class, "some-query");

        assertThat(resource).contains(new AuditResource(ActivityResourceType.SavedQuery, "some-query"));
    }

    @Test
    void dropsEntitiesOutsideTheAuditedSurface() {
        assertThat(mapper.resolve(OtelSinkEntity.class, "usage-sink")).isEmpty();
    }

    @Test
    void readsTheOwningTableOffColumnResource() {
        final AuditResource column = new AuditResource(ActivityResourceType.TableColumn, "conversations:chat_id");

        assertThat(mapper.parentOf(column)).contains(new AuditResource(ActivityResourceType.Table, "conversations"));
    }

    @Test
    void tableResourceHasNoOwningTable() {
        final AuditResource table = new AuditResource(ActivityResourceType.Table, "conversations");

        assertThat(mapper.parentOf(table)).isEmpty();
    }

    @Test
    void savedQueryResourceHasNoOwningResource() {
        final AuditResource savedQuery = new AuditResource(ActivityResourceType.SavedQuery, "some-query");

        assertThat(mapper.parentOf(savedQuery)).isEmpty();
    }

    @Test
    void pipelineResolvesItsParentThroughItsEffectiveInputTable() {
        given(pipelines.findEffectiveInputTable("sentiment")).willReturn(Optional.of("conversations"));

        final AuditResource resource = new AuditResource(ActivityResourceType.Pipeline, "sentiment");

        assertThat(mapper.parentOf(resource)).contains(new AuditResource(ActivityResourceType.Table, "conversations"));
    }

    @Test
    void pipelineWithNoResolvableInputTableOrNoSuchPipelineResolvesToNoParent() {
        given(pipelines.findEffectiveInputTable("sentiment")).willReturn(Optional.empty());

        final AuditResource resource = new AuditResource(ActivityResourceType.Pipeline, "sentiment");

        assertThat(mapper.parentOf(resource)).isEmpty();
    }

    @Test
    void pipelineWithBlankInputTableResolvesToNoParent() {
        given(pipelines.findEffectiveInputTable("sentiment")).willReturn(Optional.of("  "));

        final AuditResource resource = new AuditResource(ActivityResourceType.Pipeline, "sentiment");

        assertThat(mapper.parentOf(resource)).isEmpty();
    }

    @Test
    void translatesEveryEnversRevisionType() {
        assertThat(mapper.activityType(RevisionType.ADD)).isEqualTo(ActivityType.Create);
        assertThat(mapper.activityType(RevisionType.MOD)).isEqualTo(ActivityType.Update);
        assertThat(mapper.activityType(RevisionType.DEL)).isEqualTo(ActivityType.Delete);
    }
}
