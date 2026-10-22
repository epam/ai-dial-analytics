## Context

Output naming is currently decided independently by three components, which is why aliases are
mandatory in one place and synthetic in another:

- `translate/StructuredQueryBuilder` (execute path) aliases a select entry with `as` when present,
  falls back to the field name for a plain `FieldExpr`, and otherwise throws
  `aggregate output column requires an 'as' alias` (`requireAlias`, aggregate mode) or emits the
  expression with **no alias at all** (row mode). The response keys come from
  `StructuredQueryExecutor.materialize`, which seeds each row map from `Field::getName` of the jOOQ
  select list and overwrites it with whatever column names ClickHouse returns — so an unaliased
  computed column is only "correctly" keyed by accident, and two entries carrying the same alias
  silently collapse into one map key.
- `sql/RelToStructuredQueryTranslator` (SQL surface) takes names from Calcite's validated row type:
  real names for `AS` aliases and column references, `EXPR$n` for everything else. Those names become
  both the DSL `as` values and the `ResultColumn` names that drive the REST SQL row keys and the
  Flight SQL Arrow schema.
- `sql/StructuredQueryToSqlRenderer` (DSL→SQL translation) renders `AS "<alias>"` only when `as` is
  present, so a translated aggregate query that relied on a derived name would render SQL whose
  column names are the engine's, not the ones the execute path promises.

Constraints that shape the design:

- Rows are `Map<String, Object>` (`QueryResultPage`, `StructuredQueryResultDto`) and the Arrow schema
  is a flat field list, so output names **must be unique** — PostgreSQL's tolerance of duplicate
  output names cannot be reproduced faithfully.
- The DSL's expression surface is a closed catalog of 14 named functions plus fields, literals, and
  array literals (`in` right operand only). There are no operators, casts, or `CASE`, so the
  PostgreSQL naming rules needed here are a small subset of `FigureColname`.
- Enrichment columns are exposed under a **qualified flat name** (`deployment_ref.display_name`) and
  the spec requires that exact string as the result key, so the naming rule must not treat a dot as a
  table qualifier the way PostgreSQL's `ColumnRef` rule would.

## Goals / Non-Goals

**Goals:**

- `as` is optional for every select entry on every surface; no query is rejected for lacking a name.
- One shared, pure naming function decides output names for the execute path, the DSL→SQL renderer,
  and the SQL front end, so response keys, rendered SQL aliases, and Arrow schema names cannot drift.
- Derived names are predictable to anyone who knows PostgreSQL, and stable for a given select list.
- Derived names are usable in `group_by`, `sort`, and `having` exactly like explicit aliases.
- Every projected column is rendered with an explicit SQL alias, removing the accidental dependence
  on ClickHouse's own naming in `materialize`.

**Non-Goals:**

- Reproducing PostgreSQL's full `FigureColname` (no casts, `CASE`, `COALESCE`, `GREATEST`, row/array
  constructors, or sub-selects exist in this DSL).
- Allowing duplicate output names (impossible with map-keyed rows and a flat Arrow schema).
- Changing how `select` omission expands to the source's columns, or any filter/sort/paging semantics.
- Positional references (`ORDER BY 1`) or renaming of the omitted-projection column list.
- PostgreSQL's tolerance of an output name that is *also* used inside a larger `ORDER BY` expression
  (PostgreSQL only resolves an output name that stands alone; so do we, by construction).

## Decisions

### D1 — One resolver: `OutputColumnNaming.resolve(List<OutputColumn>) → List<String>`

A new pure static helper in `service/query/model` (package of the DSL model, no Spring wiring, no
dependency on jOOQ or Calcite) maps a select list to its final output names. Every surface calls it
instead of deriving names locally.

*Alternatives considered*: putting the logic on `OutputColumn` as an instance method — rejected
because de-duplication is a property of the whole list, not of one entry; a Spring `@Component` —
rejected as the logic is pure and is needed from static contexts (`RelToStructuredQueryTranslator`).

### D2 — Base-name rules (PostgreSQL subset)

