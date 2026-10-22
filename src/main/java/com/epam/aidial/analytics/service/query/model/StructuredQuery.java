package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * Top-level query envelope. {@code entity} and {@code mode} are always required; the remaining
 * sections are constrained by {@code mode}, but that coherence is enforced during translation
 * ({@code StructuredQueryBuilder}/{@code FilterTranslator}), not structurally here.
 *
 * <p>{@code finalRead} is the wire's {@code final} (a Java keyword, hence the component name): whether the
 * entity is read under ClickHouse {@code FINAL} semantics. It is three-valued on purpose — {@code null}
 * (absent) reads a version-collapsing source final and an {@code append} one raw, which {@code false} does
 * not — so it is a boxed {@link Boolean} that stays absent through a round trip, never the
 * {@code AS_EMPTY} idiom {@code distinct} uses. See {@code FinalDecision}.
 */
public record StructuredQuery(
        String entity,

        @JsonDeserialize(using = FilterNodeDeserializer.class)
        FilterNode filter,

        @NotNull QueryMode mode,

        // Optional flag: absent/null defaults to false rather than failing deserialization.
        @JsonSetter(nulls = Nulls.AS_EMPTY) boolean distinct,
        List<OutputColumn> select,
        @JsonProperty("group_by") List<String> groupBy,

        @JsonDeserialize(using = FilterNodeDeserializer.class)
        FilterNode having,

        List<SortItem> sort,
        PageSpec page,

        @JsonProperty("final") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean finalRead) {

    /** A query that says nothing about {@code final}: its source's engine decides (see {@code FinalDecision}). */
    public StructuredQuery(
            String entity, FilterNode filter, QueryMode mode, boolean distinct, List<OutputColumn> select,
            List<String> groupBy, FilterNode having, List<SortItem> sort, PageSpec page) {
        this(entity, filter, mode, distinct, select, groupBy, having, sort, page, null);
    }

    /** The same query with a different page. */
    public StructuredQuery withPage(PageSpec newPage) {
        return new StructuredQuery(entity, filter, mode, distinct, select, groupBy, having, sort, newPage, finalRead);
    }

    /** The same query with a different filter ({@code null} for none). */
    public StructuredQuery withFilter(FilterNode newFilter) {
        return new StructuredQuery(entity, newFilter, mode, distinct, select, groupBy, having, sort, page, finalRead);
    }

    /** The same query against a different entity name. */
    public StructuredQuery withEntity(String newEntity) {
        return new StructuredQuery(newEntity, filter, mode, distinct, select, groupBy, having, sort, page, finalRead);
    }

    /** The same query with {@code final} set to {@code value} ({@code null} for absent). */
    public StructuredQuery withFinalRead(Boolean value) {
        return new StructuredQuery(entity, filter, mode, distinct, select, groupBy, having, sort, page, value);
    }
}
