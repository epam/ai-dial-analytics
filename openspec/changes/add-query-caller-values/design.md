## Context

See `proposal.md` for the motivation. The pieces this design builds on:

- Row policies already resolve the two caller values per read. `RowPolicyPredicates` rewrites a policy's filter
  before translation: a known value becomes a string value node, an unknown one replaces the whole comparison with a
  condition that is `NULL` for every row. The values come from the request's identity (`SecurityClaimsExtractor`),
  and `RowPolicyFilter.callerValue` logs and puts a span attribute on an unknown one. See
  [table-access-control](../../specs/table-access-control/spec.md).
- A policy condition written in SQL reaches the same `param` through `RowPolicyConditions.markCallerValues`: each bare
  spelling outside a quoted section or a comment becomes a string literal holding a per-call marker before parsing,
  and a string value equal to the marker becomes the `param` after translation. A policy condition is a single
  `WHERE`, which Calcite does not simplify beyond null comparisons.
- Every query surface ends in one of two translations. A single-block query (DSL, or SQL that plans to one block)
  becomes a `StructuredQuery` built by `StructuredQueryBuilder`; a composite SQL statement becomes a `CompositeQuery`
  built by `CompositeQueryBuilder`. Both render filters through `FilterTranslator`, and every user read executes
  through `StructuredQueryExecutor`, which builds a second, count statement for `include_total`
  (`StructuredQueryBuilder.buildCount`). Pipeline scan, group-fetch, materialize and aggregate paths use the same
  `FilterTranslator` and SQL front end, with no caller.
- Several passes build a query without executing it: the DSL translate endpoint and saved-query and dashboard
  structured bodies (`PortableQueryRenderer.render`), and `translate-sql` and `validate-sql`
  (`StructuredQueryService.validate`). A saved or dashboard `sql` body is only planned (`SqlQueryFrontEnd.plan`), never
  built.
- The SQL front end's entry points are shared: `plan(String, Privilege)` serves `validate-sql` and `planInsert`;
  `translate(String)`, used by the enrichment filter and expression validators, delegates to the overload
  `translate-sql` and the policy condition use. No pipeline validator refuses a `param` itself.
- A written cast to a character type is rewritten into an internal `TO_STRING_CAST` before validation and translates
  to its operand when that is already text (`SqlTextRebinder`, `RexToExprTranslator.castExpr`), as the main spec's
  "Text composition and value rendering in the SQL subset" requires. A caller who spells that internal name is refused
  by `SqlTextRebinder.rejectSpelledMarker` (`unsupported_function`), and one who spells the lambda marker by
  `SqlLambdaRewriter`.
- Today a query `param` is refused in `ExprTranslator` (build), `StructuredQueryToSqlRenderer` (DSL → SQL), and
  `SavedQueryBodyValidator` (saved queries and dashboard inline bodies, `422`).
- `RexToExprTranslator.orFilter` folds a disjunction of equalities with literals on one column into an `in` list and
  keeps any other disjunction as an `or`; `ReadShaping` pushes an enrichment-column conjunct, and drives partition
  preselection, only when its operand is a literal value node.
- A Flight SQL ticket carries only the SQL text, and redeeming it plans the statement again under the redeeming
  caller. Prepared statements are not supported. Statements against `INFORMATION_SCHEMA` are evaluated in-process.

## Goals / Non-Goals

**Goals:**

- One stored form: the `param` node is what the DSL, saved queries, dashboards and policies carry; the call spelling
  is only how SQL writes it.
- A caller value in a query behaves exactly as in a row policy once it is resolved, including the unknown case.
- Translation and validation never need, read or reveal a caller's value, and agree with execution.

**Non-Goals:**

- Acting as one of one's roles for a query, so that a role's policies apply. It is a separate feature with a broader
  meaning ("what this role may see", not "mine").
- Caller values in projections, sort keys, `case` expressions or function arguments, and caller values beyond the
  two.
- Passing a caller value through a header, a query parameter or a query setting.
- Any change to row policies' evaluation, to how caller values are derived, or to pipelines.

## Decisions

**1. SQL spells a caller value as a call: `caller.user_hash()`.** The bare `caller.user_hash` is valid ClickHouse for
the column of a relation aliased `caller`, so admitting it in a query would need a precedence rule and would change
what a valid ClickHouse statement means. A qualified call is not valid ClickHouse, so no statement loses its meaning,
and it reads as a server-side value, as `currentUser()` does in ClickHouse and `auth.uid()` in Supabase. Rejected:
`$caller.user_hash`, which Grafana interpolates as a dashboard variable before the query reaches the service and
which reads as a client-side template; the bare spelling with a precedence rule.

