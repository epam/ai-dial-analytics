## ADDED Requirements

### Requirement: FINAL table modifier

The SQL surface SHALL accept ClickHouse's `FINAL` table modifier on an entity reference, and an entity
reference carrying it SHALL be read final while one without it is read raw (see
[structured-query](../structured-query/spec.md) "Read result deduplication" for what each reads, the
`query.force-final` migration flag, and the partition-local setting). The rule SHALL hold on every surface
that shares the SQL front end: `POST /v1/queries/execute-sql`, `POST /v1/queries/execute-sql-csv`,
`POST /v1/queries/translate-sql` and the Flight SQL / ADBC endpoint.

**Placement.** `FINAL` SHALL be accepted, case-insensitively, directly after an entity reference in a
`FROM` list (after `FROM` or a comma, including a comma that follows a join's `ON`/`USING` condition, as in
`FROM a JOIN b ON a.k = b.k, c FINAL`) or a `JOIN` — bare, double-quoted, or schema-qualified — and after
that reference's alias when it has one (`FROM dial_usage_log FINAL`, `FROM dial_usage_log AS l FINAL`,
`FROM dial_usage_log l FINAL`, `JOIN rate_analytics r FINAL ON …`). These are the placements ClickHouse
26.3 accepts; it refuses `FINAL` between a reference and its alias (`FROM t FINAL AS a`, `FROM t FINAL a`),
and so does this surface, with the parse failure it reports today (HTTP 400 `bad_request`). It SHALL be accepted wherever an entity reference may appear: the outer statement, a joined
entity, a derived table's body, a CTE body, a subquery and each branch of a set operation.

**Scope.** `FINAL` marks the one entity reference it follows. In a composite statement each reference SHALL
be read final or raw independently, so `FROM dial_usage_log l FINAL JOIN rate_analytics r ON …` reads
`dial_usage_log` final and `rate_analytics` raw, and the same entity referenced twice MAY be read final in
one reference and raw in the other. A final-marked reference SHALL deduplicate every table of its entity
that collapses versions — the source when it is `upsert_by_key`, and every enrichment the statement joins
for that reference.

**What is not a modifier.** `FINAL` SHALL be recognised only in the positions above. The word inside a
string literal (`'FINAL'`), inside a comment, as a double-quoted identifier (`"FINAL"`, which is an alias or
a name), or as a column or alias name in any other position SHALL keep meaning what it means today. A
`FINAL` in any other position — after a `WHERE`, `GROUP BY` or `ORDER BY` clause, in a select list — SHALL
be rejected with the parse failure the surface reports today (HTTP 400 `bad_request`).

**Rejections.**

- `FINAL` after an entity that is not final-capable — an `append` source with no enrichment — SHALL be
  rejected with HTTP 400, `error` = `illegal_final` (see the structured-query requirement), whether or not
  `query.force-final` is on.
- `FINAL` after a derived table, after a reference to a CTE name, or after a catalog metadata table
  (`INFORMATION_SCHEMA`) SHALL be rejected with HTTP 400 `bad_request` and a message stating that `FINAL`
  applies to an entity, not to a derived table, CTE or catalog table; the modifier belongs inside the body,
  on the entity it reads.

A single-block statement with `FINAL` translates to a structured query with `"final": true`, and one
without translates to `"final": false` (see
[query-translation-endpoint](../query-translation-endpoint/spec.md) "Translation carries FINAL").

#### Scenario: FINAL after an entity reads it final

- **WHEN** `query.force-final` is off and a client executes
  `SELECT trace_id FROM dial_usage_log FINAL WHERE trace_id = 'x'` over a key with two unmerged versions
- **THEN** the response holds one row for the key

#### Scenario: No FINAL reads the entity raw

- **WHEN** `query.force-final` is off and a client executes `SELECT trace_id FROM dial_usage_log WHERE
  trace_id = 'x'` over a key with two unmerged versions
- **THEN** the response holds two rows, as ClickHouse returns for the same statement

#### Scenario: FINAL after an alias

- **WHEN** a client executes `SELECT l.trace_id FROM dial_usage_log AS l FINAL`
- **THEN** the statement is accepted and `dial_usage_log` is read final

#### Scenario: FINAL between a reference and its alias is a parse failure

- **WHEN** a client executes `SELECT l.trace_id FROM dial_usage_log FINAL AS l`
- **THEN** the request is rejected with HTTP 400 `bad_request` as an unparseable statement, as ClickHouse
  refuses it

#### Scenario: FINAL is case-insensitive and follows a quoted or qualified name

- **WHEN** a client executes `SELECT trace_id FROM "dial_usage_log" final`, or the same with the entity
  schema-qualified
- **THEN** the statement is accepted and the entity is read final

#### Scenario: FINAL marks one reference of a join

- **WHEN** `query.force-final` is off and a client executes a join of `dial_usage_log l FINAL` with
  `rate_analytics r` on `l.response_id = r.response_id`
- **THEN** `dial_usage_log` and the enrichments joined for it are read final and `rate_analytics` is read
  raw

#### Scenario: FINAL in a set-operation branch marks that branch only

- **WHEN** `query.force-final` is off and a client executes
  `SELECT model FROM dial_usage_log FINAL UNION ALL SELECT model FROM dial_usage_log`
- **THEN** the first branch reads `dial_usage_log` final and the second reads it raw

#### Scenario: FINAL inside a CTE or derived table body

- **WHEN** a client executes `WITH t AS (SELECT trace_id FROM dial_usage_log FINAL) SELECT count(*) FROM t`
- **THEN** the statement is accepted and the CTE body reads `dial_usage_log` final

#### Scenario: FINAL after a CTE name or derived table is rejected

- **WHEN** a client executes `WITH t AS (SELECT trace_id FROM dial_usage_log) SELECT * FROM t FINAL`, or
  `SELECT * FROM (SELECT trace_id FROM dial_usage_log) FINAL`
- **THEN** the request is rejected with HTTP 400 `bad_request` stating that `FINAL` applies to an entity,
  and no statement is executed

#### Scenario: FINAL after an append entity is rejected

- **WHEN** a client executes `SELECT * FROM <append_source> FINAL` over an `append` source with no
  enrichment, with `query.force-final` on or off
- **THEN** the request is rejected with HTTP 400, `error` = `illegal_final`, naming the entity

#### Scenario: The word FINAL elsewhere is not a modifier

- **WHEN** a client executes `SELECT 'FINAL' AS "final" FROM dial_usage_log -- FINAL` with
  `query.force-final` off
- **THEN** the statement is accepted, the literal and the alias keep their meaning, and `dial_usage_log`
  is read raw

#### Scenario: FINAL in a clause position is a parse failure

- **WHEN** a client executes `SELECT trace_id FROM dial_usage_log WHERE trace_id = 'x' FINAL`
- **THEN** the request is rejected with HTTP 400 `bad_request` as an unparseable statement

#### Scenario: Flight SQL honours FINAL

- **WHEN** `query.force-final` is off and a Flight SQL client executes
  `SELECT trace_id FROM dial_usage_log FINAL WHERE trace_id = 'x'` over a key with two unmerged versions
- **THEN** the stream carries one row for the key
