package com.epam.aidial.analytics.service.audit;

import com.epam.aidial.analytics.exception.RevisionNotFoundException;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableView;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Reads a table as it stood at a past revision.
 *
 * <p>The result is a {@link TableView} — the very aggregate the live read builds — so the snapshot goes
 * through the same mapper and comes back in the same shape. A client therefore diffs two identical
 * shapes rather than a shape against an audit-specific projection, and a field added to the table API
 * appears in history for free.
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
     */
    @Transactional(readOnly = true)
    public TableView snapshot(String name, int revision) {
        final AuditReader reader = AuditReaderFactory.get(entityManager);
        AuditSnapshotGuards.requireExistingRevision(reader, revision);
        AuditSnapshotGuards.requireCreationWithinTheTrail(
                reader, TableDefinitionEntity.class, name, revision, "table '" + name + "'");
        final TableDefinitionEntity definition = reader.find(TableDefinitionEntity.class, name, revision);
        if (definition == null) {
            throw new RevisionNotFoundException(
                    "table '" + name + "' did not exist at revision " + revision);
        }
        return new TableView(definition, columnsAt(reader, name, revision));
    }

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