**2. In SQL the call is an operator Calcite cannot fold, not a marker literal.** A marker literal is an ordinary
constant to Calcite, which simplifies `HAVING` and `ON` conditions with it: `project_id = <marker> AND project_id =
'P1'` becomes an empty relation in `HAVING` and a `false` join condition in `ON`, and in `HAVING`
`project_id <> <marker> AND project_id = 'P1'` drops the caller-value term, so an unknown value would match rows.
Throwaway probes on Calcite 1.42 with the front end's parser and validator settings showed that a zero-argument
function marked non-deterministic and dynamic survives `WHERE`, `HAVING`, `ON`, derived tables and `EXISTS`
subqueries, including next to a literal on the same column, and that `x IN ('a', f())` lowers to
`OR(x = 'a', x = f())`. Its return type is a **nullable** `VARCHAR` (`ReturnTypes.VARCHAR_FORCE_NULLABLE`;
`VARCHAR_NULLABLE` derives `NOT NULL` for a call with no arguments): with a `NOT NULL` type Calcite decides
`f() IS NOT NULL` as true and drops it, and turns `f() IS NULL` in `HAVING` into an empty relation, before anything
can refuse them; nullable is also what the value is, since an unknown one is `NULL`.

Calcite parses `caller.user_hash()`, `caller . user_hash()` and `caller.user_hash /* c */ ()` into the same
qualified name, so making that name resolve would accept spellings the spec refuses; and the probes could not make
Calcite's routine lookup admit a qualified name from an extra operator table anyway. The front end therefore rewrites
the text `caller.user_hash` or `caller.project`, matched case-insensitively like every SQL function name, when the
next character after optional whitespace is `(`, to an internal operator name of the same length (for example
`caller_user_hash`), outside literals, quoted identifiers and comments, before parsing. The name must start at a word
boundary (not after a name character or `.`), so `xcaller.user_hash()` is left alone. The scanner shares one tokenizer
with the `FINAL` rewrite and knows every literal and comment form Babel accepts, `E'…'` with backslash escapes and
`//` comments included. A comment between the name and `(` stops the rewrite; anything between the parentheses is left
to the parser, so a comment there is accepted and an argument is an arity error. Equal length means the rewrite moves
no position Calcite reports, and messages about a rewrite-produced call, the arity error included, name it by the
call spelling rather than the internal name.

The rewrite is opted into explicitly. The front end's entry points take a flag that only the user-statement call
sites (`execute-sql`, `execute-sql-csv`, `validate-sql`, `translate-sql`, Flight SQL, saved-query and dashboard `sql`
validation) and the policy condition pass. The pipeline validators, which share the `translate(String)` and
`plan(String, Privilege)` overloads with those call sites and refuse no `param` of their own, never pass it.

The operator is registered in the shared operator table, and Calcite matches function names case-insensitively and
through quoted and Unicode-escaped identifiers, so a statement could call the internal name itself
(`CALLER_USER_HASH()`, `"caller_user_hash"()`, `U&"caller\005fproject"()`), even next to a legitimate call. Every
statement the front end parses — user statements, policy conditions, aggregate `INSERT … SELECT` declarations and
enrichment filter and expression SQL — therefore has each call of an internal name checked by parser position: the
statement is first parsed as written (the screen already does this), where `caller.user_hash()` is a call of the
qualified name, and those positions are the only ones a call of an internal name may stand at after the rewrite,
which keeps every position. Any other is refused; a statement nothing was rewritten in, every pipeline declaration
included, admits none. Positions come from the parser on both passes, so tabs, line terminators and comments cannot
make them disagree, and a word that is no call — a CTE named `caller_project` — is untouched. The tokenizer ends a line
comment at `\r` as well as `\n`, as the parser does. The refusal uses Calcite's own wording, "No match found for
function signature" followed by the unquoted name, so `validate-sql` classifies it `unknown_function` and the message
does not suggest the valid spelling is missing. Unlike `TO_STRING_CAST`, refused as `unsupported_function`, these names are reported as unknown
on purpose: a caller should not learn that they exist. Pipeline SQL therefore reaches the operator by no spelling, and
a column named like an internal name stays readable. The internal names are internal markers and are never listed in
the SQL function catalog.