| DSL expression | Derived base name | PostgreSQL rule |
|---|---|---|
| `FieldExpr` | the field name **verbatim**, including any `enrichment.column` dots | `ColumnRef` → column name |
| `FnExpr` (scalar, aggregate, ordered-set, with or without `distinct`) | the function's lowercase catalog name (`count`, `sum`, `date_bin`, `percentile_cont`) | `FuncCall` → function name |
| `ValueExpr` | `?column?` | no name available |
| `ArrayExpr` | `array` | `A_ArrayExpr` → `array` |
| anything else | `?column?` | fallback |

`?column?` is PostgreSQL's literal placeholder text and is used verbatim so a caller familiar with
`psql` recognises it; it is a legal JSON object key and a legal quoted SQL identifier, so it needs no
escaping beyond the quoting already applied to every alias. `ArrayExpr` is unreachable in a projection
today (array literals are rejected outside an `in` operand) — it is mapped for totality, not as a
feature.

*Alternatives considered*: `expr_1`/`col_1` positional names — rejected, the request explicitly asks
for PostgreSQL parity; the SQL text of the expression as the name (`count()`) — rejected, ClickHouse
would then dictate identifier syntax in JSON keys.

### D3 — De-duplication: explicit aliases reserve first, derived names yield

`resolve` runs in two passes over the select list:

1. **Reserve explicit aliases** in list order. A non-blank `as` keeps its exact value; a later entry
   repeating an earlier explicit alias gets the first free `<alias>_<n>` (n from 1).
2. **Assign derived names** in list order for entries without `as`, taking the first free candidate
   from `base`, `base_1`, `base_2`, …, where "free" means not reserved in pass 1 and not already
   assigned in pass 2.

So `[count(), count(distinct user), {field: model, as: "count"}]` resolves to
`count_1`, `count_2`, `count` — the caller's explicit `count` is honoured and the derived names move
out of its way. Ordering is deterministic and depends only on the select list.

PostgreSQL would return three columns and allow two to share a name; we cannot. Suffixing (rather
than rejecting a collision with HTTP 400) was chosen because the whole point of the change is that a
caller need not think about names: two unaliased `count()` entries are a legitimate query, and the
current behavior for duplicate *explicit* aliases is silent data loss (one map key wins), so
suffixing is strictly better than today on that path too.

*Alternatives considered*: reject duplicates with 400 — rejected, it re-introduces "you must supply an
alias" for the exact case the change targets; Calcite's `name0`/`name1` style — rejected, `_1` reads as
deliberate and matches DuckDB/pandas conventions; keeping the first occurrence unsuffixed and
suffixing only later ones (i.e. `count`, `count_1`, `count`) — rejected, pass 1 must win to keep an
explicit alias exact.

### D4 — The execute path always renders an explicit alias

`StructuredQueryBuilder` aliases **every** entry of a non-empty `select` with its resolved name
(`field.as(resolvedName)`), in both modes; `requireAlias` is deleted. The omitted-projection case is
unchanged (bound columns already carry their own names).

This removes the `materialize` hazard: `Field::getName` and the name ClickHouse echoes are now the
same string, so the seeded null placeholder and the returned value share one map key. A column whose
alias equals its field name still renders `"col" AS "col"`, which is harmless and keeps the rule
uniform (no name-dependent branching).

### D5 — Ambiguity resolution follows PostgreSQL clause by clause

A derived name can collide with a real entity column (an entity legitimately owning a column named
`count`, plus an unaliased `count()` entry). PostgreSQL resolves such a bare name differently per
clause, and we follow it:

