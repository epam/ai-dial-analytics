-- Progress of the automatic relayout of an existing enrichment into the aligned layout (capability
-- table-management, "Existing eligible enrichments are relaid out automatically"). One row per table the job has
-- started on, so a restart or a redeploy resumes from the last completed step instead of starting over.
--
-- `hold` is the writer hold: while it is true every write to the table is refused with 503
-- table_relayout_in_progress. It lives here rather than in memory because the hold must stop every instance's
-- writers, and each instance reads it on each write.
--
-- The catch-up marks are `_updated_at` values of the table being copied, as epoch milliseconds: the copy is exact
-- up to a mark, and every later step copies what was written after the previous one.
--
-- `copied_through` is where the history copy resumes: the history is copied in slices of the table's grain keys, and
-- this is the last key a completed slice copied through, rendered as text whatever the key's type.
CREATE TABLE enrichment_relayout
(
    table_name       TEXT PRIMARY KEY REFERENCES table_definition (name) ON DELETE CASCADE,
    step             TEXT        NOT NULL,
    copy_table       TEXT        NOT NULL,
    copied_through   TEXT,
    copy_mark        BIGINT,
    catch_up_mark    BIGINT,
    hold             BOOLEAN     NOT NULL DEFAULT FALSE,
    error            TEXT,
    started_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);
