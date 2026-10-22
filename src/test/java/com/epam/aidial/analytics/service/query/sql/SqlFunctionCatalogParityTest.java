package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import com.epam.aidial.analytics.web.dto.query.SqlFunctionDto;
import com.epam.aidial.analytics.web.dto.query.SqlFunctionForm;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Binds the SQL function catalog ({@code GET /v1/queries/sql-functions}) to the SQL front end and to the DSL
 * catalog, in every direction a drift could hide in:
 *
 * <ul>
 *   <li>every advertised spelling — each entry's example, and each alias substituted into it — is accepted;
 *   <li>every DSL catalog function has a SQL spelling, so a function added to the DSL is not silently absent
 *       from SQL discovery;
 *   <li>every caller-facing name the custom operator table registers is advertised, so a function added to
 *       the SQL surface is not silently undiscoverable;
 *   <li>the DSL-only names really are refused, so advertising their absence is true.
 * </ul>
 */
class SqlFunctionCatalogParityTest {

    private static final String METRICS = "otel_claude_code_metrics";

    /**
     * Registered in the custom operator table only so the validator can resolve a rewritten call by name;
     * the surface refuses both when a caller spells them ({@code SqlTextRebinder}, {@code SqlLambdaRewriter}).
     */
    private static final Set<String> INTERNAL_MARKERS = Set.of("to_string_cast", "__lambdaparam");

    private final SqlFunctionCatalog catalog = new SqlFunctionCatalog();
    private final SqlQueryFrontEnd frontEnd = newFrontEnd();

