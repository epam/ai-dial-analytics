## ADDED Requirements

### Requirement: Star projection expands to the non-heavy source columns

A star select item — `SELECT *` or `SELECT <table-or-alias>.*` — SHALL be expanded, **before the
statement is validated**, into the entity's **source** columns that are not marked `heavy` in the
table-management catalog (see the `table-management` capability's "Heavy column metadata"), each as a
plain column reference in catalog order.

**BREAKING**: a star SHALL NOT expand to the entity's enrichment columns. Previously a star expanded to
the entity's whole flat schema, dotted enrichment columns included, which also emitted a `LEFT JOIN`
per attached enrichment. A star SHALL now expand to exactly the field set a row-mode DSL query with an
omitted `select` projects (see the `structured-query` capability's "Wildcard projection excludes heavy
columns"), so the two wildcard surfaces agree and an enrichment join stays opt-in. An enrichment column
SHALL remain selectable, filterable, and sortable by its quoted qualified flat identifier
(e.g. `"deployment_ref.display_name"`) — the only spelling under which it was ever addressable in SQL —
and SHALL remain present in schema discovery and in the SQL-visible schema.

Because the expansion happens in the parsed statement, an expanded star SHALL be indistinguishable
downstream from a caller-written column list: validation, the relational plan, the produced
`StructuredQuery` `select`, and the reported `ResultColumn` names SHALL all be exactly those of the
equivalent explicit statement, and each column SHALL keep its own name (no derived name, no alias).

A `heavy` field named **explicitly** SHALL be accepted and projected unchanged, and a `heavy` field
SHALL remain usable in `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, and as a function argument exactly
as before — subject only to the pre-existing array/object projection-only rules. Heaviness SHALL NOT
remove a field from the SQL-visible schema; an unknown-column error SHALL never be produced for a
heavy field.

A star whose expansion would be **empty** — every source column visible to the caller is heavy — SHALL
be rejected at translation time with a message naming the entity and directing the caller to list
columns explicitly (`400 bad_request` on REST, gRPC `INVALID_ARGUMENT` on Flight), and nothing SHALL
be sent to ClickHouse.

Only a **single-entity** `FROM` is expanded. Where the `FROM` is a derived table that flattens
losslessly into the single-entity shape, the expansion SHALL be applied to the inner select that names
the entity, so heaviness is honored at the point the base table is referenced; every other unsupported
`FROM` shape stays rejected by the existing fail-closed rules. `COUNT(*)` and any other function-star
argument SHALL NOT be affected — only top-level select-list star items are expanded.

#### Scenario: Star omits a heavy column

- **WHEN** a client submits `SELECT * FROM dial_usage_log LIMIT 1` on an entity whose `response_body`
  column is marked `heavy`
- **THEN** the translated query projects every non-heavy source column, `response_body` is absent from
  the projection and from the reported result columns, and each remaining column keeps its own name

#### Scenario: Star omits enrichment columns and joins no enrichment

- **WHEN** a client submits `SELECT * FROM <source>` for a source with an attached enrichment
- **THEN** the projection carries only the source's non-heavy columns, no `<enrichment>.<column>` output
  appears, and the executed query emits no enrichment `LEFT JOIN`

#### Scenario: Enrichment column stays selectable by its qualified name

- **WHEN** a client submits `SELECT request_id, "deployment_ref.display_name" FROM dial_usage_log`
- **THEN** translation succeeds, the enrichment is joined, and the column is projected under its
  qualified flat name — the star rule restricts only what a star expands to

#### Scenario: Explicitly named heavy column is projected

- **WHEN** a client submits `SELECT request_id, response_body FROM dial_usage_log LIMIT 1`
- **THEN** translation succeeds and both columns are projected — an explicit reference is never pruned

#### Scenario: Heavy column is usable in WHERE and ORDER BY

- **WHEN** a client submits `SELECT * FROM dial_usage_log WHERE response_body LIKE '%error%' ORDER BY response_body LIMIT 5`
- **THEN** the predicate and the sort translate normally against the heavy column, while the projection
  still omits it

#### Scenario: Qualified star is expanded

- **WHEN** a client submits `SELECT t.* FROM dial_usage_log t LIMIT 1`
- **THEN** the star is expanded to the same non-heavy field list as an unqualified `*`

#### Scenario: COUNT(*) is unaffected

- **WHEN** a client submits `SELECT count(*) FROM dial_usage_log`
- **THEN** the statement translates to a global aggregate exactly as before, with no column expansion

#### Scenario: All-heavy star is rejected before execution

- **WHEN** a client submits `SELECT * FROM <entity>` where every source column visible to the caller is
  marked `heavy`
- **THEN** translation fails with a message naming the entity and the explicit-columns remedy, and
  ClickHouse is not contacted

#### Scenario: Star over a derived table honors heaviness at the base table

- **WHEN** a client submits `SELECT * FROM (SELECT * FROM dial_usage_log) LIMIT 1`
- **THEN** the inner star expands to the non-heavy fields and the outer star projects exactly those,
  so the heavy column is absent
