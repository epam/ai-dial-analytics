## Context

The Query Builder assembles four separable things: a **source entity**, a **query body** (either a
structured DSL tree or a SQL string), a **time intent** (a relative period like "last 2 days", or an
absolute range, or "whatever the toolbar has"), and **presentation** (table vs chart, and the chart's
axes). Today none of it survives the page.

A saved query in this change is a **complete, runnable query, not a template**. Parameterization is
out of scope (D10), which removes a whole class of half-states: every stored body is one the execute
endpoints would accept as-is.

Three properties of the existing system constrain the design more than anything in the feature
request itself:

1. **The sensitive-column filter is a resolution-time chokepoint, not a check.**
   `CatalogQueryService.buildModel` omits `sensitive` columns from `bindings` and `baseSchema`
   entirely for a non-`FULL_ADMIN` caller. A sensitive column is therefore not *forbidden* to such a
   caller, it is *absent* — every reference to it fails as an ordinary unknown field. Everything that
   resolves through that model (discovery, `/execute`, `/execute-sql`, SQL translation via
   `CalciteQueryCatalog`, Flight) inherits the behaviour. The `authentication` spec makes the
   indistinguishability explicit: no error path may confirm a sensitive column exists.

2. **`param` expressions cannot be executed.** `ExprTranslator.toField` and
   `StructuredQueryToSqlRenderer` both reject `ParamExpr` outright ("no server-side param registry").
   A stored query containing a `param` expression can never be handed to `/v1/queries/execute` as-is.

3. **Each execute endpoint shares a front end with its translate-only twin.**
   `/v1/queries/execute-sql` and `/v1/queries/translate-sql` both go through `SqlQueryFrontEnd`;
   `/v1/queries/execute` and `/v1/queries/translate` both go through `CatalogQueryService` +
   `StructuredQueryBuilder`, the latter via `QueryTranslationService.toSql`, which runs the full
   validation pass and discards the result without contacting ClickHouse. So for either body kind
   there is an existing, already-specified way to ask *"would this run?"* without running it.

Fact 1 means the sensitive-literal guard only ever fires for a `FULL_ADMIN` caller; everyone else is
stopped one step earlier, by the ordinary unknown-field error. Fact 2 is why parameterization is out
of scope (D10) rather than merely unimplemented. Fact 3 gives save-time validation a single rule for
both kinds (D8) and removes any need for an escape hatch for untranslatable bodies.

## Goals / Non-Goals

**Goals:**

- Persist an authored Query Builder state — intent only — and read it back unchanged.
- Two sharing scopes with an authorization model that leaks nothing: `personal` (owner-only) and
  `common` (readable by any caller with an application role, writable only by `FULL_ADMIN`).
- Guarantee, at persistence time and server-side, that no literal bound to a `sensitive` column
  enters the metadata database.
- Guarantee that a stored body is one the execute endpoints would accept, so saving never produces a
  query that cannot run.
- Keep the read path free of redaction: because nothing sensitive is stored, the stored body is
  returned as-is.
- Leave room for role-scoped sharing without a re-modelling.

**Non-Goals:**

- Executing a saved query. No `POST /v1/saved-queries/{id}/execute`.
- **Parameterized saved queries** in any form — no `params` column, no placeholder binding, no
  template semantics (D10).
- Run history, run counts, `last_run_at`, pin-to-dashboard, scheduling.
- Name uniqueness, folders, or any organisational structure beyond the single optional `tag`.
- Frontend work.

## Decisions

### D1 — One table, JSONB bodies

`saved_query` in PostgreSQL, with `query`, `time`, and `chart` as `JSONB` and `sql` as
`TEXT`. This follows the established catalog precedent (`pipeline.spec`, `rule.input_bindings`,
`evaluator_version.params`), which stores a loosely-structured declaration as JSONB and validates it
in the service layer. Mapped with `@JdbcTypeCode(SqlTypes.JSON)`, as those entities do.

*Alternative rejected*: normalising the query tree into relational columns. The DSL is an arbitrarily
nested expression grammar; normalising it would duplicate `service/query/model` in DDL and force a
migration on every DSL addition.

### D1a — JSON payloads are stored as text, converted by the application's own mapper

The `query`, `time`, and `chart` columns are `jsonb`, but the entity holds them as `String` and
`SavedQueryJson` converts them with the application's `JsonMapper`. They are **not** mapped as typed
fields for Hibernate to serialize.

