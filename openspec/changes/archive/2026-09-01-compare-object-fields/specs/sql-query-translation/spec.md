## MODIFIED Requirements

### Requirement: Object fields are projection-only in SQL

An `object`-typed field SHALL be usable in SQL only as a plain projection item (optionally
aliased), **as an operand of a document comparison** (see the paragraph below),
**or as the first argument of a `JSON_EXTRACT_STRING`/`JSON_EXTRACT_LONG`/
`JSON_EXTRACT_DOUBLE`/`JSON_EXTRACT_BOOL`/`JSON_EXTRACT_STRING_CI`/`JSON_EXTRACT_ARRAY` call**.
Any other use SHALL be rejected at translation
time — before any ClickHouse contact —
with an error naming the object field and stating that object fields can only be projected;
the error SHALL NOT be an "unknown column" error and SHALL NOT surface as a raw ClickHouse
failure. Rejected uses include: as an operand of `IS NULL` / `IS NOT NULL`, as an operand of an
**ordering** comparison (`<`, `>`, `<=`, `>=`), as a `GROUP BY` key, as an `ORDER BY` key, under
`SELECT DISTINCT`, and as an argument to any scalar or aggregate function other than the
`JSON_EXTRACT_*` family. The resulting
`StructuredQuery` SHALL carry the object field as an ordinary field projection, and the derived
result schema SHALL type the column as an object column.

**Document comparison.** An `object` field SHALL additionally be legal as the **left** operand of
`LIKE`, `ILIKE`, `NOT LIKE`, `NOT ILIKE`, `=`, `<>` and `IN` against character literals, in `WHERE`.
The carve-out SHALL NOT extend to `HAVING`, and that is a consequence rather than a separate rule: a
`HAVING` predicate may only reference a grouped or aggregated expression, and `GROUP BY` on an
`object` field stays rejected above — so a statement putting an `object` field in `HAVING` is refused
either for the ungrouped reference or for the `GROUP BY` key, and none reaches the carve-out. (The DSL
surface's `having` is a different clause with different resolution rules; see the `structured-query`
capability.) That shape — `<object field> <operator> <character literal>` — is the whole of the
carve-out: a comparison whose other operand is anything else SHALL be rejected at translation time with
an error naming the object field and the projection-only restriction. This covers an `object` field as
the **right** operand (`'{"a":1}' = jwt_claims`, `event_kind = jwt_claims`) and an `object` field
compared with any non-literal (`jwt_claims = event_kind`, `request_tags = jwt_claims`). The rejection is
what keeps the SQL front end from admitting more than the DSL surface does: `/v1/queries/execute` reads
the carve-out off the left operand only and refuses a projection-only field on the right, so a shape
accepted here but refused there would break the `translate-sql` → `execute` round trip.

Such a comparison SHALL translate to the corresponding DSL comparison
(`co`, `ico`, `nc`, `inc`, `eq`, `ne`, `in`) carrying the bare `object` field as its left operand, and
a `StructuredQuery` carrying one SHALL render back to the same SQL — the field rendering as its plain
quoted identifier, never wrapped in a coercion, so the round-trip is single-valued. The comparison's
meaning is defined over the document's **canonical JSON text** (see the `structured-query` capability):
`LIKE`/`ILIKE` match a substring of it, and `=`/`<>`/`IN` compare documents for equality independently
of key order, whitespace and number format. A literal compared with `=`, `<>` or `IN` SHALL parse as
JSON; one that does not SHALL be rejected at translation time with an error naming the field and the
literal, and SHALL NOT reach ClickHouse. Literals compared with `LIKE`/`ILIKE` are ordinary patterns
and carry no JSON requirement. `IS NULL` and `IS NOT NULL` stay rejected: a ClickHouse `JSON` column is
never null, so either would be a constant predicate rather than a document test.

