package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.SensitiveColumnNotEntitledException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Mirrors the query surface's sensitive-column rule at every pipeline-scoped egress point: a caller who may
 * not read sensitive values (not both {@code TABLE_READ} and the {@code TABLE_SENSITIVE} clearance on the
 * owning table, outside {@code none} mode) must not cause a
 * {@code sensitive}-flagged source column to be read or derived. Governance already blocks such
 * references at pipeline registration, but re-checking here against the *calling* identity is what stops a
 * least-privilege caller from operating a pipeline an entitled author registered — and it closes
 * the raw-name projection fallback, which would otherwise still select a sensitive column that the
 * role-filtered query model omitted from its bindings.
 *
 * <p>Sensitive columns are rejected, never silently stripped: a projection missing the very column a
 * pipeline was written to read would produce a quietly wrong result, so the misconfiguration fails loudly.
 * Shared by the incremental scan, group-input assembly, and the Class-1 materialize path so their
 * behaviour cannot drift apart.
 */
@Component
@RequiredArgsConstructor
public class SensitiveProjectionGuard {

    private final SensitiveColumnAccess sensitiveAccess;
    private final ColumnMappingRepository columns;
    private final CatalogQueryService catalog;

    /**
     * Rejects the operation if {@code projection} includes a sensitive column of {@code source} and the
     * calling identity is not entitled to see sensitive columns.
     *
     * @param operation short label used in the error message (e.g. {@code "scan"})
     * @throws SensitiveColumnNotEntitledException (403) if a sensitive column is projected un-entitled
     */
    public void enforce(String source, Collection<String> projection, String operation) {
        enforce(source, projection, operation, "source");
    }

    /**
     * As {@link #enforce(String, Collection, String)}, with {@code kind} naming what {@code source} is in the
     * refusal message. The aggregate pipeline path enforces per <em>owning</em> table, so it reaches this
     * with an enrichment as often as with a source, and calling an enrichment a "source" in the refusal
     * misdescribes the very thing the operator has to go and look at.
     */
    public void enforce(String source, Collection<String> projection, String operation, String kind) {
        // Read AND clearance, not the clearance alone: nothing on these paths enforces TABLE_READ on the
        // owning table first, so the clearance by itself would let a caller with no read reach the values.
        if (sensitiveAccess.mayReadSensitive(source)) {
            return;
        }
        // Either spelling of every sensitive column, collected once: a projection names columns by their
        // logical name, but a raw-name fallback can name the physical one.
        Set<String> sensitive = new HashSet<>();
        for (ColumnMappingEntity column : columns.findByTableNameOrderByOrdinal(source)) {
            if (column.isSensitive()) {
                sensitive.add(column.getName());
                sensitive.add(column.getSourceName());
            }
        }
        for (String name : projection) {
            if (sensitive.contains(name)) {
                throw new SensitiveColumnNotEntitledException(operation + " of " + kind + " '" + source
                        + "' reads sensitive column '" + name
                        + "'; reading it requires TABLE_READ and TABLE_SENSITIVE on '" + source + "'");
            }
        }
    }

    /**
     * Re-attributes a governed-expression failure that only a non-entitled caller can see.
     *
     * <p>The check above can only run once an expression has been parsed, because parsing is what reveals
     * which columns it reads — but the SQL front end validates against a schema from which sensitive
     * columns are <em>hidden</em> for a non-entitled caller, so such a caller's parse fails first with an
     * unknown-column error. That would surface a sensitive-access refusal as a 422 "invalid expression",
     * which is both misleading and leaks nothing useful to a legitimate caller.
     *
     * <p>The stored pipeline passed the registration gate under an entitled author, so for a non-entitled
     * caller a fresh parse failure over a source that has sensitive columns is attributable to that
     * narrowed view rather than to a malformed pipeline. Re-attributing it to
     * {@link SensitiveColumnNotEntitledException} fails closed and keeps the contract honest; an entitled
     * caller's failure is returned untouched, so a genuinely broken pipeline (e.g. a source column dropped
     * since registration) still reports accurately to the caller who can act on it.
     *
     * @return the exception to throw — the original one, or a 403 in its place
     */
    public RuntimeException reattribute(String source, String operation, RuntimeException failure) {
        return reattribute(source, operation, failure, false);
    }

