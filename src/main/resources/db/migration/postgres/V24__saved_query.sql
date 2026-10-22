-- Saved queries (capability saved-queries): a named, reusable Query Builder state holding authored
-- INTENT only. Never catalog data, never anything a run produces, never personal data.
--
-- Exactly one of `query` / `sql_text` carries the body. The CHECK is deliberately redundant with the
-- service-layer validation: the service check is what produces the ErrorView a caller can act on,
-- while the constraint makes a future code path that forgets it fail loudly rather than write a row
-- no reader can interpret.
--
-- There is NO foreign key on `source`. A saved query outlives the entity it names: deleting a catalog
-- table leaves its saved queries readable and unchanged, and the client renders an unresolvable
-- reference as a single "unavailable" state — identical for a dropped column and one the caller may
-- not see, which is what the authentication capability's indistinguishability rule requires. A
-- cascade here would silently delete a user's work as a side effect of a catalog change.
--
-- `owner_id` is NULLABLE on purpose: in `none` security mode there is no authenticated caller and the
-- whole feature fails open, exactly as the sensitive-column filter does. A synthetic placeholder owner
-- would become a real, colliding identity the moment the deployment switched to `oidc`.
--
-- Enum-like columns are uppercase TEXT with CHECK constraints (the @Enumerated(EnumType.STRING)
-- convention used across this catalog); the loosely-structured client state lives in JSONB, validated
-- in the service layer on create and on replace.
CREATE TABLE saved_query (
    -- Server-assigned, opaque, and deliberately RANDOM rather than sequential: the "404 never 403"
    -- rule only conceals another caller's saved queries if their ids cannot be enumerated.
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL CHECK (length(trim(name)) > 0),
    description  TEXT,
    -- At most one label per saved query; free text, not a controlled vocabulary.
    tag          TEXT,
    scope        TEXT NOT NULL CHECK (scope IN ('PERSONAL', 'COMMON')),
    -- Authorship is a pair, and the two halves do different jobs.
    --   owner_id    the principal claim (default `oid`): opaque, directory-assigned, never reassigned,
    --               stored without being parsed, and the ONLY field ownership is decided by.
    --   owner_email the same person's email, for showing "Saved by". DISPLAY ONLY — nothing may
    --               authorize on it. An email changes on a name change or a domain migration and can
    --               later belong to someone else, so an equality check against it would decide access
    --               from a mutable, recyclable value. It sits next to an identity it resembles, which
    --               is exactly what makes that mistake easy.
    -- Both are NULL in `none` mode. owner_email is additionally NULL whenever the caller has no email
    -- to record: `security.require-email` defaults to false, and a plain DIAL project key has no person
    -- behind it. Both are preserved across a replace, so the pair always describes one person and an
    -- admin editing a common saved query cannot relabel its author.
    -- This mirrors how ai-dial-admin-backend and the deployment manager record audit authorship
    -- (`author` + `email`), fed from these same two claims.
    owner_id     TEXT,
    owner_email  TEXT,
    -- The queried entity, DERIVED from the body (never accepted from the request) so the two cannot
    -- disagree. No FK — see the header.
    source       TEXT NOT NULL,

    -- Exactly one of these two is the body. `query` is a structured-query DSL tree; `sql_text` is a
    -- SQL statement. Both are validated at save time by the same translation the corresponding
    -- translate-only endpoint performs, so a stored body is always one the execute endpoints accept.
    query        JSONB,
    sql_text     TEXT,

    -- The caller's TIME INTENT, not a resolved range: {"mode":"relative","period":"last_2d"} or
    -- {"mode":"absolute","from":...,"to":...} or NULL ("use whatever the toolbar has on open").
    -- Storing instants for a relative period would silently freeze the query to its authoring day.
    time_intent  JSONB,

    result_view  TEXT NOT NULL CHECK (result_view IN ('TABLE', 'CHART')),
    chart        JSONB,

    -- Bumped on every mutation, mirroring `rule` and `pipeline`. Nothing enforces it yet (PUT is an
    -- unconditional full replace), but it gives a client a change detector and leaves room for
    -- optimistic locking without a migration.
    generation   BIGINT NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT saved_query_exactly_one_body CHECK ((query IS NOT NULL) <> (sql_text IS NOT NULL))
);

-- The personal-scope read predicate (owner_id = :author AND scope = 'PERSONAL').
CREATE INDEX idx_saved_query_owner_scope ON saved_query (owner_id, scope);
-- The common-scope list, which has no owner term.
CREATE INDEX idx_saved_query_scope ON saved_query (scope);

COMMENT ON COLUMN saved_query.owner_email IS
    'Author''s email at creation time; display only, never used for authorization (see owner_id).';