| clause | PostgreSQL | Implementation |
|---|---|---|
| `sort` (`ORDER BY`) | output column wins | select-output set is consulted first (today's behavior) |
| `group_by` (`GROUP BY`) | **input column wins** | `resolveGroupKey` tries the field binding first, falling back to the select output (needed for computed keys such as a `width_bucket` output, which is not a physical column); the one shape where that flip would change an existing document's meaning is rejected instead (below) |
| `having` | input columns only | derived names bind with `putIfAbsent`, so the physical column keeps the name; an **explicit** alias still overwrites it (today's behavior, kept as a documented DSL nicety) |

The `group_by` order is a fix, not just an extension: `resolveGroupKey` currently checks select
outputs *before* bindings, so a select output shadowing a column name changes the grouping. The flip
is transparent for the case D5 targets (a *derived* name colliding with a column) and for the existing
case that relies on output resolution (grouping by a `width_bucket` output names something that is not
a bound field, so it still falls through to the select output). It is **not** transparent for an
*explicit* alias that names an entity column while projecting something else: `select: [{date_bin(1,
'hour', request_time), as: "request_time"}, {count()}], group_by: ["request_time"]` used to group by
the hour bucket and would, after the flip, group by the raw timestamp while still projecting the
bucket — same response keys, no error, one row per distinct timestamp. Aliasing a time bucket back to
the timestamp column name is a common BI/Grafana idiom, so silently regrouping it is not acceptable.

`resolveGroupKey` therefore **fails closed** on exactly that shape (a group-by key that is both a bound
entity column and the explicit `as` alias of a select entry projecting a different expression):
`QueryValidationException` → 400, naming the key and asking for an alias that is not a column of the
entity. Rejection rather than either precedence, because

- the document is genuinely ambiguous — one name, two groupings, and no DSL syntax that distinguishes
  them;
- the DSL→SQL rendering of the key is the bare name (`GROUP BY "request_time"`), which resolves to the
  *column* when re-parsed, so keeping output precedence in execution would make `/v1/queries/translate`
  emit SQL with different semantics than the document it rendered;
- the SQL surface rejects every statement that maps onto such a document (D5b), so both surfaces answer
  the same way instead of one silently regrouping what the other 400s.

An alias that merely repeats the name of the field it projects (`{field: model, as: "model"}`) is not a
collision — both readings group by the same column — and is unaffected. With the guard in place no
existing document changes meaning silently: aggregate mode previously required an alias on every
computed entry, so an explicit alias shadowing a column is the *only* pre-existing shape the flip
touches, and it is now an error rather than a different result. Listed under BREAKING in the proposal.

### D5b — The SQL surface fails closed on a computed group key whose output name is a column

The flip does have a consequence on the SQL surface, where the output name is caller-chosen rather
than DSL-authored. `RelToStructuredQueryTranslator.groupByNames` can only express "group by this
computed expression" as the *name* of the select output carrying it, so with input-column-wins in
`group_by`, `SELECT date_bin(1,'hour',request_time) AS deployment, count(*) … GROUP BY
date_bin(1,'hour',request_time)` would emit `group_by: ["deployment"]` and execute grouped by the
unrelated `deployment` column — silently wrong aggregates over `execute-sql`, `translate-sql`, and
Flight SQL, and a violation of the "executes with semantics equivalent to the submitted SQL"
guarantee.

The translator therefore picks, among the select outputs carrying that expression, one whose name is
not a column of the entity, and rejects the statement (`SqlTranslationException` naming the collision)
when every candidate is shadowed. Failing closed is right here: the DSL genuinely cannot say "the
output column, under a name a column already owns", and a caller can always alias the expression to a
free name. Keeping select-output-first precedence in `resolveGroupKey` for computed entries was the
alternative — rejected because it re-breaks the PostgreSQL parity D5 specifies for the DSL surface
(`GROUP BY <name>` must mean the input column) and because the bare name is all the DSL→SQL renderer
can emit for a group key, so output precedence would only move the divergence into `/translate`.

The mirror shape needs the same treatment: a `GROUP BY` on a plain **column** while a select item is
explicitly aliased after that column but projects something else (`SELECT deployment AS
parent_deployment, parent_deployment AS deployment, … GROUP BY deployment, parent_deployment`). The DSL
key is again a name that is both a column and a foreign alias — the shape `resolveGroupKey` now rejects
(D5) — so `groupByNames` rejects it here too, with the SQL-level reason, instead of emitting a document
that only fails when executed (`/translate-sql` does not run the execute-path validation). Calcite
resolves a bare `GROUP BY` name to the input column, and a projected expression that is not a group key
is rejected earlier, so in practice this guard is reached only by statements that swap two grouped
columns' names.

`having` and `sort` need no equivalent guard: `having` carries the aggregate *expression* rather than a
name, and `sort` resolves output-name-first on both surfaces.

### D6 — SQL front end: inject the derived aliases **before** validation

The names must exist before Calcite validates, not after, or they are not identifiers the statement can
reference: `SELECT count(*) FROM t ORDER BY count` works in PostgreSQL (a bare `ORDER BY` name resolves
against output columns) but would fail Calcite validation with "column 'count' not found" if the output
were still called `EXPR$0` at validation time.

So between `planner.parse` and `planner.validate`, a new `sql/SqlSelectAliasInjector` rewrites the
parsed select list in place: every item that is not already named — not a bare identifier, not an
`AS` call, not `*` — is wrapped in `AS <derived name>`, with the names resolved through the shared
uniquifier (D3) so Calcite never has a duplicate to rename on its own. Base names come from the parse
tree, mirroring PostgreSQL's `FigureColname` over its own parse tree:

- a function-syntax call (`SqlSyntax.FUNCTION`, `FUNCTION_ID`, `FUNCTION_STAR`) → its lowercase
  operator name, so `count(*)` → `count` and `CHAR_LENGTH(x)` → `char_length` (PostgreSQL names by the
  spelling written, even though the DSL maps that call onto `length`);
- `WITHIN GROUP` → the name of the aggregate it wraps, so
  `percentile_cont(0.5) WITHIN GROUP (ORDER BY x)` → `percentile_cont`;
- anything else (literal, operator expression, `CASE`, `CAST`) → `?column?`. Every one of those except
  a bare literal is rejected downstream anyway, so the placeholder is a totality rule, not a feature.

Because the aliases are real aliases by the time the validator runs, `ORDER BY count`, `GROUP BY count`
and Calcite's own ambiguity rules apply exactly as PostgreSQL's do — including "input column wins" in
`GROUP BY`, which Calcite resolves before select aliases.

A star (`SELECT *`) cannot be expanded before validation, so its columns are not in the reserved set;
`RelToStructuredQueryTranslator` therefore keeps a safety net over the *validated* names: any name
still matching `EXPR$<digits>` takes the D2 base name of its DSL expression, and the whole list goes
through the shared uniquifier. In the normal case the net is a no-op — the validated names are already
the injected ones.

*Alternatives considered*: deriving names only after validation (the original plan) — rejected once it
became clear `ORDER BY count` is valid PostgreSQL and would still fail here, leaving the surface
PostgreSQL-shaped in its output but not in what it accepts; rewriting the SQL *text* rather than the
AST — rejected as unsound (quoting, comments); teaching the validator a custom name generator — Calcite
offers no hook for target-list naming, and `EXPR$n` is produced deep inside `SqlValidatorUtil`.

`OutputColumn.as` is then **dropped only where dropping it provably changes nothing**
(`OutputColumnNaming.withMinimalAliases`): the select list starts with `as` set on every entry — which
resolves to the reported names by construction — and each entry's alias is then nulled out
tentatively and kept null only if re-resolving the *whole* list still yields the same name list. So
`SELECT count(*) FROM t` translates to `{"expr": {"type":"fn","name":"count","args":[]}}` with no `as`,
while `SELECT count(*) AS n` keeps `"as": "n"`. Consistency is structural — the decision is made by the
same resolver execution will run — so the DSL echoed by `/translate-sql` always executes to the
`ResultColumn` names reported next to it.

Comparing instead against "the names this list would take if *no* entry had an alias" (the first
implementation) is wrong: a retained alias reserves its name in pass 1 of D3 and shifts what the derived
entries get, so the hypothetical baseline is not the resolution of the list actually produced. Concretely,
`SELECT count(*) AS foo, count(deployment) AS count_1` has `count_1` equal to the second entry's
alias-free default and would drop it — after which the entry executes as `count`, silently renaming the
caller's column on `/execute-sql` and streaming an all-null Arrow field over Flight SQL (the schema keeps
`count_1` while rows are keyed `count`). Re-resolving the candidate list closes that hole for every shape,
and the invariant `resolve(translation.query().select()) == translation.columns()` names is pinned by a
test over a matrix of alias/collision combinations.

### D6b — Two derivations of one rule, held together by a parity test

D2's base names are derived from `Expr` (the DSL side); D6's from `SqlNode` (the parse tree). One rule,
two implementations — the same shape as PostgreSQL, which also names from its parse tree. The
uniquifier itself is shared, and a parity test drives every function in the closed catalog through both
paths (`SELECT f(args) FROM entity` vs. the equivalent DSL `FnExpr`) and asserts identical output names,
so the two cannot drift as the catalog grows. The one deliberate difference is a SQL synonym:
`char_length(x)` names its column `char_length` (what the caller wrote, as PostgreSQL does) while the
DSL's `length` call names it `length` — the test pins that expectation rather than glossing over it.

*Alternatives considered*: dropping Calcite's names entirely and deriving every name ourselves —
rejected, Calcite is the authority on which `AS` alias belongs to which output after projection
trimming; always emitting `as` — rejected, it hides where the caller actually asked for a name and
makes the translated DSL noisier than the SQL it came from.

### D7 — DSL→SQL renderer emits the resolved alias

`StructuredQueryToSqlRenderer` renders `AS "<resolved name>"` for every output column whose resolved
name is not already the name the bare expression produces — in practice: always, except a plain
`FieldExpr` whose resolved name equals its field name. The rendered SQL therefore declares exactly the
columns the execute path returns, and re-parsing it through the SQL front end yields the same names
(round-trip stability). The renderer's existing `projected` map (used to resolve `group_by`/`sort` keys
that name an output) is built from resolved names for the same reason.

