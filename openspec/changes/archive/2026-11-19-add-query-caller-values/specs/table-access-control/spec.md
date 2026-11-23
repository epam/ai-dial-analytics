## MODIFIED Requirements

### Requirement: Caller values in row policies

A row policy filter SHALL accept a **caller value**: a `param` expression whose `name` is exactly
`caller.user_hash` or `caller.project`. The set is closed and the names are matched case-sensitively; a
`param` with any other name, including one that differs only in case, SHALL be refused
`422 validation_error`. No other content of the caller's credential SHALL be reachable from a policy.

A caller value is a string whose content is not known when the policy is stored. It SHALL appear only as
a direct operand of an `eq` or `ne` comparison whose other operand is a field of the table's `string` type.
Anywhere else — a function argument, a `case` branch, an array item, a comparison with a field of another
type or with anything that is not a field — the policy SHALL be refused `422 validation_error`. The
comparison MAY sit under any logical operator, `not` included. A stored filter SHALL be returned with the `param` expression intact, never with a
resolved value. The 16 KiB limit SHALL be checked with each caller value taken at its maximum length of
512 bytes, so a policy that was accepted cannot exceed the limit on a read.

A query MAY use the same two caller values under its own rules (see
[structured-query](../structured-query/spec.md), "Caller values in a query filter"). A caller value in a query only
narrows what that query returns: it SHALL NOT change which policies confine the caller, nor lift or add a
confinement.

How each value is derived from the caller's credential is specified in
[authentication](../authentication/spec.md), "Caller values derived from the credential".

#### Scenario: An own-rows policy is stored

- **WHEN** a caller holding `TABLE_GRANT` stores for role `user` on `dial_usage_log` the policy
  `{"filter": {"op": "eq", "args": [{"type": "field", "name": "user_hash"}, {"type": "param", "name": "caller.user_hash"}]}}`
- **THEN** the policy is stored, and reading it back returns the `param` expression unchanged

#### Scenario: A name outside the closed set is refused

- **WHEN** a policy's filter contains `{"type": "param", "name": "caller.email"}` or
  `{"type": "param", "name": "Caller.User_Hash"}`
- **THEN** the request is refused `422 validation_error`, and nothing is stored

#### Scenario: A caller value is compared with a non-string column

- **WHEN** a policy's filter compares `caller.project` with a numeric, enum or object column
- **THEN** the request is refused `422 validation_error`, and nothing is stored

#### Scenario: A caller value outside a direct comparison is refused

- **WHEN** a policy's filter uses a caller value as a function argument, e.g.
  `coalesce(caller.user_hash, '') = user_hash`, inside a `case` expression, as an item of an `in` list,
  or compares it with a literal
- **THEN** the request is refused `422 validation_error`, and nothing is stored

### Requirement: A row policy may be written as a SQL condition

`PUT /v1/tables/{name}/access?role=<role>` SHALL accept `"rows": {"where": "<condition>"}` as an alternative to
`"rows": {"filter": <filter node>}`. The condition is the text that would follow `WHERE` in a SQL statement over the
table, in the SQL subset the ad-hoc SQL surface accepts (see [rest-sql-endpoint](../rest-sql-endpoint/spec.md)).
Exactly one of `where` and `filter` SHALL be given: a `rows` carrying both, or neither, SHALL be refused
`422 validation_error`.

The system SHALL translate the condition into a filter node and from there treat the policy exactly as one written as
`filter`: the same validation against the table's own columns, the same refusals, the same size limit, the same
storage and the same enforcement. The condition text SHALL NOT be stored, SHALL NOT be sent to the data store, and
SHALL NOT be returned: the response and every later read of the entry carry the stored `filter`.

The condition SHALL be resolved against every column the table stores, sensitive columns and columns a column policy
hides included, whatever the writing caller may itself see or read.

