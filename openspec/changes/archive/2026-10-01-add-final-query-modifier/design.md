## Context

Today `FinalSettings.apply` puts `final = 1` (plus `do_not_merge_across_partitions_select_final = 1` for a
partition-local entity) on every statement ADAS issues. There are two callers. The first is
`StructuredQueryExecutor.withFinal`, used by `execute` (row + count), `executeComposite` and every
`executeScan` overload. The second is `ClickHouseInsertSelectExecutor` (Class-1 materialize, aggregate
refresh). Because the dedup is a statement setting, the rendered SQL never carries `FINAL`. The SQL front end
is Calcite with the Babel parser (`SqlQueryFrontEnd.PARSER_CONFIG`). `FINAL` is a Calcite keyword (used by
`MATCH_RECOGNIZE`), so `FROM t FINAL` is not a table modifier there. `QueryEntityModel` already carries
the source table definition (and so its `write` facet) and `partitionLocal()`.

See proposal.md for the motivation and the specs for the required behaviour.

## Goals / Non-Goals

**Goals:**
- One place decides whether an entity reference is read final. Every surface, translate included, asks
  it.
- A user-facing read expresses per-reference `FINAL` in the rendered SQL, so a composite statement can mix
  final and raw references.
- The internal pipeline paths keep byte-identical settings and SQL.

**Non-Goals:**
- Moving shipped dashboards / doc examples / MCP prompts onto `FINAL` (follow-up).
- `SAMPLE`, `PREWHERE` or any other ClickHouse table-expression clause.
- A per-enrichment final/raw choice. The entity is the unit.
- Letting a user read's `SETTINGS final = …` through. The SQL surface accepts no `SETTINGS` clause and
  this change adds none.

## Decisions

### D1. Render `FINAL` per table reference for user reads, keep the setting for pipeline reads

