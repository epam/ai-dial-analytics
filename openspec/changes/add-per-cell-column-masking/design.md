## Context

See proposal.md for the motivation. The facts that shape the approach:

- **One decision, many consumers.** `SensitiveColumnAccess.visibleColumns` returns a `ColumnVisibility` (all, or an
  allowed set), and every consumer asks it `allows(column)`: the query model (`CatalogQueryService.buildModel`), the
  catalog read and revision snapshots (`TableMapper`), `column_count` (`TableCatalogService`), schema changes
  (`TableSchemaService`), export and import (`CatalogExportService`, `ImportTargetReader`), the pipeline guards
  (`SensitiveProjectionGuard`) and `columns_restricted` (`TableCallerPermissions`). The clearance is a separate check
  at each of them and stays one.
- **The query model is the read chokepoint.** `buildModel` binds each field to a `QueryFieldBinding` whose `field()`
  is the physical `"<table>"."<source_name>"`, and every surface — DSL, SQL (Calcite → DSL), CSV, Flight, translation,
  validation — resolves fields through it. Inside the query builder, `ExprTranslator.resolveFieldOrNull` is the point
  most references pass through. Several paths read `field()` as a physical name on purpose: enrichment derived-table
  projection (`EnrichmentJoins.derived`), object subcolumn access (`ObjectPathUsage.subcolumn`,
  `ExprTranslator.subcolumn`), partition bounds (`ReadShaping.partitionBounds`), composite key reduction
  (`CompositeQueryBuilder.addReduction`) and `toJSONString` over an object. Others bypass the resolver: the wildcard
  projection, object-path argument extraction and the read-shaping pushdown, which renders pushed conjuncts and calls
  **inside** the enrichment's derived table, where the source's columns are not in scope.
- **Row policies are per role and already stored as filter trees.** `SensitiveColumnAccess` reads the role → policy map
  (`rowPolicyCodec`); `RowPolicyPredicates.render` translates a filter against the table's physical bindings with caller
  values inlined. `RowPolicyFilter.confining` is not the right source for a mask: it returns nothing when an
  unrestricted reading role exists, yet such a caller can still have masked columns.
