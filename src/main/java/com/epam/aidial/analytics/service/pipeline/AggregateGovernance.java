package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.exception.PipelineConflictException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.SensitiveGrantWidensException;
import com.epam.aidial.analytics.exception.SystemTableException;
import com.epam.aidial.analytics.service.enrichment.support.EntityFields;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionReturnType;
import org.apache.commons.lang3.StringUtils;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The governance rules an aggregate declaration is held to <b>whichever way it is declared</b> — the form
 * ({@code group_by} + {@code measures}, {@link AggregateSpecValidator}) or one SQL statement
 * ({@link AggregateSqlValidator}). They are typed on table names and {@link EntityFields} rather than on the form's
 * {@code GroupKey}/{@code Measure}, which is what lets both gates share one copy: two copies of the input and target
 * checks, the sensitive gate or the lineage rule could disagree about the same pipeline.
 *
 * <p>Not a bean: {@link AggregateSpecValidator} builds it from its own collaborators, so the gate's construction is
 * unchanged for every caller.
 */
final class AggregateGovernance {

    /** Which declared target column types can hold a given aggregate function's result. */
    static final Map<QueryFunctionReturnType, Set<QueryFieldType>> RETURN_TYPE_TARGETS = Map.of(
            QueryFunctionReturnType.LONG, EnumSet.of(QueryFieldType.LONG, QueryFieldType.INTEGER),
            QueryFunctionReturnType.NUMERIC,
            EnumSet.of(QueryFieldType.DECIMAL, QueryFieldType.LONG, QueryFieldType.INTEGER),
            // An array result has exactly one home. The shape is all this map can say — the element type
            // follows the operand, and is checked separately by requireArrayElementAccepts.
            QueryFunctionReturnType.ARRAY, EnumSet.of(QueryFieldType.ARRAY));

    /**
     * The aggregates that never return NULL, whatever their input column's nullability, and are therefore
     * exempt from the nullability rule. {@code count} returns 0 for an empty group; {@code group_uniq_array}
     * skips NULLs and returns an empty array. For the latter the exemption is not a convenience but the only
     * workable rule: ClickHouse has no {@code Nullable(Array)}, so a target array column <em>cannot</em> be
     * declared nullable, and keying the rule on the input alone would leave an array measure over a nullable
     * column with no declarable target at all.
     */
    static final Set<String> NEVER_NULL_AGGREGATES = Set.of("count", ArrayValuedFunctions.GROUP_UNIQ_ARRAY);

    private final TableDefinitionRepository definitions;
    private final PipelineRepository pipelines;
    private final EnrichmentFreshness freshness;
    private final SensitiveProjectionGuard sensitiveGuard;
    private final SensitiveGrantInvariant sensitiveGrants;

    AggregateGovernance(TableDefinitionRepository definitions, PipelineRepository pipelines,
            EnrichmentFreshness freshness, SensitiveProjectionGuard sensitiveGuard, SensitiveGrantInvariant sensitiveGrants) {
        this.definitions = definitions;
        this.pipelines = pipelines;
        this.freshness = freshness;
        this.sensitiveGuard = sensitiveGuard;
        this.sensitiveGrants = sensitiveGrants;
    }

    // --- measures ----------------------------------------------------------------------------------

    /**
     * A measure's target column must be one {@link #RETURN_TYPE_TARGETS} lists for its aggregate's declared return
     * type. {@code SAME_AS_ARGUMENT} (min/max) follows the input column, so any declared type is plausible and is left
     * to the caller.
     */
    static void requireReturnTypeAccepts(String measure, QueryFunctionReturnType returns, QueryFieldType declared) {
        final Set<QueryFieldType> accepted = RETURN_TYPE_TARGETS.get(returns);
        if (accepted != null && !accepted.contains(declared)) {
            throw new PipelineValidationException("measure '" + measure + "' returns " + returns.name().toLowerCase(Locale.ROOT)
                    + " but target column '" + measure + "' is declared '" + declared.code() + "'");
        }
    }

    /** The values an array aggregate collects must be scalars: a set of an array, object or map is no declarable type. */
    static void requireCollectsScalar(String measure, String fn, String operand, QueryFieldType source) {
        if (source == QueryFieldType.ARRAY || source == QueryFieldType.OBJECT || source == QueryFieldType.MAP) {
            throw new PipelineValidationException("measure '" + measure + "' computes '" + fn + "' over input column '"
                    + operand + "', which is declared '" + source.code() + "'; the values collected into an array must be "
                    + "scalars, and an array of " + source.code() + " is not a declarable column type");
        }
    }