    private static SqlQueryFrontEnd newFrontEnd() {
        final CatalogQueryService entities = mock(CatalogQueryService.class);
        when(entities.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY, METRICS));
        when(entities.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));
        when(entities.findModel(eq(METRICS), any())).thenReturn(Optional.of(metricsModel()));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(entities));
    }

    /** The slice of an OTel sink's metrics table the map examples read: a map column and a numeric value. */
    private static QueryEntityModel metricsModel() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        final List<QuerySchemaFieldDto> schema = new ArrayList<>();
        column(bindings, schema, "metric_name", QueryFieldType.STRING);
        column(bindings, schema, "point_value", QueryFieldType.DECIMAL);
        column(bindings, schema, "point_attributes", QueryFieldType.MAP);
        return new QueryEntityModel(METRICS, DSL.table(DSL.name(METRICS)), bindings, List.of(), schema);
    }

    private static void column(
            Map<String, QueryFieldBinding> bindings, List<QuerySchemaFieldDto> schema, String name, QueryFieldType type) {
        bindings.put(name, new QueryFieldBinding(name, DSL.field(DSL.name(METRICS, name), JooqTypes.of(type)), type));
        schema.add(new QuerySchemaFieldDto(name, type, null, null, name, null, null, null, false, false));
    }

    @Test
    void everyEntryCarriesAnExample() {
        for (final SqlFunctionDto entry : catalog.listFunctions()) {
            assertThat(entry.example()).as("example of '%s'", entry.name()).isNotBlank();
        }
    }

    @Test
    void everyExampleTranslates() {
        final List<String> failures = new ArrayList<>();
        for (final SqlFunctionDto entry : catalog.listFunctions()) {
            try {
                final String plan = frontEnd.translate(entry.example()).query().toString();
                // Where the entry names a DSL function and is written as a call, the translation must actually
                // reach it: an example that happens to translate through a different function proves nothing.
                if (entry.dslFunction() != null && entry.form() == SqlFunctionForm.FUNCTION
                        && !plan.contains("=" + entry.dslFunction() + ",")) {
                    failures.add(entry.name() + ": translation does not reach '" + entry.dslFunction() + "': " + plan);
                }
            } catch (RuntimeException e) {
                failures.add(entry.name() + ": " + e.getMessage());
            }
        }
        assertThat(failures).as("examples the SQL front end refuses").isEmpty();
    }

    @Test
    void everyAliasTranslates() {
        final List<String> failures = new ArrayList<>();
        int aliases = 0;
        for (final SqlFunctionDto entry : catalog.listFunctions()) {
            for (final String alias : entry.aliases()) {
                aliases++;
                final String statement = withAlias(entry, alias);
                assertThat(statement).as("alias '%s' substituted into '%s'", alias, entry.example())
                        .isNotEqualTo(entry.example());
                try {
                    frontEnd.translate(statement);
                } catch (RuntimeException e) {
                    failures.add(alias + " (" + statement + "): " + e.getMessage());
                }
            }
        }
        assertThat(aliases).as("the catalog advertises aliases at all").isPositive();
        assertThat(failures).as("aliases the SQL front end refuses").isEmpty();
    }

    @Test
    void everyDslFunctionHasSqlSpelling() {
        final Set<String> reached = catalog.listFunctions().stream()
                .map(SqlFunctionDto::dslFunction)
                .filter(name -> name != null)
                .collect(Collectors.toSet());
        final Set<String> missing = new TreeSet<>();
        for (final QueryFunctionDto function : new QueryFunctionCatalog().listFunctions()) {
            if (!reached.contains(function.name())) {
                missing.add(function.name());
            }
        }
        assertThat(missing).as("DSL functions with no SQL catalog entry lowering onto them").isEmpty();
    }

    /**
     * An entry naming a DSL function carries that function's group, returns, distinct_supported,
     * deterministic and args; the one exception is {@code ||}, a binary operator declaring its own two
     * operands where {@code concat} declares a variadic list.
     */
    @Test
    void entriesLoweringOntoDslFunctionCarryItsValues() {
        final List<String> failures = new ArrayList<>();
        for (final SqlFunctionDto entry : catalog.listFunctions()) {
            if (entry.dslFunction() == null) {
                continue;
            }
            final QueryFunctionDto dsl = QueryFunctionCatalog.describe(entry.dslFunction());
            if (entry.group() != dsl.group() || entry.returns() != dsl.returns()
                    || entry.distinctSupported() != dsl.distinctSupported() || entry.deterministic() != dsl.deterministic()) {
                failures.add(entry.name() + ": group/returns/distinct_supported/deterministic differ from " + dsl.name());
            }
            if (!entry.name().equals("||") && !entry.args().equals(dsl.args())) {
                failures.add(entry.name() + ": args differ from " + dsl.name());
            }
        }
        assertThat(failures).isEmpty();
        final SqlFunctionDto concatOperator = catalog.listFunctions().stream()
                .filter(entry -> entry.name().equals("||")).findFirst().orElseThrow();
        assertThat(concatOperator.args()).hasSize(2);
    }

    @Test
    void everyRegisteredCallerFacingNameIsAdvertised() {
        final Set<SqlOperator> standard = Collections.newSetFromMap(new IdentityHashMap<>());
        standard.addAll(SqlStdOperatorTable.instance().getOperatorList());
        final Set<String> advertised = new TreeSet<>();
        for (final SqlFunctionDto entry : catalog.listFunctions()) {
            advertised.add(entry.name().toLowerCase(Locale.ROOT));
            entry.aliases().forEach(alias -> advertised.add(alias.toLowerCase(Locale.ROOT)));
        }
        final Set<String> unadvertised = new TreeSet<>();
        for (final SqlOperator operator : SqlQueryOperators.TABLE.getOperatorList()) {
            if (standard.contains(operator)) {
                continue;
            }
            final String name = operator.getName().toLowerCase(Locale.ROOT);
            if (!INTERNAL_MARKERS.contains(name) && !advertised.contains(name)) {
                unadvertised.add(name);
            }
        }
        assertThat(unadvertised).as("names the SQL surface registers but the SQL catalog does not list").isEmpty();
    }

    @Test
    void dslOnlyNamesAreAbsentAndRefused() {
        for (final SqlFunctionDto entry : catalog.listFunctions()) {
            assertThat(SqlFunctionCatalog.DSL_ONLY).doesNotContain(entry.name());
            assertThat(entry.aliases()).doesNotContainAnyElementsOf(SqlFunctionCatalog.DSL_ONLY);
        }
        for (final String name : SqlFunctionCatalog.DSL_ONLY) {
            assertThatThrownBy(() -> frontEnd.translate(
                    "SELECT " + name + "(total_tokens, 2) AS v FROM dial_usage_log"))
                    .as("DSL-only name '%s' in SQL", name)
                    .isInstanceOf(RuntimeException.class);
        }
    }

    /**
     * The SQL-specific rules the catalog's notes state, measured rather than asserted in prose: each refused
     * form is refused, so a description telling a caller to write the other form is telling the truth.
     */
    @Test
    void theRefusalsTheSqlNotesDescribeHold() {
        assertThat(sqlNote("count")).contains("count(*)");
        assertThatThrownBy(() -> frontEnd.translate("SELECT count() AS n FROM dial_usage_log"));
        assertThat(sqlNote("percentile_cont")).contains("WITHIN GROUP");
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT percentile_cont(0.5, operation_duration_ms) AS p FROM dial_usage_log"));
        assertThat(sqlNote("if")).contains("comparison is not accepted");
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT if(total_tokens > 1000, 'large', 'small') AS size FROM dial_usage_log"));
        // ...and the form the note recommends instead is accepted.
        frontEnd.translate("SELECT CASE WHEN total_tokens > 1000 THEN 'large' ELSE 'small' END AS size FROM dial_usage_log");
        assertThat(sqlNote("like")).contains("prefix or suffix pattern");
        assertThatThrownBy(() -> frontEnd.translate("SELECT count(*) AS n FROM dial_usage_log WHERE deployment LIKE 'gpt%'"));
    }

    /** The harness itself rejects what the surface rejects — otherwise every translation assertion is vacuous. */
    @Test
    void unlistedStandardFunctionIsRefused() {
        assertThatThrownBy(() -> frontEnd.translate("SELECT substring(deployment, 1, 3) AS s FROM dial_usage_log"));
    }

    private String sqlNote(String name) {
        return catalog.listFunctions().stream()
                .filter(entry -> entry.name().equals(name))
                .findFirst()
                .orElseThrow()
                .description();
    }

    @Test
    void internalMarkersAreNotAdvertised() {
        for (final SqlFunctionDto entry : catalog.listFunctions()) {
            assertThat(INTERNAL_MARKERS).doesNotContain(entry.name().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void sqlOperatorDescriptionsDropTheDslOnlyNote() {
        for (final SqlFunctionDto entry : catalog.listFunctions()) {
            assertThat(entry.description()).as("description of '%s'", entry.name())
                    .doesNotContain(QueryFunctionCatalog.DSL_ONLY_NOTE_PREFIX);
        }
    }

    /**
     * The entry's example with its spelling replaced by {@code alias}: the call's name for a function, the
     * whole word for an operator. {@code now()} is the one niladic function whose aliases take no parentheses.
     */
    private static String withAlias(SqlFunctionDto entry, String alias) {
        if (entry.name().equals("now")) {
            return entry.example().replace("now()", alias.toUpperCase(Locale.ROOT));
        }
        final String pattern = entry.form() == SqlFunctionForm.FUNCTION
                ? "\\b" + Pattern.quote(entry.name()) + "\\s*\\("
                : "\\b" + Pattern.quote(entry.name()) + "\\b";
        final String replacement = entry.form() == SqlFunctionForm.FUNCTION ? alias + "(" : alias;
        final Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(entry.example());
        if (!matcher.find()) {
            fail("example of '%s' does not spell it: %s", entry.name(), entry.example());
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }
}
