## Context

A row policy is stored as a filter tree and rendered to a ClickHouse predicate on every read; see
[table-access-control](../../specs/table-access-control/spec.md). The SQL front end (`SqlQueryFrontEnd`)
already turns a single-block `SELECT … WHERE …` over a queryable entity into a structured query whose
`filter` is that same tree.

## Goals / Non-Goals

**Goals:**

- A policy can be written as the SQL condition an operator would put after `WHERE`.
- A policy written in SQL is indistinguishable, once stored, from the same policy written as a tree.

**Non-Goals:**

- Sending operator-written SQL text to ClickHouse. The text is an input format only.
- Storing or returning the SQL text; rendering a stored tree back to SQL.
- Any construct a policy cannot hold today: subqueries, aggregates, window functions, enrichment fields.

## Decisions

**1. Translate, then reuse the existing path.** The service builds `SELECT * FROM <table> WHERE <condition>`,
runs it through the SQL front end and takes the resulting `filter`. From there the existing policy
validation, storage and rendering apply unchanged, so the scope rules, the caller-value placement rule and
the size limit need no second implementation. Rejected: storing the text and passing it to
`additional_table_filters` as written, which would let a policy hold arbitrary ClickHouse SQL (subqueries
over other tables, any function) and bypass every rule a policy has.

**2. The condition is resolved against the catalog as the system identity.** A policy is validated against
every column the table stores, whatever the writing caller may see, and the caller holds `TABLE_GRANT`, not
necessarily a read of the table. The front end resolves names at the calling identity's visibility, so the
translation runs under the system identity, which reads every table and every sensitive column and can
administer nothing.

**3. Caller values are written as `caller.user_hash` and `caller.project`.** In SQL that spelling is a
column of a relation named `caller`, which does not exist, so the text is rewritten before parsing: outside
string literals and quoted identifiers, each of the two names becomes a string literal holding a marker
generated for that one request, and after translation a string value equal to a marker becomes the `param`
node of that name. A random marker cannot be written by the operator, so a literal in the condition can
never turn into a caller value. Rejected: a SQL function such as `caller_user_hash()`, which would have to
be registered for every SQL surface and then refused on all of them but this one.

**4. A table that is not a queryable entity takes the `filter` form only.** An enrichment is never a
statement's `FROM`, so a condition over it cannot be translated; the request is refused with a message
saying so.

**5. Translation failures are `422 validation_error`**, like every other invalid policy, with the front
end's message.

## Risks / Trade-offs

- [The front end may rewrite a condition into an equivalent shape] → the stored tree is what is returned,
  so the operator sees what was stored. One visible case: two comparisons of one column joined by `OR`
  may be folded into an `in` list, which a caller value may not sit in; such a policy is refused and can be
  written as `filter`.
- [The marker rewrite is textual] → it tracks quotes only. A mis-scan can make a condition fail to
  translate, or leave a name unreplaced so that it fails as an unknown column; it cannot widen a policy,
  because the result is still a validated tree echoed back to the operator.