    /**
     * An array measure's declared {@code element_type} must equal the collected input column's type; a target column
     * declaring none ({@code element == null}) is left alone.
     */
    static void requireArrayElementMatches(String measure, String operand, QueryFieldType source, QueryFieldType element, String target) {
        if (element != null && element != source) {
            throw new PipelineValidationException("measure '" + measure + "' collects input column '" + operand + "' declared '"
                    + source.code() + "' but target column '" + measure + "' on '" + target + "' is declared array of '"
                    + element.code() + "'; the set holds the input values themselves, so declare the element type '"
                    + source.code() + "' — otherwise ClickHouse converts them on insert and the rollup reads as "
                    + element.code() + " data");
        }
    }

    /** A measure whose result is fractional must land in a {@code decimal} column. */
    static void requireFractionPreserved(String measure, String fn, String operand, boolean fractional, QueryFieldType declared) {
        if (fractional && declared != QueryFieldType.DECIMAL) {
            throw new PipelineValidationException("measure '" + measure + "' computes '" + fn + "' over '" + operand
                    + "', which yields a fractional value, but target column '" + measure + "' is declared '" + declared.code()
                    + "'; declare it 'decimal', or the fraction is truncated on insert and the rollup reads as an exact "
                    + "whole number");
        }
    }

    // --- input -------------------------------------------------------------------------------------

    TableDefinitionEntity validateInput(String inputTable, String target) {
        if (StringUtils.isBlank(inputTable)) {
            throw new PipelineValidationException("a pipeline must name exactly one input table");
        }
        if (inputTable.equals(target)) {
            throw new PipelineValidationException("a pipeline may not read its own target '" + target + "'");
        }
        final TableDefinitionEntity input = definitions.findById(inputTable)
                .orElseThrow(() -> new PipelineValidationException(
                        "input table '" + inputTable + "' does not exist"));
        if (input.getType() != TableType.SOURCE) {
            throw new PipelineValidationException("input table '" + inputTable + "' is not a source");
        }
        if (input.getStatus() != TableStatus.ACTIVE) {
            throw new PipelineValidationException(
                    "input table '" + inputTable + "' is not materialized; it has no usable ClickHouse table");
        }
        // The refresh windows the input by its total order, which only a source that declares the pair has:
        // it is optional at schema-define, so an input may legitimately not be scannable — and a rollup
        // keyed on several columns should not declare one at all (no single column identifies its rows).
        if (StringUtils.isBlank(input.getVersionColumn()) || StringUtils.isBlank(input.getIdentityColumn())) {
            throw new PipelineValidationException("input table '" + inputTable + "' is not scannable: it "
                    + "declares no version/identity column pair, so a refresh could not window it");
        }
        return input;
    }

    // --- target ------------------------------------------------------------------------------------

    TableDefinitionEntity validateTarget(String pipelineName, String target) {
        if (StringUtils.isBlank(target)) {
            throw new PipelineValidationException("a pipeline must name a target table");
        }
        final TableDefinitionEntity definition = definitions.findById(target)
                .orElseThrow(() -> new PipelineValidationException(
                        "target table '" + target + "' does not exist"));
        if (definition.getType() != TableType.SOURCE) {
            throw new PipelineValidationException("target '" + target + "' is not a source; an aggregate "
                    + "pipeline writes a source (aggregation is not a table kind)");
        }
        if (definition.getStatus() != TableStatus.ACTIVE) {
            throw new PipelineValidationException(
                    "target '" + target + "' is not materialized; define its schema first");
        }
        if (definition.getWriteMode() != TableWriteMode.UPSERT_BY_KEY) {
            throw new PipelineValidationException("target '" + target + "' is written '"
                    + definition.getWriteMode().code() + "'; a rollup target must be written "
                    + "'upsert_by_key' so a recomputed group key replaces its row instead of adding a "
                    + "second one");
        }
        if (definition.isSystem()) {
            throw new SystemTableException(
                    "table '" + target + "' is system-owned and cannot be a pipeline target");
        }
        pipelines.findByTarget(target)
                .filter(existing -> !existing.getName().equals(pipelineName))
                .ifPresent(existing -> {
                    throw new PipelineConflictException("target '" + target
                            + "' already has pipeline '" + existing.getName() + "'; one pipeline per table");
                });
        return definition;
    }

