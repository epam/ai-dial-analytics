## ADDED Requirements

### Requirement: Caller values in SQL

A SQL statement SHALL spell a caller value as a call of `caller.user_hash` or `caller.project` with an empty argument
list, outside any string literal, quoted identifier or comment. The name SHALL be matched case-insensitively, as every
SQL function name is, and SHALL contain no whitespace or comment; whitespace, but not a comment, is allowed between
the name and `(`, and whitespace or a comment between the parentheses, as in any SQL call. The call's value is a
nullable string, so no test of it such as `IS NULL` is decided before it is resolved. Each such call SHALL translate
to the `param` of that name and SHALL then
follow the placement and resolution rules of the structured-query capability (see
[structured-query](../structured-query/spec.md), "Caller values in a query filter" and "Caller values in a query
resolve per execution"). Here the other operand MAY be any expression of type `string` that reads at least one column
and holds no caller value — a column, a grouped column, an output alias, a column of a derived table or a common table
expression, or a computed expression such as `lower(user_hash)` — typed by the structured-query rule whether or not
the planner folds a derived table into its enclosing block; an aggregate there is accepted only where the statement
may hold one, in `HAVING`. A cast to a character
type written around a caller value is the caller value itself, as for any operand that is already text (see "Text
composition and value rendering in the SQL subset"). A cast to any other type, or one the comparison with a
non-string column needs, makes the caller value misplaced, and so does every other position the structured-query
capability refuses, `IS [NOT] NULL`, `LIKE` and a function argument included. A misplaced caller value SHALL be
rejected `400 bad_request` with a message naming it and the rule, and `POST /v1/queries/validate-sql` SHALL report it
with the issue code `unsupported_construct`.

No other spelling SHALL yield a caller value, on any surface, pipeline declarations included. A call with an argument
SHALL be rejected as a call with the wrong number of arguments (`function_arity` on `validate-sql`), its message naming
the call by its caller-value spelling. Any other call — a spelling such as `caller . user_hash()`, and any name the
surface uses internally to carry a caller value, in any letter case or quoted — SHALL be rejected as a call of an
unknown function (`unknown_function`). Either is `400 bad_request`.

An `IN` list holding a caller value, within the existing limit on `IN`-list length, SHALL mean the disjunction of the
equalities it abbreviates, and `NOT IN` its negation; `POST /v1/queries/translate-sql` SHALL return that disjunction
as an `or` holding the caller value's `eq` comparison next to the comparison of the list's literals. A list longer
than that limit SHALL be refused as any such list is, before the caller value is read, `400 bad_request`, reported by
`validate-sql` as `in_list_too_long`.

Planning SHALL NOT change which rows a condition holding a caller value admits. The rows a statement returns SHALL be
those it returns when each caller value is replaced by hand with the caller's value as a string literal, or, when the
value is unknown, with each comparison holding it evaluated as `NULL`; this holds in `WHERE`, `HAVING` and a join's
`ON` alike, next to any other term on the same column.

In a composite statement a caller value MAY appear in the filter of any block — `WHERE`, `HAVING`, a join's `ON`
condition, a derived table, a common table expression, an `IN` or `EXISTS` subquery, a scalar subquery, or a branch of
a set operation — under the same placement rule, and SHALL resolve to the same value in every block. A comparison
counts as placed only when it is reached from such a filter through `AND`, `OR` and `NOT` alone; one reached through
an expression, such as the condition of a `CASE` or the argument of `COALESCE`, is misplaced.

The rule SHALL hold on every surface that reads a user's SQL statement: `POST /v1/queries/execute-sql`,
`POST /v1/queries/execute-sql-csv`, `POST /v1/queries/validate-sql`, `POST /v1/queries/translate-sql`, Flight SQL, and
the validation of a saved query's or a dashboard widget's `sql` body. On the surfaces that execute, the value SHALL be
resolved from the identity of the request that executes the statement. A Flight SQL ticket carries the statement and
not its result, so the value SHALL be resolved when the ticket is redeemed, for the caller that redeems it.
`POST /v1/queries/translate-sql` SHALL return the `param` unresolved, and `POST /v1/queries/validate-sql` SHALL report
a statement holding a correctly placed caller value valid and one holding a misplaced or misspelled one invalid,
exactly as `execute-sql` accepts or rejects it; the handling of a caller value SHALL move no position it reports. A
statement against `INFORMATION_SCHEMA` holding a caller value SHALL be rejected `400 bad_request`, reported by
`validate-sql` as `unsupported_construct`.

A pipeline declaration is not a user's statement: an aggregate pipeline's SQL, and an enrichment's filter or
expression SQL, SHALL keep refusing a caller value as an unknown function, `422 pipeline_validation_failed`, because a
pipeline runs with no caller.

Text that is not a call SHALL keep the meaning it has without this requirement. `caller.user_hash` without
parentheses is a column reference: it SHALL resolve against a relation named or aliased `caller` where the statement
has one, and SHALL otherwise be rejected as an unknown column, as before. A string literal SHALL remain a literal
whatever it spells, including `'caller.user_hash()'`.

#### Scenario: A caller value in WHERE

- **WHEN** a caller whose user hash is `aaaa…01` posts
  `SELECT * FROM "dial_usage_log" WHERE "user_hash" = caller.user_hash()` to `POST /v1/queries/execute-sql`
- **THEN** the response is `200` with only the rows whose `user_hash` is `aaaa…01`

#### Scenario: Letter case and whitespace around the argument list

- **WHEN** the same statement is written with `CALLER.USER_HASH()`, with `caller.user_hash ( )`, or with
  `CAST(caller.user_hash() AS VARCHAR)`
- **THEN** it is accepted and returns the same rows

#### Scenario: translate-sql returns the parameter

- **WHEN** the same statement is posted to `POST /v1/queries/translate-sql`
- **THEN** the returned filter compares `user_hash` with `{"type": "param", "name": "caller.user_hash"}`

#### Scenario: validate-sql agrees with execution

- **WHEN** the same statement is posted to `POST /v1/queries/validate-sql`, and so is one selecting
  `caller.user_hash()` as a column
- **THEN** the first is reported valid, and the second invalid with an issue of code `unsupported_construct`

#### Scenario: A computed or grouped text operand

- **WHEN** a caller runs `SELECT lower(project_id) AS p, count(*) AS n FROM dial_usage_log GROUP BY p HAVING p =
  caller.project()`, filters a common table expression's computed column `lower(user_hash) AS h` with
  `h = caller.user_hash()`, or filters `CAST(prompt_tokens AS VARCHAR) = caller.user_hash()`
- **THEN** each statement is accepted, and `HAVING m = caller.user_hash()` over `max(event_id) AS m`, a `uuid`, is
  rejected `400 bad_request` naming the caller value, as is `k = caller.user_hash()` over a derived table's literal
  column `'x' AS k` — on `POST /v1/queries/execute-sql` and when a saved query's `sql` body is validated alike

#### Scenario: A caller value inside a subquery and a join

- **WHEN** a caller runs a statement that joins `dial_usage_log AS l` to another table with the condition
  `l.user_hash = caller.user_hash()` in its `ON`, or filters `deployment IN (SELECT deployment FROM dial_usage_log
  WHERE user_hash = caller.user_hash())`
- **THEN** each statement executes with the caller's own user hash in place of the call

#### Scenario: A left join with an unknown caller value in ON

- **WHEN** a caller with no project runs `SELECT ... FROM a LEFT JOIN b ON a.k = b.k AND b.project_id =
  caller.project()`
- **THEN** the response is `200` and returns every row of `a`, each with `b`'s columns as for a row of `a` that
  matches nothing

#### Scenario: A conjunction with a literal on the same column keeps both terms

- **WHEN** a project key of project `P1` runs
  `SELECT project_id, count(*) FROM dial_usage_log GROUP BY project_id HAVING project_id = caller.project() AND
  project_id = 'P1'`, and a caller with no project runs the same statement with `<>` in place of the first `=`
- **THEN** the project key receives the `P1` group, and the caller with no project receives no group
- **AND** the same holds when the two comparisons sit in an inner join's `ON`

#### Scenario: A disjunction or an IN list with a literal on the same column

- **WHEN** a caller posts `SELECT * FROM "dial_usage_log" WHERE "user_hash" = caller.user_hash() OR "user_hash" = 'x'`,
  or the same with `"user_hash" IN (caller.user_hash(), 'x')`, to `POST /v1/queries/execute-sql`
- **THEN** each response is `200` with the rows of the caller's own hash and the rows whose `user_hash` is `x`
- **AND** `POST /v1/queries/translate-sql` returns an `or` of two `eq` comparisons for both statements

#### Scenario: An IN list over the length limit

- **WHEN** a statement filters `"user_hash" IN (...)` with 20 literals and `caller.user_hash()`
- **THEN** the response is `400` with machine code `bad_request`, and `POST /v1/queries/validate-sql` reports an issue
  of code `in_list_too_long`, as it does for the same list without the caller value

#### Scenario: A Flight SQL ticket resolves for the caller that redeems it

- **WHEN** caller A obtains a Flight SQL ticket for `SELECT count(*) FROM "dial_usage_log" WHERE "user_hash" =
  caller.user_hash()`, and caller B, allowed to read the same table, redeems it
- **THEN** the count is that of B's own rows

#### Scenario: The bare spelling stays a column reference

- **WHEN** a caller posts `SELECT caller.user_hash FROM dial_usage_log AS caller`
- **THEN** the statement reads the `user_hash` column of `dial_usage_log`, and no caller value is substituted

#### Scenario: A literal spelling the call stays a literal

- **WHEN** a statement filters `"user_hash" = 'caller.user_hash()'`
- **THEN** it compares `user_hash` with the string `caller.user_hash()`, and no caller value is substituted

#### Scenario: A misplaced call is rejected

- **WHEN** a statement selects `caller.user_hash()` as a column, calls `upper(caller.user_hash())` or
  `CAST(caller.user_hash() AS INTEGER)`, compares `caller.user_hash() = caller.project()`, compares it with the
  `integer` column `prompt_tokens`, with an `enum` column, or with `>`, uses it as a `LIKE` pattern, tests
  `caller.user_hash() IS NULL` or `IS NOT NULL` in `WHERE` or in `HAVING`, or filters
  `NOT (CASE WHEN user_hash = caller.user_hash() THEN TRUE ELSE FALSE END)`
- **THEN** the response is `400` with machine code `bad_request` and a message naming the caller value, nothing is
  executed, and `POST /v1/queries/validate-sql` reports each statement with an issue of code `unsupported_construct`

#### Scenario: A caller value in a scalar subquery

- **WHEN** a caller selects `(SELECT count(*) FROM dial_usage_log WHERE user_hash = caller.user_hash()) AS n`
- **THEN** the statement executes with the caller's own user hash in place of the call

#### Scenario: A misspelled call is rejected

- **WHEN** a statement writes `caller . user_hash()`, `caller.user_hash /* c */ ()`, or `caller.user_hash('x')`
- **THEN** the response is `400` with machine code `bad_request`, and `POST /v1/queries/validate-sql` reports the first
  two as `unknown_function` and the third as `function_arity`

#### Scenario: No other name reaches a caller value

- **WHEN** a statement, or an enrichment's filter SQL, calls a name the surface uses internally to carry a caller
  value, in any letter case or as a quoted name
- **THEN** it is rejected as a call of an unknown function — `400 bad_request` and `unknown_function` on
  `validate-sql` for a statement, `422 pipeline_validation_failed` for the filter — and no caller value is resolved

#### Scenario: A catalog metadata statement is rejected

- **WHEN** a caller posts `SELECT caller.user_hash() AS x FROM INFORMATION_SCHEMA."TABLES"` or filters an
  `INFORMATION_SCHEMA` statement on a caller value
- **THEN** the response is `400` with machine code `bad_request`, and `POST /v1/queries/validate-sql` reports an issue
  of code `unsupported_construct`

#### Scenario: A pipeline declaration refuses a caller value

- **WHEN** an aggregate pipeline is created whose SQL filters `user_hash = caller.user_hash()`, or an enrichment's
  filter SQL does
- **THEN** the response is `422` with machine code `pipeline_validation_failed`, and nothing is stored
