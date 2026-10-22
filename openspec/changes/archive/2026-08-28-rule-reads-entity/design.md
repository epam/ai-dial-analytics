## Context

`aggregate-reads-entity` moved the **pipeline** path onto the flat entity — a source plus its ACTIVE
enrichments, joined on the grain key, published under qualified flat names
(`"usage_client_identity.client_session_id"`). It left the **rule** paths on
`ScanSourceMetadata#sourceModel`, the same model with `enrichments` replaced by `List.of()`, and left
`RuleGovernanceValidator` resolving every reference against `column_mapping` of one table.

That work also built, generically, almost everything this change needs:

| Component | State |
|---|---|
| `ScanSourceMetadata#entityModel(source)` | built |
| `EnrichmentJoins.addReferenced` / `.referencedEnrichments` | built — joins are **opt-in by reference** |
| `SourceScopeGuard#requireSourceScoped(…, allowEnrichmentColumns)` | built, rule path passes `false` |
| `FilterSqlValidator#analyze(…, allowEnrichmentColumns)` | built, rule path passes `false` |
| `SensitiveProjectionGuard#enforce` / `#reattribute(…, includeEnrichments)` | built, rule path passes `false` |
| `EnrichmentFreshness` — the clamp | built, generic over `(enrichments, windowedTable)` |
| `AggregateSpecValidator#entityColumns` — the flat field view | built, **private to the pipeline package** |
| `RuleReadSourceDependents` — drop safety for a declared read source | built, enrichments not covered |

So the shape of this change is mostly *threading the rule paths through machinery that exists*, plus
three genuinely new pieces: the group key's projection alias, a dependency-cycle guard, and the
clamp on two rule windows.

The concrete blocker is session-level labelling. `session_insights` (source `sessions`, grain
`client_session_id`) and `session-insights` v1 can be registered yet stay idle; the rule that would
fill them must read hop **bodies**, which exist only on `dial_usage_log`, and group them by a session
id that only `usage_client_identity` computes.

## Goals / Non-Goals

**Goals:**

- A rule declaration resolves every column reference against the flat entity of its **read source**,
  in the same namespace and by the same membership decision the pipeline path and the query surface
  use.
- All four reference surfaces, uniformly: grain key / `group_by`, governed SQL fragments,
  `input_bindings`, Class-1 output expressions.
- One correctness mechanism for the enrichment-arrival race across all four, reusing the pipeline
  clamp rather than growing a second one.
- No wire-shape change and no new rule field; every registered rule keeps validating and rendering
  identically.

**Non-Goals:**

- **A rule reading an enrichment *table*.** `source` still names a source table; the enrichment is
  reached through the grain join. `source '…' is not a source table` stays.
- **A change to grain.** `group_by` still resolves to the target enrichment's grain key.
- **Rule-over-enrichment-output at a different grain**, a second input, or general joins.
- **Automatic downstream re-derivation** when an upstream enrichment's history moves (D-7).
- **Reading an enrichment whose materialized position cannot be established** (D-6).

## Decisions

### D-1: No opt-in flag; `sourceModel` is removed

The `ScanSourceMetadata` javadoc argues the rule paths must keep the narrow model because widening
"would put names into scope that a stored rule's governed SQL could resolve against, silently changing
what an existing rule means." Both halves of that turn out not to hold:

- **Names cannot collide.** `CatalogQueryService#addColumn` publishes an enrichment column *only* as
  `owner + "." + name`. A bare name resolves to the source, always. Widening adds only spellings that
  are currently a hard 422 — it cannot reinterpret one that currently succeeds.
- **Joins cost nothing unless referenced.** `EnrichmentJoins.addReferenced` emits a LEFT JOIN only
  for an enrichment whose exposed names the statement actually references. A rule naming none renders
  byte-identical SQL.

So the flag would guard against nothing while adding a field that is `true` on every new rule within
a year — and, with four surfaces, either four flags or one overloaded one. `sourceModel` then has no
callers and is deleted.

*Alternative rejected — `read_entity: true` (or `source_scope: entity`), default `false`, as the
working note proposed.* It buys back-compatibility that monotonic widening already provides, and it
costs a migration, a DTO field, a runner-visible field, and a permanent second code path through
every validator. That the general version *deletes* the narrow model instead of adding a switch beside
it is the signal that the widening is the right shape.

### D-2: The flat field view is extracted, not copied

