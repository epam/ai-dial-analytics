package com.epam.aidial.analytics.service.audit;

import com.epam.aidial.analytics.exception.RevisionNotFoundException;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableView;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Reads a table as it stood at a past revision.
 *
 * <p>The result carries a {@link TableView} — the very aggregate the live read builds — so the snapshot goes
 * through the same mapper and comes back in the same shape. A client therefore diffs two identical
 * shapes rather than a shape against an audit-specific projection, and a field added to the table API
 * appears in history for free. The one exception is a {@code system} table, whose snapshot has no columns (see
 * {@link #snapshot}).
 */
@Service
@RequiredArgsConstructor
public class AuditSnapshotService {

    private final EntityManager entityManager;

    /**
     * The table {@code name} as of the end of {@code revision}, or 404 when there is nothing to return.
     *
     * <p>A revision that does not exist and a revision at which this table did not exist (before its
     * creation, or at/after its deletion) are the same answer to the caller — no point-in-time state —
     * so both raise {@link RevisionNotFoundException}.
     *
     * <p>A {@code system} table, as the revision records it, is exempt from the creation guard: a migration creates it,
     * and the API never changes its columns, so the trail holds only its definition row. Its view carries {@code null}
     * columns, meaning not tracked, rather than a list the trail cannot vouch for. Its definition is the row last recorded
     * at or before the revision: a migration changes that row outside the trail, so its change shows only from the
     * table's next recorded change.
     */
    @Transactional(readOnly = true)
    public TableSnapshot snapshot(String name, int revision) {
        final AuditReader reader = AuditReaderFactory.get(entityManager);
        AuditSnapshotGuards.requireExistingRevision(reader, revision);
        final TableDefinitionEntity definition = reader.find(TableDefinitionEntity.class, name, revision);
        if (definition == null) {
            throw new RevisionNotFoundException(
                    "table '" + name + "' did not exist at revision " + revision);
        }
        if (!definition.isSystem()) {
            AuditSnapshotGuards.requireCreationWithinTheTrail(
                    reader, TableDefinitionEntity.class, name, revision, "table '" + name + "'");
        }
        final Life life = lifeAt(reader, name, revision);
        TableDefinitionEntity sourceLife = null;
        if (definition.getType() == TableType.ENRICHMENT && definition.getSourceTable() != null) {
            // An ended enrichment life is judged by the source just before that deletion, which rewrote the source's policies.
            if (life.endedAt() != null) {
                sourceLife = reader.find(TableDefinitionEntity.class, definition.getSourceTable(), life.endedAt() - 1);
            }
            if (sourceLife == null) {
                sourceLife = lifeAt(reader, definition.getSourceTable(), revision).state();
            }
        }
        final List<ColumnMappingEntity> columns = definition.isSystem() ? null : columnsAt(reader, name, revision);
        return new TableSnapshot(new TableView(definition, columns), life.state(), sourceLife);
    }

    /**
     * The life of table {@code name} that contains {@code revision}. Its state is the live row while no deletion follows
     * the revision, otherwise the row recorded at the first deletion after it ({@code store_data_at_delete} keeps the
     * whole row), which then wins over a live row of a later life; {@code null} when neither exists. Not the newest row
     * of the name: after a recreation that is another life's.
     *
     * <p>The live row is read first: at read committed, a deletion committed after that read is visible to the trail
     * query that follows, so a concurrent delete or recreation cannot leave no state or the next life's.
     */
    private Life lifeAt(AuditReader reader, String name, int revision) {
        final TableDefinitionEntity live = entityManager.find(TableDefinitionEntity.class, name);
        final List<?> deletions = reader.createQuery()
                .forRevisionsOfEntity(TableDefinitionEntity.class, false, true)
                .add(AuditEntity.id().eq(name))
                .add(AuditEntity.revisionNumber().gt(revision))
                .add(AuditEntity.revisionType().eq(RevisionType.DEL))
                .addOrder(AuditEntity.revisionNumber().asc())
                .setMaxResults(1)
                .getResultList();
        if (deletions.isEmpty()) {
            return new Life(live, null);
        }
        final Object[] deletion = (Object[]) deletions.getFirst();
        return new Life((TableDefinitionEntity) deletion[0], ((AuditRevisionEntity) deletion[1]).getId());
    }

    /**
     * A table's life containing a revision.
     *
     * @param state   its state: the live row, or the row recorded at the deletion that ended it; {@code null} when none
     * @param endedAt the revision of that deletion, or {@code null} while the life runs
     */
    private record Life(TableDefinitionEntity state, Integer endedAt) {}

    /**
     * The table's columns as of {@code revision}, in declared order.
     *
     * <p>{@code forEntitiesAtRevision} resolves each column to its latest audit row at or before the
     * revision and drops the ones whose latest row is a delete — which is exactly why the delete path
     * must issue real deletes rather than leaning on a database cascade: a column removed without a
     * delete row would keep answering here at every future revision.
     *
     * <p>The predicate is {@code AuditEntity.property("tableName")}, not {@code AuditEntity.id()}.
     * {@code ColumnMappingEntity} has a composite {@code @IdClass}, and an id predicate needs the whole
     * id — given a partial one it matches nothing and silently returns an empty schema. Filtering on the
     * id <em>component</em> works because Envers rewrites a property name that matches an id component
     * to {@code originalId.<name>}.
     */
    private List<ColumnMappingEntity> columnsAt(AuditReader reader, String name, int revision) {
        @SuppressWarnings("unchecked")
        final List<ColumnMappingEntity> columns = reader.createQuery()
                .forEntitiesAtRevision(ColumnMappingEntity.class, revision)
                .add(AuditEntity.property("tableName").eq(name))
                .getResultList();
        return columns.stream()
                .sorted(Comparator.comparingInt(ColumnMappingEntity::getOrdinal))
                .toList();
    }
}