This is forced, not stylistic. Hibernate's JSON format mapper is Jackson 2
(`com.fasterxml.jackson.databind`); this codebase's databind is Jackson 3 (`tools.jackson`). The DSL
depends on Jackson 3 wiring that Hibernate's mapper cannot see — `FilterNode` is routed by
`FilterNodeDeserializer` because its `op` key is both discriminator and operator data, and that routing
is declared with a Jackson 3 `@JsonDeserialize`. Mapping `StructuredQuery` directly writes fine and then
fails on **read**, with `Cannot construct instance of FilterNode`.

*Alternative rejected*: a parallel Jackson 2 deserializer for `FilterNode`. That would mean two
implementations of one parse — on a path that decides what a stored query means — free to drift.

Converting through the application mapper instead has a property worth keeping regardless: a stored body
is parsed by exactly the mapper the query endpoints use, so what is validated, what is stored, and what a
client can post back to `/v1/queries/execute` are the same thing by construction.

### D2 — Server-generated opaque ids: `sq_` + a random UUID

`id = "sq_" + UUID.randomUUID()` with dashes stripped. Random, not sequential, and this is
**load-bearing**: the `404`-never-`403` rule (D5) only conceals another caller's saved queries if
their ids are not enumerable. A sequential or timestamp-ordered id would let a caller walk the
keyspace and learn how many saved queries exist and when they were created.

*Alternative rejected*: ULIDs (as sketched in the feature request). They would add a dependency for a
cosmetic property (lexicographic sortability) that this design does not use — listing sorts on
`updated_at`, not on the id — and their embedded timestamp is exactly the leak the previous paragraph
argues against.

### D3 — Exactly one of `query` / `sql`, enforced twice

A `CHECK ((query IS NOT NULL) <> (sql IS NOT NULL))` on the table, and a service-layer check that
produces the `ErrorView`. Both layers deliberately: the service check gives the caller a `422` with a
usable message, and the CHECK constraint makes a future code path that forgets it fail loudly instead
of writing a row that no reader can interpret.

The two layers must agree on what "present" means, or the second one turns a `422` into a `500`. The
service reads a blank `sql` as *absent*, so `{"query":…, "sql":""}` is one body — and it must therefore
be **stored** as one, with `sql_text` normalized to `NULL` rather than the raw `""` that would make the
CHECK see two. `SavedQueryBodyValidator.sqlBody` is the single definition both the rule and the write
call.

The stored `editor` mode (`builder` / `json` / `sql`) is **not** persisted — it is derivable (`sql`
set means the SQL editor; otherwise whether the body is builder-representable decides Builder vs
JSON) and a stored copy can disagree with the body it claims to describe.

### D4 — The sensitive-literal guard is one walker over `StructuredQuery`

Both kinds reduce to the same input. For a `query`-kind body, the tree is the request. For a
`sql`-kind body, `SqlQueryFrontEnd.translate(sql).query()` produces the tree — the identical
translation `/v1/queries/translate-sql` returns. **No regex over SQL on a security boundary.**

The walker visits every `ComparisonNode` in `filter` and `having`. For each node it partitions the
arguments into *column references* (a `FieldExpr`, or an `FnExpr` transitively containing one) and
*literals* (a `ValueExpr`, or an `ArrayExpr` of them). If a node contains at least one of each, and
any referenced field is marked `sensitive` in the caller's `QueryEntityModel.baseSchema()`, the
request is rejected `422 sensitive_literal_not_allowed`, naming the column. Comparing a `sensitive`
column to *another column* stays legal — it copies nothing out of the data store.

**`having` is walked against the select aliases, not against bare names.** `StructuredQueryBuilder`
translates `having` with bindings augmented by every select output, so `max(user_email) AS m` plus
`having m = 'x'` binds a literal to a sensitive column while the comparison mentions only `m`. The
walker therefore builds *output name → expression* from `select` with the same `OutputColumnNaming`
the translator uses, and substitutes an alias back into what it projects — in both directions, since a
select entry can just as well be the literal. A name on the substitution path is not re-expanded, so a
self-referential entry (`{"expr":{"field":"m"},"as":"m"}`) terminates. A name that is both an output
and an entity column counts as both: over-approximating costs a rejected column-to-column comparison
in a corner case; under-approximating persists personal data. The `sql` kind needs none of this —
`RelToStructuredQueryTranslator` materializes `HAVING max(user_email) = 'x'` as the `FnExpr` itself —
but it costs nothing there, and one walker for both kinds is the point of this design.