    /**
     * As {@link #reattribute(String, String, RuntimeException)}, with {@code includeEnrichments} also
     * treating a sensitive column of {@code source}'s ACTIVE enrichments as grounds for the
     * re-attribution — for a caller whose predicates may name one.
     *
     * <p><b>Only a caller that admits enrichment references may pass true</b> — the aggregate pipeline path
     * and, since pipelines resolve against their read source's entity, every pipeline-scoped read. For such a caller
     * an unknown-column parse failure over an enrichment column is an entitlement refusal wearing a parse
     * error's clothes: the column is hidden from a non-entitled caller's schema view, so the predicate
     * cannot parse. Without this the refusal would surface as a 422 validation failure whenever the
     * <em>source</em> happens to declare no sensitive column of its own — fails closed either way, but the
     * wrong stable code, and the contract promises 403. A caller that refuses enrichment references passes
     * false, because for it the same failure really is a malformed declaration.
     */
    public RuntimeException reattribute(
            String source, String operation, RuntimeException failure, boolean includeEnrichments) {
        // Entitlement is per owning table, so "is anything hidden from this caller?" is asked per table:
        // the read source, and — for a caller whose predicates may name one — each of its enrichments.
        boolean anythingHidden = hidesSensitive(source)
                || (includeEnrichments && catalog.activeEnrichmentsOf(source).stream()
                        .anyMatch(enrichment -> hidesSensitive(enrichment.getName())));
        if (!anythingHidden) {
            return failure;
        }
        return new SensitiveColumnNotEntitledException(operation + " of source '" + source
                + "' could not be validated for a caller who may not read its sensitive columns;"
                + " reading them requires TABLE_READ and TABLE_SENSITIVE on the owning table");
    }

    /**
     * Whether {@code table} has a sensitive column hidden from the caller's schema view. The clearance
     * alone, deliberately: this asks what the query model hid, and the model filters by the clearance.
     */
    private boolean hidesSensitive(String table) {
        return hasSensitiveColumn(table) && !sensitiveAccess.maySeeSensitive(table);
    }

    private boolean hasSensitiveColumn(String table) {
        return columns.findByTableNameOrderByOrdinal(table).stream()
                .anyMatch(ColumnMappingEntity::isSensitive);
    }

    /**
     * Runs {@code action} — the parse/analysis of a stored pipeline's governed SQL — with
     * {@link #reattribute} applied to any {@link PipelineValidationException} it raises. Every path that
     * analyses a stored pipeline's SQL against the <em>calling</em> identity's schema view goes through this,
     * so the scan, group assembly, and materialize cannot report the same refusal differently.
     */
    public <T> T reattributing(String source, String operation, Supplier<T> action) {
        return reattributing(source, operation, action, false);
    }

    /**
     * As {@link #reattributing(String, String, Supplier)}, with {@code includeEnrichments} treating a
     * sensitive column of {@code source}'s ACTIVE enrichments as grounds for the re-attribution too — for a
     * pipeline-scoped read, which admits enrichment references and so can fail to parse over one.
     */
    public <T> T reattributing(
            String source, String operation, Supplier<T> action, boolean includeEnrichments) {
        try {
            return action.get();
        } catch (PipelineValidationException ex) {
            throw reattribute(source, operation, ex, includeEnrichments);
        }
    }

    /**
     * As {@link #enforce(String, Collection, String)} over a whole <b>entity</b>: the source's own sensitive
     * columns under either spelling, <em>and</em> each ACTIVE enrichment's under the qualified flat name a
     * pipeline-scoped read projects them by.
     *
     * <p>This strictly tightens the gate rather than relaxing it — it is the check a pipeline-scoped read needs
     * once it may reference an enrichment column, because the source-only form knows nothing about
     * {@code "<enrichment>.<column>"} and would let a sensitive one through unexamined. The source half is
     * enforced by delegating to the existing method unchanged, so the posture that closes a raw-name
     * fallback is preserved exactly.
     */
    public void enforceEntity(String source, Collection<String> projection, String operation) {
        enforce(source, projection, operation);
        for (TableDefinitionEntity enrichment : catalog.activeEnrichmentsOf(source)) {
            String owner = enrichment.getName();
            if (sensitiveAccess.mayReadSensitive(owner)) {
                continue;
            }
            Set<String> qualified = new HashSet<>();
            for (ColumnMappingEntity column : columns.findByTableNameOrderByOrdinal(owner)) {
                if (column.isSensitive()) {
                    // Only the qualified flat name: that is the sole spelling an enrichment column has in
                    // the entity namespace, and the sole one a pipeline-scoped read can project it under.
                    qualified.add(owner + "." + column.getName());
                }
            }
            for (String name : projection) {
                if (qualified.contains(name)) {
                    throw new SensitiveColumnNotEntitledException(operation + " projects sensitive column '"
                            + name + "' on enrichment '" + owner
                            + "'; reading them requires TABLE_READ and TABLE_SENSITIVE on the owning table");
                }
            }
        }
    }
}
