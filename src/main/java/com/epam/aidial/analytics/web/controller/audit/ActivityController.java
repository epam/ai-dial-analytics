package com.epam.aidial.analytics.web.controller.audit;

import com.epam.aidial.analytics.exception.ActivityNotFoundException;
import com.epam.aidial.analytics.service.audit.AuditActivityService;
import com.epam.aidial.analytics.web.dto.audit.AuditActivityDto;
import com.epam.aidial.analytics.web.dto.page.PageDto;
import com.epam.aidial.analytics.web.dto.page.PageRequestDto;
import com.epam.aidial.analytics.web.mapper.audit.AuditActivityMapper;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The audit activity feed: what changed in the table catalog, when, and by whom.
 *
 * <p>Read-only by construction. There is no endpoint here that creates, edits, or deletes an activity,
 * a revision, or a snapshot — an audit trail that could be edited through its own API would not be one.
 *
 * <p>The listing is a {@code POST} despite being a read: its filter list is a structured body, not
 * something a query string expresses without inventing an encoding. This is the contract the sibling
 * DIAL control planes use, adopted verbatim so their admin frontend needs no second client.
 *
 * <p>Both routes <b>declare</b> {@link com.epam.aidial.analytics.web.security.Privilege#AUDIT_READ}.
 * Nothing is inherited: the {@code /v1/**} rule only authenticates. {@code AUDIT_READ} is the feed's own
 * privilege rather than a table one because a feed entry names a resource and an author and carries no
 * column value, so there is no single table whose bar it could be derived from — this capability's own
 * spec withdrew that derivation once reads became per-table and one of four rungs. The consequence is
 * stated rather than hidden: a caller holding {@code AUDIT_READ} learns that a table it cannot read
 * changed. Filtering the feed by table visibility needs the predicate inside the SQL, because paging
 * happens there, and is a named follow-up.
 */
@RestController
@RequestMapping("/v1/activities")
@RequiredArgsConstructor
@Tag(name = "Audit trail", description = "What changed in the table catalog, when, and by whom.")
public class ActivityController {

    private final AuditActivityService service;
    private final AuditActivityMapper mapper;

    @PostMapping
    @RequiresPrivilege(Privilege.AUDIT_READ)
    @Operation(summary = "List activities",
            description = "Paged, sorted and filtered. Filters are a flat AND-list over an allow-listed "
                    + "column set; a range is two filters on the same column. With no sorts the feed is "
                    + "ordered newest first, so paging is stable.")
    public PageDto<AuditActivityDto> list(@Valid @RequestBody(required = false) PageRequestDto request) {
        // An absent body means "the defaults": first page, newest first, unfiltered. Reading that as an
        // empty request rather than rejecting it keeps the simplest call the simplest to make.
        return mapper.toPageDto(service.list(request == null
                ? new PageRequestDto(null, null, null, null)
                : request));
    }

    @GetMapping("/{activityId}")
    @RequiresPrivilege(Privilege.AUDIT_READ)
    @Operation(summary = "Get one activity",
            description = "Returns the single feed entry with this identifier.")
    public AuditActivityDto get(
            @Parameter(description = "Activity identifier, as returned by the listing")
            @PathVariable String activityId) {
        return mapper.toDto(service.get(parseId(activityId)));
    }

    /**
     * A malformed identifier is answered as "no such activity" rather than as a validation error: to a
     * caller, an id that cannot name anything and one that names nothing are the same outcome, and the
     * feed's ids are opaque strings it received rather than values it composed.
     */
    private static UUID parseId(String activityId) {
        try {
            return UUID.fromString(activityId);
        } catch (IllegalArgumentException e) {
            throw new ActivityNotFoundException("activity '" + activityId + "' not found");
        }
    }
}
