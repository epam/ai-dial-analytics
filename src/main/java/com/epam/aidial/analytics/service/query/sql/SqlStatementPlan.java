package com.epam.aidial.analytics.service.query.sql;

/**
 * What the SQL front end made of one statement. A single-block statement against a queryable entity
 * becomes a {@link SqlTranslation} — a {@code StructuredQuery} to run through the shared pipeline. A
 * composite statement (joins, derived tables, subqueries) becomes a {@link SqlCompositeTranslation} — a
 * plan of such blocks, run through the same pipeline but with no DSL document. A statement against
 * {@code INFORMATION_SCHEMA} becomes a {@link SqlMetadataResult}: already-evaluated rows, because the
 * catalog lives in ADAS rather than in ClickHouse and there is no entity to translate against.
 *
 * <p>The split is a sealed type rather than a flag so that every consumer has to say what it does with
 * each case. They differ in more than provenance: a metadata result is exempt from the row cap, the
 * page limit and the Flight concurrency gate, all of which govern ClickHouse execution; the two executable
 * kinds share those through {@link SqlExecutableStatement}.
 */
public sealed interface SqlStatementPlan permits SqlExecutableStatement, SqlMetadataResult {}
