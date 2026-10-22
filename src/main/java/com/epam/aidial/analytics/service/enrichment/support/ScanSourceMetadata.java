package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.PartitionLocality;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * How a pipeline-scoped read sees its source: its scan {@link CursorColumns} from the catalog definition
 * ({@code table_definition.version_column} / {@code identity_column}), and its query model — the source
 * plus its ACTIVE enrichments, joined on demand. Shared by the row scan, group-input assembly, and the
 * Class-1 materialize path, none of which may resolve a source differently.
 *
 * <p>Resolving the cursor columns from the catalog replaced a hardcoded
 * {@code if ("dial_usage_log".equals(source))} branch, so declaring a source scannable is a catalog fact
 * rather than a code change.
 *
 * <p>The two columns are <b>caller-declared</b> and optional: a source MAY declare both at
 * {@code POST /v1/tables/{name}/schema}, which is what makes a source created through the API scannable
 * without a migration, alongside the values the seeded sources get from theirs. The table API checks what
 * it can see of a declared name — it is a column of that table, non-nullable, not sensitive, and a version
 * column is a {@code timestamp} (or, on an {@code upsert_by_key} table, the engine version
 * {@code _updated_at}). The exactly-once guarantee additionally rests on {@code version_column} being
 * assigned at ingest, monotonic and never backdated, and on {@code identity_column} being unique per row —
 * physical facts about ingest that no layer can verify and that remain the declaring caller's promise.
 *
 * <p>A definition missing either column is not scannable and is rejected below. Declaring the pair is
 * deliberately not forced: a table whose rows have no single identifying column — a rollup keyed on several
 * columns, whose identity is its whole {@code ordering_key} — would otherwise have to name one anyway, and
 * a repeated cursor tuple makes keyset paging skip rows instead of reporting anything.
 */
@Component
@RequiredArgsConstructor
public class ScanSourceMetadata {

    private final TableDefinitionRepository definitions;
    private final CatalogQueryService catalog;

    /**
     * The source's scan cursor columns.
     *
     * @throws QueryValidationException (422) if the source is unknown or has no scan metadata configured
     */
    public CursorColumns cursorColumns(String source) {
        TableDefinitionEntity definition = definitions.findById(source)
                .orElseThrow(() -> new QueryValidationException("source '" + source + "' is not a known table"));
        String version = definition.getVersionColumn();
        String identity = definition.getIdentityColumn();
        if (StringUtils.isBlank(version) || StringUtils.isBlank(identity)) {
            throw new QueryValidationException("source '" + source
                    + "' has no scan metadata configured (version_column/identity_column);"
                    + " incremental scan requires a declared total order for the source");
        }
        return new CursorColumns(version, identity, definition.getName(), floorPartitionColumn(definition, version));
    }

    /**
     * The partition column {@link CursorPredicates} may floor a lower bound on, or null when the floor would
     * not be exact.
     *
     * <p>The floor prunes whole parts <em>before</em> {@code FINAL} collapses them, so it is exact only when
     * pruning a row whose version is below the bound cannot hand a key's win to a row whose version is above
     * it. That holds when the source is partition-local (it collapses nothing, or its partition column is in
     * its key, so every version of a key shares one partition value and is pruned or kept together), or when
     * the cursor's version column is the engine's own collapse version, so a pruned row could never have won.
     * The remaining shape — an {@code upsert_by_key} source partitioned outside its key whose cursor orders
     * by some other timestamp — could resurrect a superseded row, so it gets no floor.
     */
    private static String floorPartitionColumn(TableDefinitionEntity definition, String version) {
        final String partition = StringUtils.trimToNull(definition.getPartitionColumn());
        if (partition == null) {
            return null;
        }
        final boolean collapsesOnCursorVersion = definition.effectiveWriteMode() == TableWriteMode.UPSERT_BY_KEY
                && TableDdlRenderer.UPDATED_AT.equals(version);
        return PartitionLocality.isPartitionLocal(definition, null) || collapsesOnCursorVersion ? partition : null;
    }

    /**
     * The source's physical ordering key — its {@code ORDER BY} tuple — in declaration order, or an empty
     * list when it declares none.
     *
     * <p>Exposed for the group assembly's projection phase, which addresses a member by its value for
     * <b>every</b> column of this tuple. That is what lets the database prune the read to the storage
     * granules holding those members: the ordering key is the source's primary index, so a predicate over
     * all of it is a point lookup. A predicate over any other column — the source's identity column
     * included — prunes nothing and would leave the read proportional to the whole group, which is the
     * cost this exists to avoid. (The assembly still carries the identity column <em>alongside</em> the
     * tuple, to tell apart two members of a source whose ordering key repeats; it is the pruning that this
     * tuple is needed for.)
     *
     * <p>An empty result is not an error: a source may legitimately declare no ordering key, and the caller
     * is expected to fall back to a projection it can satisfy without point lookups.
     */
    public List<String> orderingKey(String source) {
        return definitions.findById(source)
                .map(TableDefinitionEntity::getOrderingKey)
                .filter(key -> key != null && !key.isEmpty())
                .map(List::copyOf)
                .orElseGet(List::of);
    }

    /**
     * The source's full query model, enrichment joins intact — the same model the query façade resolves
     * against, so a caller that references an enrichment column by its qualified flat name can emit the
     * grain join for it.
     *
     * <p>The one model every pipeline-scoped read resolves against. It replaced a narrower one that stripped
     * the enrichment joins, on the reasoning that handing them to the enrich paths would put names into scope
     * a stored pipeline's governed SQL could resolve against. That reasoning did not survive contact with the
     * two facts that make the widening monotonic: an enrichment column is published <em>only</em> under its
     * qualified flat name, so it can never shadow a source column and no stored pipeline's meaning can change;
     * and {@link com.epam.aidial.analytics.service.query.EnrichmentJoins#addReferenced} emits a join only
     * for an enrichment a statement actually names, so a pipeline referencing none renders byte-identical SQL.
     *
     * @throws QueryValidationException (422) if the source is not a queryable entity
     */
    public QueryEntityModel entityModel(String source) {
        return catalog.findModel(source, Privilege.TABLE_READ)
                .orElseThrow(() -> new QueryValidationException("source '" + source + "' is not a queryable entity"));
    }

    /**
     * The field to project for {@code name}: the model's binding when it has one, else the raw qualified
     * column. The fallback matters because the model is role-filtered — a sensitive column that an
     * entitled caller may read is absent from {@code bindings} — which is also why every caller of this
     * runs {@link SensitiveProjectionGuard#enforce} over its projection first.
     */
    public static Field<?> projected(QueryEntityModel model, Map<String, QueryFieldBinding> bindings, String name) {
        QueryFieldBinding binding = bindings.get(name);
        if (binding != null) {
            return binding.field();
        }
        if (name != null && name.indexOf('.') > 0) {
            // The fallback is for a column of the base table the role-filtered model omitted. A qualified
            // name is an enrichment field, which has no base-table spelling — rendering it would emit
            // "<source>"."<enrichment>.<column>", a column no FROM clause carries, and the read would fail
            // at execution with a message pointing nowhere near the cause. Every caller runs the sensitive
            // guard over its projection first, so an entitled caller never reaches this.
            throw new QueryValidationException("column '" + name + "' is not resolvable on entity '"
                    + model.entity() + "'; an enrichment column is projected through its grain join, so it"
                    + " has no unqualified reference on the source");
        }
        return DSL.field(DSL.name(model.entity(), name));
    }
}