A `JSON_EXTRACT_*` call whose first argument is an `object` field SHALL translate to the
corresponding DSL `json_extract_*` function expression, exactly as one over a character column does,
and the derived result schema SHALL type the column by the function's fixed return type (text,
long, numeric, boolean, or — for `JSON_EXTRACT_ARRAY` — an array of text) rather than as an object
column. For the five members whose result is a **scalar**, the translated node is an ordinary scalar
expression, so such a call SHALL be accepted wherever the SQL subset accepts a scalar expression — in
`WHERE`, `HAVING`, `GROUP BY`, `ORDER BY`, `SELECT DISTINCT`, and as an aggregate argument — and the
restriction above SHALL apply to the bare `object` field only, never to the extraction's result.
`JSON_EXTRACT_ARRAY` is the exception on the second half of that only: its `object` argument is accepted
on the same terms, and its result carries a restriction of its own, stated in the paragraph below.
Path arguments SHALL remain string literals, so an `object` field passed to
`JSON_EXTRACT_*` with a computed path SHALL be rejected for the path, not for the field's type.
The extractors remain the precise instrument for reading one key; a document comparison searches or
matches the whole document and is therefore the coarser tool of the two.

`JSON_EXTRACT_ARRAY` is the one member of the family whose **result** is itself projection-only:
it returns an array, so the array restriction (see "Array fields are projection-only in SQL" and
"The array-returning aggregate is projection-only too") applies to its output even though the
`object` first argument is accepted. On this surface that restriction reaches further than on the DSL
one, because the SQL front end types the result and refuses the nesting itself: a call used as another
expression's operand, as an `ORDER BY` key, or as a `GROUP BY` key SHALL be rejected. `SELECT DISTINCT`
over the projected call SHALL be **accepted**, unlike `SELECT DISTINCT` over a declared `array` column:
the rule is about using an array as an **operand**, and a distinct projection does not — it
deduplicates the projected rows. `JSON_EXTRACT_STRING_CI` returns a scalar and carries no such
restriction, but SHALL accept exactly two arguments — a case-insensitive key match is defined for a
single top-level key, so a third argument SHALL be rejected for arity rather than treated as a path
segment.

#### Scenario: Object projection translates
- **WHEN** a client submits `SELECT jwt_claims FROM dial_usage_log LIMIT 10`
- **THEN** the translator produces a row-mode StructuredQuery projecting `jwt_claims`, and the result schema types the column as an object column

#### Scenario: Object field in WHERE rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims IS NOT NULL`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction, and ClickHouse is not contacted

#### Scenario: Object field compared to a literal rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims = 'x'`
- **THEN** translation fails with an error naming `jwt_claims` and the JSON-literal requirement — not the projection-only restriction — and ClickHouse is not contacted

#### Scenario: Object field compared to a JSON literal translates
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims = '{"sub":"user-1"}'`
- **THEN** translation succeeds, producing a DSL `eq` comparison whose left operand is the bare `jwt_claims` field and whose right operand is the character literal

#### Scenario: Object field under LIKE and ILIKE translates
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE request_tags ILIKE '%gpt-4%'`
- **THEN** translation succeeds, producing a DSL `ico` comparison over the bare `request_tags` field, and the same query renders back to the same statement

#### Scenario: Object field under IN translates
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims IN ('{"a":1}', '{"b":2}')`
- **THEN** translation succeeds, producing a DSL `in` comparison over the bare `jwt_claims` field with both literals

#### Scenario: Object field as the right operand of a comparison rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE '{"a":1}' = jwt_claims` (or the same with the non-JSON literal `'x'`)
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction on the comparison's right operand, and ClickHouse is not contacted

#### Scenario: Object field compared with a non-literal rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims = event_kind` or `... WHERE request_tags = jwt_claims`
- **THEN** translation fails with an error naming the object field and the projection-only restriction — the document carve-out is defined only against a character literal — and ClickHouse is not contacted

#### Scenario: Object field under an ordering comparison rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims > '{"a":1}'`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction — ordering is not defined over documents

#### Scenario: Object field in a HAVING comparison rejected
- **WHEN** a client submits `SELECT event_kind, count(*) AS n FROM dial_usage_log GROUP BY event_kind HAVING request_tags ILIKE '%gpt%'`, or the same statement grouped by `request_tags` instead
- **THEN** translation fails and ClickHouse is not contacted — the first because `request_tags` is neither grouped nor aggregated, the second with the error naming `request_tags` and the projection-only restriction on the `GROUP BY` key; the document carve-out is reachable only from `WHERE`

#### Scenario: Object field in GROUP BY rejected
- **WHEN** a client submits `SELECT jwt_claims, count(*) FROM dial_usage_log GROUP BY jwt_claims`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction

