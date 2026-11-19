## ADDED Requirements

### Requirement: Caller values in a query filter

A query's `filter` and `having` SHALL accept a **caller value**: a `param` expression whose `name` is exactly
`caller.user_hash` or `caller.project`, the closed set row policies use (see
[table-access-control](../table-access-control/spec.md), "Caller values in row policies"). The names SHALL be matched
case-sensitively. A caller value stands for the value of that name carried by the caller executing the query, derived
as [authentication](../authentication/spec.md) "Caller values derived from the credential" specifies. A `param` with
any other name, including one that differs only in case, SHALL be rejected `400 bad_request` as before.

A caller value SHALL appear only as a direct operand of an `eq` or `ne` comparison whose other operand is an
expression of type `string` that reads at least one field and holds no caller value: a source or enrichment field, a
computed expression such as `lower(user_hash)`, and in `having` also a grouped field, an output, or an expression over
an aggregate. An aggregate in the other operand SHALL be accepted only in `having`. An expression of any other type,
`enum` and `uuid` included, does not qualify, nor does a value or an expression reading no field, and a field the query
cannot resolve is refused as an unknown field whether or not the caller has a value. The comparison MAY
sit under any logical operator, `not` included, and SHALL be reached from `filter` or `having` through logical
operators alone; a comparison inside an expression, such as a `case` branch nested in another comparison, is
misplaced. Anywhere else the query SHALL be rejected `400 bad_request` with a
message naming the caller value and the rule: as a `select` expression; as a function argument; in a `case` branch,
its condition or its result; as an item of an `in` list; in a comparison with any other operator; and in a comparison
with a field of another type, with a value, or with another caller value.

Validation that does not execute the query — the translation endpoints, and saved-query and dashboard body
validation — SHALL apply the same placement rule with the same outcome, and SHALL NOT read the caller's values.

A caller value SHALL only narrow what the query returns. The query SHALL still be governed by everything that governs
any other query of that caller: its read bar, its column visibility, and the row policies that confine it on each
table it reads (see table-access-control, "Row-policy evaluation by caller role").

#### Scenario: An administrator reads its own rows

- **GIVEN** `dial_usage_log` whose entry for `*` grants `read` with the policy `user_hash = caller.user_hash`, and a
  role `admin` that reads it with no policy
- **WHEN** a caller holding `admin`, whose user hash is `aaaa…01`, posts to `POST /v1/queries/execute` a query with
  the filter
  `{"op": "eq", "args": [{"type": "field", "name": "user_hash"}, {"type": "param", "name": "caller.user_hash"}]}`
- **THEN** the response is `200` with only the rows whose `user_hash` is `aaaa…01`
- **AND** the same query without that filter returns every row

#### Scenario: One query serves user and project callers

- **WHEN** a query filters `user_hash = caller.user_hash OR project_id = caller.project`, and it is run by a user
  whose hash is known and who has no project, and by a project key of project `P1` with no user hash
- **THEN** the user receives the rows with its own hash, and the project key receives the rows of `P1`

#### Scenario: A caller value in having

- **WHEN** an aggregate query grouped by `user_hash` has the `having`
  `{"op": "eq", "args": [{"type": "field", "name": "user_hash"}, {"type": "param", "name": "caller.user_hash"}]}`
- **THEN** the response is `200` with the one group of the caller's own hash, or none
- **AND** a `having` comparing the caller value with the output `m` of `max(user_hash)` is accepted, while one comparing
  it with the output of `count()` is rejected `400 bad_request`

#### Scenario: A computed text operand is compared like a field

- **WHEN** a query filters `{"op": "eq", "args": [{"type": "fn", "name": "lower", "args": [{"type": "field", "name":
  "user_hash"}]}, {"type": "param", "name": "caller.user_hash"}]}`
- **THEN** the response is `200` with the rows whose lowercased `user_hash` equals the caller's own

#### Scenario: An unknown field is reported for every caller

- **WHEN** a query compares the field `no_such_field` with `caller.user_hash`, run by a caller with a user hash and by
  one without
- **THEN** both responses are `400` with machine code `bad_request` naming `no_such_field`

#### Scenario: A caller value does not widen a confined read

- **GIVEN** a caller confined on `dial_usage_log` by a policy admitting only `project_id = 'P1'`
- **WHEN** it runs a query filtering `NOT (user_hash = caller.user_hash)`
- **THEN** every returned row has `project_id` `P1`

#### Scenario: A misplaced caller value is rejected

- **WHEN** a query uses the `param` `caller.user_hash` as a `select` expression, as an argument of `lower`, in the
  condition of a `case` branch, as an item of an `in` list, in a `gt` comparison, compared with the `integer` field
  `prompt_tokens` or with an `enum` field, compared with the value `'x'`, compared with the `param`
  `caller.project`, or compared in `filter` with `max(user_hash)`
- **THEN** the response is `400` with machine code `bad_request` naming `caller.user_hash`, and nothing is executed

#### Scenario: Another parameter is still rejected

- **WHEN** a query contains `{"type": "param", "name": "caller.email"}` or `{"type": "param", "name": "Caller.User_Hash"}`
- **THEN** the response is `400` with machine code `bad_request`, and nothing is executed

### Requirement: Caller values in a query resolve per execution

On every execution of a query — `POST /v1/queries/execute`, `POST /v1/queries/execute-csv`, and every SQL surface
whose statement holds a caller value (see [sql-query-translation](../sql-query-translation/spec.md), "Caller values in
SQL") — each caller value SHALL be replaced by the executing caller's own value, inlined as an escaped string literal.
It SHALL be resolved from the identity of the request being served and SHALL NOT be cached across requests, so the
same query body run by two callers returns each its own rows.

A caller value SHALL be **unknown** when the caller has none, when it is blank, or when it is longer than 512 bytes,
the same bounds that apply to a row policy. A comparison with an unknown caller value SHALL evaluate as SQL `NULL`
does in a predicate: it SHALL match no row, its negation SHALL match no row, and a disjunction SHALL still match
through another branch that is true. An unknown value SHALL NOT be rendered as an empty string or any other constant,
because rows written for callers without that identity store an empty value. A query whose caller value is unknown
SHALL succeed, returning the rows for which its filter, with each such comparison evaluated as `NULL`, is true; for a
filter that is only the comparison, that is none.

#### Scenario: Two callers run the same body

- **WHEN** callers with user hashes `aaaa…01` and `aaaa…02` post the same query filtering
  `user_hash = caller.user_hash` to `POST /v1/queries/execute`
- **THEN** the first receives only rows with `aaaa…01` and the second only rows with `aaaa…02`

#### Scenario: A caller with no value reads nothing

- **WHEN** a caller with no user hash runs a query whose filter is `user_hash = caller.user_hash`
- **THEN** the response is `200` with no rows, including none of the rows whose `user_hash` is empty

#### Scenario: Negation of an unknown value matches nothing

- **WHEN** a caller with no user hash runs a query filtering `NOT (user_hash = caller.user_hash)` or
  `user_hash != caller.user_hash`
- **THEN** the response is `200` with no rows

#### Scenario: A blank or oversized value is unknown

- **WHEN** a caller's credential yields a whitespace-only user hash, or one longer than 512 bytes, and it runs a
  query filtering `user_hash = caller.user_hash`
- **THEN** the response is `200` with no rows

#### Scenario: The CSV twin resolves the same way

- **WHEN** a caller posts a query filtering `user_hash = caller.user_hash` to `POST /v1/queries/execute-csv`
- **THEN** the CSV body holds exactly the rows `POST /v1/queries/execute` returns to that caller for the same query
