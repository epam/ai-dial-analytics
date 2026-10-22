package com.epam.aidial.analytics.service.query;

import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.configuration.QueryBudgetProperties;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.repository.QueryResultPage;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import com.epam.aidial.analytics.service.query.translate.CompositeQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point for executing a {@link StructuredQuery}. Resolves the query's {@code entity} against the
 * catalog (a {@link QueryEntityModel} exists only for a materialized {@code source} table, of either
 * write discipline — a rollup written {@code upsert_by_key} is queryable like any other source), then
 * hands the model to the shared {@link StructuredQueryExecutor}. An entity that is not a queryable
 * source — unknown, an enrichment, or not yet materialized — is rejected with a validation error.
 *
 * <p>This is also where the {@code query.budget.*} resource ceilings are attached (see {@link QueryBudget}).
 * Every caller-supplied query surface funnels through here — {@code POST /v1/queries/execute}, the SQL
 * endpoint {@code POST /v1/queries/execute-sql} (which translates to the same structured query) and the
 * Arrow Flight SQL transport — so all three inherit the same ceilings and the same refusal. The enrichment
 * scan, group-fetch, materialize and aggregate-refresh paths share the executor but not this boundary, so
 * they keep their own bounds.
 */
@Component
@RequiredArgsConstructor
public class StructuredQueryService {

    /**
     * ClickHouse's {@code join_use_nulls} defaults to 0, under which an unmatched side of an outer join reads
     * as each non-nullable column's type default rather than NULL. A caller writing standard SQL expects
     * NULL, so composite statements — the only place a caller writes a join — run with the standard
     * semantics. Single-entity statements keep the default: the enrichment machinery documents and relies on
     * default-valued unmatched enrichment columns.
     */
    private static final String JOIN_USE_NULLS = "join_use_nulls";

    private final CatalogQueryService catalog;
    private final StructuredQueryExecutor executor;
    private final CompositeQueryBuilder compositeBuilder;
    private final DSLContext renderContext;
    private final QueryBudgetProperties budget;

    /** Resolves {@code query.entity()} against the catalog and executes it under the shared row governance. */
    public QueryResultPage execute(StructuredQuery query) {
        return execute(query, StructuredQueryBuilder.MAX_LIMIT);
    }

    /**
     * As {@link #execute(StructuredQuery)} but with {@code maxLimit} as the caller's own row ceiling
     * instead of the shared maximum. Only the Arrow Flight SQL transport passes this, for its
     * {@code flight.max-rows}; every other caller keeps the shared 100/1000 governance.
     */
    public QueryResultPage execute(StructuredQuery query, int maxLimit) {
        final QueryEntityModel model = resolve(query);
        try {
            return executor.execute(model, query, maxLimit, QueryBudget.settings(budget));
        } catch (RuntimeException ex) {
            // A ceiling breach becomes this capability's own 422; everything else rethrows untouched and
            // keeps its existing mapping (a malformed statement stays a 400, an outage stays a 503).
            throw QueryBudget.translate(ex, budget);
        }
    }

    /** Executes a composite SQL plan under the shared row governance. */
    public QueryResultPage execute(CompositeQuery plan) {
        return execute(plan, StructuredQueryBuilder.MAX_LIMIT);
    }

    /**
     * Executes a composite SQL plan — joins, derived tables, subqueries — with {@code maxLimit} as the
     * outermost block's row ceiling. Every entity the plan reads is resolved through the catalog exactly as a
     * single-entity query's is, the same budget settings apply, and the statement additionally runs with
     * standard outer-join NULL semantics ({@link #JOIN_USE_NULLS}).
     *
     * <p>One statement carries one partition-local switch, and the switch affects only the tables it reads
     * {@code FINAL}, so the plan deduplicates inside each partition only when it reads at least one entity
     * final and <em>every</em> entity it reads final is partition-local: the builder reports each final
     * reference as it renders it, and a single cross-partition one keeps the whole statement cross-partition
     * (see {@code FinalSettings}). An entity read raw is not consulted.
     */
    public QueryResultPage execute(CompositeQuery plan, int maxLimit) {
        if (plan == null) {
            throw new QueryValidationException("query must not be null");
        }
        final AtomicBoolean anyFinal = new AtomicBoolean(false);
        final AtomicBoolean partitionLocal = new AtomicBoolean(true);
        final SelectQuery<Record> select = compositeBuilder.build(renderContext, plan, this::resolve, maxLimit, model -> {
            anyFinal.set(true);
            if (!model.partitionLocal()) {
                partitionLocal.set(false);
            }
        });
        final QuerySettings settings = QueryBudget.settings(budget).serverSetting(JOIN_USE_NULLS, "1");
        try {
            return executor.executeComposite(select, settings, anyFinal.get() && partitionLocal.get());
        } catch (RuntimeException ex) {
            throw QueryBudget.translate(ex, budget);
        }
    }

    /**
     * Validates {@code query} exactly as {@link #execute(StructuredQuery)} would before executing it —
     * entity resolution against the catalog plus the full builder pass — and discards the result. No
     * ClickHouse contact, so no budget applies and no {@code query_budget_exceeded} can arise here: the
     * budget is reactive, observable only once a statement runs.
     */
    public void validate(StructuredQuery query) {
        executor.validate(resolve(query), query, StructuredQueryBuilder.MAX_LIMIT);
    }

    /**
     * Validates {@code query} at a chosen visibility bar; {@link Privilege#TABLE_GET} for the translation
     * endpoints, which never execute what they validate.
     */
    public void validate(StructuredQuery query, Privilege bar) {
        if (query == null) {
            throw new QueryValidationException("query must not be null");
        }
        executor.validate(resolve(query.entity(), bar), query, StructuredQueryBuilder.MAX_LIMIT);
    }

    /**
     * As {@link #validate(StructuredQuery)} for a composite plan — joins, derived tables, subqueries.
     * Every entity the plan reads is resolved through the catalog exactly as execution resolves it, and
     * the assembled statement is discarded rather than run.
     */
    public void validate(CompositeQuery plan) {
        validate(plan, Privilege.TABLE_READ);
    }

    /**
     * As {@link #validate(CompositeQuery)} at a chosen visibility bar. Every leaf of the plan is resolved
     * at that bar, so a statement joining two tables the caller can only {@code get} validates while the
     * same statement would report an unknown relation on the execute path.
     */
    public void validate(CompositeQuery plan, Privilege bar) {
        if (plan == null) {
            throw new QueryValidationException("query must not be null");
        }
        compositeBuilder.build(renderContext, plan,
                entity -> resolve(entity, bar), StructuredQueryBuilder.MAX_LIMIT);
    }

    private QueryEntityModel resolve(StructuredQuery query) {
        if (query == null) {
            throw new QueryValidationException("query must not be null");
        }
        return resolve(query.entity());
    }

    private QueryEntityModel resolve(String entity) {
        return resolve(entity, Privilege.TABLE_READ);
    }

    /**
     * Resolves {@code entity} at a chosen visibility bar. An entity the caller does not clear the bar for is
     * reported as <b>not queryable</b> — the same answer a non-existent entity gets, so the refusal does not
     * confirm that the table exists. This is the non-disclosure rule for the query surface: unknown entity,
     * never {@code 403}.
     */
    private QueryEntityModel resolve(String entity, Privilege bar) {
        return catalog.findModel(entity, bar)
                .orElseThrow(() -> new QueryValidationException("entity '" + entity
                        + "' is not queryable; supported entities: " + catalog.queryableEntityNames(bar)));
    }
}
