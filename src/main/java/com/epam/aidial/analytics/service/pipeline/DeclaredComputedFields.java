package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.MemberOrder;
import com.epam.aidial.analytics.service.enrichment.model.MemberSelect;
import com.epam.aidial.analytics.service.enrichment.model.TransformVar;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.pipeline.model.GroupKey;
import com.epam.aidial.analytics.service.pipeline.model.Measure;
import com.epam.aidial.analytics.service.pipeline.model.PipelineSpec;
import com.epam.aidial.analytics.service.query.ComputedFields;

/**
 * Refuses, on <b>every</b> declaration write, a computed field ({@link ComputedFields}) named anywhere but a
 * {@code transform.inputs} entry (see {@code openspec/specs/enrichment-registry/spec.md} and
 * {@code openspec/specs/aggregate-pipeline/spec.md}).
 *
 * <p>Not deferred to the arming gate, like the other refusals {@code PipelineService} asks of a draft: a computed
 * field's value exists only after a read's statement has run, so no further authoring makes such a declaration valid,
 * and the pipeline data plane reads a draft as it is stored. A draft ranking its members by one would be sampled by the
 * stand-in the read renders in the field's place, an order that looks plausible and means nothing.
 *
 * <p>By name alone, so it needs no entitlement, no catalog row and no parse: the registry is closed and a computed
 * field has one spelling, its qualified flat name. A fragment is scanned outside its literals and comments
 * ({@link QualifiedColumnTokens#referencedNames}); the arming gate still parses it and refuses the same names again.
 */
final class DeclaredComputedFields {

    private DeclaredComputedFields() {
    }

    /** Throws 422 {@code pipeline_validation_failed} on the first computed field the declaration names outside its inputs. */
    static void requireNone(PipelineEntity pipeline, PipelineSpec spec) {
        fragment("filter", pipeline.getFilterSql());
        name("trigger.group_by", pipeline.getTriggerGroupBy());
        if (pipeline.getReadyWhen() != null) {
            fragment("ready_when.signal", pipeline.getReadyWhen().getSignal());
        }
        final MemberSelect select = pipeline.getMemberSelect();
        if (select != null) {
            fragment("member_select.prefer_sql", select.getPreferSql());
            if (select.getOrderBy() != null) {
                for (final MemberOrder order : select.getOrderBy()) {
                    name("member_select.order_by", order == null ? null : order.getColumn());
                }
            }
        }
        if (spec instanceof EnrichSpec enrich && enrich.getTransform() != null) {
            for (final TransformVar var : enrich.getTransform().effectiveOutputVars()) {
                fragment("output variable '" + var.getName() + "'", var.getSql());
            }
        }
        if (spec instanceof AggregateSpec aggregate) {
            if (aggregate.getGroupBy() != null) {
                for (final GroupKey key : aggregate.getGroupBy()) {
                    name("group_by", key == null ? null : key.inputColumn());
                }
            }
            if (aggregate.getMeasures() != null) {
                for (final Measure measure : aggregate.getMeasures()) {
                    if (measure != null) {
                        name("measure '" + measure.getName() + "'", measure.getColumn());
                        fragment("measure '" + measure.getName() + "' where", measure.getWhere());
                    }
                }
            }
            fragment("the SQL declaration", aggregate.getSql());
        }
    }

    private static void name(String what, String column) {
        if (column != null && ComputedFields.isComputed(column.trim())) {
            throw new PipelineValidationException(ComputedFields.declarationMessage(what, column.trim()));
        }
    }

    private static void fragment(String what, String sql) {
        for (final String referenced : QualifiedColumnTokens.referencedNames(sql)) {
            name(what, referenced);
        }
    }
}
