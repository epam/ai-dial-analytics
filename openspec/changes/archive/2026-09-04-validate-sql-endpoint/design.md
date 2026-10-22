## Context

The SQL surface has one authoritative acceptance path — `SqlQueryFrontEnd.plan` followed by
`StructuredQueryBuilder.build` — and exactly one I/O boundary after it, `StructuredQueryExecutor`'s
`materialize`. Everything before that boundary is pure: the Calcite screen, parse, star expansion,
validation against the catalog snapshot and closed operator table, the Rel→DSL or Rel→composite
translation, and the builder's field allowlist, function catalog, mode coherence, window placement and
row governance. The catalog itself is read from PostgreSQL and cached as an in-memory Calcite schema; no
stage below the boundary talks to ClickHouse.

That shape is what makes a faithful validator cheap. The design question is not *how to validate SQL* —
that code exists and is the only thing allowed to define the answer — but *where to cut, and how to
report*.

## Goals / Non-Goals

**Goals:**

- A verdict that agrees with `execute-sql` by construction, not by a parallel rule set that can drift.
- An answer an automated caller can act on without parsing prose: a stable code, a position, a remedy.
- More than one problem per call, where more than one can be established safely.
- Advance warning of the failures that survive validation — the query budget refusals.
- No ClickHouse contact, so the endpoint is safe to call in a tight loop and works while ClickHouse is
  down.

**Non-Goals:**

- Certainty that execution will succeed. The budget is enforced by ClickHouse as the statement runs; a
  pre-flight promise of success would be a lie.
- A validator for the DSL endpoint. Assistants write SQL; `POST /v1/queries/translate` already validates
  a DSL document as a side effect of rendering it.
- A Flight SQL equivalent. Flight is a gRPC listener, not a servlet path, and would need its own wiring
  for a surface no Flight client has asked for.
- Reporting every problem. Calcite has no error recovery, and the recovery layer that would be needed to
  guarantee exhaustiveness is a second copy of the pipeline's rules.

## Decisions

### D1: Validation is `plan()` plus the builder pass, and stops there

The endpoint runs exactly the stages `execute-sql` runs before `materialize`. ClickHouse is never
contacted.

*Rejected — a ClickHouse `EXPLAIN` on the rendered statement.* It reads like higher fidelity and is
close to worthless here: ADAS generates that SQL itself from a plan it has already validated, so
`EXPLAIN` would be checking its own output. It would also make validation fail when ClickHouse is down,
add a round trip to every keystroke-level call, and require a read grant on the parse path.

*Rejected — a static cost estimate that turns a budget breach into `valid: false`.* The budget is
reactive: ClickHouse allocates up to the ceiling and raises `Code: 241`, which `QueryBudget.translate`
re-attributes to `query_budget_exceeded`. Any pre-flight number would be a guess, and a guess that
refuses a statement `execute-sql` would have run breaks the agreement guarantee that is the endpoint's
whole value.

### D2: The governance pass is exposed as a `validate` sibling beside each `execute`, not lifted into the caller

`StructuredQueryExecutor.validate(model, query, maxLimit)` performs the same entity-coherence check and
`queryBuilder.build(...)` as `execute`, and discards the result; `StructuredQueryService.validate`
mirrors both `execute` overloads, single-block and composite.

*Rejected — assembling the pre-flight sequence in `SqlValidationService`.* It would be a second copy of
the order in which the executor resolves, checks coherence and builds, free to drift from the real one
the first time a stage is added. `QueryTranslationService.toSql` already established the
build-and-discard idiom in this codebase; this extends it rather than inventing something.

### D3: Multi-issue reporting is a second pass, on the failure path only

On success the statement is planned once and no probe runs. Only after a rejection does
`SqlIdentifierProbe` parse the statement again and enumerate the unresolvable entity and column names.
The pipeline's own diagnosis is always `issues[0]`; the probe's findings are appended, and its
classification of the name the pipeline already named is dropped so one misspelling is reported once.

The probe parses without validating, so it has no scope resolution: it cannot know which side of a join
an unqualified column belongs to, and it does not type a derived table's or CTE's output. Every rule is
therefore written to stay silent when unsure — a column is reported only when no entity in the statement
could own it, statement-declared names (table aliases, CTE names, select aliases, derived-table output
names) are collected and treated as legal, and when no entity resolves at all no column is reported,
because there would be nothing to test against and every column would look unknown. Most of
`SqlIdentifierProbeTest` is these silences.

*Rejected — full error recovery through parse, validate and plan.* Best feedback, and it means a
hand-written validation layer duplicating rules that live in some two hundred throw sites. It would
drift from the pipeline on the first change to either.

*Rejected — a pre-pass that runs before `plan()` on every call.* It would put probe findings ahead of
the authoritative diagnosis and make the accepting path pay for a second parse it never needs.

### D4: Stable codes come from classifying the message, not from tagging the throw sites

`SqlIssueClassifier` maps a small ordered table of message shapes to a code and a remedy.

*Rejected — carrying a code on every exception.* The rejections span the front end, the Rel and Rex
translators, the star expander and the builder, and roughly a third of them are Calcite's own, worded by
the library. Threading a code through all of them is a large change to the execute path for a read-only
endpoint's benefit, and it still cannot reach the Calcite messages, which are the confusing ones.