A final user read renders `FROM <source> FINAL` and `LEFT JOIN <enrichment> FINAL` (inside the keyed
join's derived table when keyed). The count statement does the same. It carries no `final = 1` setting.
Pipeline reads keep `final = 1` and render no `FINAL`.

*Alternatives:*
- (a) Keep the statement setting and decide it per statement (`final = 1` when every entity is final,
  none when none is), rejecting a mixed composite with 400. That is a smaller change, but it cannot
  express `a FINAL JOIN b`, which ClickHouse can. That breaks the fidelity goal.
- (b) Render `FINAL` for pipeline reads too, so there is one mechanism. Rejected for this change: it
  moves the pipeline's dedup from the engine (`final = 1` is a no-op on a plain `MergeTree`) to the
  catalog facet (rendered `FINAL` on a plain `MergeTree` is an error). That is a behaviour change on
  paths this change promises not to touch.

Only `StructuredQueryBuilder` renders `FINAL`, and only the user-facing surfaces use it; the pipeline
reads assemble their own statements (`ScanService`, `GroupFetchService`, `MaterializeService`,
`AggregateCompiler`) and never see the flag. The builder resolves the decision itself (D2) with the flag
injected through `QueryFinalProperties`; `EnrichmentJoins.addReferenced` takes a `finalRead` argument that
the pipeline overloads pass as `false`.

*Measured (task 1.2):* jOOQ 3.21 has no ClickHouse `FINAL` modifier, so `FinalTables.of` renders the
plain-SQL template `DSL.table("{0} FINAL", t)`. Aliasing that template with `.as()` renders
`"t" FINAL "a"`, which ClickHouse refuses (task 1.3), so the modifier is applied only to unaliased tables. That
holds everywhere it is used: the builder renders an entity's source bare, an enrichment's grain join is
qualified by the enrichment's own name, and a composite statement renders every entity leaf as a derived
table built by the same builder (`(SELECT … FROM "t" FINAL …) t0`).

### D2. `FinalDecision`: one function, three inputs

`FinalDecision.readsFinal(model, requested, forceFinal)` → `boolean` or throws `IllegalFinalException`,
over the entity's **final-capability** (D9):

1. If `requested == TRUE` and the entity is not final-capable, throw. This comes first, so the flag never
   hides it.
2. If the entity is not final-capable, return raw.
3. If `forceFinal`, return final.
4. If `requested == null`, return final.
5. Otherwise return `requested`.

Translate calls it with `forceFinal = false`, which is why translation shows what the query says. For
SQL, the "requested" value per reference is `TRUE` when marked and `FALSE` when not. A SQL reference is
never `null`. `IllegalFinalException` maps to 400 `illegal_final` in `DefaultExceptionHandler`. The Flight
error mapping picks it up wherever it already maps `QueryValidationException` to `INVALID_ARGUMENT`.

*Alternative:* resolve absent `final` to `true` and let ClickHouse reject `append`. Rejected: every
existing JSON query on an `append` source would start failing.

### D3. The JSON field is `Boolean finalRead` serialized as `final`

`final` is a Java keyword, so the record component is `finalRead`, annotated with `@JsonProperty("final")`
and `@JsonInclude(NON_NULL)`. It is a boxed `Boolean`, so null (absent) survives the round trip. It does
not use the `@JsonSetter(nulls = AS_EMPTY)` idiom `distinct` uses, because that would collapse absent to
`false`.

### D4. SQL: rewrite `FINAL` into a Calcite table hint before parsing

*Measured (task 1.1):* Babel parses `FROM t FINAL` as the table `t` **aliased `FINAL`**, and refuses
`FROM t AS a FINAL` / `FROM t a FINAL`; it does parse a table hint `FROM t /*+ final */` and
`FROM t /*+ final */ AS a`. So the mark must be removed from the text before Calcite sees it, and the hint
is the carrier that survives to the `TableScan` — where `RelToStructuredQueryTranslator.scanCtx` binds a
reference to its entity — once `SqlToRelConverter` is given a `HintStrategyTable` registering `final` for
table scans. The star expander, CTE inlining and the other SqlNode passes left the hint intact across the
existing SQL suites.

`SqlFinalModifier.rewrite` runs first in `SqlQueryFrontEnd.plan` (and in `SqlIdentifierProbe`, which parses
the same text). A small token-aware lexer (string literals incl. C-style `e'…'`, quoted identifiers,
comments) tracks, per parenthesis depth, whether it is inside a `FROM` list, and rewrites a `FINAL` that
follows a table reference (`name`, `a.b.name`, quoted, with an optional `[AS] alias`) whose head follows
`FROM`, `JOIN` or a comma: `t [AS] a FINAL` → `t /*+ final */ [AS] a`. A `FINAL` followed by an alias
(`t FINAL AS a`, refused by ClickHouse) is left for Calcite to refuse. A `FINAL` after `)` in reference
position is a derived table and is refused there; a hinted reference to a CTE name or an
`INFORMATION_SCHEMA` table is refused after parsing by `SqlFinalReferences`, since CTE inlining would
otherwise drop the hint silently.

*Alternatives:*
- (a) Extend the Babel grammar (a custom parser via FMPP/JavaCC). Rejected: it adds a generated-parser
  build step for one keyword and makes Calcite upgrades harder.
- (b) A side table keyed by reference position or alias. Rejected: parser positions do not reach the Rel
  tree the translator reads, and an alias does not distinguish the same entity referenced twice without
  aliases (`… FROM t FINAL UNION ALL … FROM t`).

### D5. Partition-local setting over final-marked entities only

`FinalSettings` splits into `pipeline(partitionLocal)`, which is today's `apply` renamed, and
`userRead(partitionLocal)`, which carries only `do_not_merge_across_partitions_select_final = 1` when
asked. For a user statement, `partitionLocal` is the AND of `model.partitionLocal()` over the
**final-marked** entities, and `false` when none is marked. The setting only affects tables read under
`FINAL`, so a raw entity cannot make it wrong. The composite path already folds `partitionLocal` across
its entities. It now folds only the marked ones.

### D6. Keyed-join key sub-query stays raw

`SELECT source.grain_key FROM source WHERE <filter>` gets no `FINAL`. The final row of a key is one of its
raw rows, so the raw key set ⊇ the final one, and the outer `WHERE` still decides what is returned. This
saves a second merge of the source per keyed join.

### D7. Configuration

`query.force-final: ${FORCE_FINAL_QUERIES:true}` binds to a new `QueryFinalProperties` record (prefix
`query`, `@DefaultValue("true")`; `QueryBudgetProperties` is `query.budget`, so it does not fit). There is one
row in `docs/configuration.md`. It is read in one place, `StructuredQueryBuilder`, which only the user-facing
entry points use (`StructuredQueryService` for DSL, SQL and Flight, the composite builder, validation and
translation — the latter two unaffected, since they discard what the builder renders); never by pipeline
services.

### D8. Discovery field

`QueryEntityDto` gains `supports_final`: the discovery face of final-capability (D9) — the source is
`upsert_by_key`, or it has an active enrichment the caller may read at `TABLE_READ`. The bar is execution's,
not the listing's: execution resolves the entity at `TABLE_READ` and drops every enrichment the caller cannot
read, so counting one the caller may only list (or, for translation at `TABLE_GET`, only get) would advertise
a `FINAL` that execution then refuses with `illegal_final`. The catalog computes the capability once, at
model build, into `QueryEntityModel.finalCapable`, with the same helper discovery uses; the flag never
reveals an enrichment the caller cannot read.

### D9. Final-capability is an entity property, not the source's

*Found during implementation.* A user-created source defaults to `append` (a plain `MergeTree`) while every
enrichment is a `ReplacingMergeTree`. Deciding "may this be read final" from the source alone — as the first
draft did — reads an `append` source with an enrichment raw whatever the flag says, so a re-patched
enrichment fans the source out: a regression against today's always-on `final = 1`, which deduplicated the
enrichment. The entity is therefore **final-capable** when its source is `upsert_by_key` **or** it has an
enrichment the caller may read (`FinalDecision.finalCapable`, see D8 for the bar), and a final read renders `FINAL` on each table that collapses:
the source only when it is `upsert_by_key` (`QueryEntityModel.collapsing`), every joined enrichment always.
`illegal_final` is left for exactly ClickHouse's own case: `FINAL` on a bare plain `MergeTree`, i.e. an
`append` source with no enrichment.

*Alternative:* always render `FINAL` on enrichment joins (the earlier option C). Rejected in favour of
option B — without `FINAL` nothing is deduplicated — and an explicit raw read of an enriched entity should
see what ClickHouse's raw join returns.

## Risks / Trade-offs

- [The token-level `FINAL` pass misclassifies an edge case, such as an alias literally named `final`
  (unquoted) or a comment between the reference and `FINAL`] → The pass recognises only the reference-head
  positions. Unit tests cover literal, comment, quoted-identifier and alias-named-final cases. Any other
  placement is left for Calcite to reject.
- [Placements] → Measured on ClickHouse 26.3 (task 1.3): `t FINAL`, `t AS a FINAL`, `t a FINAL`, `JOIN t
  FINAL ON`, schema-qualified and lower-case `final` are accepted; `t FINAL AS a`, `t FINAL a` are syntax
  errors; `FINAL` on a subquery or CTE name is `UNSUPPORTED_METHOD`; on a plain `MergeTree`, `ILLEGAL_FINAL`
  (181). The surface follows exactly that set.
- [The hint rewrite lengthens the statement] → A parse error reported after a rewritten reference points a
  few columns further right than the caller's text (`/*+ final */` is longer than `FINAL`). The diagnosis is
  unchanged; accepted.
- [Rendered `FINAL` on a table whose facet says `upsert_by_key` but whose engine is a plain `MergeTree`
  fails in ClickHouse] → This is a catalog/engine mismatch the spec already forbids. It surfaces as the
  ClickHouse 400, not wrong rows.
- [Per-table `FINAL` and statement-level `final = 1` could differ in cost or plan] → Their semantics are
  the same. Task 1.4 checks both statements with `EXPLAIN indexes = 1` over a partition-local entity, so
  the partition-local pruning is confirmed to still hold with rendered `FINAL`.
- [Turning the flag off silently changes numbers for every SQL consumer] → The flag defaults on. The spec
  names exactly which reads change. The follow-up migrates shipped consumers before anyone turns it off.
- [A saved query stored today without `final` changes meaning?] → No. Absent `final` over a collapsing
  source still reads final, and over `append` still reads raw. Saved SQL without `FINAL` changes only when
  the flag goes off, like any other SQL.

## Migration Plan

1. Ship with `FORCE_FINAL_QUERIES=true` (the default). Results are unchanged. The only new failure is
   `illegal_final` for an explicit `FINAL` on an `append` source, which no client can send today.
2. Follow-up change: add `FINAL` to the shipped Grafana dashboard queries, doc and MCP examples that need
   exact rows. Leave aggregate panels that tolerate duplicates raw where measured cheaper.
3. Turn the flag off per environment, a test environment first. Rollback is setting `FORCE_FINAL_QUERIES=true` again,
   with no data or schema involved.
4. A later change removes the flag once every environment runs with it off.

## Open Questions

None left: the jOOQ rendering (D1) and the SQL carrier (D4) were settled by the task-1 probes.