The guard runs **before** translation (D8), so a sensitive literal always yields
`sensitive_literal_not_allowed` rather than whichever error the translator happens to reach first.

Sensitivity is read from `baseSchema()` — the caller's own, already-filtered view — rather than from
a raw catalog read. That is what preserves indistinguishability: a non-`FULL_ADMIN` caller's schema
does not contain the column at all, so the reference fails earlier as an ordinary
`400` unknown-field error and the `422` is never reached. The guard needs no role check of its own;
it inherits the one the model already made.

*Why the guard exists at all*: saved queries live in the metadata database. Personal data lives in the
data store, behind its own controls and retention. A literal like `user_email = 'someone@example.com'`
persisted here copies a person out of that perimeter — into backups, replicas, and every downstream
copy of the catalog. Read-side access control does not undo a copy. Enforcement must be server-side; a
client-side convention can be bypassed with `curl`.

*The rejection has no remedy in this change.* With parameterization out of scope (D10), a
`FULL_ADMIN` who wants "chats belonging to this one person" cannot save that query at all — they run
it ad hoc instead. That is the honest consequence of dropping parameters, and the guard is not
weakened to compensate: refusing to store the query is the whole point, and a remedy that stored the
value under another name would defeat it. Parameters are what turns the rejection into a workflow,
and they are deferred as a unit.

### D5 — Scope and authorization

|                          | `personal`                            | `common`                            |
| ------------------------ | ------------------------------------- | ----------------------------------- |
| Read                     | owner only (`owner_id = getAuthor()`) | any caller with an application role |
| Create / replace / delete | any application role, own rows only  | `FULL_ADMIN` only                   |
| Another caller's id      | `404 not_found` — never `403`         | opens normally                      |

`owner_id` is the author in both scopes; in `common` it is what the UI shows as "Saved by".

**`404`, never `403`, for another caller's `personal` row.** A `403` would confirm the row exists,
disclosing that some other person has a saved query under that id. The same reasoning as the
sensitive-column indistinguishability rule, applied to rows instead of columns. Implemented as a
*predicate*, not a post-fetch check: the repository query carries the visibility condition, so an
invisible row and a non-existent row take the identical code path and cannot be told apart by timing
or by a future refactor.

Because the `common` gate depends on the request body's `scope` (not on the URL), it cannot be a
`@FullAdminOnly` annotation on the method — the annotation would also block a legitimate `personal`
write. It is a service-layer check that throws `AccessDeniedException`, which
`DefaultExceptionHandler` already maps to `403 forbidden`. Changing a row's scope in either direction
requires satisfying the gate for **both** the stored and the requested scope, so a non-admin cannot
promote their own row into `common`, and cannot demote a `common` row to hide it.

### D6 — `owner_id` is a credential identity, not necessarily a person

`SecurityClaimsExtractor.getAuthor()` returns `Authentication#getName()`. For a Bearer JWT that is the
configured principal claim (default `oid`). For a DIAL API key,
`ApiKeyAuthenticationToken` sets the principal to **the user id for a JWT-rooted per-request key, and
the project name for a plain project key**.

So a project key's saved queries are owned by the project. This is a documented consequence, not a
defect: `personal` scope isolates per *credential identity*, and everyone sharing a project key shares
its personal saved queries. `owner_id` is stored opaque and never parsed. The alternative —
refusing to save under a non-user-rooted credential — would break the perfectly reasonable case of a
service that curates `common` definitions.

### D6a — Two fields for authorship: `owner_id` decides, `owner_email` displays

`owner_id` holds the principal claim; `owner_email` holds the email claim. Both are recorded at
creation and preserved together across a replace.

The split is the whole point. The principal is **stable**: opaque, assigned by the directory, never
reassigned. An email is a *label* on a person — it changes on a name change or a domain migration and
can later be handed to somebody else. So the principal is the only thing authorization may read, and
the email exists solely so a UI can render "Saved by" without resolving an `oid` against a directory.
Recording only the principal would make the object illegible to humans; deciding access on the email
would make access depend on a mutable, recyclable value.