The cost of classifying is drift, and it is bounded deliberately: an unmatched message becomes
`invalid_sql` with its text intact and no remedy, so the worst case is the caller reading exactly what
`execute-sql` would have told them. `SqlIssueClassifierTest` pins every pattern to a message captured
from a live rejection, so a Calcite upgrade that rewords one fails the build instead of silently
degrading a code to the fallback.

Three classifications carry information the message itself does not, and are the reason this is worth
doing at all: `unknown_column` for Calcite's *"depends on itself"* (an artefact of ADAS's own alias
injection, not a cycle the caller wrote), `in_list_too_long` for *"SQL construct 'LogicalValues' is not
supported"* (Calcite rewrote an over-threshold `IN` list into a `VALUES` relation), and
`reserved_word_alias` for the case where the parser's reported position is three characters short of the
offending word.

### D5: The reserved-word case is separated from a plain parse error by position, not by a keyword list

`SqlReservedWords.aliasHint` fires on any parse failure where an identifier would have been legal, so it
also fires on a truncated statement (`… dial_usage_log WHERE`), where it names the very keyword the
parser stopped on. The alias case is the one where the hint's position differs from Calcite's — that
mis-anchoring is why the hint exists — so two distinct positions mean the hint located something and one
position means it only echoed Calcite.

*Rejected — a list of clause keywords to exclude.* It would be a hand-kept vocabulary in a codebase that
already went to some trouble to take its reserved-word set from the Babel grammar's own metadata rather
than from a list.

### D6: Warnings are advisory, heuristic, and never affect `valid`

Three: `default_limit_applied` (free — `SqlTranslation.explicitLimit` already records it, and a caller
that does not know its result was truncated at 100 draws the wrong conclusion from the rows),
`heavy_column_unbounded` (a `heavy` binding referenced with no filter on a `timestamp` field), and
`unbounded_group_by` (aggregate mode with a `GROUP BY` and no such filter).

A composite statement receives only the limit note. Its blocks reference relation columns — a join's
merged list, a derived table's output names — not one entity's fields, so a heavy or timestamp column
cannot be attributed to a catalog binding with certainty. Under-warning leaves the caller where they
were; a warning pointing at the wrong column sends them to fix something that is not broken.

*Rejected — making these `issues`.* They describe statements `execute-sql` accepts, so reporting them as
invalid would break the agreement guarantee.

### D7: An invalid statement is `200`

The endpoint's product is the verdict. "This statement is wrong, and here is why" is a successful answer
to the question asked, and the response needs to carry a list, which `ErrorView` cannot. A malformed
request stays `400`, because that is a failure to answer rather than an answer.

*Rejected — `400 ErrorView` for an invalid statement, matching the service-wide convention.* It would
force the multi-issue payload into a single `message`, throwing away the whole point, and it makes the
common case indistinguishable from a transport or auth failure to an automated caller — the very
confusion the convention exists to prevent, inverted.

### D8: A new controller

`SqlQueryController` executes and `QueryTranslationController` translates; this neither. A fourth method
on either would leave its class javadoc describing something it no longer only does.

### D9: `translate-sql`'s missing governance pass is fixed here rather than deferred

It is two lines once D2's `validate` exists, and it is the same defect: an endpoint reporting a statement
clean that `execute` then rejects. Leaving it would also leave a second, wrong answer to the question
this change exists to answer correctly.

## Risks / Trade-offs

- **Classifier drift** on a Calcite upgrade or a reworded ADAS rejection. Mitigated by the fallback
  (degrades to the current behaviour, never to a wrong code) and by pinning every pattern to a captured
  message so the build fails first. Accepted.
- **`translate-sql` becomes stricter.** Statements it answered `200` are now `400`. Anything relying on
  the old behaviour was relying on receiving a document `/v1/queries/execute` rejects; the spec already
  promised the new behaviour. Called out in the proposal as breaking in one direction.
- **The probe could misattribute a column** on a shape its conservative rules do not cover. It only ever
  appends to an authoritative first issue, and the silence tests are the bulk of its coverage. A false
  positive costs a redundant line, not a wrong verdict.
- **Warnings can be wrong in both directions** — a warned statement may run fine, an unwarned one may
  breach the budget. They are labelled advisory in the OpenAPI text and the spec, and excluded from
  `valid`.
- **`valid: true` will be read as "this will work".** The endpoint description, the DTO schema and the
  spec each say what it does mean; the residue is exactly the budget, and that is what warnings address.

## Migration Plan

None required. The new endpoint is additive and unconditional, like `execute-sql`. No configuration, no
migration, no new grant. `translate-sql`'s stricter validation takes effect on deploy with no data or
config change; the `adas-mcp` tool can ship independently and afterwards.

## Open Questions

- Should the Flight SQL surface expose an equivalent? Deferred until a Flight client asks — the wiring is
  separate and no current caller needs it.
- Should `warnings` grow a cardinality estimate once the catalog carries row counts? Only with a real
  measurement behind it; a guessed number would be read as a promise.
