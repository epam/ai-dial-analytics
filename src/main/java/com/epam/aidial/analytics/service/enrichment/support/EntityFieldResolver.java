package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the flat field set a declaration is resolved against: one table's own columns, or a table's
 * whole <b>entity</b> — itself plus every ACTIVE enrichment reached through its grain join.
 *
 * <p>One component rather than a builder per capability, because membership has to be decided once. The
 * aggregate pipeline's gate and the enrich declaration's gate both resolve a declaration this way, and an
 * enrichment that counted for one but not the other would let a declaration name a column the compiled
 * statement never joins — a failure at execution rather than at declaration.
 */
@Component
@RequiredArgsConstructor
public class EntityFieldResolver {

    private final ColumnMappingRepository columns;
    private final CatalogQueryService catalog;

    /**
     * The table's flat <em>entity</em> field set: its own declared columns under their exposed names, plus
     * every ACTIVE enrichment's columns under the qualified flat name {@code "<enrichment>.<column>"}.
     *
     * <p>This is the same namespace {@code GET /v1/queries/entities/{name}} publishes and the query DSL
     * resolves, so a declaration and a query over the same entity name a column identically. Membership
     * comes from {@link CatalogQueryService#activeEnrichmentsOf} rather than being decided again here, so
     * validation cannot admit a column the compiled statement would not join.
     *
     * <p><b>Loaded from the catalog rows directly, not from the query model.</b> The model carries no
     * nullability or sensitivity — the two flags the checks built on this turn on — and it is role-filtered,
     * which would turn a non-entitled caller's reference to a sensitive enrichment column into an
     * unknown-field 422 instead of the 403 it is. Reading the raw rows keeps an enrichment column governed
     * exactly as a base column is, with {@link SensitiveProjectionGuard} producing the refusal.
     */
    public EntityFields entityOf(String table) {
        final Map<String, EntityFields.Field> byName = new LinkedHashMap<>();
        for (final ColumnMappingEntity column : columns.findByTableNameOrderByOrdinal(table)) {
            byName.put(column.getName(), new EntityFields.Field(table, column, false));
        }
        for (final TableDefinitionEntity enrichment : catalog.activeEnrichmentsOf(table)) {
            final String owner = enrichment.getName();
            for (final ColumnMappingEntity column : columns.findByTableNameOrderByOrdinal(owner)) {
                // Qualified so a base table and its enrichments cannot collide on a bare column name,
                // exactly as the query model qualifies them. putIfAbsent is deliberate: a well-formed
                // catalog makes a clash impossible, and silently overwriting would hide a corrupt one.
                byName.putIfAbsent(owner + "." + column.getName(),
                        new EntityFields.Field(owner, column, true));
            }
        }
        return new EntityFields(byName);
    }

    /**
     * The table's own declared columns, indexed by {@code key} — no enrichments. For a target table, whose
     * columns a declaration binds to rather than reads through a join.
     */
    public EntityFields tableOf(String table, Function<ColumnMappingEntity, String> key) {
        final Map<String, EntityFields.Field> byName = new LinkedHashMap<>();
        for (final ColumnMappingEntity column : columns.findByTableNameOrderByOrdinal(table)) {
            byName.put(key.apply(column), new EntityFields.Field(table, column, false));
        }
        return new EntityFields(byName);
    }

    /**
     * An ENRICH declaration's resolution namespace: the read source's columns under <b>either</b>
     * spelling, plus every
     * ACTIVE enrichment's columns under the qualified flat name.
     *
     * <p>Differs from {@link #entityOf} only in admitting a base column's physical spelling, which the
     * enrich gate has always accepted ({@code findColumn} matches {@code name} or {@code source_name}) and
     * which the aggregate surface does not. An enrichment column is published qualified by its
     * <em>logical</em> name alone, exactly as the query model publishes it — the physical spelling of an
     * enrichment column is not part of the entity namespace, so admitting it would let a declaration name a
     * field the compiled statement does not project.
     */
    public EntityFields enrichEntityOf(String source) {
        final Map<String, EntityFields.Field> byName = new LinkedHashMap<>();
        for (final ColumnMappingEntity column : columns.findByTableNameOrderByOrdinal(source)) {
            final EntityFields.Field field = new EntityFields.Field(source, column, false);
            byName.putIfAbsent(column.getName(), field);
            byName.putIfAbsent(column.getSourceName(), field);
        }
        for (final TableDefinitionEntity enrichment : catalog.activeEnrichmentsOf(source)) {
            final String owner = enrichment.getName();
            for (final ColumnMappingEntity column : columns.findByTableNameOrderByOrdinal(owner)) {
                byName.putIfAbsent(owner + "." + column.getName(),
                        new EntityFields.Field(owner, column, true));
            }
        }
        return new EntityFields(byName);
    }
}
