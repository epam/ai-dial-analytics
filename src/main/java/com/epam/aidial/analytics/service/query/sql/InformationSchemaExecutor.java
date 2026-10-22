package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import org.apache.calcite.DataContexts;
import org.apache.calcite.interpreter.Interpreter;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Calc;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a statement whose tables are all {@code INFORMATION_SCHEMA} relations, in process.
 *
 * <p>Executing rather than pattern-matching is the point. The alternative — recognising the handful of
 * statements one connector happens to send — stops being true the moment that connector changes, and
 * it already did: between Dremio connector 3.12.0 and 3.14.0 the catalog filter was dropped from the
 * schema query and {@code IS_NULLABLE} was added to the column query. Handing the plan to Calcite's
 * interpreter gets projection, {@code DISTINCT}, {@code WHERE}, {@code ORDER BY} and
 * {@code LIMIT}/{@code OFFSET} with standard semantics, over rows that are bounded by the catalog and
 * already in memory.
 *
 * <p>What the interpreter does <em>not</em> do is screened before it runs, because the front end applies
 * no planner rules and the interpreter is not total over the plans a validator will accept.
 * Two distinct hazards, and only one of them announces itself: it evaluates a {@code DISTINCT} aggregate
 * as though the {@code DISTINCT} were absent — a plausible wrong number, no error — and it throws raw
 * runtime exceptions for constructs it cannot build at all, which would reach a client as
 * {@code 500 internal_error} / gRPC {@code INTERNAL} rather than as the client error it is.
 */
final class InformationSchemaExecutor {

    /** What a caller can do here, named in the message that rejects anything else. */
    private static final String SUPPORTED_SHAPES =
            "projection, WHERE, DISTINCT, ORDER BY, LIMIT/OFFSET and non-DISTINCT aggregates";

    /** Marks a plan that needs no synthesized row because its own bounds discard it. */
    private static final Object[] SYNTHESIS_NOT_NEEDED = new Object[0];

    private InformationSchemaExecutor() {}

    /**
     * Whether {@code root}'s plan reads catalog relations. Throws when a statement mixes them with a
     * queryable entity: such a statement can only be a join or a set operation, and while both are
     * supported over queryable entities, neither is evaluable here — naming the mix is what keeps the
     * refusal from pointing at the wrong half. A set operation whose branches are <em>all</em> metadata
     * takes this path and is refused by {@link #requireInterpretable} instead, which names the node.
     */
    static boolean isMetadataStatement(RelRoot root) {
        final List<RelOptTable> tables = allTables(root.rel);
        if (tables.isEmpty()) {
            return false;
        }
        final long metadata = tables.stream()
                .filter(table -> InformationSchema.isMetadataSchema(table.getQualifiedName().getFirst()))
                .count();
        if (metadata == 0) {
            return false;
        }
        if (metadata != tables.size()) {
            throw new SqlTranslationException(
                    "a statement cannot combine INFORMATION_SCHEMA with a queryable entity");
        }
        return true;
    }

    /** Evaluates the plan and materializes its rows. */
    static SqlMetadataResult execute(RelRoot root) {
        // project() reorders the plan's output to the validated row type, so column names and values
        // line up without the caller having to consult RelRoot.fields.
        final RelNode plan = root.project();
        requireInterpretable(root.rel);
        requireGlobalAggregateIsAnswerable(plan);
        final List<RelDataTypeField> fields = root.validatedRowType.getFieldList();
        final List<ResultColumn> columns = fields.stream()
                .map(field -> new ResultColumn(field.getName(), kind(field.getType().getSqlTypeName())))
                .toList();
        final List<Map<String, Object>> rows = new ArrayList<>();
        try (Interpreter interpreter = new Interpreter(DataContexts.EMPTY, plan)) {
            for (final Object[] values : interpreter) {
                rows.add(row(columns, values));
            }
        } catch (SqlTranslationException e) {
            throw e;
        } catch (RuntimeException e) {
            // The screen above catches every shape known to defeat the interpreter, but it is an
            // allow-list of plan nodes, not of every expression a validator will accept: MIN over a
            // character column and a reference to CURRENT_DATE both parse, validate, and then fail
            // inside the interpreter. Whatever the cause, the statement is one this surface cannot
            // evaluate — a client error — so it must not escape as an internal one.
            throw new SqlTranslationException(
                    "this statement cannot be evaluated over INFORMATION_SCHEMA; supported: " + SUPPORTED_SHAPES, e);
        }
        if (rows.isEmpty()) {
            final Object[] identity = emptyAggregateRow(plan);
            if (identity != null && identity != SYNTHESIS_NOT_NEEDED) {
                rows.add(row(columns, identity));
            }
        }
        return new SqlMetadataResult(columns, List.copyOf(rows));
    }

