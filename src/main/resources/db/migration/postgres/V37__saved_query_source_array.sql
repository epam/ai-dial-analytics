-- Widens saved_query.source from a single entity name to an array of every entity the body reads
-- from. A sql-kind body may now be a composite statement (a join, a derived table, a CTE, or a
-- subquery) that reads from more than one entity, and a single string cannot name a set.
--
-- Every existing row's source becomes its own one-element array (USING ARRAY[source]): lossless,
-- in-place, no application-level backfill. The NOT NULL CHECK (array_length(source, 1) > 0) mirrors
-- this table's existing `name` CHECK style — source stays required, just no longer a single value.
--
-- saved_query_aud (the Envers mirror created in V35) widens in the same migration, per this
-- project's rule that an already-audited column's mirror must widen alongside it. Its column stays
-- NULLable (unlike the live table's), so its USING clause is null-safe.
ALTER TABLE saved_query
    ALTER COLUMN source TYPE TEXT[] USING ARRAY[source];

ALTER TABLE saved_query
    ADD CONSTRAINT saved_query_source_not_empty CHECK (array_length(source, 1) > 0);

ALTER TABLE saved_query_aud
    ALTER COLUMN source TYPE TEXT[] USING
        CASE WHEN source IS NULL THEN NULL ELSE ARRAY[source] END;