#### Scenario: Object field in ORDER BY rejected
- **WHEN** a client submits `SELECT jwt_claims FROM dial_usage_log ORDER BY jwt_claims`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction

#### Scenario: Object field under SELECT DISTINCT rejected
- **WHEN** a client submits `SELECT DISTINCT jwt_claims FROM dial_usage_log`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction

#### Scenario: Object field as function argument rejected
- **WHEN** a client submits `SELECT count(jwt_claims) FROM dial_usage_log`
- **THEN** translation fails before execution with an error identifying the unsupported use of the object field

#### Scenario: Star projection over an entity with object columns translates
- **WHEN** a client submits `SELECT * FROM dial_usage_log LIMIT 1` on an entity whose source columns include `object`-typed fields
- **THEN** translation succeeds and the object columns are carried as ordinary field projections

#### Scenario: JSON_EXTRACT over an object field in the projection translates
- **WHEN** a client submits `SELECT JSON_EXTRACT_STRING(jwt_claims, 'sub') AS sub FROM dial_usage_log LIMIT 10`
- **THEN** translation succeeds, producing a DSL `json_extract_string` call over the `jwt_claims` field aliased `sub`, and the result schema types the column as text rather than as an object column

#### Scenario: JSON_EXTRACT over an object field in WHERE translates
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE JSON_EXTRACT_STRING(jwt_claims, 'sub') = 'user-1'`
- **THEN** translation succeeds, producing a DSL `eq` comparison whose left operand is the `json_extract_string` call — the projection-only restriction does not apply to this position

#### Scenario: JSON_EXTRACT over an object field in GROUP BY and ORDER BY translates
- **WHEN** a client submits a statement grouping and ordering by `JSON_EXTRACT_STRING(jwt_claims, 'sub')` with an aggregate
- **THEN** translation succeeds and the group and sort keys reference the extraction's output name, not the bare object field

#### Scenario: JSON_EXTRACT over an object field with a computed path rejected
- **WHEN** a client submits `SELECT JSON_EXTRACT_STRING(jwt_claims, request_id) FROM dial_usage_log`
- **THEN** translation fails with an error naming the literal-path restriction on `json_extract_*`, not the object field's type

#### Scenario: The extraction result is not itself projection-restricted
- **WHEN** a client submits `SELECT DISTINCT JSON_EXTRACT_STRING(jwt_claims, 'sub') FROM dial_usage_log`
- **THEN** translation succeeds — `DISTINCT` is restricted over a bare `object` field, not over a scalar extracted from one

#### Scenario: Case-insensitive extraction over an object field translates
- **WHEN** a client submits `SELECT JSON_EXTRACT_STRING_CI(request_tags, 'x-session-id') AS sid FROM dial_usage_log`
- **THEN** translation succeeds, producing a DSL `json_extract_string_ci` call over `request_tags` aliased `sid`, and the result schema types the column as text

#### Scenario: Case-insensitive extraction with a path segment rejected
- **WHEN** a client submits `JSON_EXTRACT_STRING_CI(request_tags, 'a', 'b')`
- **THEN** translation fails with an error naming the function's two-argument signature

#### Scenario: Array extraction over an object field translates
- **WHEN** a client submits `SELECT JSON_EXTRACT_ARRAY(jwt_claims, 'roles') AS roles FROM dial_usage_log`
- **THEN** translation succeeds, producing a DSL `json_extract_array` call over `jwt_claims` aliased `roles`, and the result schema types the column as an array with element kind string

#### Scenario: The array extraction's result is projection-only
- **WHEN** a client submits `SELECT chat_id FROM dial_usage_log ORDER BY JSON_EXTRACT_ARRAY(jwt_claims, 'roles')`, the same call as a `GROUP BY` key, or the same call nested in another function
- **THEN** translation fails with an error naming the projection-only restriction on the array result, not on the `jwt_claims` object field

#### Scenario: A distinct projection of the array extraction is accepted
- **WHEN** a client submits `SELECT DISTINCT JSON_EXTRACT_ARRAY(jwt_claims, 'roles') FROM dial_usage_log`
- **THEN** translation succeeds — `DISTINCT` deduplicates the projected rows rather than using the array as an operand, which is the distinction the restriction draws (a declared `array` column under `DISTINCT` is still rejected, because there the restriction is on the field itself)
