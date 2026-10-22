## ADDED Requirements

### Requirement: ARRAY_JOIN expands an array into rows on the SQL surface

The SQL subset SHALL accept `ARRAY_JOIN(array)` and SHALL translate it to the DSL `array_join` call,
so the row-expanding query is expressible identically on both surfaces and round-trips exactly. The
call SHALL take exactly one operand — an `array` column or a call to `MAP_KEYS`, `SPLIT_STRING`,
`JSON_EXTRACT_ARRAY` or `GROUP_UNIQ_ARRAY` — and a call with any other operand count or a non-array
operand SHALL be rejected at translation time, before any engine contact, with an error naming the
expected form.

The derived result schema SHALL type the produced column as the **element** type of the array operand
— an ordinary scalar, not an array — so the column carries no projection-only restriction of its own.
For an `array` column that is its catalog `element_type`; for a computed array it follows the producing
call, which is `string` for `MAP_KEYS`/`SPLIT_STRING`/`JSON_EXTRACT_ARRAY` and the operand's own type
for `GROUP_UNIQ_ARRAY`. The DSL surface SHALL derive the same type for the same document.

The subset SHALL NOT impose positional restrictions of its own on `ARRAY_JOIN`: it SHALL be accepted
in `WHERE`, in `HAVING`, inside an aggregate function's arguments and inside a window function's
operands, because expansion in each of those positions is the engine's own behaviour and a narrower
subset would refuse statements the engine accepts.

`GROUP BY` over the expanded element SHALL be accepted through the projection's **alias**, which is
the one place the array-returning functions' alias rule does not extend to: `ARRAY_JOIN`'s result is a
scalar, so grouping by its alias groups by single elements rather than by whole arrays.

#### Scenario: Expansion translates and round-trips
- **WHEN** a client submits `SELECT ARRAY_JOIN("conversation_insights.topics") AS topic FROM conversations LIMIT 10`
- **THEN** translation produces a row-mode StructuredQuery whose select entry is an `array_join`
  FnExpr over that field aliased `topic`, and the result schema types `topic` as a string rather than
  an array

#### Scenario: Grouping by an expanded element translates
- **WHEN** a client submits `SELECT ARRAY_JOIN(topics) AS topic, count(*) AS n FROM conversations GROUP BY topic`
- **THEN** translation succeeds, producing an aggregate-mode StructuredQuery grouped by the `topic`
  output name

#### Scenario: Expansion over a computed array translates
- **WHEN** a client submits `SELECT ARRAY_JOIN(SPLIT_STRING(topics, ',')) AS t FROM conversations`
- **THEN** translation succeeds, the array-returning call sitting in `ARRAY_JOIN`'s operand position

#### Scenario: Expansion in a predicate translates
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE ARRAY_JOIN(execution_path) = 'x'`
- **THEN** translation succeeds, producing an `array_join` FnExpr as the comparison's operand — the
  subset adds no restriction the engine does not have

#### Scenario: A second operand is rejected
- **WHEN** a client submits `SELECT ARRAY_JOIN(topics, ',') AS t FROM conversations`
- **THEN** translation fails with an error naming the single-operand form, rather than treating the
  operand as a delimiter the way the same-named function of other dialects would

#### Scenario: A non-array operand is rejected
- **WHEN** a client submits `SELECT ARRAY_JOIN(model) AS m FROM dial_usage_log`
- **THEN** translation fails before execution with an error naming the expected array operand

## MODIFIED Requirements

### Requirement: Array fields are projection-only in SQL

An `array`-typed field SHALL be usable in SQL only as a plain projection item (optionally
aliased) **or as the first argument of an `ARRAY_HAS`/`ARRAY_HAS_ANY`/`ARRAY_HAS_ALL`/`ARRAY_LENGTH`/
`ARRAY_JOIN` call**. Any other use SHALL be rejected at translation time — before any ClickHouse contact —
with an error naming the array field and stating that array fields can only be projected.
Rejected uses include: as an operand of any predicate or comparison (including `IS NULL` /
`IS NOT NULL` and `IN`), as a `GROUP BY` key, as an `ORDER BY` key, under `SELECT DISTINCT`
(row-mode distinct deduplicates over the projected array), and as an argument to any scalar or
aggregate function other than the five named above. The resulting `StructuredQuery` SHALL carry the
array field as an ordinary field projection, and the derived result schema SHALL type the column as an
array of its catalog `element_type`.

The carve-out SHALL be **positional and function-specific**, matching the treatment `object` fields
receive for `JSON_EXTRACT_*`: only argument 0, only those five functions. An array field supplied as
`ARRAY_HAS`'s *second* argument SHALL be rejected under the rule above. The four accessor calls return scalars
(boolean, boolean, boolean, integer), so their results SHALL carry no projection-only restriction and
SHALL be legal in `WHERE`, `GROUP BY`, `ORDER BY`, `HAVING` and as an `IF` condition. `ARRAY_JOIN`
likewise returns a scalar — the array's element type — and is row-expanding; its contract is stated by
"ARRAY_JOIN expands an array into rows on the SQL surface".

Testing and expanding are the whole of the carve-out. An `array` field SHALL remain rejected as a
`GROUP BY` key: only `ARRAY_JOIN`'s scalar result is groupable, and only through the alias of the
projection carrying it, never the array column itself.

#### Scenario: Array projection translates
- **WHEN** a client submits `SELECT execution_path FROM dial_usage_log LIMIT 10`
- **THEN** the translator produces a row-mode StructuredQuery projecting `execution_path`, and the result schema types the column as an array with element type `string`

#### Scenario: Array field in WHERE rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE execution_path IS NOT NULL`
- **THEN** translation fails with an error naming `execution_path` and the projection-only restriction, and ClickHouse is not contacted

