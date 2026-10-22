package com.epam.aidial.analytics.service.query.saved;

import com.epam.aidial.analytics.exception.SavedQueryNotFoundException;
import com.epam.aidial.analytics.web.dto.query.saved.SavedQueryRequestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for saved queries, and the one place their authorization rules are applied.
 *
 * <p>Every single-row operation goes through {@link #loadVisible}, whose repository predicate treats a
 * row the caller may not see exactly like one that does not exist. Both answer {@code 404}: a
 * {@code 403} would confirm that some other person has a saved query under that id, which is the same
 * disclosure the sensitive-column rules forbid at column granularity.
 */
@Service
@RequiredArgsConstructor
public class SavedQueryService {

    /** Distinguishes a saved-query id at a glance; the remainder is random (see the class comment on ids). */
    private static final String ID_PREFIX = "sq_";

    private final SavedQueryRepository repository;
    private final SavedQueryAccess access;
    private final SavedQueryBodyValidator validator;
    private final SavedQueryBodyResolver resolver;
    private final SavedQueryJson json;

    /**
     * The caller's saved queries at {@code scope}, most recently updated first.
     *
     * <p>The owner filter is dropped <strong>only</strong> when security is disabled, never merely because
     * the author came back null. An authenticated caller can have no author — a token that carries no
     * configured principal claim authenticates with a null name — and treating that as {@code none} mode
     * would hand them everyone else's personal rows. This is the same predicate {@link #loadVisible} uses,
     * so a row reachable by one path is reachable by the other.
     */
    @Transactional(readOnly = true)
    public List<SavedQueryEntity> list(SavedQueryScope scope) {
        final String author = access.currentAuthor();
        if (scope == SavedQueryScope.COMMON || (author == null && !access.isSecurityEnabled())) {
            // COMMON has no owner term; `none` mode has no owner to filter by, so the filter fails open.
            return repository.findByScopeOrderByUpdatedAtDesc(scope);
        }
        if (author == null) {
            // Security on, no resolvable principal: this caller owns nothing addressable, so it sees
            // nothing personal. `findVisible` answers 404 for the same rows; the list must agree.
            return List.of();
        }
        return repository.findByScopeAndOwnerIdOrderByUpdatedAtDesc(scope, author);
    }

    @Transactional(readOnly = true)
    public SavedQueryEntity get(String id) {
        return loadVisible(id);
    }

    @Transactional
    public SavedQueryEntity create(SavedQueryRequestDto request) {
        access.requireAnyWriteAuthority();
        validator.validate(request);
        final SavedQueryScope scope = scopeOf(request);
        access.requireWritable(scope);
        access.requireAttributable(scope, access.currentAuthor());
        final List<String> source = resolver.resolveSource(request);

        final Instant now = Instant.now();
        final SavedQueryEntity entity = new SavedQueryEntity();
        entity.setId(ID_PREFIX + UUID.randomUUID().toString().replace("-", ""));
        entity.setOwnerId(access.currentAuthor());
        // A snapshot of the author for display, taken once. Preserved across a replace with the
        // principal it belongs to, so the pair always describes the same person and an edit by another
        // admin on a common saved query cannot overwrite the author's name with the editor's.
        entity.setOwnerEmail(access.currentAuthorEmail());
        entity.setGeneration(1L);
        entity.setCreatedAt(now);
        apply(entity, request, scope, source, now);
        return repository.save(entity);
    }

    /**
     * Full replace. {@code id}, {@code owner_id}, {@code owner_email}, and {@code created_at} are
     * preserved; {@code generation} is bumped and {@code updated_at} refreshed.
     *
     * <p>A scope change must satisfy the write rule for <strong>both</strong> the stored and the requested
     * scope. Checking only the requested one would let a non-admin demote a {@code common} definition to
     * their own {@code personal} scope, removing it from everyone else.
     */
    @Transactional
    public SavedQueryEntity replace(String id, SavedQueryRequestDto request) {
        // Before the lookup, deliberately: the endpoint gate is deferred, so loadVisible's 404-vs-403 split
        // would otherwise tell a caller holding no saved-query privilege whether a `common` row exists.
        access.requireAnyWriteAuthority();
        final SavedQueryEntity entity = loadVisible(id);
        validator.validate(request);
        final SavedQueryScope scope = scopeOf(request);
        access.requireWritable(entity.getScope());
        access.requireWritable(scope);
        // The owner is preserved, so demoting a null-owned common row to personal would orphan it just
        // as surely as creating one — the check reads the owner that will be stored, not the caller.
        access.requireAttributable(scope, entity.getOwnerId());
        final List<String> source = resolver.resolveSource(request);

        entity.setGeneration(entity.getGeneration() + 1);
        apply(entity, request, scope, source, Instant.now());
        return repository.save(entity);
    }

    @Transactional
    public void delete(String id) {
        access.requireAnyWriteAuthority();
        final SavedQueryEntity entity = loadVisible(id);
        access.requireWritable(entity.getScope());
        repository.delete(entity);
    }

    /**
     * Loads a saved query the caller may see, or throws {@link SavedQueryNotFoundException}. In
     * {@code none} mode there is no author and every row is visible.
     */
    private SavedQueryEntity loadVisible(String id) {
        final String author = access.currentAuthor();
        return (author == null && !access.isSecurityEnabled()
                ? repository.findById(id)
                : repository.findVisible(id, author))
                .orElseThrow(() -> new SavedQueryNotFoundException("saved query '" + id + "' was not found"));
    }

    /** An absent scope means {@code personal}, matching the list endpoint's default. */
    private static SavedQueryScope scopeOf(SavedQueryRequestDto request) {
        return request.scope() == null ? SavedQueryScope.PERSONAL : request.scope();
    }

    /** Copies the caller-supplied fields; every server-owned field is set by the caller of this method. */
    private void apply(
            SavedQueryEntity entity,
            SavedQueryRequestDto request,
            SavedQueryScope scope,
            List<String> source,
            Instant now) {
        entity.setName(request.name().trim());
        entity.setDescription(request.description());
        entity.setTag(request.tag());
        entity.setScope(scope);
        entity.setSource(source);
        entity.setQuery(json.write(request.query()));
        // Normalized, not raw: validation reads a blank `sql` as absent, and storing "" next to a
        // structured body would violate the table's exactly-one-body CHECK — a 500 for a 422 mistake.
        entity.setSqlText(SavedQueryBodyValidator.sqlBody(request));
        // Likewise the validated union rather than the caller's raw node, so nothing that the time-intent
        // rule did not accept reaches the jsonb column.
        entity.setTimeIntent(json.write(validator.timeIntent(request)));
        entity.setResultView(request.resultView() == null ? SavedQueryResultView.TABLE : request.resultView());
        entity.setChart(json.write(request.chart()));
        entity.setUpdatedAt(now);
    }
}