- **Computed fields are completed after the statement** (`ComputedFieldCompleter`, `RequestChainReader`), and the
  render guard requires their stand-in bare in the select list. Projection-only enrichment columns are late-
  materialized in the same statement (`StructuredQueryBuilder`: a page block, then the block as written restricted to
  the page's keys), so the source table is in scope wherever their values are selected.
- **Results pass through Java.** `StructuredQueryExecutor.materialize` builds the row maps every surface consumes; CSV
  writes `page.columns()`, Arrow reads `row.get(column.name())` with every field nullable.
- **Composite types.** On ClickHouse 26.3 and 26.8, `if(c, <array>, NULL)` and `if(c, <map>, NULL)` return a
  `Variant`, which array and map functions reject; `if(c, <array>, [])`, `if(c, <map>, map())` and
  `if(c, <json>, '{}'::JSON)` keep the column's type.
- **Measured cost.** On a running deployment's usage log (millions of rows), over 15 query shapes and 6 policy layouts:
  masked reads cost a small fraction of query time, more on exports that read many masked values; per-row flags cost
  nothing measurable beyond the mask; a filter on a masked partition-key column, unrewritten,
  read more than an order of magnitude more rows than the same filter for an unmasked caller, and the rewritten form
  read no more rows than the unmasked one in every case.

## Goals / Non-Goals

**Goals:**
- One decision with three outcomes — visible, masked with its condition, hidden — consumed by every surface.
- No path observes a stored value where its cell is absent, including paths that read physical names.
- Filters on masked fields keep the table's pruning.
- Per-row marks at no measurable cost beyond the mask.

**Non-Goals:**
- Masking on the pipeline paths: they refuse a masked column. A derived table cannot carry a per-row mask.
- A per-request switch back to hiding, or any configuration: masking replaces hiding.
- Reporting which policy made a cell absent, or the policies themselves.
- Marks on CSV and Flight SQL.

## Decisions

### The column decision returns masks, not SQL

`ColumnVisibility` gains a third state. For a masked column it carries the list of the governing and row-contributing
roles' `RowPolicy` filters that allow it, as stored, never rendered SQL. `allows(column)` keeps answering "visible or masked" for the
schema surfaces. A new `masks(column)` returns the filters, and the pipeline guard asks a strict `visible(column)`.
`CatalogQueryService.buildModel` translates the filters against the **governed table's** physical bindings, the
ones it already builds for `tableBindings`. It binds the caller values itself, from the request's claims, as
`RowPolicyFilter.predicate` does (`CallerValues.of(claims, …)`, with the comparisons pre-bound). It does not rely on
the thread-local `CallerValues.resolving` scope. `buildModel` runs before that scope opens (`StructuredQueryService`
resolves the query first), and discovery and the pipeline paths open none, so without this a caller masked by an
own-rows `*` policy would get a `400` on every query, discovery request and pipeline call. The governed table is the column's own
table, or for an enrichment column its source, whose columns the policies read: translating against the enrichment
would fail, or bind a same-named enrichment column and judge the wrong row. It ORs the filters into one jOOQ
`Condition`, deduplicated by the role set that produced it, so columns that share allowing roles share one condition.

The condition is wrapped as `ifNull(<or of the filters>, 0)`, so it is true or false on every row and never `NULL`.
An unknown caller value renders as `CAST(NULL, 'Nullable(UInt8)')`, and a policy comparing a column that is `NULL` on a
row is `NULL` there. Unwrapped, both would turn the value into the absent value as they should, but the flag
`NOT mask` would be `NULL`, and `max` ignores `NULL`s. The row's mark would be lost, and so would the null a computed
field takes from it, which would leak the completed value. As a second guard, `materialize` and the completer treat
any flag value other than an explicit `0` as absent.

**Each condition's text is rendered once per SELECT scope.** A policy filter can be up to 16 KiB, and a mask is the OR
of several, so inlining it at every reference would multiply its size by the number of references: wildcard entries,
flags, rewritten conjuncts, the copies of the filter in key subqueries and in the page block. That could exceed the
server's `max_query_size` for masked callers only. Instead, the first reference **rendered** in a scope renders
`(ifNull(<or>, 0) AS __adas_mcond_<i>)` and every later one renders the bare alias, **inside lambda bodies too**. The
definition sits at a reference outside every lambda body, and its place in the text does not matter. ClickHouse
resolves an alias from before its definition, from a lambda body (nested ones included), from `WHERE`, `HAVING` and
`ORDER BY`, and from another aggregate's argument; this was probed on 26.3 and 26.8. An alias defined inside a lambda
body is invisible outside it, so a scope whose every reference sits in lambda bodies (a filter-only lambda, a key
subquery) defines the alias at the first reference in each outermost body and reuses it there. No lambda parameter can
shadow the alias, because internal names are chosen apart from every parameter name. A parameter shadows only an
alias with exactly its name, which was probed.

The column references in a condition are the hard-qualified physical names `"<table>"."<column>"`, and those can be
captured by a caller-declared name:
- An **output alias** named after the table captures them in the whole SELECT scope. ClickHouse resolves `t.x` against
  a same-scope alias `t` first, reading `x` as a subcolumn of the alias's type, and falls back to the table column
  where that type has no such subcolumn. This was probed on 26.3 and 26.8. A `String` alias `t` gives `t.size` as its
  length while `t.d` falls back to the stored `d`, so a mask over a column named `size` turns true while the value
  reads the stored cell. That is a leak.
- A **lambda parameter** named after the table captures both the mask's and the value's references in its body. A
  missing element is an error there, not a fallback, which was probed.
- A **dotted output name** `<table>.<column>` captures them on 26.8, though not on 26.3. The 26.8 analyzer caches one
  resolution per full dotted name in a scope, so the single identifier `"t.c"` and the compound `"t"."c"` share a
  result. A `group_by` or `sort` key that names an output `t.c` renders as `"t.c"`. Resolved before a mask in `HAVING`
  or `ORDER BY`, it turns the mask's `"t"."c"` into the caller's value. Probed: `GROUP BY` that alias with
  `HAVING max(<masked deployment>) = 'd2'` answers on an absent cell, and an `ORDER BY` orders the absent row by its
  stored value.

The statement therefore refuses, with `400 bad_request`, a query that references a masked field and carries a name
that equals a table it reads (its source or an enrichment), or begins with that name followed by `.`. One output is
exempt: one whose name is the qualified flat name of the field it projects bare, as every enrichment field projected
without `as` is (`deployment_ref.display_name`). Refusing it would refuse nearly every query over a masked enrichment.
Its own value is correct: probed on 26.8, `if(m, t.c, NULL) AS "t.c"` returns the masked value. A reference to it by
name in the same block is not. On 26.8 the dotted-name cache resolves `"t.c"` there to the stored column, because
the alias body resolves `"t"."c"` first. That holds in every clause, probed on 26.8 (26.3 reads the alias in each):
- `ORDER BY "t.c"` orders absent rows by their stored values. A page of one row then returns an absent row that sorts
  first, which lets a caller binary-search a stored value through page cuts. When the group key is the expression, a
  sort by name fails `NOT_AN_AGGREGATE`.
- A window `PARTITION BY "t.c"` splits absent rows by stored value, and `row_number() OVER (ORDER BY "t.c")` numbers
  them in stored order.
- `tuple("t.c", …)` in the projection returns the stored value itself, and `WHERE "t.c" IS NULL` misses absent rows.
- `HAVING "t.c" IS NULL` over an expression group key fails `NOT_AN_AGGREGATE`.

The builder therefore renders every reference to an exempt output inside its own block, in any clause, as the output's
masked expression. Today only `StructuredQueryBuilder.sortFields` can name an exempt output (`DSL.name(key)`):
`resolveGroupKey` falls back to the name only for an output that is not a bound field, and window keys and `having`
resolve a bound field to its expression first. This change adds three more shapes that must not name it: the
row-mode distinct collapse (`GROUP BY <every output>`) and the aggregate-mode distinct window (`PARTITION BY <every
output>`), each of which fails `NOT_AN_AGGREGATE` on 26.8 by name, and the scalar-subquery `tuple(<value>, <flag>)`,
which by name returns the stored value itself. Probed on 26.8 and 26.3: sorting, grouping (alone and with another key), `HAVING`, window
keys and the distinct collapse over the rendered expression give the masked order and one null group. A block that
reads the output from a derived table reads the masked value under its name, even when it also joins the enrichment
table (probed on both). The table-name rule covers a lambda parameter, and every resolved output name
at every level: an explicit alias, a name `OutputColumnNaming` derives from a field or function, and a name
`CompositeQueryBuilder.operand` generates for a leaf output. All of them are rendered as aliases in the scope where the
mask is evaluated. A query that names the masked field fails today with `unknown field`. One that reaches it only
through a wildcard ran today over the visible columns, and is refused now, which is a declared breaking change. A query
with no masked field is untouched.

*Alternative rejected:* render the source under an internal table alias. Every binding in the builder is qualified by
the table's own name (`CatalogQueryService`), so re-qualifying them all is a far larger change than one name check, for
the same guarantee. The alias names are also
chosen to differ from every name the statement declares, not only its output names: lambda parameters, the output
names of nested blocks, and CTE and derived-table aliases. The operand aliases `CompositeQueryBuilder.nextAlias`
generates (`t<n>`) are chosen apart from the same names. A caller output aliased `t0` over an operand aliased `t0`
would otherwise capture `t0.__adas_mask_0` (probed on 26.3). That loses a mark, not a value, since the
operand's values are already masked. A name the caller writes therefore never meets an internal
one in any scope. An alias defined inside an aggregate's argument is visible in `WHERE` (also probed). ClickHouse resolves an alias
defined inside an expression anywhere in its SELECT scope: in the select list, in `WHERE`, inside aggregates. This was
probed on 26.3 and 26.8. Whether a reference defines or reuses the alias is decided while each SELECT scope is
rendered, not when the condition is built, because one translated filter `Condition` can be rendered in more than one
scope: the key subquery receives the outer filter (`StructuredQueryBuilder.keyPredicates`). Each derived table,
subquery or page block is its own scope and defines the alias again, so
the text grows with scopes × conditions, plus one copy per outermost lambda body only in a scope that references the
condition nowhere outside lambdas. It does not grow with references. Two server limits bound it, and ADAS sets neither:
`max_query_size` (256 KiB) and `max_ast_elements` (50 000). An OR-shaped policy reaches the AST limit first, at about a
dozen inlined copies of a 1 000-term filter (probed). A statement over either limit fails as any oversized statement
does today. The flag column names are chosen around the same
declared names.

**Rows-only entries narrow what they add.** Today a role whose entry carries `rows` but has no read path adds its rows
to a confined caller, and its `columns` are ignored. So the governing roles' columns show, with stored values, on rows
only that entry admits, whatever its own list says. A grant holder who writes such an entry's `columns` has that list
thrown away. The decision now also counts each **row-contributing** role: one whose policy enters the row filter but
which does not govern. Such a role allows a column only when its own list and every governing role allow it, so it
only narrows. A column it leaves out is masked, absent on the rows it adds, and its policy enters the mask condition
of the columns it allows. The set of contributing roles is the one `RowPolicyFilter.confining` computes, shared the way
`readsTable` already is, so the two cannot drift. An entry whose `columns` is `null` narrows nothing, so it behaves as
today.

*Alternative rejected:* render the condition in `SensitiveColumnAccess` through `RowPolicyPredicates`. That closes the
`RowPolicyPredicates` → `CatalogQueryService` → `SensitiveColumnAccess` bean cycle `RowPolicyFilter` already works
around, and it would hand the model a string.

### The binding keeps its physical field and gains a mask

`QueryFieldBinding` gains `mask` (the condition, or null) and `absent` (the type's absent value: `NULL`, `[]`,
`map()` or `'{}'::JSON`). `field()` stays physical, so the paths that read physical names on purpose keep working, and
a new `value()` returns the masked expression when a mask is set:

- **Scalar fields** use `if(mask, if(mask, field, defaultValueOfArgumentType(assumeNotNull(field))), NULL)`, not a bare
  `if(mask, field, NULL)`. ClickHouse builds `if(c, x, NULL)` as the stored column with a null map on top, so the
  stored value stays under the null and functions still compute on it. That holds on 26.3 and 26.8.
  `assumeNotNull(if(c, x, NULL))` returns the stored value. So does a function that fails on some values (a
  `width_bucket` count, a `toDecimal32` overflow): it fails or succeeds by the stored value, and a caller could
  binary-search it through `400`s. The inner `if` replaces the stored value with the type's default first. The probe
  showed `''`, `0` or the first enum value under the null for `String`, `LowCardinality(Nullable(String))`,
  `Nullable(Decimal)`, `UInt32` and `Enum8`, so an absent cell is indistinguishable from a stored null. The result
  loses `LowCardinality`, a cost confined to masked fields.
- **Composite fields** use `if(mask, field, <empty value>)`, which holds no hidden value.
- **An object subcolumn** reached by path is a `Dynamic` subcolumn, which holds a real `NULL` under `if`.
- **A computed field's stand-in** is never wrapped, because the render guard requires it bare. Its mask feeds only
  the row's flag, from which the completer nulls it. Every reference a query makes resolves through
`value()`:
- `resolveFieldOrNull`;
- the wildcard projection, aliased to `physicalField().getName()`, which is the key the DSL wildcard emits today,
  so the output name is unchanged;
- object-path access, as `if(mask, <subcolumn of field>, NULL)`, so the subcolumn is still read by path;
- `toJSONString` over an object;
- array expansion.

Each physical reader is checked case by case:
- Enrichment derived tables project the raw column, and the mask is applied in the outer select, where the source row
  the condition reads is in scope.
- Read shaping keeps masked fields. Copies and pushed calls read stored values in the derived table, and the outer
  statement masks the conjunct and the pushed column, where the source row is in scope. Every copyable conjunct is
  `NULL` or false on an absent cell, so the outer form implies the copy and the rows are unchanged. Every allow-listed
  call accepts any input without error, so pushing it discloses nothing. The outer mask takes the call's own value on
  an absent cell: `NULL` for the calls that return `NULL` over `NULL`, and `''` and `[]` for `json_extract_string_ci`
  and `json_extract_array`, whose input is read as `''`. A blanket scalar mask would be wrong for those two: `NULL`
  where the reference gives `''`, and a `Variant` that array functions reject. Probed on 26.3.
- Composite key reduction (`CompositeQueryBuilder.addReduction`) selects the partner's stored key column and tests the
  reduced operand's own stored key against it (`reducedBinding IN (…)`). A row whose key is absent cannot match in
  `ON` anyway, so testing its stored key drops nothing that could have matched. Its
  conjuncts are translated by the filter translator over the model's bindings, so they take the masked or rewritten
  forms like any filter. The set is then a superset of the present keys, and the join's `ON` still compares the
  operands' masked outputs, so the reduction drops only rows that could not have matched.
- The grain join and its keys read stored values on purpose: the join is part of the entity, not a query reference.
  The restrictions derived from the caller's filter to narrow it must admit every row the filter admits:
  - The key sets (`keyPredicates`, the key subqueries) are translated through the same masked or rewritten conjunct
    forms as the outer filter.
  - `ReadShaping.partitionBounds` and the transferred bounds (`StructuredQueryBuilder.transferredBounds`) re-render
    the caller's conjuncts on the exposed partition field onto the enrichment's partition column. For a masked
    partition field, they transfer only rewritten, null-rejecting conjuncts: every row those admit satisfies the
    comparison on the stored value. A conjunct such as `request_time is null` is not transferred, because it admits
    absent rows whose stored partition is not null.
  - The aligned lookup's partition pre-selection (`PartitionPreselection`) adds `partitionExpr(source.p) IN PS` to the
    statement's `WHERE` and, for a join with no aligned pair, as a key predicate. It reads the **stored** source
    partition on purpose. `PS` names the partitions of the enrichment rows that match `C`, and an aligned enrichment
    row shares its source row's stored partition, so the restriction admits exactly the rows that can match. A masked
    form would drop matching rows whose partition cell is absent. It is built from `EnrichmentJoin.sourcePartition()`,
    not from the binding, so the rename does not surface it, and it is named here and in the rendered-SQL test's
    allowlist instead.

To make a missed path visible, `field()` is renamed `physicalField()`, so every call site is reviewed in this change.

*Alternatives rejected:*
- Bind `field()` to the masked expression. That breaks every physical-name consumer, and the enrichment derived
  table has no source in scope.
- Mask in Java after the statement. Filters, grouping and aggregates would see stored values.

### Marks are one flag per mask condition, computed in the same statement

The statement projects one internal column per **(mask condition, context)** pair the outputs need. Two outputs that
depend on the same condition in different contexts — `total_price` next to `sum(total_price) OVER (PARTITION BY
project_id)`, or two different windows — get separate flags. Each name is chosen when the statement is built so that
it differs from every output name of the query: `__adas_mask_<i>`, with the suffix advanced past any output a caller
named that way. The shipped `__adas_` reservation, which applies only when a computed field is projected, is
unchanged, so the flag names refuse no query that runs today. The column's expression depends on the context:

| Context | Row mode | Aggregate mode |
|---|---|---|
| the output's own row or group | `NOT mask` | `max(NOT mask)` over the group |
| a window output `W` | `max(NOT mask) OVER W` | `max(max(NOT mask)) OVER W`: a window over the group flag, since a window in aggregate mode may only reference grouped or aggregated expressions |
| a ranking window output (`row_number`, `rank`, `dense_rank`) | `max(NOT mask) OVER (<W's partition and order> ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)` | the same over the group flag |

The flag reads the rows the function actually reads. An aggregate in a window reads `W`'s frame, so its flag uses that
frame. A ranking function ignores any frame the caller attaches (probed on 26.3: `row_number` over a one-row frame
still counts the rows sorted ahead of it). Its value depends on every row from the partition's start to the current
row, so its flag uses that frame whatever frame `W` declares.

The query builder records, for each output column, the set of conditions its expression references. Today the
executor runs `materialize`, then the computed-field completion (`ComputedFieldCompleter.complete`), which reads the
flags. A new step after both, applied before `QueryResultPage(outputColumns(select), …)` is built and in `run()`:
- ORs that set per row into `$masked`, listed in projection order;
- removes the internal columns from `columns` and from the row maps, so CSV and Arrow never see them;
- replaces a bare composite masked cell with null.

Where rows collapse, the flags do not take part in the comparison:
- **A distinct projection in row mode** is rendered as `GROUP BY <every output>` in place of `DISTINCT`:
  - Each flag becomes `max(<flag>)`.
  - The keys gain the row flag (`NOT mask`) of each output that is a bare masked composite field.
  - A sort key that is not an output is ordered by `any(<key>)`, the same arbitrary row `DISTINCT` keeps (probed on
    26.3).
  - `sort`, `page` and `having` stay where they are.
  - Memory is bounded by the distinct rows and spills under the budget's group-by threshold.

  A windowed flag, `max(<flag>) OVER (PARTITION BY <every output>)`, was rejected because it sorts every row. On
  100M synthetic rows it exceeded the 2 GiB query budget, where `GROUP BY` used tens of MiB. The SQL front end already
  lowers a SQL `GROUP BY` with no aggregate to a DSL distinct, so it takes this path too.
- **A distinct projection in aggregate mode** keeps `max(max(NOT mask)) OVER (PARTITION BY <every output>)`, which is
  a window over groups, not rows.
- **A distinct projection with a window output** cannot group by that output in the SELECT that computes it. The
  DSL builder therefore plans it as the SQL front end already does (`RelToStructuredQueryTranslator.aggregateCtx`):
  the window in an inner block, and the grouped form above in an outer block over it, where the window output is an
  ordinary column. Both surfaces then accept and mark the same queries, and none is refused.
- **Composite queries** propagate a leaf's flag through outer blocks as a flag-only binding on the leaf output's
  alias: the value is already absent there, and only the mark travels.
- **`UNION ALL`** branches project two flags per output position, so their columns align. The **mark** flag ORs the
  conditions that position depends on in that branch. The **bare-absent** flag is set where that branch's output at
  the position is a bare masked composite field whose cell is absent, the one case `materialize` must turn into null.
  Each is a constant `0` where it does not apply. Derived tables and CTEs carry the same pair per output, so an outer
  block reading a leaf's composite output nulls it from the leaf's bare-absent flag rather than guessing from the
  outer expression.
- **`UNION`** (distinct) is that `UNION ALL` grouped by the outputs, with `max` of each flag, ordered by each group's
  first occurrence:
  - Every branch also projects its ordinal and `rowNumberInAllBlocks()` over the branch as rendered, and the grouped
    block sorts by `min((ordinal, row))`.
  - The stateful row number keeps a branch's own `ORDER BY` in the plan, which ClickHouse otherwise removes under a
    `GROUP BY`.
  - The rows are then those `UNION DISTINCT` returns for the same text (probed on 26.3 and 26.8). So is which of them
    the default limit keeps where the cut falls in an ordered last branch. A cut through an unordered branch is no
    more determined than in ClickHouse's own `UNION DISTINCT`, which returned different rows across runs at 8
    threads.
  - A windowed `DISTINCT` over the union was rejected: it re-sorts by the partition, and returned other rows that
    changed with the thread count.

  No collapsing form keeps `DISTINCT`'s early stop under a page limit, because a row's mark depends on every duplicate
  of it.
- **`INTERSECT` and `EXCEPT`** compare rows, and a mark has no single source there. A branch whose output depends on a
  masked field is refused `400 bad_request`. A query that names the field fails today with `unknown field`, because
  the column is hidden. A wildcard `INTERSECT` ran today over the visible columns, and is the declared breaking case.
- **A scalar subquery in an output expression** also returns its own mark: it selects `tuple(<value>, <its output's
  flag>)`, and the outer expression reads both members, the flag as `ifNull(tupleElement(…, 2), 0)`. A row-mode
  subquery that finds no row returns `NULL` for the whole tuple, a null value with no absent input. Both were
  probed on 26.3 and 26.8. The flag is computed under the subquery's own mode: `NOT mask`
  for a row-mode subquery such as `… ORDER BY request_time DESC LIMIT 1`, and `max(NOT mask)` for an aggregate one. A
  subquery that yields a set or a truth value (`in`, `exists`) marks nothing wherever it appears, even inside a
  projected `case`. Only the outer operands it is compared with mark, by the row rule.
- **Outer joins.** A flag propagated from a composite operand is wrapped `ifNull(flag, 0)` in the outer block. Under
  `join_use_nulls = 1` an unmatched operand's flag is `NULL`, and its cells are null for want of a match, not for want
  of permission. This is the one place a flag is not fail-closed, and it is safe: the unmatched side carries no stored
  value to protect.

*Alternatives rejected:*
- One flag per output column. It cost the same to compute, carried more bytes, and duplicated identical conditions.
- Marks derived in Java from `NULL` values. That's impossible for aggregates, and wrong for an expression that
  computes a non-null value from an absent input.

### The pruning rewrite is a narrow syntactic rule

The rewrite lives in `FilterTranslator`, applied only to the top-level conjuncts of a block's `WHERE` filter, so every
path that translates the caller's `WHERE` filter gets it: the main statement, the count, the key subqueries
(`keyPredicates`) and composite key reduction (`addReduction`). It is never applied to `having`, where the stored
column is not a group key and ClickHouse would refuse it, nor to `case` branch conditions (`toCaseCondition`), a
composite join's `ON`, or a flag-only composite binding, which has no mask to conjoin. One shape is rewritten to `mask AND (<comparison on
physicalField()>)`: a masked **scalar** field, referenced bare, compared by `eq`/`ne`/`lt`/`le`/`gt`/`ge`/`in`/the
string-match operators with any operand that contains no subquery and is not the null literal, or tested
`is not null`. In the DSL, `eq null` is `is null` and `ne null` is `is not null` (`FilterTranslator`); only the latter
is rewritten, because `is null` is true on an absent cell. The other operand keeps reading masked values. Because
`NULL op e` is never true for a comparison, whatever `e` evaluates to, the two forms reject the same rows for any such
operand, so it does not have to be a literal. The stored column is read only inside the comparison, which raises no
value-dependent error. The rows read can still depend on it, which is the accepted cost channel in Risks. That covers the relative-time windows (`date_sub('day', 7, now())`),
which never become literals and are the usual stored-query and dashboard window, and caller values
(`caller.user_hash()`). A `not in` reaches the DSL as
`not` over `in`, and stays unrewritten like any conjunct under `not`. Every other conjunct
reads `value()`. The rewrite can only add `mask AND`, so a misclassified conjunct could drop rows but never expose a
value. It applies wherever the same conjunct list is built: the count behind `totalCount` and each composite leaf. The
SQL surface inherits it through Calcite → DSL.

*Alternative rejected:* reuse `ReadShaping.pushableConjunctField`. It tests "false on the type default", not "false on
`NULL`", excludes the range operators, and returns no type for source fields.

### Composite types use the empty value inside expressions

An `array`, `map` or `object` field masks to its type's empty value, so array, map and JSON functions keep working, and
an output that is the bare field is nulled by `materialize`. Composite fields stay out of the pruning rewrite, because
`arr = []` is true on an absent cell. Where rows are compared, the empty value alone would merge an absent cell with a
stored empty one, so the comparison key gains the flag for an output that is a **bare** masked composite field. An
expression over one, such as `arrayConcat(execution_path, ['x'])`, reads `['x']` on an absent and on a stored-empty
row alike in the reference, so it is compared by value:
- a grouping key over such a field adds `NOT mask` as a further key;
- a distinct projection or a `union` that removes duplicates keeps that output's row flag in the compared row,
  instead of the windowed `max`.

Absent cells then collapse with one another and never with a stored `[]` or `{}`, as the null they become in the
output requires.

*Alternative rejected:* keep hiding composite columns. It was the smaller change, but it is a surprising exception to
the rule that gaining a role removes no column.

### Computed values are nulled from the row's flag

`ComputedFieldCompleter` consults the row's flags. For a row whose flag is anything but `0`, it skips the completion
read (`RequestChainReader`) and returns null. Because the flag was computed in the statement, where the source row is
in scope, the completion read needs no mask condition. Late-materialized enrichment columns need nothing extra: the
outer statement selects them through `value()`, with the source in scope, and the page block reads them through
`value()` wherever its filter or sort references them.

### `$masked` is reserved everywhere

A top-level output named `$masked` is refused with `QueryValidationException`, which maps to `400 bad_request`, or
`INVALID_ARGUMENT` on Flight. The check runs on the statement's **top-level** output names only: those of a
single-entity statement and of a composite plan's outermost block. It does not run in `OutputColumnNaming.resolve`,
which names every block the builder renders, composite leaves and set-operation branches included. A nested column
may carry the name, and a check there would refuse at execution what saving had accepted. The same top-level check
runs in the DSL path and the SQL plan step, so execution, CSV, translation and validation all refuse it. A `sql` saved query is validated by `SqlQueryFrontEnd.plan`, which never reaches
`CompositeQueryBuilder` or every naming path, so the `$masked`, `intersect`/`except` and table-named-name checks run
in that plan step as well as in the DSL builder. A saved query is then refused on save exactly when its execution
by the saver would be refused, as a reference to a column hidden from the saver is today. Refusing it only when masking applies would make a saved query's validity depend on who
runs it. The internal flag names avoid the same problem differently: they are chosen per statement around the query's
output names, so nothing about them is reserved.

### Consumers of the decision

| Consumer | Masked column |
|---|---|
| `TableMapper` (catalog read, revision snapshots) | listed |
| `TableCatalogService` (`column_count`) | counted |
| `TableSchemaService` | named like a visible column |
| `CatalogExportService` | exported |
| `ImportTargetReader` | kept in `prev` |
| `TableCallerPermissions` (`columns_restricted`) | counts as restricted |
| `SensitiveProjectionGuard` (every pipeline path) | refused `403 column_not_entitled` with the hidden ones, before the clearance |
| `QuerySchemaFieldDto` | `masked` member, which `docs/openapi.json` picks up |

`SensitiveProjectionGuard`'s re-attribution of parse failures is unchanged, because a masked column parses; the guard
refuses it explicitly as a column the path reads.

## Risks / Trade-offs

- **A path reads a stored value outside the mask.**
  - The rename to `physicalField()` forces a review of every call site.
  - A rendered-SQL test asserts, for each clause shape, that every occurrence of a masked column's physical name sits
    inside its mask or inside a rewritten conjunct guarded by it.
  - A ClickHouse integration test checks each surface's results against a reference: the entity's flattened rows,
    enrichments joined on stored keys, with each absent cell then replaced by its absent value.
- **The absent pattern discloses whether an allowing role's policy admits a row.** That is one bit per row, including
  rows another role admits and for a policy over a column the caller cannot see.
  - That role's own row reach discloses the same bit on its own rows.
  - The complement on the other roles' rows is the new disclosure. It is accepted as the cost of masking, and
    `docs/row-level-security.md` tells administrators to keep a policy over a sensitive column off a role whose users
    hold other reading roles.
- **Clients that ignore `$masked` read partial aggregates as complete.**
  - Discovery's `masked` flag, the per-row marks and the upgrade notes make the change visible.
  - A caller with one governing role, or none of whose governing roles carries a row policy, and with no rows-only
    entry whose `columns` list narrows it, has no masked column, so it never reads a partial value.
- **Clients that render every row member as a column, such as a generic JSON panel, show a `$masked` column** on rows
  where masking applies. → An explicit column selection in the client drops it, and the upgrade notes say so.
- **Cost.** It is proportional to rows × masked columns read, and measured at a small fraction of query time, more on
  wide exports of masked values. → Columns that share allowing roles share one condition, and the pruning rewrite
  keeps filters as cheap as for an unmasked caller.
- **The pruning rewrite opens a cost channel.** The data store prunes by the stored column, so the granules read, and
  with them latency and the query budget, depend on whether absent cells store a matching value. A probe on 26.3
  read on the order of a hundred times more rows when they did. The returned rows never change.
  - **The signal is per granule, but a single row is not out of reach.** The signal is kept per granule, per
    skip-index granule or per part. A probe on 26.3 recovered two rows exactly by equality or range probes: a row
    that starts a granule, and an absent row whose granule's other rows the caller can read.
  - **Each probe is noisy.** The caller sees latency, memory and the budget refusal, not a row count, so every probe
    needs repetition.
  - **A copied enrichment conjunct, and an aligned lookup's pre-selection over a masked enrichment field, do the
    same.** They narrow the join build, and the source partitions read, by stored values.

  The maintainers accept this over full scans on masked key and partition columns, which the benchmark showed to
  read more than an order of magnitude more rows. No form can prune by a value without the read volume depending on
  it. → It is stated as an accepted disclosure in the spec and in `docs/row-level-security.md`, with how to avoid it:
  list the column on every governing role and every row-contributing role.
- **A sort on a masked field cannot read in key order.**
  - The sort reads the masked value, not the ordering key. So ClickHouse reads every row the filter selects and keeps
    the page, where an unmasked caller could read in key order and stop at the limit.
  - The gap exists only on a raw (non-`FINAL`) read of a table whose ordering key leads with the masked column, or
    whose earlier key columns the filter fixes by equality. A probe on 26.3 and 26.8 read the whole window there,
    against a few granules for an unmasked caller.
  - A `FINAL` read, and `dial_usage_log`, whose key leads with `project_id` and `deployment`, read the whole window
    either way.
  - Sort memory stays bounded by the page, so only the time budget is at stake.

  → Accepted and stated in `docs/row-level-security.md`.

  *Alternative rejected:* sort by the stored column. It places absent rows by their stored values and changes which
  rows a page returns.
- **A row-mode distinct with a page limit no longer stops early.** The mark ORs every collapsed row, so the whole
  filtered range is read. This is the cost of exact marks, and the grouped form keeps it bounded in memory.
  Benchmarked on synthetic data (millions of rows, four threads, 26.3 and 26.8), the collapsing forms against the
  `DISTINCT` they replace: without a sort, the grouped form stays within a small factor of `DISTINCT` at low and high
  cardinality alike (both finish in hundredths of a second); with a sort at high cardinality it is faster, since
  `DISTINCT` then sorts every distinct value; over a JSON output it is within about half again. The aggregate-mode
  window costs up to a few times the plain aggregate-mode `DISTINCT` it extends, over many groups. None changes the
  order of magnitude of the statement.
- **Row order discloses masked ordering-key values.** Without a total order, and inside order-sensitive aggregates,
  ClickHouse returns rows in roughly storage order, which follows the stored ordering-key and partition values. An
  absent key cell between present ones can be read from its neighbours, often exactly. This was probed on 26.3. The
  maintainers accept it rather than impose a sort on every query. A mask-independent tie-breaker would cost a sort
  even on `LIMIT` queries and still leave `groupArray`/`any` order open. Hiding key columns would give up masking on
  exactly the columns most queries filter by. → The spec states it as an accepted disclosure and tells an
  administrator how to avoid it: list the key column on every governing and row-contributing role, or keep it off
  roles whose rows interleave in storage order. `docs/row-level-security.md` repeats that.
- **An enrichment value can disclose its absent key.** The grain join runs on stored keys, so a visible enrichment
  column keyed by a masked source column still shows the value the absent key selects, and the caller can learn the
  mapping from present rows. → `docs/row-level-security.md` tells administrators to allow the key wherever its
  enrichment's columns are allowed, or to restrict those columns like the key.
- **A rewritten comparison can run on masked rows.** ClickHouse can evaluate it ahead of the mask (PREWHERE), so a
  comparison that throws on particular stored values would reopen a `400`/`200` oracle. No catalog type does today:
  every catalog decimal is `Decimal(38,12)`, and string, date and enum comparisons fail only at analysis. → The rewrite
  applies only to operand types whose comparisons cannot fail by value, and a catalog type added later is checked
  against that rule.
- **Collapsed rows mark conservatively.** A distinct or `UNION` row is marked when any row it collapses had the cell
  absent, even if another collapsed row stored it. This is accepted as for aggregates: the mark means "partial".
- **`INTERSECT` and `EXCEPT` over a masked output, and a table-named name next to a masked field, are refused.** A
  query that names the masked field fails today with `unknown field`. One that reaches it only through a wildcard ran
  today, and is the declared breaking case. Both are role-dependent refusals. → The `400` names the cause, and the
  docs say so.
- **An entity any of whose tables stores a column named after a table of the entity hides instead of masking.** That
  covers a source column named after the source or an enrichment, and an enrichment column named after its own
  enrichment: on 26.8, an enrichment `e` with a `Map` column `e` resolves `"e"."keys"` to that map's keys, which was
  probed. The data store resolves a table-qualified name to such a source column's subcolumns. It resolves `"t"."p"`
  through a column `t` of `t`, and an enrichment's `"e"."size"` through a source column `e`, which was probed
  on 26.3 and 26.8. An enrichment column named after the source does not capture. → The spec states the fallback, so no mask is evaluated there. The same resolution
  already affects the row filter on such a table today. That predates this change and is out of its scope.
- **A TTL cannot be over a masked column.** Which rows a TTL removes would reveal stored values of absent cells.
  → A `ttl.column` naming one, and any `ttl` member while the stored TTL is on one, is refused
  `403 column_not_entitled`. It is not the hidden column's `422`, which hides a type and nullability this caller
  already reads in `GET`.
- **An absent array expands to no element**, so a count over an expansion silently covers the present rows only. →
  This is stated in the spec and in `docs/row-level-security.md`.

## Migration Plan

There is no data or catalog migration and no configuration. Deploying the build switches, at once, every caller with
a would-be-masked column (one that is not visible but that some governing role allows) from hiding to masking, except
on a table under the hiding fallback. Rows-only entries with a `columns` list start narrowing the rows they add. The
`$masked` output-name refusal applies to every caller.

On rollback, an older build hides masked columns again: queries, saved queries and dashboards that reference them fail
with `unknown field` for those callers, as they did before. No data is at risk. The upgrade notes tell operators to
check that dashboards over masked columns tolerate `$masked`, and that consumers summing masked columns read `$masked`.