    /** One result row: values keyed by column name, missing trailing values read as null. */
    private static Map<String, Object> row(List<ResultColumn> columns, Object[] values) {
        final Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            row.put(columns.get(i).name(), i < values.length ? normalize(values[i], columns.get(i).kind()) : null);
        }
        return row;
    }

    /**
     * Rejects the plans the in-process interpreter cannot evaluate <em>correctly</em>.
     *
     * <p>A {@code DISTINCT} aggregate is the reason this screen exists rather than a bare try/catch.
     * Calcite's {@code AggregateNode} ignores {@link AggregateCall#isDistinct()}, and the rule that would
     * rewrite it away ({@code AggregateExpandDistinctAggregatesRule}) never runs because this front end
     * applies no planner rules. {@code COUNT(DISTINCT TABLE_SCHEMA)} therefore returns the total row
     * count: a number a client cannot tell from the right one. Rejecting is narrower than the surface
     * could be — {@code SELECT DISTINCT} is unaffected and remains the way to ask the question — but a
     * refusal is the only answer here that is not a lie.
     *
     * <p>Everything else is an allow-list of plan nodes and expressions, so a construct the interpreter
     * cannot build (a join between two catalog relations, a subquery, a window function, {@code ROLLUP})
     * is named in a 400 instead of escaping as a 500. The list is a property of the in-process interpreter,
     * not a mirror of the data path: statements over queryable entities accept joins, derived tables,
     * uncorrelated subqueries and set operations, every one of which is refused here, and loosening this
     * screen to "restore parity" would hand the interpreter shapes it cannot evaluate. A set operation
     * over catalog relations reaches this list as a {@code LogicalUnion}/{@code LogicalIntersect}/{@code
     * LogicalMinus} and is named in the 400 like any other unbuildable node.
     */
    private static void requireInterpretable(RelNode rel) {
        if (rel instanceof Aggregate aggregate) {
            requireInterpretableAggregate(aggregate);
        } else if (!(rel instanceof TableScan || rel instanceof Project || rel instanceof Filter
                || rel instanceof Calc || rel instanceof Sort || rel instanceof Values)) {
            throw new SqlTranslationException("SQL construct '" + rel.getRelTypeName()
                    + "' cannot be evaluated over INFORMATION_SCHEMA; supported: " + SUPPORTED_SHAPES);
        }
        requireInterpretableExpressions(rel);
        for (final RelNode input : rel.getInputs()) {
            requireInterpretable(input);
        }
    }

    /**
     * Refuses a global aggregate whose mandatory single row this class cannot produce.
     *
     * <p>Deterministic by design: the check is on the plan's shape, not on whether the input happened to
     * be empty. A statement that returns the right answer for a populated catalog and the wrong one for an
     * empty filter match would be the worst of both — a bug that surfaces only against a deployment whose
     * catalog differs from the one it was tested on.
     */
    private static void requireGlobalAggregateIsAnswerable(RelNode plan) {
        if (containsGlobalAggregate(plan) && emptyAggregateRow(plan) == null) {
            throw new SqlTranslationException("a global aggregate cannot be evaluated over INFORMATION_SCHEMA "
                    + "under HAVING or under an expression computed over the aggregate; select the aggregate "
                    + "itself, or group the statement");
        }
    }

    /** Whether the plan aggregates with no {@code GROUP BY}, which SQL requires to yield exactly one row. */
    private static boolean containsGlobalAggregate(RelNode rel) {
        if (rel instanceof Aggregate aggregate && aggregate.getGroupCount() == 0) {
            return true;
        }
        return rel.getInputs().stream().anyMatch(InformationSchemaExecutor::containsGlobalAggregate);
    }

    /**
     * The row a global aggregate owes an empty input, expressed in {@code rel}'s own output columns —
     * {@link #SYNTHESIS_NOT_NEEDED} when the plan discards that row anyway, {@code null} when this class
     * cannot say.
     *
     * <p>Calcite's {@code AggregateNode} emits nothing for an empty input even when the grouping set is
     * empty, but SQL requires exactly one row there: {@code COUNT} answers 0 and every other aggregate
     * answers null. Without this, {@code SELECT COUNT(*) FROM INFORMATION_SCHEMA."TABLES" WHERE TABLE_NAME
     * = '&lt;typo&gt;'} — the canonical "does this object exist" probe — returns an empty result set instead
     * of 0, and a client doing {@code next(); getLong(1)} reads "no data" rather than "none".
     *
     * <p>Stopping at the first {@link Aggregate} is sound only because {@link #requireInterpretableAggregate}
     * has already refused an aggregate over another aggregate. Otherwise this would answer for the outer
     * aggregate while the inner one silently owed a row of its own.
     */
    private static Object[] emptyAggregateRow(RelNode rel) {
        if (rel instanceof Aggregate aggregate) {
            if (aggregate.getGroupCount() != 0) {
                // Grouped: no input rows means no groups, so returning nothing is already correct.
                return null;
            }
            final List<AggregateCall> calls = aggregate.getAggCallList();
            final Object[] row = new Object[calls.size()];
            for (int i = 0; i < calls.size(); i++) {
                row[i] = calls.get(i).getAggregation().getKind() == SqlKind.COUNT ? 0L : null;
            }
            return row;
        }
        if (rel instanceof Sort sort) {
            return discardsTheOnlyRow(sort) ? SYNTHESIS_NOT_NEEDED : emptyAggregateRow(sort.getInput());
        }
        if (rel instanceof Project project) {
            final Object[] input = emptyAggregateRow(project.getInput());
            if (input == null || input == SYNTHESIS_NOT_NEEDED) {
                return input;
            }
            final Object[] row = new Object[project.getProjects().size()];
            for (int i = 0; i < row.length; i++) {
                // Only a rename or a reordering can be carried through by hand. An expression computed
                // over the aggregate (COUNT(*) + 1) would have to be evaluated, so the shape is refused.
                if (!(project.getProjects().get(i) instanceof RexInputRef ref)) {
                    return null;
                }
                row[i] = input[ref.getIndex()];
            }
            return row;
        }
        // A Filter here is HAVING over a global aggregate: the synthesized row would have to be tested
        // against the predicate, which this class does not evaluate.
        return null;
    }

    /**
     * Whether {@code sort}'s own bounds drop the single row a global aggregate below it would produce — an
     * {@code OFFSET} past it, or a {@code FETCH} of none. Both make the interpreter's empty output already
     * correct. A bound this cannot read as a plain number counts as dropping the row, so an unreadable
     * bound skips synthesis rather than inventing a row the statement may not want.
     */
    private static boolean discardsTheOnlyRow(Sort sort) {
        final Long offset = numericBound(sort.offset);
        if (sort.offset != null && (offset == null || offset > 0)) {
            return true;
        }
        final Long fetch = numericBound(sort.fetch);
        return sort.fetch != null && (fetch == null || fetch < 1);
    }

    /** {@code bound}'s value, or null when it is absent or is not a plain number this can read. */
    private static Long numericBound(RexNode bound) {
        return bound instanceof RexLiteral literal && literal.getValue2() instanceof Number number
                ? number.longValue()
                : null;
    }

    /** Rejects the aggregate shapes the interpreter gets wrong, cannot build, or cannot be reasoned about. */
    private static void requireInterpretableAggregate(Aggregate aggregate) {
        // A grouping-less inner aggregate owes one row even over an empty input, so this aggregate would
        // have to be computed over that row rather than over nothing — and the interpreter, which never
        // produced the inner row, reports neither. Left accepted,
        // SELECT COUNT(n) FROM (SELECT COUNT(*) AS n FROM ... WHERE <no match>) answers 0 where 1 is
        // correct: the wrong-number class this screen exists to prevent, and a shape whose answer would
        // depend on whether the catalog happened to match.
        //
        // The test is deliberately for a *grouping-less* inner aggregate, not for any aggregate. SELECT
        // DISTINCT and a bare GROUP BY also lower to an Aggregate, and neither owes a row: rejecting those
        // would take out COUNT(*) over a DISTINCT subquery — the one way this surface can count distinct
        // values, and the form the DISTINCT-aggregate message above recommends.
        if (containsGlobalAggregate(aggregate.getInput())) {
            throw new SqlTranslationException("an aggregate over a grouping-less aggregate cannot be "
                    + "evaluated over INFORMATION_SCHEMA; group the inner statement, or aggregate it once");
        }
        for (final AggregateCall call : aggregate.getAggCallList()) {
            if (call.isDistinct()) {
                throw new SqlTranslationException("a DISTINCT aggregate cannot be evaluated over "
                        + "INFORMATION_SCHEMA; use SELECT DISTINCT on the column, or COUNT(*) over a "
                        + "SELECT DISTINCT subquery, instead");
            }
        }
        if (aggregate.getGroupSets().size() > 1) {
            throw new SqlTranslationException(
                    "ROLLUP/CUBE/GROUPING SETS cannot be evaluated over INFORMATION_SCHEMA");
        }
    }

    /**
     * Rejects a subquery or a window function in {@code rel}'s own expressions. Neither shows up as a
     * plan node without a planner rule to extract it — an {@code IN} subquery stays a {@code RexSubQuery}
     * inside a filter and {@code ROW_NUMBER() OVER (...)} stays a {@code RexOver} inside a project — and
     * both make the interpreter's expression compiler throw.
     */
    private static void requireInterpretableExpressions(RelNode rel) {
        rel.accept(new RexShuttle() {
            @Override
            public RexNode visitSubQuery(RexSubQuery subQuery) {
                throw new SqlTranslationException(
                        "a subquery cannot be evaluated over INFORMATION_SCHEMA; supported: " + SUPPORTED_SHAPES);
            }

            @Override
            public RexNode visitOver(RexOver over) {
                throw new SqlTranslationException("a window function cannot be evaluated over "
                        + "INFORMATION_SCHEMA; supported: " + SUPPORTED_SHAPES);
            }
        });
    }

    /**
     * Every table the plan reads, including those inside a subquery.
     *
     * <p>{@link RelOptUtil#findAllTables} walks plan inputs only, so a table referenced from a
     * {@code RexSubQuery} is invisible to it. Left at that, a statement mixing a catalog relation with an
     * entity through a subquery would be classified as pure metadata and handed to the interpreter, which
     * fails on the subquery — losing the "cannot combine" diagnosis for exactly the shape that most needs
     * it.
     */
    private static List<RelOptTable> allTables(RelNode rel) {
        final List<RelOptTable> tables = new ArrayList<>(RelOptUtil.findAllTables(rel));
        for (final RelNode nested : subQueryRels(rel)) {
            tables.addAll(allTables(nested));
        }
        return tables;
    }

    /** The plans held by every {@code RexSubQuery} anywhere in {@code rel}'s expressions. */
    private static List<RelNode> subQueryRels(RelNode rel) {
        final List<RelNode> rels = new ArrayList<>();
        final RexShuttle collector = new RexShuttle() {
            @Override
            public RexNode visitSubQuery(RexSubQuery subQuery) {
                rels.add(subQuery.rel);
                return super.visitSubQuery(subQuery);
            }
        };
        collectSubQueries(rel, collector);
        return rels;
    }

    /** Applies {@code collector} to every node's own expressions; {@code accept} does not recurse. */
    private static void collectSubQueries(RelNode rel, RexShuttle collector) {
        rel.accept(collector);
        for (final RelNode input : rel.getInputs()) {
            collectSubQueries(input, collector);
        }
    }

    /**
     * Converts a value out of Calcite's internal representation into the Java type the rest of ADAS
     * carries for that column kind, so a catalog result encodes exactly like a query result.
     *
     * <p>Three representations leak otherwise. A character value is not always {@link String} — a literal
     * that survives into the result arrives as an {@code NlsString}, and a {@code CHAR} comparison can
     * yield one too. A {@code DATE} arrives as an epoch <em>day</em> count and a {@code TIMESTAMP} as
     * epoch millis: left alone the date reaches the Arrow writer as an {@code Integer}, which no coercion
     * there accepts, so it escapes the evaluator's containment and fails mid-stream as gRPC
     * {@code INTERNAL} — and over REST both serialize as bare integers where every other ADAS date and
     * timestamp is an ISO string.
     */
    private static Object normalize(Object value, ResultColumnKind kind) {
        if (value instanceof org.apache.calcite.util.NlsString nls) {
            return nls.getValue();
        }
        if (value instanceof Number number) {
            return switch (kind) {
                case DATE -> LocalDate.ofEpochDay(number.longValue());
                case TIMESTAMP -> LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(number.longValue()), ZoneOffset.UTC);
                default -> value;
            };
        }
        return value;
    }

    /**
     * The result-column family for a validated SQL type. Only the families the catalog relations can
     * produce are mapped; anything else means a projection introduced a type these relations do not
     * carry, which is a translator bug rather than a client error.
     */
    private static ResultColumnKind kind(SqlTypeName type) {
        final ResultColumnKind kind = ResultColumns.sqlKind(type);
        if (kind == null) {
            throw new SqlTranslationException(
                    "catalog metadata column type " + type + " has no result mapping");
        }
        return kind;
    }
}