This mirrors `ai-dial-admin-backend` (`ConfigRevisionEntity.author` + `.email`) and the deployment
manager (`AuditActivityEntity.initiated_author` + `.initiated_email`), which record exactly this pair
from exactly these two claims for audit authorship. Same claims, same split; ours is an ownership key
rather than an audit row, which is why the *authorization* half is load-bearing here in a way it is not
there.

Consequences taken deliberately:

- **Never compare `owner_email`.** The column exists next to an identity it superficially resembles,
  which makes `WHERE owner_email = :callerEmail` an easy and silent mistake. The spec states the
  prohibition, the column comment states it, and the equality check lives in one place
  (`SavedQueryRepository.findVisibleInScope`) that reads only `owner_id`.
- **Taken from the credential, never the body** — otherwise a caller could author a saved query
  displaying somebody else as its author. Rejected like any other server-assigned field.
- **A snapshot, not a live lookup.** It is preserved on replace exactly as `owner_id` is, so the pair
  always describes one person and an admin editing a `common` saved query cannot relabel its author.
  The cost is staleness after an address change. Refreshing it when the owner themselves writes would
  reduce that, and is additive later; it is left out now because "the author's identity as recorded at
  creation" is one rule, and a conditional refresh is a rule someone will get wrong.
- **Often absent.** `security.require-email` defaults to false and a project key has no person behind
  it, so `owner_email` is null more often than not. It is a display hint; being null-omitted from the
  response, a client simply receives no key and renders nothing.

**A null *principal*, unlike a null email, is fatal for a `personal` row.** Visibility is decided by
`owner_id = :author`, which no caller can satisfy against null — so a `personal` row with no owner is
unreadable, unreplaceable and undeletable by everyone including its creator: silent, unreachable
garbage. While security is enabled the service therefore refuses to write one, on create and on any
replace that would leave one so (notably demoting a null-owned `common` row), with `500`
`principal_unavailable`. It is a `5xx` because the token *authenticated* — the service already accepted
it — so refusing to act on it afterwards is our own inconsistency, arising from a principal claim that
yields nothing, which the caller cannot fix.

`common` rows are deliberately exempt: they stay readable whatever their owner, so an unresolved
principal costs only attribution, not reachability — and refusing them would break a deployment that
curates shared definitions under principal-less tokens. The guard tracks the actual harm, not the
misconfiguration.

*Scope note*: this narrows what the capability claims about personal data. The rule is that nothing
**read out of the queried store** is copied into the metadata database — not that the row is free of
personal data, which it no longer is. The `sensitive`-literal guard (D4) defends the former, and the
spec's Purpose now says so in those terms rather than the broader phrasing it originally used.

### D7 — `none` security mode fails open

`getAuthor()` returns `null`; `owner_id` is stored `NULL`; the read predicate applies no owner filter;
every write is allowed including `common` (method security is disabled in `none` mode anyway). This
mirrors `SensitiveColumnAccess.maySeeSensitive()`, which fails open for the same reason. No synthetic
owner is invented — a placeholder string would become a real, colliding identity the moment the
deployment switched to `oidc`.

*Consequence, deliberate*: a deployment that flips `none` → `oidc` leaves its `NULL`-owner `personal`
rows visible to nobody (they match no `owner_id`). That is the safe direction to fail — a
row authored in `none` mode becoming invisible, rather than becoming everyone's.

**The trigger is the mode, never a null author.** `getAuthor()` also returns `null` in `oidc` mode when
the token carries no value for the configured principal claim — `JwtAuthenticationConverter` builds the
authentication from `jwt.getClaimAsString(principalClaim)` and does not reject a token missing it. So
"author is null" and "security is disabled" are different facts, and every branch tests
`isSecurityEnabled()` rather than inferring the mode from the author. Inferring it turns a
misconfigured principal claim into a disclosure of everyone's `personal` list. A caller in that state
sees an empty `personal` list, which is what the single-row predicate (`s.ownerId = :author`, never
true for `null`) already answers for the same rows.

### D8 — A saved query must translate; untranslatable bodies are rejected at every scope

One rule for both kinds: **a body is storable only if the corresponding translate-only endpoint would
accept it.**

| kind | save-time validator | equivalent endpoint |
| --- | --- | --- |
| `query` | `QueryTranslationService.toSql(query)` | `POST /v1/queries/translate` |
| `sql` | `SqlQueryFrontEnd.translate(sql)` | `POST /v1/queries/translate-sql` |