## Risks / Trade-offs

- **Existing clients reading `EXPR$0` from the SQL surface break** → called out as BREAKING in the
  proposal; `EXPR$n` was never a documented contract (it is Calcite's internal naming) and the new
  name is discoverable from the same response/`ResultColumn` list. No compatibility flag is added: two
  naming modes would double the surface for a name nobody deliberately depended on.
- **A derived name can shadow an entity column in `group_by`/`sort`** (e.g. entity column `count` and
  an unaliased `count()` in aggregate mode) → mirrors PostgreSQL's own output-name precedence in those
  clauses; `having` and `group_by` keep the column (D5), and a caller who wants the output can always
  name it something a column does not own. Where the two readings of a `group_by` key differ on a shape
  that used to resolve the other way — an **explicit** alias naming a column over a different
  expression — the query is rejected with 400 rather than silently regrouped (D5); the SQL surface
  rejects both statement shapes that map onto it (D5b), so neither surface picks a reading.
- **`?column?` as a JSON key looks odd** → it is exactly what PostgreSQL shows, it only occurs for a
  projected bare literal (a rare query), and `as` remains available.
- **Uniformly aliasing every column changes rendered SQL text** for row-mode queries, so
  `/v1/queries/translate` output and any test asserting on rendered SQL change → tests updated in the
  same change; the semantics of the SQL are unchanged.
- **Rewriting the parsed AST could change what the SQL surface accepts or plans** → the injector only
  wraps select items in `AS`, which adds no relational operator and leaves the
  `TableScan → … → Project` stack the RelNode walker expects unchanged; items already named and `*` are
  left untouched. Covered by tests for each supported statement shape, including `SELECT *`,
  `SELECT DISTINCT`, `WITHIN GROUP` aggregates, and every rejection message that names a construct.
- **Two `count()` entries now resolve to `count_1`/`count_2` rather than failing** → the numbering is
  positional, so a client that reorders its select list gets different keys; documented in the spec as
  deterministic-by-position.

## Migration Plan

No data migration, no configuration, no new dependency. Deployment is a single service rollout;
rollback is a redeploy of the previous version (naming is computed per request, nothing is persisted).
Grafana/BI consumers of the SQL surface that select unnamed expressions see the column rename on the
first request after rollout.

## Open Questions

None outstanding — D3 (suffixing over rejection) and D6 (`as` omitted when redundant) were the two
judgement calls and are settled above.
