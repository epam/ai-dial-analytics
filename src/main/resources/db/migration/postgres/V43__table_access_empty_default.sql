-- The `access` column's DEFAULT becomes the empty document. V5's '{"write": [], "modify": []}' named the two
-- keys that existed then; per-table grants now have five, and naming them in a DEFAULT would need another
-- migration every time one is added. None is needed: TableAccess reads an absent key as an empty list, so
-- `{}` grants exactly the nothing a document of empty lists does.
--
-- Only raw-SQL inserts that omit `access` ever see this DEFAULT. Hibernate always writes the full TableAccess
-- document on insert. Deliberately NOT a backfill UPDATE of existing rows: `table_definition` is @Audited
-- (Envers), so a bulk UPDATE writes no revision, and the rows already read correctly as they are.
ALTER TABLE table_definition
    ALTER COLUMN access SET DEFAULT '{}'::jsonb;
