package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.StructuredQueryService;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.PageSpec;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers whether {@code POST /v1/queries/execute-sql} would accept a statement, without executing it.
 *
 * <p><b>The guarantee is agreement, and it is achieved by reusing the same code rather than by
 * re-implementing the rules.</b> A statement goes through {@link SqlQueryFrontEnd#plan} — the identical
 * screen, parse, star expansion, catalog validation, closed function catalog and DSL/composite
 * translation the execute path runs — and then through {@link StructuredQueryService}'s {@code validate},
 * which is the execute path's own governance pass with the built statement discarded. What remains after
 * that is exactly one thing: the ClickHouse round trip. So a statement this service calls valid is one
 * that cannot be <em>rejected</em> by execute-sql; it can still be <em>refused</em> there by the query
 * budget, which is reactive and therefore not knowable in advance. That residue is what
 * {@link #warnings} is for.
 *
 * <p>ClickHouse is never contacted. The catalog is read from PostgreSQL, as everywhere on the query
 * surface, and through {@link CatalogQueryService} — so a caller who may not see a {@code sensitive}
 * column validates against a model that does not contain it, and a statement naming one is reported
 * unresolvable exactly as execution reports it. That is the column-level access control working, not a
 * leak: the alternative, a validator that resolved names the caller cannot query, would confirm the
 * column's existence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlValidationService {

    private final SqlQueryFrontEnd frontEnd;
    private final StructuredQueryService queryService;
    private final CatalogQueryService catalog;
    private final SqlIdentifierProbe probe;

    /**
     * Validates one statement, always answering with a verdict rather than throwing for a bad statement.
     *
     * <p>Every pass resolves at {@link Privilege#TABLE_GET}, like the translation endpoints and unlike
     * {@code execute-sql}: validation resolves against the catalog model and never contacts ClickHouse, so
     * it discloses schema rather than data. A table below that bar is absent from the model, so the
     * statement is reported as naming an unresolvable relation through the ordinary diagnostic shape rather
     * than as an authorization failure.
     */
    public SqlValidation validate(String sql) {
        try {
            return switch (frontEnd.plan(sql, Privilege.TABLE_GET)) {
                case SqlTranslation translation -> {
                    queryService.validate(translation.query(), Privilege.TABLE_GET);
                    yield SqlValidation.accepted(
                            effectiveLimit(translation.query()), warnings(translation));
                }
                case SqlCompositeTranslation composite -> {
                    queryService.validate(composite.query(), Privilege.TABLE_GET);
                    yield SqlValidation.accepted(
                            effectiveLimit(composite.query().block()), warnings(composite));
                }
                // Already evaluated in process against ADAS's own catalog: it reached no ClickHouse and no
                // row governance applies to it, so there is nothing left that could reject it.
                case SqlMetadataResult ignored -> SqlValidation.accepted(null, List.of());
            };
        } catch (SqlTranslationException | QueryValidationException rejected) {
            return SqlValidation.rejected(issues(sql, rejected));
        }
    }

    /**
     * The pipeline's own diagnosis first, then every additional unresolvable identifier the probe finds.
     *
     * <p>The order is load-bearing: the first issue is the authoritative one — the rejection the caller
     * would have received from execute-sql — and the probe's findings are supplementary.
     *
     * <p>A finding about the identifier the pipeline already named is not reported twice, but neither is
     * it discarded: its closest-name suggestion is merged onto the primary issue. That suggestion is the
     * most actionable thing the response carries and the pipeline's own message never has one, so dropping
     * such a finding wholesale would lose exactly the help a single misspelling needs — the commonest
     * case there is.
     */
    private List<SqlIssue> issues(String sql, RuntimeException rejected) {
        SqlIssue primary = SqlIssueClassifier.classify(rejected.getMessage());
        final List<SqlIssue> extras = new ArrayList<>();
        for (SqlIdentifierProbe.Finding finding : probe.probe(sql, Privilege.TABLE_GET)) {
            if (!primary.message().contains("'" + finding.identifier() + "'")) {
                extras.add(finding.issue());
            } else if (finding.suggestion() != null) {
                primary = withRemedy(primary, SqlIdentifierProbe.suggestionRemedy(finding.suggestion()));
            }
        }
        final List<SqlIssue> issues = new ArrayList<>();
        issues.add(primary);
        issues.addAll(extras);
        return issues;
    }

    private static SqlIssue withRemedy(SqlIssue issue, String remedy) {
        return new SqlIssue(issue.code(), issue.message(), issue.line(), issue.column(), remedy);
    }

    /** The outermost row ceiling execution would apply: the statement's own, or the default. */
    private static Integer effectiveLimit(StructuredQuery query) {
        final PageSpec page = query.page();
        // 0 is the model's "no explicit limit" sentinel, which validateLimit turns into the default.
        if (page instanceof OffsetPage offset && offset.limit() > 0) {
            return offset.limit();
        }
        return StructuredQueryBuilder.DEFAULT_LIMIT;
    }

    /**
     * Advisory notes about a statement that passes every check. These are heuristics on the translated
     * query, not verdicts: the query budget is enforced by ClickHouse as the statement runs, so no
     * pre-flight check can be exact. They name the two shapes that account for the refusals seen in
     * practice, plus the default limit — which is not a cost at all but is silently applied, and a caller
     * that does not know it has been truncated draws the wrong conclusion from the rows it gets back.
     */
    private List<SqlIssue> warnings(SqlTranslation translation) {
        final List<SqlIssue> warnings = new ArrayList<>();
        if (!translation.explicitLimit()) {
            warnings.add(SqlIssue.of("default_limit_applied",
                    "the statement carries no outer LIMIT, so execution applies the default of "
                            + StructuredQueryBuilder.DEFAULT_LIMIT + " rows",
                    "add an explicit LIMIT up to " + StructuredQueryBuilder.MAX_LIMIT
                            + " so the truncation is deliberate"));
        }
        warnings.addAll(costWarnings(translation.query()));
        return warnings;
    }

    /**
     * A composite statement gets only the limit note. Its blocks reference relation columns — a join's
     * merged column list, a derived table's output names — rather than one entity's fields, so a heavy or
     * timestamp column cannot be attributed to a catalog binding with certainty. Staying silent is the
     * right trade: the notes below are advice, and advice pointing at the wrong column is worse than none.
     */
    private static List<SqlIssue> warnings(SqlCompositeTranslation composite) {
        if (composite.explicitLimit()) {
            return List.of();
        }
        return List.of(SqlIssue.of("default_limit_applied",
                "the statement carries no outer LIMIT, so execution applies the default of "
                        + StructuredQueryBuilder.DEFAULT_LIMIT + " rows to the outermost block",
                "add an explicit LIMIT up to " + StructuredQueryBuilder.MAX_LIMIT
                        + " so the truncation is deliberate"));
    }

    private List<SqlIssue> costWarnings(StructuredQuery query) {
        // TABLE_GET, like the translation endpoints: validation resolves against the catalog model and
        // never contacts ClickHouse, so it discloses schema rather than data.
        final QueryEntityModel model = catalog.findModel(query.entity(), Privilege.TABLE_GET).orElse(null);
        // A time bound is what both notes below are about, so its presence settles them together.
        if (model == null || timeBounded(model, query)) {
            return List.of();
        }

        final List<SqlIssue> warnings = new ArrayList<>();
        final List<String> heavy = StructuredQueryBuilder.referencedFields(query).stream()
                .filter(name -> isHeavy(model, name))
                .toList();
        if (!heavy.isEmpty()) {
            warnings.add(SqlIssue.of("heavy_column_unbounded",
                    "the statement reads the heavy column(s) " + String.join(", ", heavy)
                            + " with no filter on a timestamp field, so the read is not pruned to a few"
                            + " partitions and is likely to exceed the query budget",
                    "add a range filter on a timestamp field, e.g. request_time >= TIMESTAMP '…'"));
        }
        if (query.mode() == QueryMode.AGGREGATE && query.groupBy() != null && !query.groupBy().isEmpty()) {
            warnings.add(SqlIssue.of("unbounded_group_by",
                    "the statement groups over the whole entity with no filter on a timestamp field; a"
                            + " high-cardinality GROUP BY builds one accumulator per group and may exceed"
                            + " the query budget",
                    "add a range filter on a timestamp field to bound the scan"));
        }
        return warnings;
    }

    /** Whether the filter constrains any {@code timestamp} field — the only pruning the sources support. */
    private static boolean timeBounded(QueryEntityModel model, StructuredQuery query) {
        if (query.filter() == null) {
            return false;
        }
        return StructuredQueryBuilder.referencedFields(query.filter()).stream()
                .map(name -> model.bindings().get(name))
                .anyMatch(binding -> binding != null && binding.type() == QueryFieldType.TIMESTAMP);
    }

    private static boolean isHeavy(QueryEntityModel model, String name) {
        final QueryFieldBinding binding = model.bindings().get(name);
        return binding != null && binding.heavy();
    }
}
