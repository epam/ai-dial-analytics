package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.QueryEntityNotFoundException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.sql.SqlInsertPlan;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads an {@code aggregate} pipeline's SQL declaration ({@code INSERT INTO <target> (<columns>) SELECT …}) at the
 * pipeline boundary, in the two strengths the registry needs.
 *
 * <ul>
 *   <li>{@link #inputTableOf} — the <b>best-effort</b> read a draft write asks: which
 *       catalog table the statement reads, or empty. It never throws, because a statement is authored over several
 *       saves and its first drafts rarely parse; whether it is admissible is the gate's question.</li>
 *   <li>{@link #require} — the <b>gate</b> read: the planned statement, or a 422 {@code pipeline_validation_failed}
 *       naming {@code sql}. Every refusal the SQL front end raises for the text is mapped here, so no
 *       {@link SqlTranslationException} escapes the pipeline surface as the query surface's error code.</li>
 * </ul>
 *
 * <p>The best-effort read plans at {@link Privilege#TABLE_GET} — it only asks which table is named, and executes
 * nothing — while the gate plans at {@link Privilege#TABLE_READ}, the bar of a statement that will run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AggregateSqlReader {

    private final SqlQueryFrontEnd frontEnd;
    private final TableDefinitionRepository definitions;

    /**
     * The catalog table {@code sql} reads, or empty when it cannot be read — it does not parse or plan, or its
     * {@code FROM} names no table in the catalog. Never throws.
     */
    public Optional<String> inputTableOf(String sql) {
        if (StringUtils.isBlank(sql)) {
            return Optional.empty();
        }
        try {
            final String entity = frontEnd.planInsert(sql, Privilege.TABLE_GET).translation().query().entity();
            return entity != null && definitions.existsById(entity) ? Optional.of(entity) : Optional.empty();
        } catch (SqlTranslationException | QueryValidationException | QueryEntityNotFoundException ex) {
            log.debug("aggregate sql declaration is not readable yet: {}", ex.getMessage());
            return Optional.empty();
        } catch (RuntimeException ex) {
            // Not a refusal of the text (a catalog read failing, a sensitive refusal): still never thrown here, since
            // the gate read raises it, but logged where an operator sees it.
            log.warn("aggregate sql declaration could not be read for a reason other than its text", ex);
            return Optional.empty();
        }
    }

    /**
     * The planned statement, refusing one from which no input table can be read with a 422 naming {@code sql}.
     * A refusal that is not about the text — a sensitive column the caller may not read (403) — propagates as is.
     */
    public SqlInsertPlan require(String sql) {
        if (StringUtils.isBlank(sql)) {
            throw new PipelineValidationException("sql must not be blank");
        }
        final SqlInsertPlan plan;
        try {
            plan = frontEnd.planInsert(sql, Privilege.TABLE_READ);
        } catch (SqlTranslationException | QueryValidationException | QueryEntityNotFoundException ex) {
            final String message = ex.getMessage() == null ? "" : ex.getMessage();
            // Calcite's unknown-function wording names the call; point at the catalog of what SQL may call instead.
            throw new PipelineValidationException("sql cannot be read: " + message + (message.contains("No match found for function")
                    ? "; the functions SQL may call are listed at GET /v1/queries/sql-functions" : ""));
        }
        final String entity = plan.translation().query().entity();
        if (entity == null || !definitions.existsById(entity)) {
            throw new PipelineValidationException("sql reads no table in the catalog"
                    + (entity == null ? "" : " ('" + entity + "')") + "; its FROM must name the pipeline's input table");
        }
        return plan;
    }
}