Both run the full validation the execute path runs — entity resolution, field allowlist, function
catalog, mode coherence, limit governance — and neither contacts ClickHouse. Both are already
specified, as the `query-translation-endpoint` capability. Validation order for a structured body is:
envelope shape → entity resolution → sensitive-literal guard (D4) → translation. For a SQL body,
translation necessarily comes first, because it is what identifies the entity the guard then needs;
nothing is persisted before the guard runs, so the ordering carries no security consequence.

Translation is not by itself the whole of what those endpoints check. Each declares
`@Valid @RequestBody`, so the body's own bean-validation constraints are enforced at the controller
boundary *before* translation runs, and translation does not re-check them: a `StructuredQuery` with
`mode` omitted translates happily as a row query, while `POST /v1/queries/execute` refuses it outright.
`SavedQueryController` therefore declares the same `@Valid` and cascades it into `query`. Without that,
"validated by the same translation" would fall one step short of "executable as stored" — the body
would be stored and could then never be run. The cascade is the only bean validation on
`SavedQueryRequestDto`; the envelope rules stay in `SavedQueryBodyValidator` at `422`, because they are
this capability's own, not the structured query's.

**Translation failures pass through unchanged** (`400 bad_request`) rather than being re-coded as
`422`. Two reasons. Saving a body and running it then give one answer for one mistake. And nothing has
to inspect an exception message to separate "unresolvable column" from other translation failures — a
distinction that would be fragile precisely where it matters, since an unresolvable column is exactly
how a *restricted* column must appear. `422` is therefore reserved for envelope rules that belong to
this capability alone.

The feature request proposed allowing *untranslatable SQL* at `personal` scope, on the grounds that an
opaque body cannot be checked. This design rejects it instead, at `422`, because **fact 3** makes the
escape hatch pointless and unsafe at once:

- *Pointless*: `/v1/queries/execute-sql` runs through the same `SqlQueryFrontEnd`. SQL that will not
  translate cannot be executed by ADAS on any endpoint. Permitting it stores a query that can never
  run.
- *Unsafe*: the guard's rationale is the **perimeter**, not readership — a copy in the metadata
  database is a copy regardless of who may read it. A `personal`-scope escape hatch would admit
  exactly the unchecked bodies the guard exists to keep out.

Extending the same rule to structured bodies became free once parameters left the design (D10): the
only reason not to translate a `query` body at save time was that a parameterized one would be
rejected by the translator. With no parameters, the asymmetry is gone.

*Accepted cost*: save-validation and execute-validation are now coupled. A future tightening of the
translator could make a stored body unsaveable on its next `PUT` — it stays readable, and it was
already unrunnable at that point, so the coupling surfaces the problem rather than causing it.

### D9 — `source` is derived, never accepted

`source` is `query.entity` for a `query`-kind body and `SqlQueryFrontEnd.translate(sql).query().entity()`
for a `sql`-kind one. Accepting it from the client would create a second place for the same fact to
live, and the two can disagree. Like `owner_id`, `created_at`, and `updated_at`, it is **rejected** in
a request body rather than silently ignored, so a client sending it learns immediately that it is not
an input.

### D10 — No parameterization: a saved query is a query, not a template

There is no `params` column, no placeholder binding, and no template semantics. A body containing a
`param` expression is **rejected** at save time with `422 validation_error`.

The reason is fact 2: `ExprTranslator` and `StructuredQueryToSqlRenderer` both reject `ParamExpr`,
so a parameterized body cannot be executed by any ADAS endpoint. Storing one would persist a query
that can never run — precisely what D8 exists to prevent. Parameters are not "unimplemented here";
they are meaningless until a server-side binding path exists, and that is a change to the
`structured-query` capability rather than to this one.

The rejection is explicit and checked **before** translation, so the caller gets
`validation_error` with a message about parameters not being supported, rather than the translator's
internal "param expressions are not supported by the query translator". The error belongs to this
capability's contract; the translator's message is an implementation detail.

*What this costs*: a `FULL_ADMIN` cannot save any query that filters a `sensitive` column against a
value (D4). *What it buys*: every stored body is complete and runnable, `GET` → post-to-`/execute` is
the entire client flow with no substitution step, and no half-specified parameter model has to be
lived with later. When binding lands, this capability adds a `params` column and relaxes the
rejection — additively, with no change to anything shipped here.

