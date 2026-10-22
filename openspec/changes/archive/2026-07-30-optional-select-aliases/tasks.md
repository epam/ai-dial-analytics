## 1. Shared naming resolver

- [x] 1.1 Add `service/query/model/OutputColumnNaming` — a pure static helper resolving a
      `List<OutputColumn>` to its final output names: base name per D2 (field name verbatim,
      lowercase function name, `?column?` for a literal, `array` for an array expression), explicit
      aliases reserved first in list order with `_<n>` on repeats, derived names then taking the first
      free `base`/`base_1`/`base_2` candidate.
- [x] 1.2 Expose a companion helper `withMinimalAliases(exprs, names)` that builds the select list
      carrying `as` only where it is load-bearing — an alias is dropped only when re-resolving the whole
      candidate list still yields the given names — so the SQL front end's redundancy decision is made by
      the same resolver execution will run and can never disagree with it.
- [x] 1.3 Update the `OutputColumn` javadoc: `as` is optional for every expression kind; the derived
      name is PostgreSQL-shaped and resolved by `OutputColumnNaming`.
- [x] 1.4 Unit-test the resolver: each base-name rule, dotted enrichment names kept whole, explicit
      alias winning over a colliding derived name, repeated explicit aliases, multi-collision
      suffixing, empty/blank `as` treated as absent, order stability.

## 2. Execute path

- [x] 2.1 `StructuredQueryBuilder.buildRow`: resolve names via `OutputColumnNaming` and alias every
      projected field with its resolved name; register the resolved names as select outputs so
      `sort` can reference them.
- [x] 2.2 `StructuredQueryBuilder.buildAggregate`: delete `requireAlias`; alias every entry with its
      resolved name; register resolved names for `group_by`/`sort`; bind them for `having` — explicit
      aliases overwrite, derived names via `putIfAbsent` so a physical column keeps precedence (D5).
- [x] 2.3 `resolveGroupKey`: try the field binding **before** the select-output set, so an ambiguous
      `group_by` name resolves to the entity column as PostgreSQL does, still falling back to the
      select output for computed keys (D5). `sort` keeps preferring the select output.
- [x] 2.3b `resolveGroupKey`: reject (400) a key that is both a bound entity column and the **explicit**
      alias of a select entry projecting a different expression — the one shape where the flipped
      precedence would silently change an existing document's grouping (D5). An alias repeating the name
      of the field it projects is not a collision. Mirrored on the SQL surface by `groupByNames` (D5b).
- [x] 2.4 Verify the omitted/empty `select` path is untouched (source-owned columns under their own
      names) and that `buildCount` needs no change.
- [x] 2.5 Tests: aggregate query with no `as` executes and keys rows by the derived name; sort and
      having on a derived name; two unaliased `count` entries yield `count`/`count_1`; a derived name
      colliding with an entity column does not hijack `having`; existing aliased behavior unchanged.

## 3. DSL → SQL renderer

- [x] 3.1 `StructuredQueryToSqlRenderer`: render `AS "<resolved name>"` for every output column whose
      resolved name is not what the bare expression already produces (i.e. everything except a plain
      field reference whose resolved name is its field name).
- [x] 3.2 Build the renderer's projected-output map (used to resolve `group_by`/`sort` keys naming an
      output) from the resolved names instead of `as` alone.
- [x] 3.3 Tests: aggregate DSL with no `as` renders `count(*) AS "count"`; rendered SQL round-trips
      through the SQL front end to the same output names; existing rendering tests updated for the
      now-explicit aliases.

## 4. SQL front end — derived names before validation

- [x] 4.1 Add `sql/SqlSelectAliasInjector`: rewrite a parsed select list in place, wrapping every
      unnamed item (not a bare identifier, not `AS`, not `*`) in `AS <derived name>`. Base names from
      the parse tree — function-syntax call → lowercase operator name, `WITHIN GROUP` → the wrapped
      aggregate's name, anything else → `?column?` — uniquified through the shared resolver (task 1.1)
      against the names already taken by identifiers and explicit aliases.
- [x] 4.2 Call the injector in `SqlQueryFrontEnd.translate` between `planner.parse` and
      `planner.validate`, handling both `SqlSelect` and `SqlOrderBy` parse shapes.
- [x] 4.3 `RelToStructuredQueryTranslator`: keep a safety net over the validated names — substitute the
      D2 base name for any name still matching `EXPR$<digits>` (reachable via star expansion), then
      uniquify the list through the shared resolver.
- [x] 4.4 Build the select list through `withMinimalAliases` (task 1.2), so unnamed SQL expressions
      translate to alias-free DSL entries while the produced list still resolves to exactly the reported
      names (including an explicit `<base>_<n>` alias that some other entry would derive by default).
- [x] 4.5 Confirm `ResultColumn` names, `group_by`/`sort` output references, and the Flight SQL Arrow
      schema all take the final names (one list, one source).
- [x] 4.5b `groupByNames`: also fail closed on a plain-column group key whose name is carried by an
      explicitly aliased select item projecting a different expression (the mirror of the computed-key
      guard), so `/translate-sql` never emits a document the execute path rejects as ambiguous (D5b).
- [x] 4.6 Tests: `SELECT count(*)` → column `count`, DSL entry without `as`; `SELECT count(*) AS n`
      unchanged; `SELECT count(*), count(deployment) AS "count"` → `count_1`/`count`; projected literal
      → `?column?`; `char_length(x)` → `char_length`; `percentile_cont … WITHIN GROUP` →
      `percentile_cont`; `ORDER BY count` accepted without an alias; `GROUP BY <name>` matching both a
      column and an output resolves to the column; `SELECT *` and `SELECT DISTINCT` unaffected;
      existing `EXPR$0` expectations updated.
- [x] 4.7 Parity test across both naming paths: for every function in the closed catalog, the SQL
      statement `SELECT f(args) FROM <entity>` and the equivalent DSL `FnExpr` produce the same output
      name (with `char_length` pinned as the documented exception).
- [x] 4.8 Invariant test: `OutputColumnNaming.resolve(translation.query().select())` equals the reported
      `ResultColumn` names for a matrix of alias/collision shapes, including
      `SELECT count(*) AS foo, count(deployment) AS count_1` where a hypothetical alias-free baseline
      would wrongly call `count_1` redundant.

## 5. Specs, docs, and verification

- [x] 5.1 Re-read the four delta specs against the implemented behavior and correct any drift
      (`structured-query`, `sql-query-translation`, `rest-sql-endpoint`, `query-translation-endpoint`).
- [x] 5.2 Update the REST SQL / structured-query response javadoc contracts
      (`StructuredQueryResultDto`, `QueryResultPage`) to say keys are resolved output names, not
      "aliases".
- [x] 5.3 Integration coverage: an aggregate query with no aliases over the REST DSL endpoint and the
      same query as SQL over `execute-sql`, asserting identical row keys.
- [x] 5.4 `./gradlew clean build` passes (compile + checkstyle + tests).
