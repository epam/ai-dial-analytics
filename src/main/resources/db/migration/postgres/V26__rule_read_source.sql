-- A rule's READ source: the single table it scans to derive its values.
--
-- Until now this was not stored at all — it was derived as `target_enrichment.source_table`, which
-- conflated two different facts. An enrichment's `source_table` is its READ-SIDE attachment: the table
-- it LEFT JOINs into on the shared grain key, whose flat schema view carries its columns, and which
-- cannot be dropped while the enrichment exists. Forcing the rule to read that same table meant an
-- enrichment could only ever be derived from the table it hangs off. `conversations_feedback` is an
-- enrichment of `conversations` keyed on `chat_id`, but the evidence that fills it lives in
-- `rate_analytics` — which carries `chat_id` too, and which the rule could not name.
--
-- NULL means "derive from the target enrichment", which is exactly what every existing row means, so
-- this migration deliberately does NOT backfill. Backfilling would be safe today (an enrichment's
-- source_table is immutable) and would make the resolution a plain column read, but it would erase the
-- distinction between "follows its target" and "happens to point at the same table" — the distinction
-- the version-column generation bump and the delete guard key off. (The API does not surface it: a rule
-- read reports the RESOLVED read source under `source`, declared or derived alike.)
--
-- The foreign key is RESTRICT (the default), NOT `ON DELETE CASCADE`. Cascade is right for
-- `target_enrichment` — a rule without its target is meaningless — but wrong here: silently deleting
-- governed rules as a side effect of dropping a table would be a surprising loss of configuration.
-- TableCatalogService refuses such a delete up front with a 409 naming the rule; this constraint is the
-- database backstop for a race that slips past that pre-check.
ALTER TABLE rule
    ADD COLUMN source_table TEXT REFERENCES table_definition (name);

-- Repointing a source's `version_column` bumps the generation of every rule reading that source; the
-- lookup by declared read source is that query's access path.
CREATE INDEX rule_source_table_idx ON rule (source_table);