### D11 — Listing returns the full saved query (reversed)

`GET /v1/saved-queries` returns the same object `GET /v1/saved-queries/{id}` does, body included.
There is one read shape.

**This reverses the decision originally taken here**, which returned a summary — identity and
presentation metadata without `query`, `sql`, `time`, or `chart` — mirroring the existing
`TableSummary` / `TableView` split so that listing a few hundred saved queries did not ship a few
hundred query trees.

The reversal, on three counts:

- **The client cannot act on a summary.** Which editor a saved query opens in is derived from its body
  (`sql` set means SQL; otherwise whether the structured body is builder-representable decides Builder
  vs JSON), and its period and chart type come from `time` and `chart`. So every row selection cost a
  second round trip, plus a loading state and a cache to invalidate on save and delete — complexity in
  the client bought to save bytes on the wire.
- **The sizes do not justify it.** A realistic builder state is ~0.5–2 KB; fifty of them is ~75 KB.
- **It withheld nothing.** The same caller could `GET` each row individually. It was a bandwidth
  optimization wearing the shape of a projection, never access control — and the `TableSummary`
  analogy misled: a table summary omits data a caller may genuinely not need, whereas here the omitted
  part *is* the object.

The cost accepted: the response is now bounded only by the per-row cap (D15) times the row count, and
the row count is unbounded. If that needs bounding it will be bounded by **rows** — paging, or a cap
per owner — never by reintroducing projection, which would restore the round trip without restoring
any guarantee. The spec records this as an explicit non-goal rather than an oversight.

### D12 — `generation`, bumped per mutation

A monotonically increasing counter, mirroring `RuleEntity` and `PipelineEntity`. Returned on both read
surfaces. Nothing enforces it yet — `PUT` is an unconditional full replace — but it gives the UI a
change detector, and adding `If-Match` later needs no migration. Recorded here because a full-replace
`PUT` on a `common` row genuinely can lose an edit when two admins are editing at once, and the
column is the cheap half of the eventual fix.

### D13 — `time` is a validated union, not an opaque blob

`null`, or `{"mode":"relative","period":"<token>"}` with the token matching `^[a-z0-9_]{1,32}$`, or
`{"mode":"absolute","from":<instant>,"to":<instant>}` with `from <= to`. ADAS does not interpret the
period — the client does — but it does bound its **shape**. An unvalidated free-form object in the
metadata database is a place to smuggle arbitrary text past every other guard in this design.

A relative period is never resolved to instants at save time. Storing `2026-08-04T00:00Z …
2026-08-06T00:00Z` for "last 2 days" would silently freeze the query to the day it was authored.

The request DTO carries `time` as a **raw JSON node**, and the validator binds it. Declared as the
typed union, the shapes the union cannot hold — `{"mode":"weekly"}`, a non-ISO instant, a `time` that
is not an object — would fail inside Jackson, and a binding failure is a `400 bad_request`, not the
`422` this rule is specified as. Same reason the `scope` query parameter is parsed by hand rather than
bound as an enum. Persistence then writes the bound union rather than the caller's node, so a key the
rule did not accept cannot ride along into the `jsonb` column.

### D14 — No name uniqueness, no foreign key to the source table

*Uniqueness*: `id` is identity; `name` is a label. Scoping a uniqueness constraint has no obviously
right answer (per owner? per scope? case-folded?), and the `409` it would produce buys no safety.

*No FK on `source`*: a saved query outlives the entity it names. Deleting a table does not delete
saved queries that referenced it, and the read path returns such a row unchanged. This is the same
posture the client already needs for a dropped column: it renders an unresolvable reference as a
single "unavailable field" state, identical for a dropped column and a restricted one — which is what
the `authentication` indistinguishability rule requires. Adding a distinct error for the restricted
case would break it.

### D15 — A size cap on the stored body

`saved-queries.max-body-bytes` (default `65536`), applied to the serialized `query` + `sql` + `chart`
+ `time`; over it, `422 validation_error`. A `JSONB` column with no cap is an unbounded
write primitive available to any authenticated caller. Documented in `docs/configuration.md` only,
per the project convention.

### D16 — Error mapping

One rule, so the codes stay predictable:

