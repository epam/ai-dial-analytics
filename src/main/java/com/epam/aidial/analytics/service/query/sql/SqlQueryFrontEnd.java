package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.observability.SqlLog;
import com.epam.aidial.analytics.service.query.model.SetOpKind;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.hint.HintPredicates;
import org.apache.calcite.rel.hint.HintStrategyTable;
import org.apache.calcite.rel.type.DelegatingTypeSystem;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.sql.validate.SqlDelegatingConformance;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.implicit.TypeCoercion;
import org.apache.calcite.sql.validate.implicit.TypeCoercionImpl;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * The SQL front end for the SQL surfaces: screens the statement (exactly one read-only SELECT, optionally
 * under a non-recursive WITH), parses it with Calcite's Babel parser, expands its stars to the entity's wildcard fields
 * ({@link SqlStarExpander}), names its unnamed select items
 * ({@link SqlSelectAliasInjector}), validates it case-insensitively against the catalog-derived schema
 * ({@link CalciteQueryCatalog}), lowers it to a {@code RelNode} with no optimization rules,
 * and translates the plan into a {@link SqlTranslation} (one block over one entity) or a
 * {@link SqlCompositeTranslation} (joins, derived tables, CTEs, subqueries). Every failure — parse,
 * validation, or an inexpressible construct — surfaces as {@link SqlTranslationException} with a message
 * that names the problem.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlQueryFrontEnd {

    /**
     * The literal Calcite writes between a parse failure's diagnosis and its expectation set. Everything
     * before it locates the mistake; everything after it is the grammar, which no caller can act on.
     */
    private static final String EXPECTATION_MARKER = "Was expecting one of:";

    /** The refusal of a window function in an aggregate SQL declaration, shared with the pipeline gate. */
    public static final String WINDOW_REFUSAL = "a window function is not supported in an aggregate SQL declaration; "
            + "its value would depend on which groups a refresh happened to recompute";

    /**
     * Babel conformance minus its non-strict {@code GROUP BY}. Reaches the validator through
     * {@link #CONNECTION_CONFIG}: {@code PlannerImpl} takes the validator's conformance from its connection
     * config, whatever the validator config says, and the parser config can only carry an enum.
     *
     * <p>Under non-strict grouping Calcite wraps every non-grouped, non-aggregated select item in
     * {@code ANY_VALUE} instead of rejecting it — PostgreSQL rejects it, and so should a PostgreSQL-shaped
     * surface — and the check that decides which items to wrap walks a searched {@code CASE}'s operand list,
     * whose first operand is null, and fails with a {@code NullPointerException} on every
     * {@code CASE … END AS k … GROUP BY k}. Strict grouping is both the standard behaviour and the way around
     * that defect. Everything else Babel allows (grouping by an alias, the lenient function set) stays.
     */
    private static final SqlConformance CONFORMANCE = new SqlDelegatingConformance(SqlConformanceEnum.BABEL) {
        @Override
        public boolean isNonStrictGroupBy() {
            return false;
        }
    };

    /**
     * The connection config the planner reads the validator conformance from. Both properties the planner
     * would otherwise copy from the parser config are pre-set, so it keeps this instance — and with it the
     * {@link #CONFORMANCE} override — rather than rebuilding a plain one.
     */
    private static final CalciteConnectionConfigImpl CONNECTION_CONFIG = connectionConfig();

    /**
     * Calcite's default type system with ragged character types unified as <em>varying</em>. By default the
     * least restrictive type of {@code CHAR(5)} and {@code CHAR(6)} is {@code CHAR(6)}, and the validator then
     * pads the shorter literal — {@code CASE … THEN 'large' … ELSE 'small' END} would answer {@code 'small '},
     * and {@code IN ('gpt', 'claude')} would compare against {@code 'gpt   '}. ClickHouse has no padded
     * character type and PostgreSQL treats literals as text, so the unified type is {@code VARCHAR} and every
     * literal keeps its own length.
     */
    private static final RelDataTypeSystem TYPE_SYSTEM = new DelegatingTypeSystem(RelDataTypeSystem.DEFAULT) {
        @Override
        public boolean shouldConvertRaggedUnionTypesToVarying() {
            return true;
        }
    };

    /**
     * Package-visible so {@link SqlIdentifierProbe} parses with the <em>same</em> grammar and casing this
     * front end validates with. A probe on a differently-configured parser would disagree about which
     * statements parse at all, and its findings would not describe the statement execution sees.
     */
    static final SqlParser.Config PARSER_CONFIG = SqlParser.config()
            .withParserFactory(SqlBabelParserImpl.FACTORY)
            .withConformance(SqlConformanceEnum.BABEL)
            .withCaseSensitive(false)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED);

    /** The one hint the surface accepts: {@link SqlFinalModifier#HINT}, on a table scan. */
    private static final HintStrategyTable FINAL_HINT_STRATEGY = HintStrategyTable.builder()
            .hintStrategy(SqlFinalModifier.HINT, HintPredicates.TABLE_SCAN)
            .build();

    private final CalciteQueryCatalog catalog;

    /**
     * Translates one SQL statement into the DSL query + result schema, without executing it. A
     * statement against {@code INFORMATION_SCHEMA} has no DSL equivalent and is rejected here rather
     * than returned in another shape: the enrichment validators build their own SQL and call this,
     * and must never receive catalog rows in place of a translation. A composite statement is rejected on
     * the same terms: it executes through {@link #plan}, but the DSL cannot carry it.
     */
    public SqlTranslation translate(String sql) {
        return translate(sql, Privilege.TABLE_READ);
    }

    /**
     * As {@link #translate(String)} at a chosen catalog visibility bar. The translation and validation
     * endpoints pass {@link Privilege#TABLE_GET}.
     */
    public SqlTranslation translate(String sql, Privilege bar) {
        return switch (plan(sql, bar)) {
            case SqlTranslation translation -> translation;
            // Executes, but has no DSL document: a join, a derived table or a subquery is more than one block.
            case SqlCompositeTranslation ignored -> throw new SqlTranslationException(
                    RelToStructuredQueryTranslator.NO_DSL_EQUIVALENT);
            case SqlMetadataResult ignored -> throw new SqlTranslationException(
                    "catalog metadata statements have no structured-query equivalent");
        };
    }

    /**
     * Every entity a composite plan reads from, directly or through a derived table, join, or subquery —
     * the multi-entity counterpart of {@link SqlTranslation#query()}{@code .entity()} for a statement
     * that has no single entity. Exposed here, rather than as a public type in this package, because a
     * caller outside it ({@code SavedQueryBodyResolver}) has no other reason to see the internal
     * composite-plan model at all — it is never on the wire.
     */
    public Set<String> entitiesOf(SqlCompositeTranslation composite) {
        return CompositeQueryEntities.leafEntities(composite.query());
    }

    /**
     * Plans one SQL statement: a {@link SqlTranslation} for a single-block statement against a queryable
     * entity, a {@link SqlCompositeTranslation} for a statement that joins, nests or carries a subquery, an
     * already-evaluated {@link SqlMetadataResult} for one against {@code INFORMATION_SCHEMA}.
     */
    public SqlStatementPlan plan(String rawSql) {
        return plan(rawSql, Privilege.TABLE_READ);
    }

    /**
     * As {@link #plan(String)} at a chosen catalog visibility bar — {@link Privilege#TABLE_READ} for a
     * statement that will execute, {@link Privilege#TABLE_GET} for one that is only translated or validated.
     */
    public SqlStatementPlan plan(String rawSql, Privilege bar) {
        // FINAL is rewritten into a table hint before anything parses the text: Babel has no FINAL modifier
        // and reads `FROM t FINAL` as an alias (see SqlFinalModifier).
        final String sql = SqlFinalModifier.rewrite(stripTrailingSeparator(rawSql));
        screen(sql);
        final CalciteQueryCatalog.Snapshot snapshot = catalog.snapshot(bar);
        final FrameworkConfig config = Frameworks.newConfigBuilder()
                .context(Contexts.of(CONNECTION_CONFIG))
                .typeSystem(TYPE_SYSTEM)
                .parserConfig(PARSER_CONFIG)
                .sqlValidatorConfig(SqlValidator.Config.DEFAULT
                        .withConformance(SqlConformanceEnum.BABEL)
                        .withIdentifierExpansion(true)
                        .withTypeCoercionFactory(SqlQueryFrontEnd::strictCaseCoercion))
                .operatorTable(SqlQueryOperators.TABLE)
                // Calcite's default throws away the collation of an ORDER BY that is not the statement's
                // outermost clause and carries no LIMIT/OFFSET, on the standard's reading that such a sort
                // is unobservable. ClickHouse observes it: it sorts the subquery, and a set operation's
                // trailing clause is a branch's own ORDER BY after SqlSetOperations re-associates it — so
                // `a UNION ALL b ORDER BY x` would come back with no ordering at all, and with the default
                // row limit then choosing which rows, not just their order. Keeping the sort is what makes
                // the statement mean here what it means there.
                .sqlToRelConverterConfig(SqlToRelConverter.config()
                        .withRemoveSortInSubQuery(false)
                        // Carries the FINAL hint onto the TableScan, where the translator binds the reference.
                        .withHintStrategyTable(FINAL_HINT_STRATEGY))
                .defaultSchema(snapshot.rootSchema())
                .build();
        try (Planner planner = Frameworks.getPlanner(config)) {
            SqlNode parsed = planner.parse(sql);
            // A FINAL on a CTE name or a catalog table names no entity; inlining would lose it silently.
            SqlFinalReferences.check(parsed);
            // Set operations are made to mean what they mean to ClickHouse before anything else looks at
            // the tree: a bare INTERSECT/EXCEPT is promoted to ALL, a bare UNION and a MINUS are refused,
            // and a trailing ORDER BY/LIMIT is re-associated into the last branch. Both readings are
            // recoverable only from the source text, so this runs on what the caller wrote.
            parsed = SqlSetOperations.rewrite(parsed, sql);
            // A star is expanded to the entity's wildcard fields (its non-heavy source columns) before
            // validation, so the rest of the pipeline sees the same statement a client would have written
            // by hand — including the result-column names.
            SqlStarExpander.expandStars(parsed, snapshot);
            // date_trunc is bound to Calcite's own (BigQuery-shaped) operator by the Babel grammar itself,
            // so the operator table cannot carry the DSL signature the way it does for date_bin. The
            // PostgreSQL-shaped call parses correctly and only needs its operator swapped.
            SqlDateTruncRebinder.rebind(parsed);
            // Unnamed select items are aliased with their PostgreSQL-shaped names before validation, so
            // the derived name is a real alias the statement can reference (ORDER BY count) instead of
            // Calcite's EXPR$n.
            SqlSelectAliasInjector.injectAliases(parsed);
            // || and CAST(x AS VARCHAR) are likewise bound by the grammar rather than resolved through the
            // operator table, and both mean a DSL text function: || an operator whose type checker is
            // string-only (so a mixed-type concatenation would fail validation before reaching the concat
            // that renders every operand as text), and a caller-written cast to text a to_string call. The
            // rewrite has to happen before validation, on what the caller wrote, because validation injects
            // casts of its own — see SqlTextRebinder — and AFTER the alias injection above, because the
            // injector names an unnamed item off the operator it finds there: PostgreSQL names neither a
            // cast nor a `||` expression after an operator (both are `?column?`), and rewriting first would
            // have it name them `to_string_cast`/`concat` after the operators this swap introduces.
            parsed = SqlTextRebinder.rebind(parsed);
            // A lambda is inlined last of the pre-validation passes, once every other spelling has been
            // rewritten: the body it inlines is then the same tree the validator would have seen anywhere
            // else, so a `||` or a cast inside a lambda body means there what it means outside one.
            parsed = SqlLambdaRewriter.rewrite(parsed);
            final SqlNode validated = planner.validate(parsed);
            final RelRoot root = planner.rel(validated);
            // The catalog relations live in ADAS, not ClickHouse, so a statement over them is evaluated
            // here instead of being translated into a query against an entity that does not exist.
            if (InformationSchemaExecutor.isMetadataStatement(root)) {
                return InformationSchemaExecutor.execute(root);
            }
            return RelToStructuredQueryTranslator.translate(root, snapshot);
        } catch (SqlParseException e) {
            throw parseError(sql, e);
        } catch (ValidationException e) {
            throw new SqlTranslationException("SQL validation error: " + rootMessage(e), e);
        } catch (RelConversionException e) {
            throw new SqlTranslationException("SQL cannot be planned: " + rootMessage(e), e);
        }
    }

    /**
     * Plans one {@code INSERT INTO <target> (<columns>) <query>} statement — the aggregate pipeline's SQL
     * declaration — into its target, its column list and the single-block {@link SqlTranslation} of its source.
     *
     * <p>The INSERT itself is parsed but never validated or {@linkplain #screen screened}: its target is not a
     * modifiable table to Calcite, and the screen refuses every INSERT. Only the source is planned, by cutting
     * the caller's <em>raw</em> text just past the column list's closing parenthesis and handing the rest to
     * {@link #plan(String, Privilege)}. The cut is taken from the column list rather than from the source's own
     * position, which is unusable: a {@code SqlOrderBy} reports only its last sort item and a {@code SqlWith}
     * only its WITH list. The offset is computed on the {@link SqlFinalModifier}-rewritten text and applied to
     * the raw one, which is sound because the rewrite only touches text from the {@code FROM}/{@code JOIN} reference
     * a {@code FINAL} modifies onwards, and every such reference stands after the column list. The source is planned
     * with the INSERT head blanked rather than cut off, so the positions in a refusal point into the caller's text.
     *
     * <p>A {@code FORMAT} clause ({@code FORMAT JSON} anywhere, or a trailing {@code FORMAT <name>}) is refused
     * before anything parses: Calcite reads {@code expr FORMAT JSON} as JSON value syntax and would report a
     * misleading grouping or condition error instead. A column or alias named {@code format} is not a clause. A composite source (join, set
     * operation, subquery, a derived table that does not fold) and an {@code INFORMATION_SCHEMA} source are
     * refused, each naming what it found.
     *
     * @throws SqlTranslationException for every refusal and every parse or validation failure
     */
    public SqlInsertPlan planInsert(String rawSql, Privilege bar) {
        final String sql = stripTrailingSeparator(rawSql);
        if (sql == null || sql.isBlank()) {
            throw new SqlTranslationException("SQL statement must not be empty");
        }
        if (SqlFinalModifier.hasFormatClause(sql)) {
            throw new SqlTranslationException("a FORMAT clause is not supported in an aggregate SQL declaration; "
                    + "ADAS writes the result into the target itself");
        }
        final String rewritten = SqlFinalModifier.rewrite(sql);
        final SqlNodeList statements;
        try {
            statements = SqlParser.create(rewritten, PARSER_CONFIG).parseStmtList();
        } catch (SqlParseException e) {
            throw insertParseError(rewritten, e);
        }
        if (statements.size() != 1) {
            throw new SqlTranslationException(
                    "an aggregate SQL declaration is exactly one statement; got " + statements.size());
        }
        if (!(statements.getFirst() instanceof SqlInsert insert) || insert.isUpsert()) {
            throw new SqlTranslationException("an aggregate SQL declaration is an INSERT INTO <target> (<columns>) SELECT …; got "
                    + statements.getFirst().getKind().lowerName.toUpperCase(java.util.Locale.ROOT));
        }
        if (!(insert.getTargetTable() instanceof SqlIdentifier targetId) || !targetId.isSimple()) {
            throw new SqlTranslationException("the INSERT target must be a bare table name, with no database qualifier; got "
                    + insert.getTargetTable());
        }
        final SqlNodeList columnList = insert.getTargetColumnList();
        if (columnList == null || columnList.isEmpty()) {
            throw new SqlTranslationException(
                    "the INSERT column list is required: INSERT INTO <target> (<column>, …) SELECT …, naming every target column");
        }
        final List<String> columns = new ArrayList<>(columnList.size());
        for (final SqlNode column : columnList) {
            if (!(column instanceof SqlIdentifier id) || !id.isSimple()) {
                throw new SqlTranslationException("the INSERT column list must name plain target columns; got " + column);
            }
            columns.add(id.getSimple());
        }
        final SqlNode source = insert.getSource();
        if (source.getKind() == SqlKind.VALUES || !source.isA(SqlKind.QUERY)) {
            throw new SqlTranslationException("the INSERT source must be a SELECT over the pipeline input; got "
                    + source.getKind().lowerName.toUpperCase(java.util.Locale.ROOT));
        }
        requireNoStar(source);
        final int cut = new SqlSourceRegion(rewritten).offsetAfter(columnList.getParserPosition());
        if (cut <= 0 || cut > sql.length() || sql.charAt(cut - 1) != ')' || !sql.regionMatches(0, rewritten, 0, cut)) {
            throw new SqlTranslationException("could not locate the end of the INSERT column list");
        }
        final SqlStatementPlan planned = plan(blankHead(sql, cut), bar);
        return switch (planned) {
            case SqlTranslation translation -> new SqlInsertPlan(targetId.getSimple(), columns, translation);
            case SqlCompositeTranslation composite -> throw compositeRefusal(composite);
            case SqlMetadataResult ignored -> throw new SqlTranslationException(
                    "an aggregate SQL declaration reads its input entity, not INFORMATION_SCHEMA");
        };
    }

    /**
     * {@code sql} with its first {@code cut} characters blanked to spaces, line breaks kept, so the source plans at the
     * line and column the caller wrote it at and every parse or validation position Calcite reports points into the
     * caller's own text rather than into a substring.
     */
    private static String blankHead(String sql, int cut) {
        final StringBuilder source = new StringBuilder(sql);
        for (int i = 0; i < cut; i++) {
            final char c = source.charAt(i);
            if (c != '\n' && c != '\r') {
                source.setCharAt(i, ' ');
            }
        }
        return source.toString();
    }

    /**
     * Refuses a projected {@code *}, a {@code SELECT DISTINCT} and a window function anywhere in the source (a CTE, a
     * derived table or the top level), and an outermost {@code GROUP BY} expression no select item projects. The star
     * is checked on the parse, before {@link SqlStarExpander} replaces it, because the expansion depends on the reader:
     * the declaring caller and the refresh's system identity would see different columns. A {@code *} that is a
     * function argument ({@code count(*)}) is not a projection and is left alone.
     *
     * <p>The window and the unprojected key are refused here, not left to the pipeline gate, because the planner seals
     * the aggregating block around either one. The plan then reaches {@link #compositeRefusal} as "a nested block
     * carrying DISTINCT / an aggregate", which names a construct the caller never wrote. The messages match the gate's
     * own refusals for the same constructs when they plan to one block.
     */
    private static void requireNoStar(SqlNode source) {
        SqlNode top = source;
        if (top instanceof SqlOrderBy order) {
            top = order.query;
        }
        if (top instanceof SqlWith with) {
            top = with.body;
        }
        final SqlNode outermost = top;
        source.accept(new SqlBasicVisitor<Void>() {
            @Override
            public Void visit(SqlCall call) {
                if (call instanceof SqlSelect select) {
                    // Refused on the parse, because the planner folds a DISTINCT over a GROUP BY away and the planned
                    // block no longer shows it.
                    if (select.isDistinct()) {
                        throw new SqlTranslationException("SELECT DISTINCT is not supported in an aggregate SQL declaration; "
                                + (select == outermost
                                        ? "the GROUP BY already yields one row per key"
                                        : "a nested DISTINCT seals its block, and the declaration must plan to a single block"));
                    }
                    for (final SqlNode item : select.getSelectList()) {
                        if (item instanceof SqlIdentifier id && id.isStar()) {
                            throw new SqlTranslationException("SELECT * is not supported in an aggregate SQL declaration; "
                                    + "list the columns, because * expands differently for the declaring caller and the refresh");
                        }
                    }
                    if (select == outermost) {
                        requireGroupKeysProjected(select);
                    }
                }
                if (call.getKind() == SqlKind.OVER) {
                    throw new SqlTranslationException(WINDOW_REFUSAL);
                }
                return super.visit(call);
            }
        });
    }

    /**
     * Refuses an outermost {@code GROUP BY} <em>expression</em> that no select item projects, since that is what seals the
     * block (a dropped computed key). A plain column, an alias or an ordinal is left to the pipeline gate: it plans to
     * one block, and the gate names it with the same message. Items are matched by their unparsed text, case-folded, so
     * a key repeated in the select list in another case still matches. A spelling that differs in some other way is
     * not matched, and then reaches the composite refusal, as it did before.
     */
    private static void requireGroupKeysProjected(SqlSelect select) {
        final SqlNodeList groupBy = select.getGroup();
        if (groupBy == null) {
            return;
        }
        final Set<String> projected = new java.util.HashSet<>();
        for (final SqlNode item : select.getSelectList()) {
            final SqlNode expr = item.getKind() == SqlKind.AS ? ((SqlCall) item).operand(0) : item;
            projected.add(normalized(expr));
        }
        for (final SqlNode key : groupBy) {
            if (key instanceof SqlCall && !projected.contains(normalized(key))) {
                throw new SqlTranslationException("the GROUP BY key '" + key + "' is not projected by any select item; every "
                        + "GROUP BY key must be written to an ordering_key column of the target");
            }
        }
    }

    private static String normalized(SqlNode node) {
        return node.toString().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
    }

    /** A parse failure of an INSERT, with the two known non-grammar causes — backticks, SETTINGS — named. */
    private static SqlTranslationException insertParseError(String sql, SqlParseException e) {
        final SqlTranslationException base = parseError(sql, e);
        String message = base.getMessage();
        if (sql.indexOf('`') >= 0) {
            message += " Backtick quoting is not supported; quote identifiers with double quotes.";
        }
        if (SqlFinalModifier.containsWord(sql, "SETTINGS")) {
            message += " A SETTINGS clause is not supported in an aggregate SQL declaration; ADAS applies its own query settings.";
        }
        return message.equals(base.getMessage()) ? base : new SqlTranslationException(message, e);
    }

    /** The refusal of a source that did not plan to one block, naming the construct that kept it apart. */
    private static SqlTranslationException compositeRefusal(SqlCompositeTranslation composite) {
        final QueryReferences refs = QueryReferences.of(composite.query());
        final String entities = String.join(", ", refs.entityCounts().keySet());
        if (!refs.setOperations().isEmpty()) {
            return new SqlTranslationException("a set operation (" + refs.setOperations().stream().map(SetOpKind::keyword)
                    .collect(java.util.stream.Collectors.joining(", ")) + ") is not supported in an aggregate SQL declaration; "
                    + "read the input once, in one SELECT");
        }
        if (refs.subqueries() > 0) {
            return new SqlTranslationException(
                    "a subquery used as an expression (scalar, IN or EXISTS) is not supported in an aggregate SQL declaration");
        }
        if (refs.entityCounts().size() == 1 && refs.occurrences() > 1) {
            return new SqlTranslationException("the input " + entities + " is referenced " + refs.occurrences()
                    + " times; an aggregate SQL declaration reads its input exactly once (a self-join, or a CTE read twice, "
                    + "counts every reference)");
        }
        if (refs.joins() > 0) {
            return new SqlTranslationException("an explicit JOIN is not supported in an aggregate SQL declaration (it reads "
                    + entities + "); read the input only, and enrichment columns as \"<enrichment>.<column>\" through the "
                    + "implicit grain join");
        }
        if (refs.entityCounts().size() > 1) {
            return new SqlTranslationException("an aggregate SQL declaration reads only its input and the input's enrichments; "
                    + "this statement reads " + entities);
        }
        final String sealing = refs.nestedSealing().isEmpty() ? "a nested block" : String.join(", ", refs.nestedSealing());
        return new SqlTranslationException("an aggregate SQL declaration must flatten to one aggregating block; a CTE or derived "
                + "table may filter rows and compute columns, but this one carries " + sealing);
    }

    private static CalciteConnectionConfigImpl connectionConfig() {
        final Properties properties = new Properties();
        properties.setProperty(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), "false");
        properties.setProperty(CalciteConnectionProperty.CONFORMANCE.camelName(), SqlConformanceEnum.BABEL.name());
        return new CalciteConnectionConfigImpl(properties) {
            @Override
            public SqlConformance conformance() {
                return CONFORMANCE;
            }
        };
    }

    /**
     * Calcite's implicit type coercion, minus the CASE/COALESCE branch coercion.
     *
     * <p>Left on, the validator silently widens mismatched branches to text — {@code CASE WHEN c THEN 'yes'
     * ELSE 1 END} becomes {@code … ELSE '1'} — and folds the cast into the literal, so nothing downstream can
     * tell the caller wrote a number. PostgreSQL refuses the statement, and so does the DSL's own {@code if},
     * so the SQL surface refuses it too: without the coercion the validator finds no common type and reports
     * the mismatch. Every other coercion (a string literal against a timestamp column, numeric widening)
     * stays exactly as it was.
     */
    private static TypeCoercion strictCaseCoercion(RelDataTypeFactory typeFactory, SqlValidator validator) {
        return new TypeCoercionImpl(typeFactory, validator) {
            @Override
            public boolean caseWhenCoercion(SqlCallBinding callBinding) {
                return false;
            }
        };
    }

    /**
     * Removes a single trailing statement separator. Clients that terminate what they generate — the
     * catalog SQL a Dremio-compatible connector emits ends its column query with one — would otherwise
     * fail on the terminator alone.
     *
     * <p>Only the last non-whitespace character is considered, which is why this cannot corrupt a
     * string literal: a statement ending in a literal ends with a quote. Exactly one separator is
     * removed, so a batch still fails the "exactly one statement" screen below — tolerating a
     * terminator is not the same as accepting a second statement.
     */
    private static String stripTrailingSeparator(String sql) {
        if (sql == null) {
            return null;
        }
        final String trimmed = sql.stripTrailing();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).stripTrailing() : sql;
    }

    /** Statement screen: exactly one statement, of a read-only SELECT kind. */
    private static void screen(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new SqlTranslationException("SQL statement must not be empty");
        }
        final SqlNodeList statements;
        try {
            statements = SqlParser.create(sql, PARSER_CONFIG).parseStmtList();
        } catch (SqlParseException e) {
            throw parseError(sql, e);
        }
        if (statements.size() != 1) {
            throw new SqlTranslationException("exactly one SQL statement is required; got " + statements.size());
        }
        SqlNode statement = statements.getFirst();
        // A top-level ORDER BY wraps the whole query it sorts, WITH included — `WITH … SELECT … ORDER BY x`
        // parses as SqlOrderBy(SqlWith(…)) — so the wrapper is peeled before the WITH is examined, or a
        // sorted WITH RECURSIVE would slip past the rejection below.
        if (statement instanceof SqlOrderBy order) {
            statement = order.query;
        }
        if (statement instanceof SqlWith with) {
            // A non-recursive WITH is a SELECT with named derived tables; Calcite inlines each reference. A
            // recursive one is a fixpoint iteration, which nothing downstream expresses.
            for (final SqlNode item : with.withList) {
                if (item instanceof SqlWithItem withItem && withItem.recursive != null && withItem.recursive.booleanValue()) {
                    throw new SqlTranslationException("recursive common table expressions (WITH RECURSIVE) are not supported");
                }
            }
            statement = with.body;
        }
        final SqlKind kind = statement.getKind();
        // A set operation is a read-only query of the same standing as a SELECT. Its own branches are
        // screened by nothing here — they are query blocks, validated like any other — while the WITH
        // check above still refuses a recursive CTE whose body happens to be a UNION ALL.
        if (kind != SqlKind.SELECT && kind != SqlKind.ORDER_BY
                && kind != SqlKind.UNION && kind != SqlKind.INTERSECT && kind != SqlKind.EXCEPT) {
            throw new SqlTranslationException(
                    "only SELECT statements are supported; got " + kind.lowerName.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /**
     * A parse failure, with the construct responsible named when that is what went wrong — a nested block
     * the grammar cannot give a trailing clause to ({@link SqlNestedTrailingClause}), or, failing that, the
     * reserved word behind the failure ({@link SqlReservedWords}).
     *
     * <p><b>Why Calcite's own message points at the wrong token.</b> For
     * {@code SELECT count(*) AS rows FROM t}, the offender is {@code rows} — a reserved word (the
     * window-frame keyword) used as an unquoted alias — but the message reads
     * {@code Incorrect syntax near the keyword 'AS'}. That is not a Calcite defect: its generated parser
     * reformats a keyword-adjacent failure around {@code currentToken.next}, the first token it could not
     * consume, and JavaCC's optional-alias production {@code [ [AS] <IDENTIFIER> ]} needs two tokens of
     * lookahead. Since {@code rows} is not an {@code <IDENTIFIER>}, the parser never enters the alias
     * branch and stops at {@code AS} instead. The diagnosis is accurate about where parsing stopped and
     * blind to why.
     *
     * <p>A caller cannot reason their way out of it either: the reserved set is not intuitive. Under BABEL
     * {@code rows} is reserved while {@code first}, {@code last}, {@code turns}, {@code tokens},
     * {@code spend} and {@code conversations} all parse.
     *
     * <p><b>The hint is appended, never substituted.</b> Where parsing stopped is the one thing that is
     * certainly true; the identification is an inference from position. Appending means a wrong inference
     * costs a redundant sentence rather than a misleading message.
     *
     * <p><b>At most one hint, the more specific first.</b> {@link SqlNestedTrailingClause} reads two facts
     * off the text rather than inferring one from a position, and where both fire they contradict each
     * other: the reserved-word sentence would tell the caller to quote the trailing clause's own keyword,
     * which produces a different statement and still does not parse. So it stands in for the reserved-word
     * hint rather than joining it, and only there — every other failure reaches {@link SqlReservedWords}
     * exactly as before.
     */
    private static SqlTranslationException parseError(String sql, SqlParseException e) {
        final String message = "SQL parse error: " + rootMessage(e);
        final String nested = SqlNestedTrailingClause.hint(sql, e);
        final String hint = nested != null ? nested : SqlReservedWords.aliasHint(sql, e);
        return new SqlTranslationException(hint == null ? message : message + " " + hint, e);
    }

    /** The most specific message in the chain — Calcite nests the useful context inside wrappers. */
    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause().getMessage() != null) {
            current = current.getCause();
        }
        return trimExpectations(current.getMessage() == null ? e.toString() : current.getMessage());
    }

    /**
     * Reduces a parse failure to its diagnosis. Calcite reports one as {@code Encountered "<token>" at
     * line L, column C.} followed by every token the grammar would have accepted there — some fifty lines
     * of it, which displaces the one line that locates the mistake and makes the response unreadable
     * anywhere errors surface as a single line.
     *
     * <p>The cut is anchored on Calcite's own marker rather than on a line or character count, so it lands
     * on the boundary the text already has instead of one that could fall mid-diagnosis or mid-token. A
     * message without the marker — a differently-shaped failure, or a Calcite version that rewords it —
     * passes through unchanged, so an unrecognised shape is un-improved rather than mangled. That
     * fallback is also why validation and plan-conversion messages, which share this method, need no
     * guard: they never carry the marker.
     *
     * <p>The untrimmed text is logged at debug rather than discarded, and deliberately at this same site:
     * split apart, a later refactor that only sees a string being shortened loses the full text with it.
     */
    private static String trimExpectations(String message) {
        final int marker = message.indexOf(EXPECTATION_MARKER);
        if (marker < 0) {
            return message;
        }
        // On the SQL logger: Calcite's diagnosis quotes the offending token, which can be a literal from the
        // caller's statement, so a TRACE on this class must not print it.
        SqlLog.LOG.debug("Parser rejected the input: {}", message);
        return message.substring(0, marker).strip();
    }
}