The bare spelling keeps the marker in the policy condition, where only a single `WHERE` is involved; a policy
condition's call spelling takes the operator path like a query. The marking pass skips a name followed, after
whitespace, by `(`, which today it would mark into a parse error, and the unmarking expands a marker found inside an
`in` array into the disjunction of equalities, so `IN (…, caller.user_hash)` stores the same filter as
`IN (…, caller.user_hash())`.

**3. Placement is checked twice: structurally before SQL translation, by type over the query.** Before
`RexToExprTranslator` translates anything, a pre-pass walks every Rex tree it receives — filters, join conditions,
projections, sort and group expressions, and the plans of subqueries — and finds each caller operator. After
stripping the casts that leave a text value as it is (a written character cast of a text operand, which arrives as
`TO_STRING_CAST`, and identity casts; a written cast of a non-text operand stays, as the `to_string` it translates
to), the operator's parent must be `EQUALS` or `NOT_EQUALS` against a `string` expression whose translation reads a
column, and that
comparison must be reached from a filter root (`WHERE`, `HAVING`, `ON`) through `AND`, `OR` and `NOT` alone. Anything
else raises the placement message there and then: a cast to another type (how a comparison with a non-string column
arrives), a `LIKE` pattern, `IS [NOT] NULL`, a function or literal-only argument, a projection, a `CASE` condition, or
a comparison used as a value. Running first is what makes the message reliable: the translator's own refusals of
those enclosing nodes (`unsupported_cast`, "LIKE requires a string literal pattern", an unsupported `=`) would
otherwise answer first without stating the rule. The translator then turns each admitted operator into the `param`.

The pre-pass also checks the other operand: it must read a column, hold no caller value, and be of type `string`
by the structured query's own typing (`ExprFieldType.ofDeclared`, over the block's declared column types), so an
`enum` or `uuid` column, which Calcite types as `VARCHAR`, and an aggregate over one do not qualify, while a computed
expression (`lower(user_hash)`), a grouped expression read through its alias and an aggregate output do — whether or
not the planner folded a derived table into the block. The surfaces that only plan (saved and dashboard `sql`
bodies) thereby enforce the rule without a build.

The other operand was first limited to a field, as in a row policy. That cannot be kept consistent across the two
surfaces: an alias in SQL `HAVING` translates to the aggregate or expression it names, and a derived table's computed
column becomes the computation once the planner folds the block, so the DSL accepted queries whose rendered SQL the
SQL surface refused, depending on folding the caller cannot see. The soundness rule — no function around the caller
value — concerns the caller value's side only, so the other side is allowed any text expression over a column.

The structured query is checked where it is built, on every build path — execution, validation, translation and
saved bodies alike — against the block's own bindings, which cover enrichment columns and, for `having`, the grouped
and output names. `FilterTranslator` admits a caller value only as a direct `eq`/`ne` operand whose other operand is
a `string` expression reading a field, in a predicate; an aggregate in it only under `having`, which it is told apart
by its own entry point (`toHavingCondition`), because in a `filter` an unknown value would replace the comparison
before the engine could refuse the aggregate, and the answer would depend on the caller. The other operand is
translated before anything is bound, so an unknown field is the same error for a caller with a value and one without. A comparison inside a `case` branch, which it translates as an expression, is refused, which is
what keeps an unknown value from taking the branch's `else`. A caller value anywhere else — a `select` entry, a
function argument, an `in` item — reaches `ExprTranslator`, which refuses it with the same placement message wherever
caller values are admitted, and as any other `param` where they are not. Both use the rule
`RowPolicyPredicates.requireCallerComparison` applies to policies. The placement message, like the
`INFORMATION_SCHEMA` refusal (decision 7), contains "is not supported by the query translator", which
`SqlIssueClassifier` already reports as `unsupported_construct`, so no new classifier rule is needed.

An `IN` list of 20 items or more holding a caller value is planned as an `IN` subquery over a union of literal rows and
a projection of the operator. It is refused exactly as a long `IN` list is today (`in_list_too_long`), before the
caller value is reached; a test pins the code.

**4. Resolution happens in the filter translator, under one of three scopes.** `FilterTranslator` gains the
rewrite `RowPolicyPredicates` already performs, extracted into one shared piece (`CallerValues`) so a policy and a query
cannot diverge on the unknown case. Which values it binds comes from a scope the current thread runs under:

- **none**, the default: every `param` is refused as today, so pipelines and any path nobody wired keep refusing;
- **validating**, for builds that only validate (`PortableQueryRenderer.render`, `StructuredQueryService.validate`
  and its composite counterpart): a fixed 512-byte string stands in for each value, no identity is read, and the
  built statement is discarded;
