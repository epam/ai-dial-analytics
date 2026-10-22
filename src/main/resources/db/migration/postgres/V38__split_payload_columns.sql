-- Publish the five verbatim-content columns from dial_usage_log_payload instead of dial_usage_log.
--
-- The physical move is the ClickHouse trio V10 (create the payload table) / V11 (copy the existing
-- content) / V12 (drop the columns from dial_usage_log). This is the catalog half: it registers the
-- new table as a system ENRICHMENT of dial_usage_log keyed on event_id, and re-points the five
-- column_mapping rows onto it. From here the columns are addressed by their qualified flat names —
-- "dial_usage_log_payload.request_body", "dial_usage_log_payload.jwt_claims" and so on — in the DSL,
-- in execute-sql, in the translation endpoints and over Flight SQL, and the grain LEFT JOIN
-- (source.event_id = enrichment.event_id) is emitted only for a query that names one.
--
-- WHICH FIVE, AND WHY. request_body, response_body and assembled_response are the bulk — almost every
-- byte of dial_usage_log and all of its raw prompt/response content. jwt_claims and request_tags are
-- small documents and move for the other half of the argument: they are the two columns that can carry
-- credentials, cookies and forwarded client IPs (see V3 and V12), so a year is a long time to hold
-- them. All five are already seeded `sensitive` and stay that way — the rows are moved, not rewritten.
--
-- THE TWO JSON COLUMNS STAY NON-NULLABLE, and that is now explicitly safe rather than merely tolerated.
-- ClickHouse has no Nullable(JSON), so an unmatched grain join renders them as the empty document
-- rather than as NULL. Every accessor ADAS exposes for an object field returns over {} exactly what it
-- returns over a present document that lacks the key (toJSONString → '{}', subcolumn access → NULL,
-- json_extract_array → [], json_extract_string_ci → ''), so absence and emptiness are indistinguishable
-- by construction and no derived value can be silently corrupted by the default. That is the same
-- exemption array and map columns have always had; EntityFields.absenceIsUnrepresentable extends it to
-- object columns in this change, and TableColumnRules now refuses `nullable: true` on an object column
-- so the catalog can no longer record a flag the DDL cannot render.
--
-- BREAKING: the bare names stop resolving. A saved query, a pipeline declaration or a Class-1 sql
-- expression naming request_body / response_body / assembled_response / jwt_claims / request_tags on
-- dial_usage_log fails as an unknown field on its next use, and must be repointed at the qualified
-- name. There is deliberately no compatibility alias: the enrichment mechanism exists for exactly this
-- shape, and a bare name that silently meant a 90-day column on a one-year table would be worse than
-- one that fails.
--
-- WHY THE ROWS ARE MOVED RATHER THAN RE-INSERTED. column_mapping's primary key is
-- (table_name, source_name), so re-keying is legal, and every per-column attribute rides along
-- untouched: sensitive, heavy, nullable, description, display_name, tag, ordinal-independent metadata.
-- Re-declaring them by hand would restate six attributes across five columns — thirty chances to
-- silently drop `sensitive` from a column holding raw user content or a bearer token. The seeded flags
-- therefore need no restatement here and no separate verification: they are the same rows.
--
-- Ordinals 0-4 on the new table follow the physical column order of V10.
-- The vacated ordinals on dial_usage_log (32, 33, 34, 35 and 38) are left as GAPS: ordinal only
-- orders, nothing requires it dense, and renumbering the survivors would rewrite the column order
-- every client sees for no gain.
--
-- Note what is NOT set on the definition row:
--   * cardinality — not a column. V25 folded enrichment_grain into table_definition and derives
--     ZERO_OR_ONE at the mapper from "has a grain key", so setting grain_key is the whole declaration.
--   * partition_column / partition_granularity — the API surfaces partition_by for a SOURCE only
--     (TableMapper), so a value here would be stored and never read. The physical PARTITION BY is
--     owned by V10.
--   * ttl_column / ttl_interval / ttl_unit — V17's explicit rule: a seeded system table's retention
--     stays owned by its ClickHouse migration and is not mirrored into a catalog the API could then
--     try to modify. The 90-day TTL lives in V10; an operator changes it with ALTER TABLE ... MODIFY TTL.
--
-- system = TRUE for the same reason the two seeded sources carry it: the table is materialized out of
-- band by a migration and populated by data-sync writing straight to ClickHouse, so every modifying
-- /v1/tables operation on it must be refused (409). It stays fully readable and queryable through its
-- parent's grain join.
--
-- ordering_key mirrors the physical ORDER BY (event_id), exactly as CatalogSchemaWriter records it for
-- an API-created enrichment (setOrderingKey(List.of(grainKey))). Left at the column default it would
-- read back as [] from GET /v1/tables/dial_usage_log_payload — the catalog-versus-physical divergence
-- V22 had to retrofit for write_mode, so it is written here rather than retrofitted later.
--
-- No audit_trail rows are produced: a raw SQL UPDATE fires no Hibernate event. That matches every
-- other seeding migration; the audit feed does not claim to cover migrations.
INSERT INTO table_definition (name, description, type, source_table, status, system, grain_key, ordering_key)
VALUES ('dial_usage_log_payload',
        'Verbatim request and response content for a dial_usage_log event — bodies, JWT claims and '
            || 'request headers — held under a shorter retention than the event itself.',
        'ENRICHMENT', 'dial_usage_log', 'ACTIVE', TRUE, 'event_id', '{event_id}');

-- The cutover. The inverse of this statement — table_name back to 'dial_usage_log' with the original
-- ordinals (32, 33, 34, 35, 38) — restores the previous surface, but ONLY as a shape: V12 has deleted
-- the physical columns, so the bare names would resolve against columns that exist and are empty.
-- Rolling back the catalog alone is therefore not a recovery; treat this migration as one-way.
UPDATE column_mapping SET table_name = 'dial_usage_log_payload', ordinal = 0
    WHERE table_name = 'dial_usage_log' AND source_name = 'jwt_claims';
UPDATE column_mapping SET table_name = 'dial_usage_log_payload', ordinal = 1
    WHERE table_name = 'dial_usage_log' AND source_name = 'request_tags';
UPDATE column_mapping SET table_name = 'dial_usage_log_payload', ordinal = 2
    WHERE table_name = 'dial_usage_log' AND source_name = 'request_body';
UPDATE column_mapping SET table_name = 'dial_usage_log_payload', ordinal = 3
    WHERE table_name = 'dial_usage_log' AND source_name = 'response_body';
UPDATE column_mapping SET table_name = 'dial_usage_log_payload', ordinal = 4
    WHERE table_name = 'dial_usage_log' AND source_name = 'assembled_response';

-- The five UPDATEs above assert nothing on their own: a renamed or missing column_mapping row updates
-- zero rows, Flyway records success, and the qualified name silently never resolves — the payload table
-- would publish four columns, or none, with nothing failing until a query names the fifth. So the
-- expected end state is checked here and a shortfall fails the migration, which stops startup loudly
-- instead of leaving a catalog hole.
DO $$
DECLARE
    moved INTEGER;
BEGIN
    SELECT count(*) INTO moved FROM column_mapping
     WHERE table_name = 'dial_usage_log_payload'
       AND source_name IN ('jwt_claims', 'request_tags', 'request_body', 'response_body', 'assembled_response');
    IF moved <> 5 THEN
        RAISE EXCEPTION 'V38: expected the five payload columns on dial_usage_log_payload, found %', moved;
    END IF;
END $$;
