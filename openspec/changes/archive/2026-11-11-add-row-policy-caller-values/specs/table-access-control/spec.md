## MODIFIED Requirements

### Requirement: Per-role row policies

The system SHALL store, for each catalog table, a set of **row policies** co-located on the table
definition, at most one per raw provider role. A policy SHALL be `{"filter": <filter node>}` — a predicate
in the structured query's filter grammar (see [structured-query](../structured-query/spec.md), "Filter
sub-grammar (CQL2-JSON)" and "Expression grammar") over that table's own columns.

There SHALL be no unrestricted policy shape: a role that may read the table and has no policy there already
reads every row (see "Row-policy evaluation"). A body without a `filter` SHALL be refused
`422 validation_error`; a member other than `filter`, like any body that does not bind to the request type,
SHALL be refused by the strict request mapper as `400 bad_request` (see "Row-policy management API").

A row policy SHALL **narrow** a read and SHALL NOT **grant** one. Whether a caller may read a table at
all SHALL remain decided by its global privileges and the table's grant lists, unchanged; a policy on a
role that has no read path to the table SHALL confer nothing.

A role name SHALL follow the grantee rules of the grant lists: a raw provider role name, trimmed,
non-blank, and not a bundle or privilege name. Row policies SHALL be storable on `system` tables, which
carry the shared data a policy most often narrows. Deleting a table SHALL remove its row policies.

The filter SHALL be validated against the table's **own** stored columns — every one of them, sensitive
columns included, independent of what the writing caller may see — and SHALL be refused when it:

- references a column the table does not store, including a dotted enrichment field (a policy narrows
  the table it is stored on; an enrichment is narrowed by a policy of its own);
- uses a `param` expression other than a caller value (see "Caller values in row policies");
- calls a function outside the function catalog's scalar group, or one the catalog declares as calling a
  service outside the data store;
- fails any validation the structured query surface would apply to the same filter; or
- renders to a predicate longer than 16 KiB.

A filter MAY call the relative time functions (`now`, `date_add`, `date_sub`): a policy is evaluated on
a read, where a moving window is meaningful, never on a derivation path.

A stored filter SHALL be returned in the structured query's stable wire serialization, so a policy read
back is a filter the query surface accepts.

#### Scenario: A policy narrows a role to one project

- **WHEN** a caller holding `TABLE_GRANT` stores for role `X` on `dial_usage_log` the policy
  `{"filter": {"op": "in", "args": [{"type": "field", "name": "project_id"}, {"type": "array", "items": [{"type": "value", "value_type": "string", "value": "X-PRJ-1"}]}]}}`
- **THEN** the policy is stored and reading the table's row policies returns it under `X`

#### Scenario: A policy names a column the table does not store

- **WHEN** a policy's filter references a field the table does not store, or a dotted enrichment field
- **THEN** the request is refused `422 validation_error` naming the field, and nothing is stored

#### Scenario: A policy filter may not take a parameter or aggregate

- **WHEN** a policy's filter contains a `param` expression that is not a caller value, an aggregate
  function, or a window expression
- **THEN** the request is refused `422 validation_error`, and nothing is stored

#### Scenario: A policy without a filter is refused

- **WHEN** a policy body carries no `filter`
- **THEN** the request is refused `422 validation_error`

#### Scenario: An unknown member does not bind

- **WHEN** a policy body carries a member other than `filter`, e.g. `{"where": {}}` or `{"all_rows": true}`
- **THEN** the request is refused `400 bad_request`, and nothing is stored

#### Scenario: A sensitive column may constrain a policy

- **WHEN** a caller holding `TABLE_GRANT` but no clearance stores a policy filtering on a sensitive
  column of the table
- **THEN** the policy is accepted, because it is validated against the table's stored columns rather
  than the caller's view

#### Scenario: A relative time window is a valid policy

- **WHEN** a policy's filter is `request_time >= date_sub('day', 90, now())`
- **THEN** the policy is accepted

## ADDED Requirements

### Requirement: Caller values in row policies

A row policy filter MAY reference a **caller value**: a `param` expression whose `name` is exactly
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

The reserved names SHALL have this meaning only inside a row policy. A `param` in a query SHALL be refused
as before, whatever its name, and SHALL NOT receive the caller's value.

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

### Requirement: Caller values resolve per read and an absent one is unknown

On every read that carries a caller's row filters, each caller value in a confining policy SHALL be
replaced by the caller's own value, inlined as an escaped string literal. A caller value SHALL be
resolved from the identity of the request being served and SHALL NOT be cached across callers.

A caller value SHALL be **unknown** when the caller's credential yields none, when it is blank, or when it
is longer than 512 bytes. A comparison with an unknown caller value SHALL evaluate as SQL `NULL` does in a
predicate: it SHALL match no row, its negation SHALL match no row, and a disjunction SHALL still match
through another branch that is true. An unknown value SHALL NOT be rendered as an empty string or
any other constant, because rows written for callers without that identity store an empty value.

A filter's shape remains its author's responsibility where the value is known: `NOT (user_hash =
caller.user_hash)` selects every other caller's rows.

Everything else about evaluation is unchanged: which policies confine a caller, that a role reading the
table with no policy lifts the confinement, that several confining policies are combined with `OR`, and
that a pipeline refuses a confined caller.

#### Scenario: A user reads only its own rows

- **WHEN** role `user` carries the policy `user_hash = caller.user_hash` on `dial_usage_log`, and a caller
  holding only that role, whose user hash is `aaaa…01`, reads the table
- **THEN** the read returns only rows whose `user_hash` is `aaaa…01`, including the enrichment columns
  joined to those rows

#### Scenario: A role without a policy still reads every row

- **WHEN** the same caller also holds a role that may read the table and has no policy there
- **THEN** the read returns every row

#### Scenario: One policy serves user and project callers

- **WHEN** a role carries `user_hash = caller.user_hash OR project_id = caller.project`, and it is held by
  a user whose hash is known and who has no project, and by a project key of project `P1` with no user
- **THEN** the user reads the rows with its own hash, and the project key reads the rows of `P1`

#### Scenario: A caller with no value reads nothing

- **WHEN** role `user` carries `user_hash = caller.user_hash` and a caller holding only that role has no
  user hash
- **THEN** the read succeeds and returns no rows, including rows whose `user_hash` is empty

#### Scenario: Negation of an unknown value matches nothing

- **WHEN** a role carries `NOT (user_hash = caller.user_hash)` or `user_hash != caller.user_hash`, and a
  caller holding only that role has no user hash
- **THEN** the read returns no rows

#### Scenario: A blank value is unknown

- **WHEN** a caller's credential yields an empty or whitespace-only value for a caller value a confining
  policy references
- **THEN** the value is unknown and the comparison matches no row
