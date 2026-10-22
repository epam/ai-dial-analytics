package com.epam.aidial.analytics.service.audit;

import com.epam.aidial.analytics.exception.ActivityNotFoundException;
import com.epam.aidial.analytics.service.query.saved.SavedQueryAccess;
import com.epam.aidial.analytics.service.query.saved.SavedQueryEntity;
import com.epam.aidial.analytics.service.query.saved.SavedQueryScope;
import com.epam.aidial.analytics.web.dto.page.PageRequestDto;
import com.epam.aidial.analytics.web.mapper.page.FilterableColumn;
import com.epam.aidial.analytics.web.mapper.page.PageEntityMapper;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the activity feed. There is deliberately no write side: activities are produced by
 * {@link AuditRevisionListener} through the revision cascade, and nothing may edit or remove one.
 *
 * <p>One resource type needs narrower visibility than the {@code /v1/**} catch-all this feed otherwise
 * inherits: {@link ActivityResourceType#SavedQuery}, which is owner/{@code common}-scoped
 * ({@code SavedQueryRepository#findVisible}). The narrowing is a content filter within an
 * otherwise-successful feed response, not an authorization denial — mirroring how the feed already omits
 * rows that do not match a filter rather than refusing the request. It resolves against the resource's
 * <strong>current</strong> stored state, so a deleted saved query's history becomes visible to
 * a {@code SAVED_QUERY_PUBLISH} holder only, including to its former owner — a stated trade-off, not
 * an oversight.
 *
 * <p>{@link ActivityResourceType#Pipeline} deliberately has <b>no</b> narrowing. The bar for a
 * resource's audit surface is whatever reading that resource's current state requires, and a pipeline's
 * own reads need only a mapped application role — so its activities ride the catch-all like a table's.
 * A narrowing here would leave a caller able to read a pipeline but not to see that it was created.
 *
 * <p>{@link #get(UUID)} applies the same narrowing the feed does: a filtered-out row answers
 * {@code 404 activity_not_found}, keeping the no-disclosure shape the saved-query capability
 * already uses for its live reads.
 */
@Service
public class AuditActivityService {

    /** The attribute the feed falls back to ordering by; see {@link #PAGE_MAPPER}. */
    private static final String TIMESTAMP_COLUMN = "epochTimestampMs";

    /**
     * The feed's filterable and sortable vocabulary.
     *
     * <p>Every audited column of the entity is here; these are the axes the reference frontend queries by.
     * The author's email among them discloses nothing new: every row returns it, and the feed is fully
     * pageable, so any caller who may read the feed may already read every email in it. Withholding emails
     * from some readers would take an actual mechanism — masking the field by role — and a decision that
     * the feed should be less readable than the tables it describes.
     *
     * <p>The default sort is newest-first, which matters beyond aesthetics: offset paging over an
     * unordered result set silently duplicates and skips rows between pages, and the references document
     * having no default as a client hazard. It is also what the frontend sends anyway.
     *
     * <p>{@code revision} then {@code activityId} are the tie-breakers that make any ordering total.
     * Ties on the timestamp are the rule rather than the exception here — every activity of one revision
     * carries that revision's timestamp — so without them the "stable paging" the default sort exists to
     * provide would not actually hold.
     *
     * <p>The revision comes first, and that ordering of the two is the point. {@code activityId} is a
     * UUIDv7 whose counter makes it monotonic within a millisecond, but only <em>per process</em>: two
     * replicas writing in the same millisecond would order arbitrarily against each other, so a feed
     * broken only by the id could show an older revision's activity above a newer one. The revision is a
     * database identity, so it is monotonic across every replica; the id then separates rows within one
     * revision, where Envers reports no order to preserve anyway.
     */
    private static final PageEntityMapper<AuditActivityEntity> PAGE_MAPPER = new PageEntityMapper<>(
            Map.of(
                    "activityId", FilterableColumn.uuid("activityId"),
                    "activityType", FilterableColumn.enumerated("activityType", ActivityType.class),
                    "resourceType", FilterableColumn.enumerated("resourceType", ActivityResourceType.class),
                    "resourceId", FilterableColumn.string("resourceId"),
                    TIMESTAMP_COLUMN, FilterableColumn.number(TIMESTAMP_COLUMN),
                    "initiatedAuthor", FilterableColumn.string("initiatedAuthor"),
                    "initiatedEmail", FilterableColumn.string("initiatedEmail"),
                    // integer, not number: the attribute is an int, and a 64-bit value would sail past
                    // this layer only to fail as a 5xx when Hibernate narrowed it.
                    "revision", FilterableColumn.integer("revisionNumber"),
                    "parentActivityId", FilterableColumn.uuid("parentActivityId"),
                    "importId", FilterableColumn.uuid("importId")),
            Sort.by(Sort.Order.desc(TIMESTAMP_COLUMN)),
            List.of("revision", "activityId"));

    private final AuditActivityRepository activities;
    private final SavedQueryAccess access;

    public AuditActivityService(AuditActivityRepository activities, SavedQueryAccess access) {
        this.activities = activities;
        this.access = access;
    }

    /** One page of the feed, filtered and ordered as requested, with the size of the whole match. */
    @Transactional(readOnly = true)
    public Page<AuditActivityEntity> list(PageRequestDto request) {
        final Specification<AuditActivityEntity> spec = Specification
                .where(PAGE_MAPPER.toSpecification(request.filtersOrEmpty()))
                .and(savedQueryNarrowing());
        return activities.findAll(spec, PAGE_MAPPER.toPageRequest(request));
    }

    /** One activity by id; 404 if there is no such entry or it is narrowed out for this caller. */
    @Transactional(readOnly = true)
    public AuditActivityEntity get(UUID activityId) {
        final Specification<AuditActivityEntity> idEquals =
                (root, query, builder) -> builder.equal(root.get("activityId"), activityId);
        final Specification<AuditActivityEntity> spec = Specification
                .where(idEquals)
                .and(savedQueryNarrowing());
        return activities.findOne(spec)
                .orElseThrow(() -> new ActivityNotFoundException("activity '" + activityId + "' not found"));
    }

    /**
     * A {@code SavedQuery}-typed row is included for a {@code SAVED_QUERY_PUBLISH} holder
     * unconditionally, for any caller
     * when the saved query's <strong>current</strong> scope is {@code COMMON}, or for the caller recorded
     * as its <strong>current</strong> owner — the same rule {@code SavedQueryRepository.findVisible}
     * applies to the live read. A deleted saved query has no current row, so the {@code EXISTS} fails for
     * every caller but a {@code SAVED_QUERY_PUBLISH} holder, including its former owner — a stated
     * trade-off, not an
     * oversight (see the class Javadoc).
     */
    private Specification<AuditActivityEntity> savedQueryNarrowing() {
        return (root, query, builder) -> {
            if (!access.isSecurityEnabled() || access.maySeeEverySavedQuery()) {
                return builder.conjunction();
            }
            final Subquery<String> visible = query.subquery(String.class);
            final Root<SavedQueryEntity> savedQuery = visible.from(SavedQueryEntity.class);
            visible.select(savedQuery.get("id"));
            visible.where(builder.and(
                    builder.equal(savedQuery.get("id"), root.get("resourceId")),
                    builder.or(
                            builder.equal(savedQuery.get("scope"), SavedQueryScope.COMMON),
                            builder.equal(savedQuery.get("ownerId"), access.currentAuthor()))));
            return builder.or(
                    builder.notEqual(root.get("resourceType"), ActivityResourceType.SavedQuery),
                    builder.exists(visible));
        };
    }
}
