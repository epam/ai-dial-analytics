package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.observability.SqlLog;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlTableRef;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Enumerates every entity, column and function name in a statement that the catalog cannot resolve.
 *
 * <p><b>Why this exists.</b> Calcite has no error recovery: validation stops at the first offending
 * identifier, so a statement misspelling three columns is rejected three times, once per round trip. That
 * is the cost this removes — it is the difference between an assistant converging in one call and in
 * four. The probe runs <em>only</em> after the real pipeline has already rejected the statement, so the
 * accepting path pays nothing for it and the authoritative diagnosis is always the pipeline's own; the
 * probe can only add to it.
 *
 * <p><b>Deliberately conservative.</b> It parses but does not validate, so it has no scope resolution: it
 * cannot know which side of a join an unqualified column belongs to, and it does not track a derived
 * table's or CTE's output columns. Every rule below is therefore written to stay silent when unsure, and
 * a name is reported only when <em>no</em> entity in the statement could own it. Under-reporting leaves
 * the caller exactly where the pipeline's own message left them; misattributing a column that is in fact
 * legal would send them to fix something that is not broken, which is strictly worse than saying nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlIdentifierProbe {

    /**
     * Edit distance within which a catalog name is offered as the intended one. Two accommodates the
     * realistic slips (a dropped, doubled or transposed character) without pairing genuinely different
     * names — {@code project_id} and {@code product_id} differ by two and are both real, but only one of
     * them is ever in the catalog, so the suggestion is still the right one.
     */
    private static final int SUGGESTION_DISTANCE = 2;

    private final CatalogQueryService catalog;

    /**
     * The unresolvable names in {@code sql}, as issues. Empty whenever the statement does not parse (there
     * is no tree to walk, and the parse error is the only honest diagnosis), or when every name resolves —
     * which happens routinely here, because the statement may have been rejected for something other than
     * an identifier.
     */
    List<Finding> probe(String sql) {
        return probe(sql, Privilege.TABLE_READ);
    }

    /**
     * As {@link #probe(String)} at a chosen catalog visibility bar. The validation endpoint passes
     * {@link Privilege#TABLE_GET}, the same bar it planned the statement at — resolving the diagnostics at
     * a higher bar would report a table the caller <em>can</em> validate against as unknown, and suggest
     * alternatives from a catalog it never saw.
     */
    List<Finding> probe(String sql, Privilege bar) {
        final SqlNode parsed;
        try {
            parsed = SqlParser.create(SqlFinalModifier.rewrite(sql), SqlQueryFrontEnd.PARSER_CONFIG).parseQuery();
        } catch (Exception cannotParse) {
            // The parser's message quotes the caller's statement, so it goes to the SQL logger only.
            SqlLog.LOG.debug("Identifier probe skipped, statement does not parse: {}", cannotParse.getMessage());
            return List.of();
        }

        final Scope scope = new Scope();
        parsed.accept(scope);

        final List<Finding> issues = new ArrayList<>();
        final List<QueryEntityModel> models = new ArrayList<>();
        for (Named entity : scope.entities) {
            // A CTE name is a relation the statement declares itself, not a catalog entity.
            if (scope.declaredNames.contains(lower(entity.name()))) {
                continue;
            }
            final Optional<QueryEntityModel> model = catalog.findModel(entity.name(), bar);
            if (model.isPresent()) {
                models.add(model.get());
            } else if (!InformationSchema.SCHEMA.equalsIgnoreCase(entity.qualifier())) {
                issues.add(issue("unknown_entity", "entity '" + entity.name() + "' is not queryable",
                        entity, suggest(entity.name(), catalog.queryableEntityNames(bar)),
                        "list the queryable entities with GET /v1/queries/entities"));
            }
        }

        // Without any resolved entity there is no set of legal columns to test against, and every column
        // would be reported as unknown — including the ones that are fine.
        if (models.isEmpty()) {
            return List.copyOf(issues);
        }
        final Set<String> known = new LinkedHashSet<>();
        models.forEach(model -> model.bindings().keySet().forEach(name -> known.add(lower(name))));
        known.addAll(scope.declaredNames);

        for (Named column : scope.columns) {
            if (!known.contains(lower(column.name()))) {
                issues.add(issue("unknown_column", "column '" + column.name()
                                + "' is not a field of any entity the statement reads",
                        column, suggest(column.name(), known),
                        "list the entity's fields with GET /v1/queries/entities/schema/{entity}"));
            }
        }
        return List.copyOf(issues);
    }

    private static Finding issue(String code, String message, Named at, String suggestion, String fallback) {
        final String remedy = suggestion == null ? fallback : suggestionRemedy(suggestion);
        return new Finding(new SqlIssue(code, message, at.line(), at.column(), remedy), at.name(), suggestion);
    }

    /** The wording of a closest-name suggestion, shared so the merge onto a primary issue reads the same. */
    static String suggestionRemedy(String suggestion) {
        return "did you mean '" + suggestion + "'?";
    }

    /**
     * One unresolvable name, with the parts the caller of the probe needs in order to merge it.
     *
     * <p>{@code identifier} and {@code suggestion} are carried alongside the issue rather than left to be
     * recovered from its message, because the pipeline's own rejection often names the same identifier —
     * and when it does, the finding must not simply be dropped: its suggestion is the most useful part of
     * it, and the pipeline's message never carries one.
     */
    record Finding(SqlIssue issue, String identifier, String suggestion) {}

    /** The closest candidate within {@link #SUGGESTION_DISTANCE}, or null when none is close enough. */
    private static String suggest(String name, Iterable<String> candidates) {
        String best = null;
        int bestDistance = SUGGESTION_DISTANCE + 1;
        for (String candidate : candidates) {
            final int distance = distance(lower(name), lower(candidate));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return bestDistance <= SUGGESTION_DISTANCE ? best : null;
    }

    /** Levenshtein distance, two rows at a time — the strings are identifier-length. */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                final int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitute);
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /** An identifier as written, with the position to point the caller at. */
    private record Named(String name, String qualifier, Integer line, Integer column) {}

    /**
     * Collects the statement's identifiers, separating the ones in a FROM position (relations) from the
     * ones in an expression position (columns), and remembering every name the statement declares for
     * itself — a table alias, a CTE name, a select alias — since none of those is a catalog name.
     *
     * <p>Column identifiers are reduced to their last part, so {@code d.event_kind} is tested as
     * {@code event_kind}: the qualifier is a statement-local alias the catalog knows nothing about. An
     * enrichment column is the one catalog name that legitimately contains a dot, and it arrives quoted as
     * a single part ({@code "deployment_ref.display_name"}), so it is tested whole and matches its binding.
     */
    private static final class Scope extends SqlBasicVisitor<Void> {

        private final List<Named> entities = new ArrayList<>();
        private final List<Named> columns = new ArrayList<>();
        private final Set<String> declaredNames = new LinkedHashSet<>();

        @Override
        public Void visit(SqlCall call) {
            switch (call) {
                case SqlSelect select -> {
                    from(select.getFrom());
                    // The FROM is walked as relations above; everything else is expressions.
                    acceptAll(select.getSelectList());
                    accept(select.getWhere());
                    acceptAll(select.getGroup());
                    accept(select.getHaving());
                    acceptAll(select.getOrderList());
                    return null;
                }
                case SqlWith with -> {
                    for (SqlNode item : with.withList) {
                        if (item instanceof SqlWithItem withItem && withItem.name != null) {
                            declaredNames.add(lower(withItem.name.getSimple()));
                        }
                    }
                    return super.visit(call);
                }
                case SqlBasicCall basic when basic.getOperator().getKind() == SqlKind.AS -> {
                    // `x AS alias`: the alias is declared here, and only the operand is an expression.
                    declaredNames.addAll(aliasOf(basic));
                    accept(operand(basic, 0));
                    return null;
                }
                default -> {
                    return super.visit(call);
                }
            }
        }

        @Override
        public Void visit(SqlIdentifier identifier) {
            if (!identifier.isStar()) {
                columns.add(named(identifier, identifier.names.getLast()));
            }
            return null;
        }

        /** Walks a FROM tree, recording relations rather than columns, and their aliases as declared. */
        private void from(SqlNode node) {
            switch (node) {
                case null -> { }
                case SqlIdentifier identifier -> entities.add(named(identifier, identifier.names.getFirst()));
                // A FINAL reference, rewritten into a hinted table reference: still a relation, never a column.
                case SqlTableRef ref -> from(ref.operand(0));
                case SqlJoin join -> {
                    from(join.getLeft());
                    from(join.getRight());
                    accept(join.getCondition());
                }
                case SqlBasicCall basic when basic.getOperator().getKind() == SqlKind.AS -> {
                    declaredNames.addAll(aliasOf(basic));
                    from(operand(basic, 0));
                }
                // A derived table or a sub-query in FROM: its own body is a statement of the same shape,
                // and its output columns are names the outer block may legally reference.
                case SqlSelect select -> {
                    select.accept(this);
                    select.getSelectList().forEach(item -> declaredNames.add(lower(outputName(item))));
                }
                default -> node.accept(this);
            }
        }

        private void accept(SqlNode node) {
            if (node != null) {
                node.accept(this);
            }
        }

        private void acceptAll(SqlNodeList nodes) {
            if (nodes != null) {
                nodes.forEach(this::accept);
            }
        }

        /**
         * {@code SqlCall.operand(int)} is generic in its return type, so at a call site with an overload
         * taking {@link SqlNodeList} javac infers that and inserts a cast that fails at runtime. Pinning
         * the type here keeps every operand read honest.
         */
        private static SqlNode operand(SqlBasicCall call, int index) {
            return call.getOperandList().get(index);
        }

        /** The alias {@code call} declares, as a singleton, or empty when its second operand is not one. */
        private static Set<String> aliasOf(SqlBasicCall call) {
            return call.operandCount() > 1 && call.getOperandList().get(1) instanceof SqlIdentifier alias
                    ? Set.of(lower(alias.getSimple()))
                    : Set.of();
        }

        /** The name a select item exposes to an enclosing block: its alias, or the column's own name. */
        private static String outputName(SqlNode item) {
            if (item instanceof SqlBasicCall basic
                    && basic.getOperator().getKind() == SqlKind.AS
                    && basic.operandCount() > 1
                    && basic.operand(1) instanceof SqlIdentifier alias) {
                return alias.getSimple();
            }
            return item instanceof SqlIdentifier identifier && !identifier.isStar()
                    ? identifier.names.getLast()
                    : "";
        }

        private static Named named(SqlIdentifier identifier, String name) {
            final SqlParserPos pos = identifier.getParserPosition();
            final String qualifier = identifier.names.size() > 1 ? identifier.names.getFirst() : null;
            return pos == null
                    ? new Named(name, qualifier, null, null)
                    : new Named(name, qualifier, pos.getLineNum(), pos.getColumnNum());
        }
    }
}
