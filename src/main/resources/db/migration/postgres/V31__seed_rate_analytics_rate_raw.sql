-- Register rate_analytics.rate_raw in the catalog, seeded non-sensitive and non-heavy.
--
-- The physical column is added by db/migration/clickhouse/V8__rate_analytics_rate_raw.sql; this
-- migration only registers it, so it issues no ClickHouse ALTER — `sensitive`, `heavy`, `tag`,
-- `display_name` and `description` are catalog-only metadata and never appear in any DDL.
-- rate_analytics is a seeded SOURCE in status ACTIVE, so its catalog row can only be edited by
-- migration (PATCH /v1/tables/{name}/schema rejects a system-owned table).
--
-- NOT sensitive, deliberately, and unlike this table's other user-submitted column: `comment` is
-- AES-coded free text and gated behind FULL_ADMIN, while rate_raw holds a rating scalar of a few bytes
-- and never user-authored prose. Marking it sensitive would put the exact thumbs-down count behind
-- FULL_ADMIN for no privacy gain. NOT heavy for the same size reason, so a wildcard projection returns
-- it rather than eliding it.
--
-- tag 'request' groups it with `rate` and `comment`, the other user-submitted-with-the-rating columns
-- (V7__retag_column_semantic_groups.sql), and 'request' is already present in rate_analytics's
-- tag_order (V10__add_table_tag_order.sql), so no ordering change is needed.
--
-- ordinal 9 APPENDS it, after event_id (8). It does not sit beside `rate` (1), which would read better
-- given the two columns are meant to be read together — the trade-off is taken deliberately. This
-- table's catalog order is a curated presentation order that already diverges from the physical DDL,
-- so tracking the DDL is not the argument it was for V29; the argument is that V16 appended event_id
-- on the stated grounds that "the registration is additive, the already-exposed columns are
-- untouched", and that the live schema-patch API can only append. Renumbering ordinals 2..8 to seat
-- this at 2 would produce a catalog state no API path can produce, to improve column order in a
-- listing.
INSERT INTO column_mapping (table_name, source_name, name, type, element_type, nullable, description,
                            display_name, tag, sensitive, heavy, ordinal)
VALUES ('rate_analytics', 'rate_raw', 'rate_raw', 'string', NULL, TRUE,
        'The rating exactly as submitted, before normalization: "true"/"false" for a boolean '
            || 'submission, the literal digits for a numeric one. Read it together with `rate`, which '
            || 'maps boolean false and numeric 0 onto the same 0 and so cannot tell a thumbs-down from '
            || 'a cleared rating. Count unambiguous negatives as rate_raw = ''false'' OR (rate_raw IS '
            || 'NOT NULL AND rate < 0) — rate_raw is text and has no ordering, so the numeric half of '
            || 'the question is answered by `rate`. NULL means the event was ingested before this '
            || 'column existed; the raw form was never captured for those rows and is never inferred.',
        'Rate (as submitted)', 'request', FALSE, FALSE, 9);
