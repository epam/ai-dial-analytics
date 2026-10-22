## ADDED Requirements

### Requirement: Translation carries FINAL

The translation endpoints SHALL carry whether a query reads its entity final, in both directions, so that
the translated query reads exactly what the input reads once `query.force-final` is off (see
[structured-query](../structured-query/spec.md) "Read result deduplication"). Translation SHALL follow
what the query says, never what `query.force-final` would force on execution, so its output does not
change when the flag is turned off.

- **DSL → SQL** (`POST /v1/queries/translate`): the entity reference SHALL render with `FINAL` when the
  document's `final` is `true`, or when `final` is absent and the entity is final-capable (its source is
  `upsert_by_key`, or it has an enrichment); it SHALL render without `FINAL` when `final` is `false`, or
  when `final` is absent and the entity is not final-capable.
- **SQL → DSL** (`POST /v1/queries/translate-sql`): a statement whose entity reference carries `FINAL`
  SHALL translate to `"final": true`, and one without it to `"final": false`. `final` SHALL always be
  emitted, never left absent, because an absent `final` reads a final-capable entity final and would turn
  a raw SQL read into a final one.

Both endpoints SHALL apply the `illegal_final` rejection execution applies — `"final": true` or `FINAL` on
an `append` source with no enrichment is HTTP 400, `error` = `illegal_final` — so a query that translates is
one that executes.

#### Scenario: A DSL query omitting final over a collapsing source renders FINAL

- **WHEN** a client posts to `translate` a query over `dial_usage_log` without `final`
- **THEN** the returned SQL reads `… FROM "dial_usage_log" FINAL …`

#### Scenario: A DSL query omitting final over an append source renders no FINAL

- **WHEN** a client posts to `translate` a query over an `append` source with no enrichment, without `final`
- **THEN** the returned SQL carries no `FINAL`

#### Scenario: final false renders no FINAL

- **WHEN** a client posts to `translate` a query over `dial_usage_log` with `"final": false`
- **THEN** the returned SQL carries no `FINAL`, with `query.force-final` on or off

#### Scenario: SQL without FINAL translates to an explicit false

- **WHEN** a client posts `SELECT trace_id FROM dial_usage_log` to `translate-sql`
- **THEN** the returned document carries `"final": false`

#### Scenario: SQL with FINAL translates to true

- **WHEN** a client posts `SELECT trace_id FROM dial_usage_log FINAL` to `translate-sql`
- **THEN** the returned document carries `"final": true`

#### Scenario: The FINAL mark round-trips

- **WHEN** a DSL document with `"final": true` or `"final": false` is translated to SQL and the SQL
  translated back
- **THEN** the resulting document carries the same `final` value as the original

#### Scenario: Translation rejects FINAL on an append source

- **WHEN** a client posts to `translate` a query with `"final": true` over an `append` source with no
  enrichment, or to `translate-sql` a statement writing `FINAL` after one
- **THEN** the response is HTTP 400, `error` = `illegal_final`