- **resolving**, opened by `StructuredQueryService.execute` around the whole build and execution, the `include_total`
  count build included: the request's caller values, a known one becoming a string value node, an unknown one
  replacing the whole comparison with the always-`NULL` condition.

The scope is a static thread-local rather than a bean: the translators are constructed by hand in many places, and
every copy (`FilterTranslator.withSubqueryRenderer`, `withPushed`) sees the same scope without being handed it. It is
opened and closed inside one call on one thread and holds only the function that call supplied.

Rejected: rewriting the DSL tree into literals before translation. An unknown value has no DSL node meaning "`NULL`
for every row" (`= null` means `IS NULL`), so it would need a new node visible to every translator path.

`ReadShaping` treats a caller value as a non-default string literal when deciding whether an enrichment-column
conjunct is pushable and whether it drives partition preselection. That is sound: a known value is non-blank, and an
unknown one is `NULL`, which is false on default rows too. Without it, the same filter written with a caller value
would take a slower read path than with a typed literal.

**5. Translation renders, never resolves.** `StructuredQueryToSqlRenderer` renders the two names as
`caller.user_hash()` / `caller.project()` and keeps refusing every other `param`. The translation endpoints read no
identity, so their output is the same for every caller and never carries a hash.

**6. Saved queries and dashboards store the `param`.** `SavedQueryBodyValidator` stops counting the two names as
parameters. Placement is then checked by the translation `SavedQueryBodyResolver` runs: the planning of decision 3 for
`sql` bodies, and for `query` bodies `QueryTranslationService.toSql`, which goes through `PortableQueryRenderer.render`
and so through the placeholder build. Wiring the placeholder there also reaches the declaration previews that call
the same renderer, which is harmless because declarations refuse caller values earlier. The client runs a saved body
through the query surface, so resolution per runner follows with no further change.

**7. Metadata statements refuse caller values.** An `INFORMATION_SCHEMA` statement is evaluated in-process, where the
operator has no value; a plan of that kind holding the operator is refused `400 bad_request` rather than evaluated.

**8. Diagnosability.** An unknown caller value in a query is logged at debug level and sets the span attribute
`adas.query.unknown.<name>`, by name and never by content, mirroring the policy path's `adas.row_policy.unknown.<name>`
so the two causes of an empty result stay distinguishable.

## Risks / Trade-offs

- [A caller without a hash gets an empty "my rows" view with no error] → it is the policy semantics and the only
  safe one for `NOT`; the debug log and span attribute show which value was unknown. A deployment that wants these
  views sets the `logging-*` settings. In `none` mode no credential is read, so every caller value is unknown there.
- [The always-`NULL` condition inside a join's `ON`] → measured on the pinned ClickHouse image by the integration
  tests: the constant `NULL` term is accepted in an inner and a left join's `ON`; the inner join matches nothing and the
  left join keeps every left row with nothing matched on the right, so no typed-`NULL` fallback was needed.
- [The text rewrite sees every user statement] → it skips literals, quoted identifiers and comments as the policy pass
  does, and is length-preserving; a spelling it misses fails closed, as an unknown function.
- [Composite statements put a caller value in places a policy never sees, such as `ON` and subqueries] → the checker
  walks every block and each block's filter goes through the same translator; tests cover a join, a left join, a
  subquery and `HAVING`.
- [A sealed derived table's computed column is typed by its result kind] → a `CASE` over an `enum` column in a
  derived table the planner does not fold is read by the outer block as a `string` column, passes placement, and may
  then fail in ClickHouse when compared with a hash that is no member of the enum. Where the block is folded the
  expression itself is typed and refused; pass-through columns keep their declared type. The sealed computed case is
  an edge the spec does not promise to catch. A `string` expression whose element type is needed (`array_join` over a
  column) is typed `null` at plan time, where element types are not carried, and is refused there.
- [The resolved hash now reaches a log] → before this change no ADAS log line carried a user hash; the debug SQL log
  of an executed statement (`StructuredQueryExecutor`) now shows it as a literal. That logger is off at the default
  level and already carries every literal of a statement, and the value is the executing caller's own.
- [Cost] → `user_hash = '<literal>'` uses the same skip index a policy relies on, and decision 4 keeps enrichment
  pushdown and partition preselection.

## Migration Plan

No data, catalog or config migration. The feature is additive: a query that used no caller value is translated and
executed as before. Rolling back makes stored saved queries and dashboard bodies that hold a caller value fail to
execute (`400`) and fail their next write; remove those bodies before a rollback. Policies stored through the call
spelling are stored as the same `param` and survive a rollback.