| Condition | Status | Code |
| --- | --- | --- |
| Unknown id, or a `personal` row belonging to someone else | `404` | `not_found` |
| Writing `common` without `FULL_ADMIN` | `403` | `forbidden` |
| The **body** will not translate — unknown entity, unresolvable column, or any other translation failure (D8) | `400` | `bad_request` |
| The **envelope** is malformed: blank name, both/neither of `query`/`sql`, server-assigned field present, bad `time` shape, a parameter expression in the body, over the size cap | `422` | `validation_error` |
| A literal bound to a `sensitive` column | `422` | `sensitive_literal_not_allowed` |
| A `personal` write whose owner would be null while security is enabled (D6a) | `500` | `principal_unavailable` |

The split is *whose rule was broken*. A translation failure is the query layer's rule and keeps the
query layer's error, unmodified — deliberately the same response `/v1/queries/execute` would give for
the same body, so a restricted column stays indistinguishable from a misspelled one here too. A `422`
is always a rule this capability adds.

## Risks / Trade-offs

- **The guard blocks sensitive-column literals, not personal data in general.** A `FULL_ADMIN` can
  still type an email address into `name` or `description`, or compare it against a non-`sensitive`
  column. → Accepted and stated as a limit, not papered over. The guard is a *structural* control on
  the one path a query body offers for copying data out of the data store; the catalog's `sensitive`
  marking is the mechanism that defines what is protected, and widening protection means marking more
  columns, not adding heuristics here. The spec does not claim more than this.

- **Dropping parameters (D10) leaves the guard's rejection with no workaround.** A per-person query
  cannot be saved at all, only run ad hoc. → Accepted deliberately: this is the cost of deferring
  parameters as a unit rather than shipping a binding model that the execute path does not support.
  The feature is still useful without it — the recurring queries a team standardises on ("top 20
  chats by turn count", the Monday review) are aggregate, not per-person. If per-person saved queries
  turn out to be the dominant ask, that is the signal to build server-side binding, and it lands as an
  additive column plus a relaxed check.

- **The guard fires only for `FULL_ADMIN`.** Everyone else is stopped earlier by the unknown-field
  error. → Not a gap: the earlier stop is stricter (they cannot reference the column at all). It does
  mean the `422` path needs an admin-context test to be covered at all, which the task list calls out.

- **`personal` isolation is per credential, not per person** (D6). → Documented in the spec and in
  `docs/configuration.md`'s neighbourhood; the alternative (refusing project keys) is worse.

- **Full-replace `PUT` can lose a concurrent edit on a `common` row.** → `generation` (D12) ships now
  so the fix is additive later; the exposure is two admins editing the same shared definition in the
  same minute.

- **A saved query can rot** — its source dropped, a column removed, a role revoked. → Deliberate
  (D14): all three render as the same "unavailable" state client-side, which is what the
  indistinguishability rule demands. The alternative (validating every saved query against the live
  catalog on read) would both cost a catalog read per row and leak which columns still exist.

- **Rejecting untranslatable SQL (D8) is stricter than the original sketch** and will refuse some
  bodies the SQL editor can produce. → Every such body is one `/v1/queries/execute-sql` would also
  refuse, so nothing executable is lost.

## Migration Plan

`V24__saved_query.sql`. Purely additive: one new table, no change to an existing one, so the rollback
is `DROP TABLE saved_query` and reverting the code. No backfill and no data to migrate — the capability
starts empty.

This shipped as `V23`, which was free against the default branch (`f7deaf1`, highest `V22`) and every
in-flight worktree and remote branch when the change was written. the default branch then merged
`V23__dial_usage_log_cache_creation_nullable.sql` while this branch was in review, and the merge back
was renumbered to `V24`. Worth recording because git does not surface this: the two migrations have
different filenames, so the merge is textually clean and the collision only appears at startup, as
Flyway's "found more than one migration with version 23". **Verifying a Flyway slot is free at
authoring time is not enough — it has to be re-checked on every merge from the deployment branch.**

## Open Questions

None blocking. Two decisions depart from the original feature sketch and are flagged for the reviewer
rather than left silent:

- **D10** — parameterization is dropped entirely (author's decision at the proposal checkpoint). The
  sketch's `params` list is gone, and a parameter expression in a body is now a `422`.
- **D8** — a saved query must translate, at every scope and for both body kinds. The sketch allowed
  untranslatable SQL at `personal` scope; that hatch stores unexecutable queries and admits exactly
  the unchecked bodies the sensitive-literal guard exists to keep out.
