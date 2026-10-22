package com.epam.aidial.analytics.service.audit;

import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * Unit coverage for {@link AuditRevisionListener}: author capture and its fallbacks, deduplication
 * within a revision, and parent linking in both arrival orders.
 *
 * <p>Envers is not involved here — the listener's contract is a pair of callbacks over a revision
 * entity, so the tests call them directly. That is what makes the ordering cases testable at all: Envers
 * guarantees no order between the changes it reports, so both orders have to be exercised deliberately
 * rather than hoped for.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditRevisionListenerTest {

    private static final String TABLE = "conversations";

    @Mock
    private SecurityClaimsExtractor claims;

    @Mock
    private PipelineRepository pipelines;

    private final AuditImportScope importScope = new AuditImportScope();
    private AuditRevisionListener listener;
    private AuditRevisionEntity revision;

    @BeforeEach
    void setUp() {
        listener = new AuditRevisionListener(claims, new AuditResourceMapper(pipelines), importScope);
        revision = new AuditRevisionEntity();
        revision.setTimestamp(1_700_000_000_000L);
    }

    @Test
    void recordsTheAuthenticatedPrincipalAndEmail() {
        given(claims.isAuthenticated()).willReturn(true);
        given(claims.getAuthor()).willReturn("alice");
        given(claims.getEmail()).willReturn("alice@example.com");

        listener.newRevision(revision);

        assertThat(revision.getAuthor()).isEqualTo("alice");
        assertThat(revision.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void recordsUnknownWhenThereIsNoAuthentication() {
        given(claims.isAuthenticated()).willReturn(false);
        given(claims.getAuthor()).willReturn(null);
        given(claims.getEmail()).willReturn(null);

        listener.newRevision(revision);

        assertThat(revision.getAuthor()).isEqualTo("unknown");
        assertThat(revision.getEmail()).isNull();
    }

    @Test
    void anonymousTokenIsNotIdentity() {
        // Spring Security's anonymous filter names every unauthenticated caller "anonymousUser". Recording
        // that would be inventing an author, so the trail says `unknown` instead.
        given(claims.isAuthenticated()).willReturn(false);
        given(claims.getAuthor()).willReturn("anonymousUser");

        listener.newRevision(revision);

        assertThat(revision.getAuthor()).isEqualTo("unknown");
        assertThat(revision.getEmail()).isNull();
    }

    @Test
    void doesNotAssembleAnIdentityFromAnEmailAlone() {
        given(claims.isAuthenticated()).willReturn(true);
        given(claims.getAuthor()).willReturn(null);
        given(claims.getEmail()).willReturn("someone@example.com");

        listener.newRevision(revision);

        assertThat(revision.getAuthor()).isEqualTo("unknown");
        assertThat(revision.getEmail()).isNull();
    }

    @Test
    void anIdentityFailureDegradesTheAuthorRatherThanFailingTheCaller() {
        given(claims.isAuthenticated()).willReturn(true);
        given(claims.getAuthor()).willThrow(new IllegalStateException("token store unavailable"));

        listener.newRevision(revision);

        assertThat(revision.getAuthor()).isEqualTo("unknown");
        assertThat(revision.getEmail()).isNull();
    }

    @Test
    void anOpenImportScopeTagsTheRevisionAndEveryActivity() {
        final UUID importId = UUID.randomUUID();
        try (AuditImportScope.Scope ignored = importScope.open(importId)) {
            listener.newRevision(revision);
        }
        changed(TableDefinitionEntity.class, TABLE, RevisionType.MOD);
        columnChanged("chat_id", RevisionType.ADD);

        assertThat(revision.getImportId()).isEqualTo(importId);
        assertThat(revision.getActivities()).hasSize(2)
                .allSatisfy(activity -> assertThat(activity.getImportId()).isEqualTo(importId));
        assertThat(childOf(TABLE + ":chat_id").getParentActivityId())
                .isEqualTo(activityOf(TABLE).getActivityId());
    }

    @Test
    void noImportScopeLeavesTheRevisionAndActivitiesUntagged() {
        listener.newRevision(revision);
        changed(TableDefinitionEntity.class, TABLE, RevisionType.ADD);

        assertThat(revision.getImportId()).isNull();
        assertThat(revision.getActivities().getFirst().getImportId()).isNull();
    }

    @Test
    void closingTheImportScopeClearsIt() {
        importScope.open(UUID.randomUUID()).close();

        assertThat(importScope.current()).isEmpty();
        listener.newRevision(revision);
        assertThat(revision.getImportId()).isNull();
    }

    @Test
    void anImportScopeIsInvisibleToAnotherThread() throws Exception {
        try (AuditImportScope.Scope ignored = importScope.open(UUID.randomUUID())) {
            final AuditRevisionEntity concurrent = new AuditRevisionEntity();
            final Thread other = new Thread(() -> listener.newRevision(concurrent));
            other.start();
            other.join();
            assertThat(concurrent.getImportId()).isNull();
        }
    }

    @Test
    void importScopesDoNotNest() {
        final UUID outer = UUID.randomUUID();
        try (AuditImportScope.Scope ignored = importScope.open(outer)) {
            assertThatThrownBy(() -> importScope.open(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
            assertThat(importScope.current()).contains(outer);
        }
    }

    @Test
    void collapsesRepeatedUpdatesOfOneEntity() {
        changed(TableDefinitionEntity.class, TABLE, RevisionType.MOD);
        changed(TableDefinitionEntity.class, TABLE, RevisionType.MOD);

        assertThat(revision.getActivities()).hasSize(1);
        assertThat(revision.getActivities().getFirst().getActivityType()).isEqualTo(ActivityType.Update);
    }

    @Test
    void createTakesPrecedenceOverLaterUpdate() {
        changed(TableDefinitionEntity.class, TABLE, RevisionType.ADD);
        changed(TableDefinitionEntity.class, TABLE, RevisionType.MOD);

        assertThat(revision.getActivities()).hasSize(1);
        assertThat(revision.getActivities().getFirst().getActivityType()).isEqualTo(ActivityType.Create);
    }

    @Test
    void deleteReplacesAnAlreadyRecordedUpdate() {
        changed(TableDefinitionEntity.class, TABLE, RevisionType.MOD);
        changed(TableDefinitionEntity.class, TABLE, RevisionType.DEL);

        assertThat(revision.getActivities()).hasSize(1);
        assertThat(revision.getActivities().getFirst().getActivityType()).isEqualTo(ActivityType.Delete);
    }

    @Test
    void distinctEntitiesInOneRevisionStayDistinct() {
        changed(TableDefinitionEntity.class, TABLE, RevisionType.MOD);
        columnChanged("chat_id", RevisionType.ADD);
        columnChanged("score", RevisionType.ADD);

        assertThat(revision.getActivities()).hasSize(3);
        assertThat(revision.getActivities()).extracting(AuditActivityEntity::getResourceId)
                .containsExactlyInAnyOrder(TABLE, TABLE + ":chat_id", TABLE + ":score");
    }

    @Test
    void childRecordedAfterItsTableAdoptsThatTablesActivity() {
        changed(TableDefinitionEntity.class, TABLE, RevisionType.MOD);
        columnChanged("chat_id", RevisionType.ADD);

        assertThat(childOf(TABLE + ":chat_id").getParentActivityId())
                .isEqualTo(activityOf(TABLE).getActivityId());
    }

    @Test
    void tableRecordedAfterItsChildrenBackFillsTheirParent() {
        columnChanged("chat_id", RevisionType.ADD);
        columnChanged("score", RevisionType.ADD);
        changed(TableDefinitionEntity.class, TABLE, RevisionType.MOD);

        final AuditActivityEntity table = activityOf(TABLE);
        assertThat(childOf(TABLE + ":chat_id").getParentActivityId()).isEqualTo(table.getActivityId());
        assertThat(childOf(TABLE + ":score").getParentActivityId()).isEqualTo(table.getActivityId());
    }

    @Test
    void columnChangedWithoutItsTableHasNoParent() {
        columnChanged("chat_id", RevisionType.MOD);

        assertThat(childOf(TABLE + ":chat_id").getParentActivityId()).isNull();
    }

    @Test
    void childIsNotAdoptedByUnrelatedTable() {
        changed(TableDefinitionEntity.class, "other_table", RevisionType.MOD);
        columnChanged("chat_id", RevisionType.ADD);

        assertThat(childOf(TABLE + ":chat_id").getParentActivityId()).isNull();
    }

    @Test
    void tableActivityNeverHasParent() {
        changed(TableDefinitionEntity.class, TABLE, RevisionType.ADD);

        assertThat(activityOf(TABLE).getParentActivityId()).isNull();
    }

    @Test
    void bulkBumpedPipelineLinksToItsSourceTablesActivity() {
        given(pipelines.findEffectiveInputTable("sentiment")).willReturn(Optional.of(TABLE));

        changed(TableDefinitionEntity.class, TABLE, RevisionType.MOD);
        changed(PipelineEntity.class, "sentiment", RevisionType.MOD);

        assertThat(pipelineOf("sentiment").getParentActivityId()).isEqualTo(activityOf(TABLE).getActivityId());
    }

    /**
     * The other arrival order, which Envers gives no guarantee about: the pipeline is reported first and
     * the table's own activity back-fills the link when it arrives.
     */
    @Test
    void bulkBumpedPipelineLinksInEitherArrivalOrder() {
        given(pipelines.findEffectiveInputTable("sentiment")).willReturn(Optional.of(TABLE));

        changed(PipelineEntity.class, "sentiment", RevisionType.MOD);
        changed(TableDefinitionEntity.class, TABLE, RevisionType.MOD);

        assertThat(pipelineOf("sentiment").getParentActivityId()).isEqualTo(activityOf(TABLE).getActivityId());
    }

    @Test
    void directlyMutatedPipelineHasNoParent() {
        given(pipelines.findEffectiveInputTable("sentiment")).willReturn(Optional.of(TABLE));

        changed(PipelineEntity.class, "sentiment", RevisionType.ADD);

        assertThat(pipelineOf("sentiment").getParentActivityId()).isNull();
    }

    @Test
    void anUnauditedEntityProducesNoActivity() {
        listener.entityChanged(String.class, "java.lang.String", "anything", RevisionType.ADD, revision);

        assertThat(revision.getActivities()).isEmpty();
    }

    @Test
    void everyActivityCarriesTheRevisionsAuthorAndTimestamp() {
        revision.setAuthor("alice");
        revision.setEmail("alice@example.com");

        changed(TableDefinitionEntity.class, TABLE, RevisionType.ADD);

        final AuditActivityEntity activity = activityOf(TABLE);
        assertThat(activity.getInitiatedAuthor()).isEqualTo("alice");
        assertThat(activity.getInitiatedEmail()).isEqualTo("alice@example.com");
        assertThat(activity.getEpochTimestampMs()).isEqualTo(1_700_000_000_000L);
        assertThat(activity.getRevision()).isSameAs(revision);
    }

    private void changed(Class<?> entityClass, Object id, RevisionType type) {
        listener.entityChanged(entityClass, entityClass.getName(), id, type, revision);
    }

    private void columnChanged(String sourceName, RevisionType type) {
        changed(ColumnMappingEntity.class, new ColumnMappingId(TABLE, sourceName), type);
    }

    private AuditActivityEntity activityOf(String resourceId) {
        return find(resourceId, ActivityResourceType.Table);
    }

    private AuditActivityEntity childOf(String resourceId) {
        return find(resourceId, ActivityResourceType.TableColumn);
    }

    private AuditActivityEntity pipelineOf(String resourceId) {
        return find(resourceId, ActivityResourceType.Pipeline);
    }

    private AuditActivityEntity find(String resourceId, ActivityResourceType type) {
        final List<AuditActivityEntity> matches = revision.getActivities().stream()
                .filter(activity -> activity.getResourceType() == type
                        && activity.getResourceId().equals(resourceId))
                .toList();
        assertThat(matches).as("activity for %s %s", type, resourceId).hasSize(1);
        return matches.getFirst();
    }
}