`AggregateSpecValidator#entityColumns` and its `Columns`/`Field` records are moved to
`service/enrichment/support/` and shared. Its javadoc already states the reason this must be one
implementation: membership comes from `CatalogQueryService#activeEnrichmentsOf` "rather than being
decided again here, so validation cannot admit a column the compiled statement would not join." Two
copies is how a rule and a rollup start disagreeing about what a column means.

The view keeps its two load-bearing properties. It is built from **catalog rows, not the query
model**, because the model carries neither nullability nor sensitivity and is role-filtered — which
would turn a non-entitled caller's reference to a sensitive enrichment column into an unknown-field
422 instead of the 403 it is. And it carries the `enrichmentSourced` / `ownerOf` distinction that
D-3 and D-4 key off.

### D-3: `group_by` names the qualified column; the projection carries the bare grain key

`group_by` accepts a qualified enrichment column whose flat name's last segment is the target's grain
key. Every read then projects it **aliased to the bare grain key**:

```java
// ScanService#buildScan already aliases every projected column
select.addSelect(projected(model, bindings, name).as(alias));   // alias = grainKey
```

This is forced by the runner's contract, not chosen for elegance. Verified in
`analytics-enrichment-runner`:

```java
GroupMarkBuilder:53        Object groupValue = row.get(rule.groupBy());
                           if (groupValue == null) { continue; }      // silent skip
GroupWorkerService:226     row.put(rule.groupBy(), payload.getGroupKey());
```

A qualified `group_by` on the wire makes `row.get` miss on **every** row; `GroupMarkBuilder` then
skips all of them and the rule silently does nothing, with no error anywhere. The alias keeps the
group key a plain value end to end: the scan page, the dirty-mark table, the group-fetch lookup, the
JSONata input row.

`input_bindings[].column` is deliberately **not** aliased. It keeps its qualified name, which
`InputMapper:43` (`row.get(binding.column())`) resolves against the projection alias directly, and the
binding's `var` — not its column — is what the evaluator template sees, so no dotted-path problem
reaches JSONata.

*Alternative rejected — a required explicit `as`, as the pipeline's group key declares
(`{"column": "…", "as": "client_session_id"}`).* On the pipeline side `as` is genuinely needed: a
rollup has many group keys and each binds a freely-named target column. A rule has exactly one, and it
must equal the grain key, so `as` would have exactly one legal value — a field whose only job is to
restate a value ADAS already knows.

*Alternative rejected — match `group_by` on the last segment without aliasing the projection.* This is
the option that silently breaks the runner.

*Corrected by live verification.* The premise above is right about the runner and wrong about the fix. The
runner does read the key column as `row.get(rule.groupBy())` — but `rule.groupBy()` is what the **wire**
carries, and the wire carries the qualified name, so aliasing the projection to the bare grain key does not
make that lookup hit. It makes it miss, which is the exact silent no-op this decision was written to
prevent. Run against the live runner with only the plain name on the page: it scanned, advanced its cursor,
marked **zero** groups, dead-lettered nothing. With the qualified name restored it marked every group and
carried them through assembly, input mapping and prompt rendering.