#### Scenario: Array field in GROUP BY rejected
- **WHEN** a client submits `SELECT execution_path, count(*) FROM dial_usage_log GROUP BY execution_path`
- **THEN** translation fails with an error naming `execution_path` and the projection-only restriction

#### Scenario: Array field in ORDER BY rejected
- **WHEN** a client submits `SELECT execution_path FROM dial_usage_log ORDER BY execution_path`
- **THEN** translation fails with an error naming `execution_path` and the projection-only restriction

#### Scenario: Array field as function argument rejected
- **WHEN** a client submits `SELECT count(execution_path) FROM dial_usage_log`
- **THEN** translation fails before execution with an error identifying the unsupported use of the array field

#### Scenario: Array predicate over an array field translates
- **WHEN** a client submits `SELECT count(*) FROM dial_usage_log WHERE ARRAY_HAS("usage_client_identity.user_roles", 'admin')`
- **THEN** translation succeeds, producing an `array_has` FnExpr over that field, and the array field is not rejected

#### Scenario: Array length over an array field translates
- **WHEN** a client submits `SELECT ARRAY_LENGTH(execution_path) AS n FROM dial_usage_log`
- **THEN** translation succeeds and the result schema types `n` as an integer, not an array

#### Scenario: Array field in the predicate's value position rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE ARRAY_HAS('admin', execution_path)`
- **THEN** translation fails before execution — the carve-out is argument 0 only. The refusal comes from
  the operand-type check (`ARRAY_HAS(<ARRAY>, <ANY>)`), which fires before the projection-only rule is
  consulted, so the message names the call's declared form rather than the array field

#### Scenario: Array field as ARRAY_JOIN's operand translates
- **WHEN** a client submits `SELECT ARRAY_JOIN(execution_path) AS step FROM dial_usage_log`
- **THEN** translation succeeds and the array field is not rejected — `ARRAY_JOIN`'s operand position
  is part of the carve-out

### Requirement: Array-returning scalar functions are projection-only in SQL

A scalar function whose result is an array SHALL be usable only as a plain projection item, optionally
aliased. This covers `MAP_KEYS`, `SPLIT_STRING` and `JSON_EXTRACT_ARRAY`, and holds for the same reason
it holds for a declared `array` field and for
`group_uniq_array`: the DSL has no array literal or comparison, so nothing else is expressible. Any
other use — as a `GROUP BY` key, an `ORDER BY` key, an operand of a predicate, or an argument to
another function — SHALL be rejected at translation time with an error naming the function and stating
that its array result can only be projected.

The restriction SHALL be recognised from the producing function's name rather than from a declared
column type, since a computed column has none. The derived result schema SHALL type the column as an
array with element kind string.

The one exception is the array-operand carve-out: because `ARRAY_HAS`/`ARRAY_HAS_ANY`/
`ARRAY_HAS_ALL`/`ARRAY_LENGTH`/`ARRAY_JOIN` accept an array in argument 0, an array-returning scalar
call SHALL be legal there, so `ARRAY_HAS(SPLIT_STRING(topics, ','), 'python')`,
`ARRAY_LENGTH(MAP_KEYS(point_attributes))` and `ARRAY_JOIN(SPLIT_STRING(topics, ','))` SHALL all
translate.

#### Scenario: split_string projects
- **WHEN** a client submits `SELECT SPLIT_STRING("conversation_insights.topics", ',') AS t FROM conversations LIMIT 10`
- **THEN** translation succeeds and the result schema types `t` as an array with element kind string

#### Scenario: An array-returning call is refused as a group key by its alias too
- **WHEN** a client groups by the alias of a `MAP_KEYS` or `SPLIT_STRING` projection
- **THEN** translation fails — resolving the key through its alias SHALL NOT bypass the restriction, which
  would otherwise group by whole arrays and return a plausible-looking answer to a question nobody asked

#### Scenario: split_string as a GROUP BY key rejected
- **WHEN** a client submits `SELECT SPLIT_STRING(topics, ',') AS t, count(*) FROM conversations GROUP BY t`
- **THEN** translation fails with an error naming the function and the projection-only restriction —
  grouping by whole arrays is not in the subset. Exploding the array into rows and grouping by the
  element is expressible, but only by wrapping the call in `ARRAY_JOIN`

#### Scenario: split_string nested in an array predicate translates
- **WHEN** a client submits `SELECT count(*) FROM conversations WHERE ARRAY_HAS(SPLIT_STRING(topics, ','), 'python')`
- **THEN** translation succeeds, the array-returning call sitting in the predicate's argument 0

#### Scenario: An array-returning call nested in ARRAY_JOIN translates
- **WHEN** a client submits `SELECT ARRAY_JOIN(MAP_KEYS(point_attributes)) AS k FROM dial_usage_log`
- **THEN** translation succeeds and the result schema types `k` as a string, the map's key type,
  rather than as an array