A caller value SHALL be written in the condition as `caller.user_hash()` or `caller.project()`, the spelling a query
uses and matched as a query matches it (see [sql-query-translation](../sql-query-translation/spec.md), "Caller values
in SQL"), or as `caller.user_hash` or `caller.project` without the parentheses, exactly; either spelling SHALL become
the `param` of that name, subject to the placement rule of "Caller values in row policies". An `IN` list holding a
caller value, in either spelling, SHALL be stored as the disjunction of the equalities it abbreviates; in the `filter`
form a caller value as an `in` item stays refused. A string literal
in the condition SHALL remain a literal whatever it spells, including the text of a caller value's name in either
spelling.

A condition that does not parse, does not validate against the table, or uses a construct the SQL surface cannot
express as a single filter SHALL be refused `422 validation_error` with the reason. A condition that holds a subquery
or a sequence value, names an enrichment's column by its dotted name, or closes itself to add another clause, SHALL be
refused on its text, before any name in it is resolved, with one message whatever it names: the refusal SHALL NOT tell
whether a table or column outside the policy's own table exists. An enrichment accepts no policy in either form (see
"Role-keyed access API").

#### Scenario: A condition is stored as its filter

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s for role `X` an entry with `"rows": {"where": "project_id IN
  ('X-PRJ-1', 'X-PRJ-2')"}`
- **THEN** the response is `200` with a `rows.filter` equal to the one the same policy written as `filter` stores, and
  a caller carrying `X` sees only the rows of those two projects

#### Scenario: A caller value in a condition

- **WHEN** a policy is written as `"rows": {"where": "user_hash = caller.user_hash"}`
- **THEN** the stored filter compares `user_hash` with the `param` `caller.user_hash`, and each caller reads its own
  rows

#### Scenario: The call spelling in a condition

- **WHEN** a policy is written as `"rows": {"where": "user_hash = caller.user_hash()"}`
- **THEN** the stored filter is the one `"user_hash = caller.user_hash"` stores, comparing `user_hash` with the
  `param` `caller.user_hash`

#### Scenario: A literal spelling a caller value stays a literal

- **WHEN** a policy is written as `"rows": {"where": "user_hash = 'caller.user_hash'"}`
- **THEN** the stored filter compares `user_hash` with the string `caller.user_hash`, and no caller's value is
  substituted

#### Scenario: An IN list with a caller value is stored as a disjunction

- **WHEN** a policy is written as `"rows": {"where": "user_hash IN ('a', caller.user_hash)"}` or as
  `"rows": {"where": "user_hash IN ('a', caller.user_hash())"}`
- **THEN** both store the same filter, an `or` of the `eq` comparisons with `'a'` and with the `param`
  `caller.user_hash`

#### Scenario: A literal spelling the call stays a literal

- **WHEN** a policy is written as `"rows": {"where": "user_hash = 'caller.user_hash()'"}`
- **THEN** the stored filter compares `user_hash` with the string `caller.user_hash()`, and no caller's value is
  substituted

#### Scenario: Both forms at once are refused

- **WHEN** an entry's `rows` carries both `where` and `filter`
- **THEN** the request is refused `422 validation_error` and nothing is stored

#### Scenario: A subquery in a condition is refused

- **WHEN** a policy is written as `"rows": {"where": "project_id IN (SELECT project_id FROM other_table)"}`
- **THEN** the request is refused `422 validation_error` and nothing is stored
- **AND** the message is the same whether or not `other_table` exists

### Requirement: Row policies are inert in none mode

In `none` security mode the system SHALL NOT enforce row policies: no read SHALL carry the filter, and every caller
SHALL see every row a query selects, no policy removing any. A query's own filter still applies, a caller value in it
included, and in `none` mode a caller value is unknown (see [authentication](../authentication/spec.md), "Caller
values derived from the credential"). The access API SHALL still require nothing beyond what `none` mode requires of every endpoint,
and a policy written in `none` mode SHALL still be validated — its role, its filter against the table's stored
columns, and the schema guard that protects the columns it references — because a stored policy takes effect the
moment the deployment switches to `oidc`, when refusing it would be too late.

#### Scenario: none mode returns every row

- **WHEN** the service runs in `none` mode and a table carries policies
- **THEN** every query returns every row its own filter selects, as if the table carried no policy

#### Scenario: An invalid policy is refused in none mode

- **WHEN** the service runs in `none` mode and an entry whose policy names a column the table does not store is `PUT`
- **THEN** the request is refused `422 validation_error`