So the key is projected under **both** names — the qualified one for the consumer that addresses the column
by the name the rule declares, the plain one for the target's physical key and the write-back — and the
"no runner change required" claim holds because of the first name, not the second. The runner's own
`RuleDto` javadoc records the assumption this change breaks ("ADAS validates `groupBy` to equal the target
enrichment's grain — so for a group rule `groupBy == grainKey`"); publishing both names is what keeps that
assumption harmless. *Alternative rejected — change the runner to read `rule.grainKey()`.* It is a smaller
edit and backwards compatible, but it makes ADAS N+1 against runner N a silent no-op on exactly these
rules, which is a deploy-ordering hazard for the one failure mode that raises nothing.

*Corrected during review.* The alias exists only on the group path, but the grain-key resolution was
widened for every rule, so a **row-grain** rule could register with a grain key only an enrichment reached
and then project the bare name — a column the source does not declare — failing at execution with the
message this change went out of its way to avoid. Such a rule is now refused at registration: only a group
rule may key its derived rows on an enrichment column. That is not merely a plumbing gap either, since a
row-grain rule would write many rows onto one target key, which is the same unsoundness `requireIdentityGrain`
already refuses for Class-1. The same pass made the multi-enrichment ambiguity refusal defer to an explicit
`group_by`, which is precisely the disambiguation the refusal asks for.

### D-4: The clamp is the single mechanism, on both rule windows

`EnrichmentFreshness.bound(referencedEnrichments, source)` bounds the scan window
(`ScanService`) and the Class-1 materialize window (`MaterializeService`) to the materialized position
of every enrichment the statement joins. Verified applicable to the session-labelling case:
`usage-client-identity-live` is `type=sql`, `trigger=on_ingest`, `source=dial_usage_log` — the same
cursor axis a rule scan of `dial_usage_log` windows on, so it classifies `CLAMPABLE` with no new code.

One clamp covers all four surfaces because each failure is the same failure — a row read before its
enrichment cell landed — and excluding the row from the window removes every symptom at once: a wrong
group key, a wrong `ready_when.signal` verdict (computed *inside* the scan query), a wrong
`input_bindings` value (read at assembly), a wrong Class-1 derived value.

Clamping only the **scan** window is sufficient for a group rule, which is not obvious: the group's
members are re-read at assembly time, and `GroupFetchService` selects the **whole** group by key
rather than only new members. So a group that gets marked is always assembled from all of its
members; the only unrecoverable loss is a row that was never marked, which is precisely what the
clamp prevents.

Class-1 is the stricter case and the reason the clamp is not optional here. A group rule's mistake is
a *missing* label that a later mark repairs; a Class-1 materialize writes one value per row and
records the position it wrote through, so an unlanded upstream cell becomes a permanently wrong
**stored** value.

*Corrected during review, twice.* First: the clamp set and the **join** set are not the same set. A group
rule's scan does not project its `input_bindings`, but the assembly reads those values for the rows that
page marks, so the enrichment behind a bound column has to bound the scan window — while joining it into
the scan statement would make every tick pay a full hash read of a table the statement never selects from.
Each statement now joins what its own clauses name and clamps on the wider set. Second, and worse: a
materialize whose window the clamp emptied was recording the rule's **drained horizon**, which asserts
"nothing matching below this instant" and is exactly what releases a *downstream* clamp. Under a clamp that
assertion is false — the rows matched and were withheld — so the mechanism was handing one level down the
unbounded read it had just denied. The horizon is now recorded only when the bound sits at or above it,
where it cuts nothing the safety lag had not already cut.

### D-5: ADAS emits the non-empty guard rather than requiring it

For an enrichment-sourced group key, the scan and group-fetch statements carry an ADAS-emitted
`<key> IS NOT NULL AND <key> <> ''`.

Both halves are needed and for different reasons. `NULL` is the unmatched-join case on a nullable
column. `''` is a *derived* value — the live `usage-client-identity` evaluator returns `''` for a hop
with no session id (`… , ''))))`) — and, with `join_use_nulls = 0`, also the unmatched-join rendering
of a non-nullable column. Unguarded, every session-less row in the source collects into a single junk
group under the empty key; `String.valueOf("")` is a perfectly valid group key as far as the runner is
concerned. This is why `sessions_rollup` filters `length("usage_client_identity.client_session_id") > 0`
rather than `IS NOT NULL`.

*Alternative rejected — require the author to declare the guard in `filter_sql` and refuse the rule
without it (422), or flag it on the rule's state.* No sound check exists: proving a SQL predicate
excludes NULL and `''` is not decidable from the parse tree, and any syntactic check ("the filter must
reference the group-key column") is satisfied by `… OR true`. A requirement that can be met without
being met is worse than no requirement, because it reads as a guarantee. The emitted conjunct is
unforgeable and costs the author nothing; the price is that it does not appear in `adas_get_rule`,
which the spec records instead.

### D-6: An enrichment read with no establishable position is refused at registration

`EnrichmentFreshness.Clampability` already enumerates why a position may be unavailable. For a rule
reading an enrichment:

| Classification | Rule-side handling |
|---|---|
| `CLAMPABLE` | admitted, clamps |
| `NO_RULE` | admitted, no clamp — a rule-less enrichment will never gain a row, so its absent values are **final rather than late** |
| `GROUP_TRIGGER` | **refused (422)** |
| `NOT_CLASS1` | **refused (422)** |
| `FOREIGN_AXIS` | admitted, no clamp, reported (see Open Questions) |

`GROUP_TRIGGER` and `NOT_CLASS1` are refused rather than admitted-and-reported — which is *stricter*
than the pipeline path, deliberately. A rollup recomputes every dirty key in full on its next refresh,
so an unbounded read costs it a temporarily wrong number that self-corrects. A rule has no equivalent:
the source cursor advances once, and neither a skipped mark nor a written Class-1 value comes back.
Admitting an unclampable read would ship a rule that looks correct and is silently unprotected.

The cost is a real limitation worth stating plainly: an enrichment written by an LLM rule —
`conversation_insights`, and `session_insights` itself — cannot be read by another rule. Lifting that
needs a reported write-through position from the runner, which is out of scope.

### D-7: A re-derived upstream is reported, never cascaded

An enrichment whose evaluator version moves and whose history is re-derived invalidates every
enrichment derived from it. ADAS reports this and does not act on it: the rule DTO gains the same two
diagnostics `PipelineDto` already carries — `unclamped_reads` (which enrichments this rule reads
without a bound, and why) and `rebuild` (an enrichment whose history re-derived after this rule last
wrote). Derived on read, following `PipelineFreshnessDiagnostics`: the clamp describes what one run
did, these describe the current state of the rules a declaration points at, and storing them would
report nothing for a rule that has never run and a stale answer for one that ran before a rule
changed — in both cases exactly when an operator is asking. A failure to resolve degrades to "no
diagnostics" and is logged, so a rule state read never fails on a diagnostic.

*Alternative rejected — automatic downstream re-derivation.* The downstream of an LLM rule is
metered: cascading over history spends money no operator approved, and doing it safely needs
ordering, a cost ceiling, retry semantics and a kill switch — a scheduler, owned forever, to save an
operator one API call. *Alternative rejected — refusing to re-derive an enrichment that has readers.*
That blocks fixing a bad evaluator, which is the main reason anyone re-derives.

### D-8: A dependency cycle is refused at registration

Rule A's target enrichment reading B while B's reads A makes each window clamp on the other: both
freeze, permanently, with no error and no operator action that releases them. The edges are
`(target_enrichment → referencedEnrichments)` over the registered rules — a handful of nodes — so
registration rejects a rule that would close a cycle.

*Alternative rejected — runtime detection.* More code, and the symptom is a metric that flatlined
rather than a message naming the two rules.

*Corrected during review.* The edges were read from the rule row's own fragments, and a `type=sql` rule's
column references are not there — they live in its pinned evaluator's output expressions. So the very shape
D-6 and this decision were written for, a Class-1 rule deriving from a sibling enrichment, contributed no
edge at all: no cycle detection, no drop or rename protection on the enrichment it reads, and an empty
`unclamped_reads`. `RuleDeclaration` now carries the evaluator ref and the scan covers those expressions.

### D-9: Enrichment-sourced ⇒ nullable, and sensitivity crosses the join

Both port unchanged from the pipeline path, for the same reasons (`aggregate-reads-entity` D-3, D-4),
so this change adopts rather than re-decides them:

- An enrichment column in a **value-producing** position — a group key, or a Class-1 output
  expression — must be declared `nullable` (422 otherwise). With `join_use_nulls = 0` a non-nullable
  column renders as its type default on an unmatched join, indistinguishable from a derived value.
  `array`/`map` are exempt: ClickHouse has no `Nullable(Array)` and their default is *empty*, which
  contributes nothing.
- A `sensitive` enrichment column is entitled exactly as a sensitive source column is, with
  `SensitiveProjectionGuard` producing the refusal, and a non-entitled caller's parse failure over a
  hidden enrichment column re-attributed to `403 sensitive_column_not_entitled` rather than reported
  as a malformed rule.

*Corrected during implementation.* This decision originally also imported the aggregate path's rule that
a sensitive input read into a non-sensitive target column is refused. That is wrong for this capability:
`enrichment-registry` already carries a deliberate, reasoned requirement — "Runner identity and
derived-column sensitivity posture" — that derived enrichment columns SHALL NOT inherit sensitivity, with
the consequence stated and accepted and the control left to the enrichment author's schema declaration.
Applying the aggregate rule to an enrichment-sourced field would also have made the same value, bound to
the same target, legal or illegal depending only on which table it was joined from. The entitlement half
(who may *reference* the column) is kept; the inheritance half is dropped. Caught by
`Class1MaterializeIntegrationTest.derivingFromSensitiveColumnNeitherFailsNorMarksTheTargetSensitive`,
which is the existing pin on that posture.

## Risks / Trade-offs

**The Class-1 materialize now joins a whole second table.** → A ClickHouse hash join reads its
right-hand side in full and the window bound does not prune it, exactly as documented for pipelines.
A Class-1 window is small and frequent (`usage-client-identity-live`: 60s cadence, 20k rows), so a
full enrichment read per materialize is a materially worse ratio than for a rollup. Mitigation: the
join is emitted only for a rule that references an enrichment, so no existing rule is affected; the
cost is disclosed in `docs/configuration.md` alongside the pipeline note, sized against the
enrichment's whole table rather than the window.

**`SensitiveProjectionGuard#enforce(…, includeEnrichments = true)` on the scan path could relax the
posture it currently holds.** → Audit it as its own task rather than flipping the flag with the
others. The scan path's guard exists to close `buildScan`'s raw-name fallback, and the pipeline's use
of the flag is on a different call shape.

**`ScanSourceMetadata#projected`'s raw-name fallback renders a qualified name as garbage** —
`"dial_usage_log"."usage_client_identity.client_session_id"`. → Only reachable when a name is absent
from the role-filtered bindings, i.e. behind a sensitive-column 403 the guard raises first. Make it
refuse a qualified name explicitly rather than render an unresolvable reference.

**A group rule's clamp interacts with `ready_when.idle`.** → The clamp holds rows back, so a group's
idle timer starts later than it does today. For `session-insights-live` (`idle: 30m`) the shift is
bounded by `usage-client-identity-live`'s cadence (60s) and is immaterial, but a slow Class-1 upstream
would delay labelling proportionally. Visible via `unclamped_reads` / the scan's `lag_seconds`.

**Refusing `GROUP_TRIGGER` / `NOT_CLASS1` reads (D-6) closes a door people will ask for** — an
enrichment derived from `conversation_insights` or `session_insights`. → Accepted; the honest answer
is that no sound position exists for those today. The refusal names the classification so the message
explains itself.

**Extracting `entityColumns` touches the pipeline validator.** → Pure move, no behavior change;
`AggregateSpecValidatorTest` is the regression net and must pass unchanged.

## Migration Plan

No catalog migration, no config key, no rule field — nothing to migrate. Deployment order matters in
one place:

1. **Provision the writer grant before enabling any rule that references an enrichment.** The Class-1
   `INSERT … SELECT` now joins the enrichments its expressions reference, so the writer credential
   needs `SELECT` on those **enrichment** tables, not only on the read source. This mirrors the grant
   an aggregate pipeline already requires. A missing grant surfaces as a materialize failure on that
   rule only.
2. Deploy ADAS. No runner deployment is required — verified against `InputMapper`,
   `GroupMarkBuilder` and `GroupWorkerService`.
3. Register `session-insights-live` and watch its first scans' `lag_seconds` and
   `unclamped_reads`.

**Rollback** is a redeploy: the widening is additive, so a rule registered against the entity becomes
invalid on the previous version — it fails its next scan with the 422 it would have been refused at
registration, and no data written before rollback is wrong. Disable such rules before rolling back.

## Open Questions

- **Q-1 — `FOREIGN_AXIS`: admit-and-report, or refuse?** A rule reading an enrichment whose own rule
  scans a *different* table gets a position on an untranslatable cursor axis. The pipeline path admits
  it and reports it, and D-6 currently follows that precedent. But D-6's own argument — that a rule's
  loss is unrecoverable where a rollup's self-corrects — points at refusing it too. Not reachable for
  session-level labelling (`usage_client_identity` is on the same axis), so it can be decided during implementation.
- **Q-2 — Should the emitted non-empty guard (D-5) apply to *every* group rule, not only an
  enrichment-sourced key?** A NULL or empty group key is never addressable for any rule.
  `conversation-insights-live` already declares `length(chat_id) > 0`, so the conjunct would be a
  redundant no-op there — but it would change the emitted SQL of an existing rule, which this change
  otherwise promises not to do. Currently scoped to the enrichment-sourced case.
- **Q-3 — Does `member_select.order_by` over an enrichment column need the join in the second
  assembly phase?** Phase two addresses selected members by ordering key and projects only bound
  columns; an enrichment-sourced *ordering* column is referenced in phase one only. Confirm no
  statement references a name it does not join.
