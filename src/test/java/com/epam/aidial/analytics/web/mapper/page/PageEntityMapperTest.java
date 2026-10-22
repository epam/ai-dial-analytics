package com.epam.aidial.analytics.web.mapper.page;

import com.epam.aidial.analytics.exception.InvalidFilterColumnException;
import com.epam.aidial.analytics.exception.InvalidFilterValueException;
import com.epam.aidial.analytics.service.audit.ActivityResourceType;
import com.epam.aidial.analytics.service.audit.AuditActivityEntity;
import com.epam.aidial.analytics.web.dto.page.FilterDto;
import com.epam.aidial.analytics.web.dto.page.FilterOperatorDto;
import com.epam.aidial.analytics.web.dto.page.PageRequestDto;
import com.epam.aidial.analytics.web.dto.page.SortDirectionDto;
import com.epam.aidial.analytics.web.dto.page.SortDto;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Unit coverage for {@link PageEntityMapper}: the paging/sorting translation, and the guards that keep a
 * malformed filter a 400 instead of a 500 — the allow-list, per-column value coercion, and literal
 * treatment of LIKE wildcards.
 *
 * <p>The Criteria API is mocked rather than executed: what matters here is <em>which</em> predicate is
 * built from a given filter, and with what value. That the resulting SQL actually selects the right rows
 * is asserted end-to-end by the feed integration test, against a real database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PageEntityMapperTest {

    private static final String TIMESTAMP = "epochTimestampMs";

    @Mock
    private Root<AuditActivityEntity> root;
    @Mock
    private CriteriaBuilder builder;
    @Mock
    private Path<Object> path;
    @Mock
    private Expression<String> lowered;
    @Mock
    private Predicate predicate;

    private PageEntityMapper<AuditActivityEntity> mapper;

    @BeforeEach
    void setUp() {
        mapper = new PageEntityMapper<>(
                Map.of(
                        "resourceType", FilterableColumn.enumerated("resourceType", ActivityResourceType.class),
                        "resourceId", FilterableColumn.string("resourceId"),
                        TIMESTAMP, FilterableColumn.number(TIMESTAMP),
                        "parentActivityId", FilterableColumn.uuid("parentActivityId"),
                        "activityId", FilterableColumn.uuid("activityId"),
                        "revision", FilterableColumn.integer("revisionNumber")),
                Sort.by(Sort.Order.desc(TIMESTAMP)),
                List.of("revision", "activityId"));
        given(root.get(anyString())).willReturn(path);
        given(builder.lower(any())).willReturn(lowered);
        given(builder.like(any(), anyString(), anyChar())).willReturn(predicate);
    }

    @Test
    void anEmptySortListFallsBackToNewestFirst() {
        final PageRequest page = mapper.toPageRequest(new PageRequestDto(null, null, List.of(), List.of()));

        assertThat(page.getPageNumber()).isZero();
        assertThat(page.getPageSize()).isEqualTo(20);
        assertThat(page.getSort()).containsExactly(
                Sort.Order.desc(TIMESTAMP), Sort.Order.desc("revisionNumber"), Sort.Order.desc("activityId"));
    }

    @Test
    void sortTermsAreAppliedInOrderAndMappedToTheirAttribute() {
        final PageRequest page = mapper.toPageRequest(new PageRequestDto(2, 50,
                List.of(new SortDto("resourceType", SortDirectionDto.ASC),
                        new SortDto(TIMESTAMP, SortDirectionDto.DESC)),
                List.of()));

        assertThat(page.getPageNumber()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(50);
        assertThat(page.getSort()).containsExactly(
                Sort.Order.asc("resourceType"), Sort.Order.desc(TIMESTAMP),
                Sort.Order.desc("revisionNumber"), Sort.Order.desc("activityId"));
    }

    @Test
    void theTieBreakerFollowsTheDirectionOfTheLastExplicitTerm() {
        final PageRequest page = mapper.toPageRequest(new PageRequestDto(0, 20,
                List.of(new SortDto(TIMESTAMP, SortDirectionDto.ASC)), List.of()));

        // A fixed DESC tie-breaker would order each revision's tied activities newest-first inside an
        // oldest-first request — stable, but the reverse of what was asked for.
        assertThat(page.getSort()).containsExactly(
                Sort.Order.asc(TIMESTAMP), Sort.Order.asc("revisionNumber"), Sort.Order.asc("activityId"));
    }

    @Test
    void everyOrderingEndsWithTheUniqueTieBreaker() {
        // Ties on the timestamp are the rule here, not the exception: all of one revision's activities
        // carry that revision's timestamp. Without a unique final term the database may order tied rows
        // differently between two queries, so offset paging would repeat and skip rows.
        final PageRequest page = mapper.toPageRequest(new PageRequestDto(0, 20,
                List.of(new SortDto(TIMESTAMP, SortDirectionDto.DESC)), List.of()));

        assertThat(page.getSort()).containsExactly(
                Sort.Order.desc(TIMESTAMP), Sort.Order.desc("revisionNumber"), Sort.Order.desc("activityId"));
    }

    @Test
    void theTieBreakerIsNotAppendedTwiceWhenTheCallerSortsByIt() {
        final PageRequest page = mapper.toPageRequest(new PageRequestDto(0, 20,
                List.of(new SortDto("activityId", SortDirectionDto.ASC)), List.of()));

        assertThat(page.getSort()).containsExactly(
                Sort.Order.asc("activityId"), Sort.Order.asc("revisionNumber"));
    }

    @Test
    void defaultSortNamingUnservedColumnFailsAtConstruction() {
        // The two vocabularies coincide for most columns, which is what makes the one place they differ
        // a trap: passing the JPA attribute here would build fine and then throw on every default-sorted
        // request. Catching it at construction turns that into a startup failure instead.
        assertThatThrownBy(() -> new PageEntityMapper<AuditActivityEntity>(
                Map.of("resourceId", FilterableColumn.string("resourceId")),
                Sort.by(Sort.Order.desc("revisionNumber")),
                List.of("resourceId")))
                .isInstanceOf(InvalidFilterColumnException.class)
                .hasMessageContaining("revisionNumber");
    }

    @Test
    void tieBreakerNamingUnservedColumnFailsAtConstruction() {
        assertThatThrownBy(() -> new PageEntityMapper<AuditActivityEntity>(
                Map.of("resourceId", FilterableColumn.string("resourceId")),
                Sort.by(Sort.Order.desc("resourceId")),
                List.of("nope")))
                .isInstanceOf(InvalidFilterColumnException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void stringFilterValueIsPassedThroughVerbatim() {
        apply(new FilterDto("resourceId", FilterOperatorDto.eq, " my_table "));

        // Trimming here would answer a different question than the one asked, and would make a stored
        // value with surrounding whitespace unreachable by any operator. `in` trims only because
        // splitting a delimited list requires it.
        verify(builder).equal(path, " my_table ");
    }

    @Test
    void blankContainsValueIsRejectedRatherThanMatchingEverything() {
        // LIKE '%%' would return the whole table for `co` and nothing for `nc` — a filter that reads as
        // a restriction and acts as its opposite.
        assertThatThrownBy(() -> apply(new FilterDto("resourceId", FilterOperatorDto.co, "   ")))
                .isInstanceOf(InvalidFilterValueException.class)
                .hasMessageContaining("resourceId");
        assertThatThrownBy(() -> apply(new FilterDto("resourceId", FilterOperatorDto.nc, "")))
                .isInstanceOf(InvalidFilterValueException.class);
    }

    @Test
    void theDefaultOrderingMatchesTheSameOrderingSentExplicitly() {
        final PageEntityMapper<AuditActivityEntity> ascending = new PageEntityMapper<>(
                Map.of("resourceId", FilterableColumn.string("resourceId"),
                        "activityId", FilterableColumn.uuid("activityId")),
                Sort.by(Sort.Order.asc("resourceId")),
                List.of("activityId"));

        // The constructor completes its ordering by the same rule the request path uses, so omitting the
        // default sort and sending it explicitly cannot page rows in two different orders.
        assertThat(ascending.toPageRequest(new PageRequestDto(null, null, List.of(), List.of())).getSort())
                .isEqualTo(ascending.toPageRequest(new PageRequestDto(null, null,
                        List.of(new SortDto("resourceId", SortDirectionDto.ASC)), List.of())).getSort());
    }

    @Test
    void anUnknownSortColumnIsRejected() {
        final PageRequestDto request =
                new PageRequestDto(null, null, List.of(new SortDto("noSuchColumn", null)), List.of());

        assertThatThrownBy(() -> mapper.toPageRequest(request))
                .isInstanceOf(InvalidFilterColumnException.class)
                .hasMessageContaining("noSuchColumn");
    }

    @Test
    void anUnknownFilterColumnIsRejected() {
        assertThatThrownBy(() -> apply(new FilterDto("password", FilterOperatorDto.eq, "x")))
                .isInstanceOf(InvalidFilterColumnException.class)
                .hasMessageContaining("password");
    }

    @Test
    void nonNumericValueForNumericColumnIsRejected() {
        assertThatThrownBy(() -> apply(new FilterDto(TIMESTAMP, FilterOperatorDto.ge, "abc")))
                .isInstanceOf(InvalidFilterValueException.class)
                .hasMessageContaining(TIMESTAMP);
    }

    @Test
    void anUnknownEnumConstantIsRejected() {
        assertThatThrownBy(() -> apply(new FilterDto("resourceType", FilterOperatorDto.eq, "Nonsense")))
                .isInstanceOf(InvalidFilterValueException.class)
                .hasMessageContaining("resourceType");
    }

    @Test
    void malformedUuidIsRejected() {
        assertThatThrownBy(() -> apply(new FilterDto("parentActivityId", FilterOperatorDto.eq, "not-a-uuid")))
                .isInstanceOf(InvalidFilterValueException.class)
                .hasMessageContaining("parentActivityId");
    }

    @Test
    void nullValueIsRejectedRatherThanReachingTheQuery() {
        assertThatThrownBy(() -> apply(new FilterDto("resourceId", FilterOperatorDto.co, null)))
                .isInstanceOf(InvalidFilterValueException.class)
                .hasMessageContaining("resourceId");
    }

    @Test
    void equalityCoercesTheValueToTheColumnsType() {
        apply(new FilterDto(TIMESTAMP, FilterOperatorDto.eq, "1700000000000"));

        verify(builder).equal(path, 1_700_000_000_000L);
    }

    @Test
    void enumEqualityComparesAgainstTheConstantNotTheString() {
        apply(new FilterDto("resourceType", FilterOperatorDto.eq, "TableColumn"));

        verify(builder).equal(path, ActivityResourceType.TableColumn);
    }

    @Test
    void containsLowercasesAndEscapesTheValuesWildcards() {
        apply(new FilterDto("resourceId", FilterOperatorDto.co, "100%_Real"));

        final ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(builder).like(any(), pattern.capture(), anyChar());
        assertThat(pattern.getValue()).isEqualTo("%100\\%\\_real%");
    }

    @Test
    void containsIsRejectedOnNonTextColumn() {
        assertThatThrownBy(() -> apply(new FilterDto(TIMESTAMP, FilterOperatorDto.co, "17")))
                .isInstanceOf(InvalidFilterValueException.class)
                .hasMessageContaining(TIMESTAMP);
    }

    @Test
    void numberTooWideForItsColumnIsRejectedHere() {
        // Long.valueOf would accept this happily and let it fail inside Hibernate instead, where it
        // surfaces as a 5xx blaming the database for a value the caller typed.
        assertThatThrownBy(() -> apply(new FilterDto("revision", FilterOperatorDto.eq, "2147483648")))
                .isInstanceOf(InvalidFilterValueException.class)
                .hasMessageContaining("revision");
        assertThatThrownBy(() -> apply(new FilterDto("revision", FilterOperatorDto.eq, "-3000000000")))
                .isInstanceOf(InvalidFilterValueException.class);
    }

    @Test
    void anIntegerColumnStillAcceptsValuesInRange() {
        apply(new FilterDto("revision", FilterOperatorDto.eq, "42"));

        verify(builder).equal(path, 42);
    }

    @Test
    void rangeOperatorsAreRejectedOnColumnsWithNoMeaningfulOrder() {
        // Comparing an enum would order its stored spelling: `activityType gt "Create"` would return
        // Delete and Update, which says something about the alphabet rather than about the activities.
        assertThatThrownBy(() -> apply(new FilterDto("resourceType", FilterOperatorDto.gt, "Table")))
                .isInstanceOf(InvalidFilterValueException.class)
                .hasMessageContaining("resourceType");
        assertThatThrownBy(() -> apply(new FilterDto("parentActivityId", FilterOperatorDto.lt,
                "00000000-0000-7000-8000-000000000001")))
                .isInstanceOf(InvalidFilterValueException.class)
                .hasMessageContaining("parentActivityId");
    }

    @Test
    void notEqualsAlsoMatchesRowsWithNoValue() {
        apply(new FilterDto("resourceId", FilterOperatorDto.ne, "x"));

        // SQL's NULL <> 'x' is neither true nor false, so without widening this every row with no value
        // is silently dropped — on parentActivityId that means every top-level activity disappears.
        verify(builder).isNull(path);
        verify(builder).or(any(), any());
    }

    @Test
    void notContainsAlsoMatchesRowsWithNoValue() {
        apply(new FilterDto("resourceId", FilterOperatorDto.nc, "x"));

        verify(builder).isNull(path);
        verify(builder).or(any(), any());
    }

    @Test
    void inSplitsOnCommasAndCoercesEachMember() {
        apply(new FilterDto("resourceId", FilterOperatorDto.in, "a, b ,c"));

        verify(path).in(List.of("a", "b", "c"));
    }

    @Test
    void inRejectsValueWithNoMembers() {
        assertThatThrownBy(() -> apply(new FilterDto("resourceId", FilterOperatorDto.in, " , ")))
                .isInstanceOf(InvalidFilterValueException.class)
                .hasMessageContaining("resourceId");
    }

    @Test
    void rangeOperatorsBuildTheMatchingComparison() {
        apply(new FilterDto(TIMESTAMP, FilterOperatorDto.ge, "10"));
        apply(new FilterDto(TIMESTAMP, FilterOperatorDto.le, "20"));
        apply(new FilterDto(TIMESTAMP, FilterOperatorDto.gt, "30"));
        apply(new FilterDto(TIMESTAMP, FilterOperatorDto.lt, "40"));

        verify(builder).greaterThanOrEqualTo(any(), (Comparable<Object>) any());
        verify(builder).lessThanOrEqualTo(any(), (Comparable<Object>) any());
        verify(builder).greaterThan(any(), (Comparable<Object>) any());
        verify(builder).lessThan(any(), (Comparable<Object>) any());
    }

    @Test
    void missingOperatorIsRejected() {
        assertThatThrownBy(() -> apply(new FilterDto("resourceId", null, "x")))
                .isInstanceOf(InvalidFilterValueException.class)
                .hasMessageContaining("resourceId");
    }

    /** Runs one filter through the specification, which is where the predicate is actually built. */
    private void apply(FilterDto filter) {
        mapper.toSpecification(List.of(filter)).toPredicate(root, null, builder);
    }
}