    // --- enrichments -------------------------------------------------------------------------------

    /** The enrichments a resolved reference set reaches through the input's grain join. */
    static Set<String> referencedEnrichmentsOf(EntityFields input, Set<String> referenced) {
        return referenced.stream()
                .filter(input::isEnrichmentSourced)
                .map(input::ownerOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Refuses a reference to an enrichment whose values have <b>no settled position</b>, so a refresh could
     * never tell whether they are present yet for the rows it is about to aggregate.
     *
     * <p>Two shapes, and the distinction from the shapes that are <em>allowed</em> is what this rule is
     * about. A group-trigger enrichment's value is derived from a group of source rows and recomputed
     * whenever the group gains a member: the only sound position is the one below which every group has
     * settled, which a single long-lived conversation pins at its earliest member indefinitely, and the value
     * does not merely arrive late but <em>changes</em>, which no clamp addresses. An enrichment ADAS does not
     * write itself has a written-through position held by the runner and never reported. In both cases a
     * rollup would be silently and permanently wrong, in both directions.
     *
     * <p>What is <b>not</b> refused: an enrichment no pipeline writes at all (its absences are final rather
     * than late — nothing is coming), and one whose writer reads a different table from this input (its
     * position is
     * settled, just not on the axis a refresh windows over, so it is admitted unclamped and reported in
     * pipeline state). Refusing those would remove working capability to guard against the ordinary freshness
     * gap rather than against a permanently moving target.
     *
     * <p>Run at registration and re-run at execution like every other rule here, which is what catches an
     * upstream pipeline switched to the group trigger <em>after</em> this one was registered: it fails loudly
     * instead of
     * writing a rollup from provisional values.
     */
    void requireAggregatableEnrichments(String inputTable, EntityFields input, Set<String> referenced) {
        final Set<String> enrichments = referencedEnrichmentsOf(input, referenced);
        if (enrichments.isEmpty()) {
            return;
        }
        freshness.classify(enrichments, inputTable).forEach((enrichment, clampability) -> {
            if (clampability.refused()) {
                throw new PipelineValidationException(unaggregatable(enrichment, clampability));
            }
        });
    }

    private static String unaggregatable(String enrichment, EnrichmentFreshness.Clampability why) {
        return switch (why) {
            case GROUP_TRIGGER -> "enrichment '" + enrichment + "' is written by a pipeline using the group "
                    + "trigger, and a group-derived value has no settled position to aggregate against: its "
                    + "members span arbitrary positions in the input, and it is recomputed every time the "
                    + "group gains a member, so a rollup over it would be wrong both from reading provisional "
                    + "values and from keeping superseded ones. Aggregate a row-grain enrichment of this "
                    + "input instead, or roll up the group's own table once it is materialized";
            case NOT_CLASS1 -> "enrichment '" + enrichment + "' is written by a pipeline ADAS does not execute "
                    + "itself — its transform is not 'sql', or it declares none — so nothing records how far its "
                    + "values have been written, and a refresh could not tell whether the rows it is aggregating "
                    + "have been enriched yet. Only an enrichment computed in the database (a Class-1 'sql' "
                    + "transform) can be read by a pipeline";
            default -> "enrichment '" + enrichment + "' cannot be aggregated (" + why + ")";
        };
    }

    // --- sensitive columns -------------------------------------------------------------------------

    /**
     * A target column derived from a {@code sensitive} input column must itself be declared
     * {@code sensitive}. An aggregate is otherwise a laundering path: a protected column as a group key, or
     * {@code min}/{@code max} over one, copies its values into a target column that the query surface would
     * then serve to every caller. The scheduler refreshes under a system identity that may read sensitive
     * columns, so nothing downstream would stop it — the flag has to be carried across the derivation, and
     * the honest place to require that is where the derivation is declared.
     *
     * <p>Applied to every measure, not only the value-revealing ones: a {@code sum} over a group of one is
     * that row's value, so exempting the additive functions would leave the same leak behind a narrower door.
     *
     * <p><b>And to a column reached through the grain join, on the same terms.</b> {@code input} is the flat
     * entity view, so a {@code sensitive} enrichment column answers {@code true} here exactly as a sensitive
     * input column does and the refusal names it by its qualified flat name. That is not a nicety: the
     * scheduler's elevation to the two read privileges ({@code SystemIdentity}) is justified in its own
     * javadoc by
     * this rule being total, so an enrichment column exempt from it would turn the elevation into a
     * laundering path rather than leaving a gap to follow up.
     */
    static void requireSensitivityPropagated(
            String inputColumn, String targetColumn, EntityFields input, EntityFields output, String target) {
        if (StringUtils.isBlank(inputColumn) || !input.isSensitive(inputColumn)) {
            return;
        }
        if (!output.isSensitive(targetColumn)) {
            throw new PipelineValidationException("target column '" + targetColumn + "' derives from sensitive "
                    + "input column '" + inputColumn + "', so it must itself be declared sensitive on target '"
                    + target + "'; otherwise the rollup would expose protected values to every caller");
        }
    }

    /**
     * The grants half of the laundering rule for a <b>rollup</b> target, on the same terms as the enrich
     * path. Distinct from {@link #requireSensitivityPropagated}, which requires the derived column to carry
     * the flag: carrying it stops the column being served to an unentitled caller, but says nothing about
     * <em>who</em> is entitled, and the target is a {@code SOURCE} with its own {@code sensitive}
     * list. Without this, a rollup of a protected column into a target granted more widely was readable by
     * a caller with no grant at all on the input — and more directly than through an enrichment, because a
     * rollup is addressable on its own.
     *
     * <p>Checked against {@code inputTable} explicitly rather than left to the invariant's stored-edge walk,
     * because a registration in progress has no pipeline row yet for that walk to find.
     *
     * <p>Re-thrown as a validation failure for the reason the enrich path gives: a governance violation on
     * register or enable is this capability's {@code 422 pipeline_validation_failed}, and one condition
     * must not be reported under two codes.
     */
    void requireSensitiveGrantsNotWidened(TableDefinitionEntity target, String inputTable) {
        try {
            sensitiveGrants.requireNotWidenedAgainst(target, inputTable);
        } catch (SensitiveGrantWidensException widens) {
            throw new PipelineValidationException(widens.getMessage());
        }
    }

    /**
     * The same rule against every enrichment the declaration reaches through the grain join. Separate from
     * the input-table call because the two edges are found differently: the input is on the pipeline row,
     * while an enrichment column is addressed as {@code "<enrichment>.<column>"} and exists only in the
     * resolved reference set — which is why a rollup of a protected enrichment column into a widely-granted
     * target went unchecked while the input edge was covered.
     */
    void requireSensitiveGrantsNotWidened(TableDefinitionEntity target, Set<String> enrichments) {
        try {
            sensitiveGrants.requireNotWidenedAgainst(target, enrichments);
        } catch (SensitiveGrantWidensException widens) {
            throw new PipelineValidationException(widens.getMessage());
        }
    }

    /**
     * Runs the entitlement gate once per owning table.
     *
     * <p>{@link SensitiveProjectionGuard#enforce} resolves a projection against <em>one</em> table's catalog
     * rows, so a qualified enrichment reference has to reach it under the enrichment's own name and its bare
     * column spelling. Handed to the input table whole, {@code "usage_client_identity.user_email"} matches no
     * sensitive column of the input and the gate passes — which is the one shape this change would otherwise
     * introduce a way to launder, since a non-entitled caller could then read a protected enrichment column
     * that the same caller cannot select through the query surface.
     *
     * <p>The input table is enforced even when nothing is referenced under it, so a declaration reading only
     * enrichment columns still makes the call the input has always had; the guard is a no-op on an empty set.
     */
    void enforceSensitive(String inputTable, EntityFields input, Set<String> referenced, String operation) {
        final Map<String, Set<String>> byOwner = new LinkedHashMap<>();
        byOwner.put(inputTable, new LinkedHashSet<>());
        for (final String name : referenced) {
            // Attributed to the input unless it resolves through the grain join: every reference here has
            // already been validated, so an unresolvable name is unreachable — and attributing it to the
            // input fails closed rather than skipping the gate for it.
            final boolean joined = input.isEnrichmentSourced(name);
            byOwner.computeIfAbsent(joined ? input.ownerOf(name) : inputTable, table -> new LinkedHashSet<>())
                    .add(joined ? name.substring(name.indexOf('.') + 1) : name);
        }
        byOwner.forEach((owner, names) -> sensitiveGuard.enforce(
                owner, names, operation, owner.equals(inputTable) ? "source" : "enrichment"));
    }
}
