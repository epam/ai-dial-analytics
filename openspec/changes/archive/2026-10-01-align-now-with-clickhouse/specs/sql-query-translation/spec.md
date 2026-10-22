## MODIFIED Requirements

### Requirement: The clock has one DSL name and three SQL spellings

The SQL subset SHALL accept `now()`, `CURRENT_TIMESTAMP` and `LOCALTIMESTAMP` — the last two with or
without empty parentheses, as the grammar spells them — and SHALL lower all three to the single closed
catalog function `now` (see [structured-query](../structured-query/spec.md), "Relative time
expressions") in the resulting `StructuredQuery`. No new expression kind SHALL be introduced: the DSL
carries the clock as an ordinary zero-argument `fn` expression.

Three inbound spellings exist because three populations write them and none should have to learn the
others': `now()` is what ClickHouse and PostgreSQL users write, `CURRENT_TIMESTAMP` is what standard
SQL and most BI clients emit, and `LOCALTIMESTAMP` is what a client emits when it means "without a
zone" — which, on a surface whose one zone is UTC, is the same instant. They SHALL therefore produce
the **same** document, not three documents that behave alike.

Sharing a name SHALL NOT merge evaluations. Each occurrence of any of the three spellings SHALL lower
to its own `now` call, and each call is one call site evaluated on its own, with the semantics
"Relative time expressions" defines: one value across every row of the statement, independent of any
other call site. `now()` and `CURRENT_TIMESTAMP` in one statement are therefore two call sites that MAY
differ by a clock tick, exactly as two `now()` calls MAY — and exactly as the same statement does in
ClickHouse.

`CURRENT_DATE` SHALL remain rejected. It is not a spelling of the clock but a truncation of it, and
lowering it silently would answer a date-typed request with a timestamp; the subset's own
`date_trunc('day', now())` says it exactly.

The DSL → SQL renderer SHALL emit `now()` and never a second spelling, so a rendered statement reads
back to the same document it was rendered from.

#### Scenario: The three clock spellings produce one document

- **WHEN** a client submits `SELECT event_id FROM dial_usage_log WHERE request_time >= now()`, the
  same statement with `CURRENT_TIMESTAMP`, and the same with `LOCALTIMESTAMP`
- **THEN** all three translate successfully to the same `StructuredQuery`, whose filter compares
  `request_time` against a zero-argument `now` function expression

#### Scenario: Two spellings in one statement stay two calls

- **WHEN** a client submits
  `SELECT event_id FROM dial_usage_log WHERE request_time >= now() - INTERVAL 1 HOUR AND request_time < CURRENT_TIMESTAMP`
- **THEN** the resulting `StructuredQuery` carries two separate zero-argument `now` calls, one per
  bound, and no single shared instant

#### Scenario: The clock round-trips through the renderer

- **WHEN** a DSL document whose filter calls `now()` is rendered to SQL and the rendered statement is
  translated back
- **THEN** the rendered statement spells the call `now()` and the resulting document equals the
  original

#### Scenario: CURRENT_DATE stays rejected

- **WHEN** a client submits a statement using `CURRENT_DATE`
- **THEN** translation fails with HTTP 400 and `error` `bad_request`
