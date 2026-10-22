package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.exception.SavedQueryNotFoundException;
import com.epam.aidial.analytics.exception.SavedQueryValidationException;
import com.epam.aidial.analytics.service.audit.AuditSavedQuerySnapshotService;
import com.epam.aidial.analytics.service.audit.AuditSnapshotGuards;
import com.epam.aidial.analytics.service.query.saved.SavedQueryAccess;
import com.epam.aidial.analytics.service.query.saved.SavedQueryRepository;
import com.epam.aidial.analytics.service.query.saved.SavedQueryScope;
import com.epam.aidial.analytics.service.query.saved.SavedQueryService;
import com.epam.aidial.analytics.web.dto.query.saved.SavedQueryDto;
import com.epam.aidial.analytics.web.dto.query.saved.SavedQueryListDto;
import com.epam.aidial.analytics.web.dto.query.saved.SavedQueryRequestDto;
import com.epam.aidial.analytics.web.mapper.query.SavedQueryMapper;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Saved queries — named, reusable Query Builder state.
 *
 * <p>A saved query stores authored <strong>intent</strong>: a name, an optional description and tag,
 * either a structured query or SQL, the author's time intent, and how they last rendered the result. It
 * stores no catalog data and no run outcome. The body is stored exactly as authored, including a literal
 * bound to a {@code sensitive} column: the metadata database sits inside the same trusted perimeter as the
 * queried data store, so persisting such a value copies nothing across a boundary. What a caller may
 * reference at all is still decided by their own resolved view of the entity, so a column they cannot see
 * remains unresolvable here exactly as it is on the query endpoints.
 *
 * <p>On the <em>read</em> side that has a consequence worth stating plainly: a {@code common} row is
 * readable by every caller holding an application role, and its body is returned unredacted, so a literal
 * an entitled author bound to a {@code sensitive} column reaches callers who could not resolve that column
 * themselves. Writing {@code common} requires {@code SAVED_QUERY_PUBLISH} — separately grantable from
 * the authority to read sensitive
 * columns — so such a value is published by a caller entitled to it, deliberately; the read path adds no
 * sensitivity filter of its own.
 *
 * <p>A saved query does not execute itself. There is deliberately no {@code .../{id}/execute}: the client
 * reads the stored body and posts it to {@code /v1/queries/execute} or {@code /v1/queries/execute-sql},
 * which keeps a run a read and keeps this row free of run state.
 *
 * <p>Authorization is by <em>scope</em>, not by URL, so it lives in the service rather than in a
 * endpoint annotation: {@code common} writes require {@code SAVED_QUERY_PUBLISH}, while
 * {@code personal} writes need only the application role that {@code /v1/**} already requires. A saved
 * query the caller may not see answers {@code 404}, never {@code 403}.
 */
@RestController
@RequestMapping("/v1/saved-queries")
@RequiredArgsConstructor
@Tag(name = "Saved queries", description = "Store and reuse named Query Builder state.")
public class SavedQueryController {

    private final SavedQueryService service;
    private final SavedQueryMapper mapper;
    private final AuditSavedQuerySnapshotService snapshotService;
    private final SavedQueryRepository repository;
    private final SavedQueryAccess access;

    @GetMapping
    @RequiresPrivilege(Privilege.SAVED_QUERY_READ)
    @Operation(summary = "List saved queries",
            description = "Each entry is the same full object the single read returns, body included. "
                    + "Defaults to the caller's personal saved queries, most recently updated first.")
    public SavedQueryListDto list(
            @Parameter(description = "personal (default) or common")
            @RequestParam(name = "scope", required = false) String scope) {
        return mapper.toListDto(service.list(parseScope(scope)));
    }

    @PostMapping
    @RequiresPrivilege(value = Privilege.SAVED_QUERY_WRITE, deferred = true)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a saved query",
            description = "The body is validated by the same translation the matching translate-only "
                    + "endpoint performs, so a saved query is always executable as stored. id, owner_id, "
                    + "source, generation and the timestamps are server-assigned and rejected if supplied.")
    public SavedQueryDto create(@Valid @RequestBody SavedQueryRequestDto request) {
        return mapper.toDto(service.create(request));
    }

    @GetMapping("/{id}")
    @RequiresPrivilege(Privilege.SAVED_QUERY_READ)
    @Operation(summary = "Read a saved query",
            description = "Returns the full object including its body, exactly as persisted. A saved query "
                    + "the caller may not see is reported as not found.")
    public SavedQueryDto get(@PathVariable String id) {
        return mapper.toDto(service.get(id));
    }

    @PutMapping("/{id}")
    @RequiresPrivilege(value = Privilege.SAVED_QUERY_WRITE, deferred = true)
    @Operation(summary = "Replace a saved query",
            description = "Full replace. id, owner_id and created_at are preserved; generation is bumped. "
                    + "Changing scope requires the write permission for both the stored and the new scope.")
    public SavedQueryDto replace(@PathVariable String id, @Valid @RequestBody SavedQueryRequestDto request) {
        return mapper.toDto(service.replace(id, request));
    }

    @DeleteMapping("/{id}")
    @RequiresPrivilege(value = Privilege.SAVED_QUERY_WRITE, deferred = true)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a saved query")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    @GetMapping("/{id}/revision/{revision}")
    @RequiresPrivilege(Privilege.SAVED_QUERY_READ)
    @Operation(summary = "Get a saved query as it stood at a revision",
            description = "Returns the saved query's own stored fields reconstructed from the audit trail "
                    + "at the end of the given revision. 404 if the saved query did not exist at that "
                    + "revision, the revision is unknown, or the caller may not currently see it (visibility "
                    + "is resolved against the saved query's current scope/owner, not its historical one; a "
                    + "deleted saved query's history is visible to SAVED_QUERY_PUBLISH holders only, "
                    + "including its former "
                    + "owner). Revision ids come from the activity feed.")
    public SavedQueryDto snapshot(
            @PathVariable String id,
            @Parameter(description = "Revision id, as carried by an activity") @PathVariable String revision) {
        requireCurrentlyVisible(id);
        return mapper.toDto(snapshotService.snapshot(id, AuditSnapshotGuards.parseRevision(revision)));
    }

    /**
     * Mirrors the live read's own visibility rule ({@code SavedQueryService#loadVisible}) rather than the
     * historical state at the requested revision: a {@code SAVED_QUERY_PUBLISH} holder unconditionally,
     * any caller when the
     * saved query's <strong>current</strong> scope is {@code common}, or the caller recorded as its
     * <strong>current</strong> owner. A saved query invisible to the caller and one that never existed
     * answer the same way here, for the same disclosure reason {@code loadVisible} does.
     *
     * <p>Unconditionally passes in {@code none} mode — fails open, the same way every other
     * authorization check in the application does when security is off ({@link SavedQueryAccess}
     * documents this exact pattern). Deliberately <strong>not</strong> {@code existsById} there: this
     * gate is about a resource's <em>current</em> visibility, and a deleted saved query's snapshot is a
     * legitimate historical read whose subject no longer exists by definition.
     */
    private void requireCurrentlyVisible(String id) {
        if (!access.isSecurityEnabled() || access.maySeeEverySavedQuery()) {
            return;
        }
        if (repository.findVisible(id, access.currentAuthor()).isEmpty()) {
            throw new SavedQueryNotFoundException("saved query '" + id + "' was not found");
        }
    }

    /**
     * Parses the {@code scope} parameter by hand so an unrecognised value is a {@code 422} from this
     * capability's own vocabulary. Letting Spring bind the enum would raise a type-mismatch and answer
     * {@code 400} with a message naming the Java type.
     */
    private static SavedQueryScope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return SavedQueryScope.PERSONAL;
        }
        try {
            return SavedQueryScope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SavedQueryValidationException(
                    "unknown scope '" + scope + "'; expected 'personal' or 'common'");
        }
    }
}
