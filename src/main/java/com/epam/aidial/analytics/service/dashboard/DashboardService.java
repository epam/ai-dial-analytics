package com.epam.aidial.analytics.service.dashboard;

import com.epam.aidial.analytics.exception.DashboardConflictException;
import com.epam.aidial.analytics.exception.DashboardNotFoundException;
import com.epam.aidial.analytics.exception.DashboardValidationException;
import com.epam.aidial.analytics.exception.InlineQueryRejectedException;
import com.epam.aidial.analytics.service.dashboard.DashboardValidator.Operation;
import com.epam.aidial.analytics.service.dashboard.model.DashboardWidget;
import com.epam.aidial.analytics.service.query.saved.SavedQueryBodyResolver;
import com.epam.aidial.analytics.web.dto.dashboard.DashboardRequestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for dashboards, and the one place their authorization and concurrency rules are applied.
 *
 * <p>Every single-row operation goes through {@link #loadVisible}, whose predicate treats a dashboard the
 * caller may not see exactly like one that does not exist. A replace then runs the capability's ordered
 * checks, so a caller that may not write a dashboard learns nothing about its generation:
 * any-write-authority ({@code 403}), lookup ({@code 404}), authority for the stored and the requested
 * visibility ({@code 403}), the generation ({@code 409}, or {@code 422} when unusable), then the body
 * ({@code 422}, or the query surface's status for an inline body it rejects).
 *
 * <p>{@code generation} is the entity's {@code @Version}. The early comparison gives a stale caller its
 * {@code 409} before any body work; the versioned {@code UPDATE} and {@code DELETE} at flush close the race
 * after it. The flush is explicit so that a lost race surfaces here, inside the transaction, as an
 * optimistic-lock failure this class turns into {@code 409 dashboard_conflict}. Left to commit, it would
 * escape the method and reach the handler as a data-access failure.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** Distinguishes a dashboard id at a glance; the remainder is random, so ids cannot be enumerated. */
    private static final String ID_PREFIX = "dash_";

    private final DashboardRepository repository;
    private final DashboardAccess access;
    private final DashboardValidator validator;
    private final SavedQueryBodyResolver resolver;
    private final DashboardJson json;

    /**
     * Every dashboard the caller may see, most recently updated first. The owner filter is dropped only in
     * {@code none} mode, never because an authenticated caller's principal came back absent: such a caller
     * owns nothing and sees the {@code public} dashboards only, as {@link #loadVisible} answers for them.
     */
    @Transactional(readOnly = true)
    public List<DashboardSummary> list() {
        return access.isSecurityEnabled()
                ? repository.findVisibleSummaries(access.currentAuthor())
                : repository.findAllSummaries();
    }

    @Transactional(readOnly = true)
    public DashboardEntity get(String id) {
        return loadVisible(id);
    }

    @Transactional
    public DashboardEntity create(DashboardRequestDto request) {
        access.requireAnyWriteAuthority();
        if (request != null && request.visibility() != null) {
            access.requireWritable(request.visibility());
        }
        final ValidatedDashboard dashboard = validator.validate(request, Operation.CREATE);
        resolveInlineBodies(dashboard.widgets());
        access.requireAttributable(dashboard.visibility());

        final Instant now = Instant.now();
        final DashboardEntity entity = new DashboardEntity();
        entity.setId(ID_PREFIX + UUID.randomUUID().toString().replace("-", ""));
        entity.setOwnerId(access.currentAuthor());
        // A display snapshot taken once and preserved with the principal it belongs to, so an edit by a
        // publisher cannot relabel the author.
        entity.setOwnerEmail(access.currentAuthorEmail());
        entity.setCreatedAt(now);
        apply(entity, dashboard, now);
        return repository.saveAndFlush(entity);
    }

    /** Whole-document replace. {@code id}, the owner pair and {@code created_at} are preserved. */
    @Transactional
    public DashboardEntity replace(String id, DashboardRequestDto request) {
        access.requireAnyWriteAuthority();
        final DashboardEntity entity = loadVisible(id);
        access.requireWritable(entity.getVisibility());
        if (request != null && request.visibility() != null) {
            access.requireWritable(request.visibility());
        }
        requireCurrentGeneration(entity, request == null ? null : request.generation());
        final ValidatedDashboard dashboard = validator.validate(request, Operation.REPLACE);
        if (access.orphans(dashboard.visibility(), entity.getOwnerId())) {
            // The owner is preserved, so withdrawing an ownerless public dashboard would leave a private one
            // no caller could reach. The caller's identity is not at fault; the dashboard has no owner.
            throw new DashboardValidationException("this dashboard has no owner, so it cannot be made private; it "
                    + "would be unreachable by everyone");
        }
        resolveInlineBodies(dashboard.widgets());
        apply(entity, dashboard, Instant.now());
        try {
            return repository.saveAndFlush(entity);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw conflict(id);
        }
    }

    @Transactional
    public void delete(String id) {
        access.requireAnyWriteAuthority();
        final DashboardEntity entity = loadVisible(id);
        access.requireWritable(entity.getVisibility());
        try {
            repository.delete(entity);
            repository.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw conflict(id);
        }
    }

    /**
     * The generation check: unusable is {@code 422}, stale is {@code 409}. Only an integral JSON number in
     * {@code long} range is usable — {@code 3.0} is a {@code DoubleNode} that would otherwise convert.
     */
    private static void requireCurrentGeneration(DashboardEntity entity, JsonNode generation) {
        if (generation == null || generation.isNull()) {
            throw new DashboardValidationException(
                    "'generation' is required on a replace: send the generation of the dashboard you read");
        }
        if (!generation.isIntegralNumber() || !generation.canConvertToLong()) {
            throw new DashboardValidationException("'generation' must be an integer");
        }
        if (generation.longValue() != entity.getGeneration()) {
            // The current generation is deliberately not named: a publish-only caller cannot read it.
            throw new DashboardConflictException("dashboard '" + entity.getId() + "' has changed since generation "
                    + generation.longValue() + "; read it again and reapply your change");
        }
    }

    /**
     * Translates every inline body against the caller's view of the catalog, as the query surface would,
     * without executing it. A rejection keeps its own status and machine code and gains the widget's id.
     */
    private void resolveInlineBodies(List<DashboardWidget> widgets) {
        for (final DashboardWidget widget : widgets) {
            if (widget.inlineQuery() == null) {
                continue;
            }
            try {
                resolver.resolveSource(widget.inlineQuery().query(), widget.inlineQuery().sql());
            } catch (RuntimeException e) {
                throw new InlineQueryRejectedException(widget.id(), e);
            }
        }
    }

    private void apply(DashboardEntity entity, ValidatedDashboard dashboard, Instant now) {
        entity.setName(dashboard.name());
        entity.setDescription(dashboard.description());
        entity.setVisibility(dashboard.visibility());
        entity.setTimeIntent(json.write(dashboard.time()));
        entity.setCompare(dashboard.compare());
        entity.setWidgets(json.write(dashboard.widgets()));
        entity.setUpdatedAt(now);
    }

    /** Loads a dashboard the caller may see, or throws {@link DashboardNotFoundException}. */
    private DashboardEntity loadVisible(String id) {
        return (access.isSecurityEnabled()
                ? repository.findVisible(id, access.currentAuthor())
                : repository.findById(id))
                .orElseThrow(() -> new DashboardNotFoundException("dashboard '" + id + "' was not found"));
    }

    private static DashboardConflictException conflict(String id) {
        return new DashboardConflictException("dashboard '" + id + "' was changed or deleted by another write; "
                + "read it again and reapply your change");
    }
}
