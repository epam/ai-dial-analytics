## Context

See `proposal.md` — Why. Design-relevant current state, and what four investigations established
before this design was fixed:

- The catalog is Spring Data JPA over PostgreSQL with `spring.jpa.hibernate.ddl-auto: none`; the
  schema is owned entirely by Flyway (`classpath:db/migration/postgres`, currently through `V26`).
  Nothing validates the entity mapping against the physical schema at startup.
- A table resource is stored across **two** physical tables: `table_definition` (the row — including
  the JSONB `access` lists, the `text[] tag_order`, and, since `V25__denormalize_table_catalog`, the
  `text[] ordering_key` and the `grain_key`) and `column_mapping` (composite `@IdClass` of
  `table_name` + `source_name`). `V25` dropped `enrichment_grain` and `table_ordering_key`, deleting
  `EnrichmentGrainEntity` and its repository; `TableView` is now `(definition, columns)`. The grain's
  `cardinality` is no longer stored at all — it had one legal value and is emitted as a constant by
  `TableMapper` — so it is not audit-relevant state.
- The enrichment control plane (`evaluator`, `evaluator_version`, `rule`) and `pipeline` are
  deliberately out of scope for this increment — see `proposal.md`. The design below is nonetheless
  built so that adding them later is an `@Audited` annotation, an `_aud` table, and a mapper case.
  `V26__rule_read_source` has since added `rule.source_table`, confirming the surface is still
  moving — which is the reason for the exclusion, not an argument against it.
- `SecurityClaimsExtractor` already reads the caller's principal and email from the security context
  and returns `null` when unauthenticated. `SystemIdentity` already runs ADAS's scheduled work under
  an authenticated `adas-system` principal, so background mutations arrive with an identity.
- **The reference filtering layer is portable.** `ai-dial-admin-mcp-manager-backend` runs Spring Boot
  4.0.6, Jackson 3, MapStruct 1.6.3 — ADAS's stack. Its `PageEntityMapper<T>` (~115 lines) is generic
  over the entity type and couples to nothing repo-specific; the DTOs are plain Lombok POJOs with no
  custom deserializers or polymorphic typing.
- **`ai-dial-admin-frontend` already consumes this contract**, per-service by construction:
  `ActivityAuditApi` and `DeploymentAuditApi` are the same class against two configurable hosts. It
  compares enum values with strict `===` against PascalCase and performs no case normalization
  anywhere.
- **Envers can filter on an `@IdClass` component.** `CriteriaTools.determinePropertyName` rewrites a
  property name that matches an id component to `originalId.<name>`; for `@IdClass` the id type is an
  `EmbeddedComponentType`, so the rewrite fires. Confirmed by reading the Envers source *and* by
  running a probe against a mirror of `ColumnMappingEntity`, which produced the correct correlated
  `max(rev)` SQL and correctly excluded a deleted column.

## Goals / Non-Goals

**Goals:**

- Auditing that cannot be forgotten at a call site — it attaches to the persistence layer, so a
  future mutation path is audited by construction rather than by remembering to log.
- A wire contract identical to the sibling DIAL control planes, so the existing admin frontend can be
  pointed at ADAS with additive changes rather than a new client.
- Snapshot reads that return the *same* DTO shape as the live read surface, so a client diffs two
  identical shapes rather than a shape against an audit-specific projection.
- A trail that is lossless at entity grain, with enough structure (revision, parent) that one
  administrative act is still recoverable as one thing.

**Non-Goals:**

- Restoring a resource to a past revision, and the revision-list endpoint that exists to drive it.
- A generic "audit anything" framework. The entity → resource mapping is an explicit, exhaustive
  switch; an unmapped audited entity is dropped rather than guessed at.
- Multi-vendor DDL. ADAS's catalog is PostgreSQL-only, unlike the reference services which carry
  H2/SQL Server variants of every migration.
- Reproducing the reference implementation's defects for the sake of parity (see the fixes below).

## Decisions

### Hibernate Envers over a hand-written activity log

Envers is added (`org.hibernate.orm:hibernate-envers`, Boot BOM-managed) and the two table-catalog
entities are annotated `@Audited`.

*Why:* the requirement is not only "who changed what" but "what did it look like before" — full
prior state is what makes an audit trail actionable. Envers writes that snapshot inside the caller's
transaction, which also gives the rollback guarantee for free. The alternative, writing activity rows
explicitly in `TableCatalogService` / `TableSchemaService`, was rejected on two counts: it captures no
prior state, and coverage decays — the next mutation path added is audited only if its author
remembers. `org.hibernate.envers.store_data_at_delete: true` is enabled so a `Delete` audit row
carries the final state rather than a row of nulls; "what was deleted" is the question an auditor
asks about a deletion.

*Alternative considered and worth recording:* PostgreSQL row triggers writing `to_jsonb(OLD/NEW)` into
a single generic audit table. It is genuinely stronger on two axes — it catches database cascades,
bulk SQL, and manual edits that Envers cannot see, and its `jsonb` history survives schema drift
(a column added later is *absent* from old rows rather than NULL, and a dropped column's history stays
readable). It was rejected for three ADAS-specific reasons: it cannot know the actor without the
application setting a session variable at every transaction boundary — the same forgettable-step
problem, inverted; the snapshot endpoint needs entity-shaped prior state rather than row diffs; and
neither sibling control plane does it, so it would cost the frontend compatibility that motivated the
contract choices below.

### Ignoring a change: what the framework can and cannot suppress

Recorded because it is the mechanism behind two requirements below (an unchanged entity produces no
activity; a re-defined schema reports only what changed) and because an earlier draft of this design
got it wrong. Verified by reading the Envers 7.2.12 sources, not by inference:

- **`@NotAudited` on a property suppresses the entire write, not just the column.** `ModWorkUnit`
  computes its `changes` flag from `PropertyMapper.map(...)` over the *audited* properties only;
  `AuditProcess.addWorkUnit` enqueues the unit only `if (vwu.containsWork())`; and
  `doBeforeTransactionCompletion` returns early when `workUnits.isEmpty() && undoQueue.isEmpty()`.
  The `revinfo` row is written inside `executeInSession` via `getCurrentRevisionData(session, true)`,
  which that early return never reaches. So an update touching only `@NotAudited` properties writes
  no `_aud` row, no revision, and — since `newRevision`/`entityChanged` never fire — no activity.
- **Hibernate's dirty checking sits upstream of all of it.** Saving an entity whose field values are
  unchanged issues no UPDATE, so nothing reaches Envers at all. This is why the reconcile fix to
  `replaceColumns` is sufficient on its own to keep the feed truthful.
- **Entity and relation level:** an unannotated entity is invisible to the trail, and
  `@Audited(targetAuditMode = NOT_AUDITED)` keeps a relation's foreign key without auditing its
  target.
- **What is not available:** there is no hook to veto a revision once created, and no value-dependent
  auditing — Envers decides on *which properties* changed, never on what they changed to.
  `@Audited(withModifiedFlag = true)` only adds `_MOD` columns; it suppresses nothing.
- **Our listener is a fourth, weaker lever:** dropping an entity in `entityChanged` suppresses the
  feed row but not the `_aud` row that was already written. It is for noise, not for growth.

The practical consequence is that adding a high-write entity to the trail later is a question of
classifying its fields as runtime or configuration, not of restructuring its table — see the pipeline
note in `proposal.md`.

### Activities are derived in an `EntityTrackingRevisionListener`, one per changed entity

`AuditRevisionListener` implements `EntityTrackingRevisionListener`. `newRevision` stamps the author
and timestamp; `entityChanged` maps the changed entity to a `(resourceType, resourceId)` pair and
appends an `AuditActivityEntity` to the revision's cascaded collection.

Every audited entity is its own resource — nothing is folded into a parent:

| Changed entity          | Resource type | Resource id                |
|-------------------------|---------------|----------------------------|
| `TableDefinitionEntity` | `Table`       | its `name`                 |
| `ColumnMappingEntity`   | `TableColumn` | `<tableName>:<sourceName>` |
| anything else           | *dropped*     | —                          |

There is no `EnrichmentGrain` resource type. Before `V25` the grain was its own entity and would have
been one; it is now the `grain_key` column of the definition row, so setting or changing a grain is an
`Update` on the owning `Table` — the same treatment the ordering key gets below, and for the same
reason. Nothing is lost: the grain's full prior value is in `table_definition_aud`, and a snapshot
returns it in the `grain` member of `TableDto` exactly as the live read does.

*Why:* the trail is meant to be lossless. Folding a column change onto its table would record that
*something* about the table changed while discarding *which column* — precisely the fact an auditor
is looking for. The cost, that one administrative act spans several rows, is paid back by the
revision id and the parent link below.

A composite id is rendered `<a>:<b>` because `resource_id` is a single string column in the shared
schema. The separator is a colon rather than a dot: a ClickHouse column's physical name can contain
neither, but `.` reads as a qualified name and invites a client to re-parse it as one. The reference
frontend never splits `resourceId` — it only `decodeURIComponent`s it — so an opaque compound id is
safe.

The ordering key gets no resource type of its own. Since `V25` it is a `text[]` column on
`table_definition` rather than an `@ElementCollection`, so a reordering is a plain column update on
the definition row and surfaces as a `Table` activity with no collection machinery involved — the
earlier reliance on Envers' `revision_on_collection_change` is no longer needed. The conclusion is
unchanged and the mechanism is simpler: an ordered list of column names has no identity apart from the
row that owns it.

The unmapped case is dropped rather than thrown on (the reference throws to surface a bug). ADAS will
gain audited entities for other capabilities later; a listener that kills the transaction when it
meets one it does not know is a worse failure than a missing feed entry, and the coverage contract is
enforced by tests rather than at runtime.

### Dependent deletes are issued through the ORM, because a database cascade is invisible

`TableCatalogService.delete()` currently deletes only the definition row and lets PostgreSQL remove
the rest:

```java
// The catalog row's remaining child (column_mapping) is removed by ON DELETE CASCADE; the grain and
// ordering key are columns of the row itself and go with it.
definitions.delete(entity);
```

Envers listens on Hibernate's post-delete event, so it sees only what the persistence context deletes.
`column_mapping` is an independent `@Entity` type with no JPA-level cascade from
`TableDefinitionEntity`. Left as is, deleting a table would record a `Delete` for the `Table` and
**silently nothing** for its columns.

The delete path therefore removes them explicitly through their repository before deleting the parent
— `columns.deleteByTableName(name)`. Spring Data's derived `deleteBy…` loads the entities and deletes
them individually (unlike a `@Modifying` bulk query), so the events fire and Envers records each one.
The database cascade stays in place as a backstop; it simply finds nothing left to remove.

The grain needs nothing: `V25` made it a column of the definition row, so it is captured by the same
`Delete` row that records the table's own final state. That is one fewer cascade to work around than
this design originally had — the denormalization removed the hole rather than moving it.

The full cascade inventory, checked rather than assumed (every `ON DELETE` in
`db/migration/postgres`):

| Cascade | Reaches an audited row? | Status |
|---|---|---|
| `column_mapping.table_name → table_definition` | yes, both sides audited | closed above |
| `table_ordering_key`, `enrichment_grain → table_definition` | — | dead; both tables dropped in `V25` |
| `rule.target_enrichment → table_definition` | deletes an unaudited row | hole when rules join the trail |
| `pipeline.target → table_definition` | deletes an unaudited row | hole when pipelines join the trail |
| `evaluator_version.name → evaluator` | neither side audited | not applicable |
| `rule.source_table → table_definition` (`V26`) | `RESTRICT`, not a cascade | pre-checked with a 409 |

Nothing cascades *into* `table_definition` or `column_mapping` from elsewhere, so after the fix above no
audited row can be removed without an event. The two remaining rows of the table are the mirror image:
deleting a table silently removes its rule and its pipeline, which costs nothing today because neither
is audited, and becomes a hole the moment either joins the trail. Closing them belongs to that later
change; they are recorded here so they are not rediscovered.

*Why it matters beyond the feed:* a missing delete row is not just a gap in the activity list, it
corrupts the snapshot surface. `forEntitiesAtRevision` decides membership from the latest `_aud` row at
or before the requested revision, excluding delete rows — so a column removed without one keeps
answering as though it still existed, at every future revision, forever.

*Alternative considered:* mapping the columns as `@OneToMany(cascade = REMOVE, orphanRemoval = true)`
on `TableDefinitionEntity`. Rejected — it would change fetch and lifecycle behavior on the catalog's
hottest read path to fix a problem confined to one rarely-called admin method.

### A schema re-define rewrites every column row, and Envers already handles it

`CatalogSchemaWriter.replaceColumns` — the write behind `POST /v1/tables/{name}/schema` for both a
source and an enrichment — is a wholesale replace: `columns.deleteByTableName(name)`, flush,
`saveAll(rows)`. On the normal path that costs nothing, because a `PENDING` table has no columns to
delete. But re-posting the schema of a `FAILED` table is explicitly supported (`persistSourceSchema`
keeps an omitted `identityColumn`/`versionColumn` precisely so a re-post is non-destructive), and on
that path every column is deleted and re-inserted **with the same id, in one transaction**.

An earlier draft of this design called that a defect on two counts — a primary-key collision in
`column_mapping_aud`, and a feed reporting twelve columns changed when one did — and specified a
reconciling rewrite of `replaceColumns`. **The first count was wrong; the second is real.** Both were
settled against the running system rather than by argument:

- Envers keys pending work by `(entityName, id)` within a revision and *merges* a later unit into the
  one already held (`AuditProcess.addWorkUnit`, `DelWorkUnit.merge(AddWorkUnit)`). A same-id
  delete+insert therefore becomes **one** row, and can never reach the primary key as two. The
  collision does not exist.
- That merge produces a `ModWorkUnit`, and an integration test shows it does so **even when the
  re-inserted values are identical** — `DelWorkUnit.merge` has a null-return path for equal state, but
  the two state arrays it compares are not equal across a delete-then-insert of a fresh instance. So a
  re-post does report every column it re-states, not only the ones that differ.

The over-reporting is accepted rather than fixed. It is confined to re-posting the schema of a table
that is not yet materialized; it names rewrites that genuinely happened rather than inventing them; and
the fix has a worse failure mode than the problem. The reconciling version was written and then
reverted: updating rows in place breaks the case where two columns swap exposed names, which violates
`exposed_name_unique_per_table` mid-flush where deleting both first does not. Trading a coarse feed
entry on a rare admin path for a 500 on a legal request is the wrong direction.

The spec states this limit explicitly rather than promising minimal-diff auditing it does not deliver.

`renameColumn` deletes and re-inserts under a *different* id (`source_name` is part of the key), so no
merge applies — and none should: a rename genuinely is the end of one `TableColumn` resource and the
start of another, and the trail says so with a `Delete` and a `Create`. `updateColumnMetadata` and
`addColumn` already touch one row each.

*What this leaves as a real obligation:* the merge behavior is load-bearing and version-specific, so it
is asserted by an integration test on the re-define path rather than trusted.

### A staged write is two revisions, and that is left alone

`POST /v1/tables/{name}/schema` produces two revisions: `CatalogSchemaWriter.persistSchema` commits the
columns and keys, the ClickHouse `CREATE TABLE` is issued, and `markStatus(ACTIVE)` commits separately.
The feed therefore shows two `Table` activities for one request, and a snapshot at the first says
`pending`.

Considered as a defect and rejected as one. The split is why `TableSchemaCreationService` can promise
that it "never leaves an `ACTIVE` definition without a confirmed ClickHouse table": ClickHouse DDL
cannot join a PostgreSQL transaction, so committing the catalog first is what makes the failure path
recoverable — the alternative, one transaction spanning the DDL, would orphan a real ClickHouse table
whenever the commit failed after the `CREATE` succeeded. Collapsing the revisions to tidy the feed
would trade that invariant for cosmetics.

And the trail is not lying: the table genuinely was `pending` at the first revision. A snapshot that
reported `active` there would be describing a state the system never occupied. What was worth fixing was
the *claim*: a test called `…UnderOneRevision` asserted no such thing, and the spec left a reader to
assume one request is one revision. Both now say what actually happens.

### Child activities carry `parentActivityId`, resolved bidirectionally in the collection

A `TableColumn` activity's `parentActivityId` is the id of the `Table` activity for its own table in
the same revision, when one exists; otherwise null. `TableColumn` is the only child resource type
today; the mechanism is written against the resource type's parent table name rather than against
that one case, so a later audited child needs no rework.

Envers fires `entityChanged` in no guaranteed order, so the parent may not exist yet when a child is
recorded. Rather than deferring resolution to a hook Envers does not provide, the listener resolves
in both directions against the revision's own collection: a new child adopts an already-present
parent, and a new `Table` activity back-fills the parentless children already recorded for it.

*Why:* this is the reference's own answer to the grain problem — the admin backend carries
`parentActivityId` and the frontend groups feed rows by it, fetching children with an `in` filter.
Tracking everything and linking children is strictly better than choosing between them: the detail
survives, and a reader still sees one collapsed row per act. A null parent is normal, not an error
— a column edited without touching the definition row has no parent activity to point at.

### The listener is a Spring bean only because `hibernate.cdi.extensions` is enabled

`AuditRevisionListener` needs two collaborators (`SecurityClaimsExtractor`, `AuditResourceMapper`), and
how it gets them is not a free choice. `DefaultRevisionInfoGenerator.resolveRevisionListenerBean`
resolves the listener through Hibernate's `ManagedBeanRegistry` **only** when
`Helper.allowExtensionsInCdi(...)` is true — `hibernate.cdi.extensions`, which defaults to false.
Otherwise it calls `FallbackBeanInstanceProducer`, i.e. reflects a no-arg instance Spring never sees.

So the property is set, and Boot's already-registered `SpringBeanContainer` (`HibernateJpaConfiguration`
installs it whenever `BeanContainer` is on the classpath) hands Envers the managed singleton with
constructor injection.

*Why not the `ApplicationContextAware` approach this design originally specified:* it cannot work. An
object instantiated reflectively by Envers has no channel to Spring, so the context could only reach it
through a `static` holder — and a static is shared by every Spring context in the JVM, which is exactly
what breaks under cached test contexts: the last context to start would own the listener for all of
them. The property is one line and removes the problem rather than managing it.

*Cost, recorded:* `hibernate.cdi.extensions` is global, so Hibernate will consult the bean container for
its other extension points too. That is benign here — `SpringBeanContainer` falls back to instantiating
anything that is not a registered bean — but it is a wider switch than the one thing it was flipped for.

### Deduplicate against the revision's own collection, not a `ThreadLocal`

Dedup keeps one activity per `(resourceType, resourceId)` per revision, with `Create`/`Delete`
replacing a previously recorded `Update`. The lookup scans the revision entity's activity collection.

*Why:* the reference keeps a `ThreadLocal<Map>` and documents that it is never cleared, because
Envers offers no after-flush callback — it relies on the next `newRevision` replacing the map. The
revision entity is already the exact per-transaction scope needed, and it is passed into every
`entityChanged` call, so the `ThreadLocal` (and its documented residue on pooled threads) is
unnecessary. The collection holds one entry per changed entity — single digits in practice — so a
linear scan is cheaper than the map it replaces, and it is the same structure the parent resolution
above already needs.

### Author fallback: `unknown`, never a fabricated identity

`newRevision` takes the principal and email from `SecurityClaimsExtractor`. A null principal records
the author `unknown` with a null email.

"No principal" turns out to need saying carefully. Spring Security's anonymous filter installs an
`AnonymousAuthenticationToken` on every unauthenticated request, whose `getName()` is the literal
`"anonymousUser"` — so a plain null check on the principal records a fabricated identity in exactly the
case this rule exists to prevent, and `none` mode (the default, and the whole test suite) is that case.
`SecurityClaimsExtractor` therefore grew an `isAuthenticated()` that resolves anonymity through
Spring's own `AuthenticationTrustResolver`, and the listener asks that first. `SystemIdentity` supplies
a real `UsernamePasswordAuthenticationToken`, so background work still attributes to `adas-system`.
`getAuthor()` itself is deliberately left alone: its other caller already guards on whether security is
enabled at all, and widening the fix would change saved-query ownership semantics for no reason here.

*Why:* the reference distinguishes `unknown` (unauthenticated HTTP) from `system` (no HTTP context).
ADAS does not need that split: its background work runs under `SystemIdentity`'s authenticated
`adas-system` principal, so scheduler-driven changes already carry a real principal, and the only
remaining null case is a genuinely unattributable request — `none` security mode. One honest value is
better than a second one inferred from the presence of a request context.

Claim extraction is wrapped so that a failure degrades the author to `unknown` rather than failing the
caller's transaction: an audit trail must not be able to break the operation it observes.

**Attribution rests on an invariant nothing enforces, so it is written down here.** Envers builds the
revision entity in `AuditProcess.doBeforeTransactionCompletion` — at commit, not when the entity
changed — so the listener reads the security context *then*. Every audited write today commits on the
request thread that holds the caller's context, which is why this works. It would stop working
silently, not loudly, for a write that commits on another thread, or after `SystemIdentity.runAsSystem`
has restored the previous context: the trail would record `unknown`, or whichever principal happens to
occupy that thread-local, with nothing anywhere reporting a problem. Any future audited write on an
`@Async` or pooled path must carry the identity explicitly rather than inherit it from the thread.

### The reference filter contract is adopted, its defects are not

`POST /v1/activities` takes `{pageNumber, pageSize, sorts, filters}` and returns
`{total, totalPages, data}`; filters are a flat AND-list of `{column, operator, value}` where `value`
is always a string. The generic `PageEntityMapper<T>`, the DTOs, and `PageDto<T>` are transplanted
from `ai-dial-admin-mcp-manager-backend` — that code already runs on Boot 4 with Jackson 3.

The path is `/v1/activities`, not `/api/v1/activities`: ADAS serves functional endpoints under `/v1`
and does not carry the `/api` prefix. The frontend hardcodes `api/v1`, so it needs a change to reach
ADAS regardless — as it does for its view switch and detail-route resolver — and inventing an `/api`
alias in ADAS to avoid one line of FE change would be the wrong trade.

Operators are the mcp-manager's eight (`eq ne co nc lt gt le ge`) plus `in` from the admin backend,
which the frontend emits (comma-joined) to fetch child activities. `isnull`/`isnotnull` are left out;
nothing consumes them.

Five deliberate departures, each fixing a defect found in the reference implementations:

- **A bad numeric filter value is a 400, not a 500.** In both references, `epochTimestampMs ge "abc"`
  reaches Hibernate and throws `CoercionException`, which is not an `IllegalArgumentException` and so
  falls through to the catch-all handler. Values are coerced per column type before the query is
  built, and a failure raises a filter-validation exception mapped to 400.
- **Filter and sort columns are allow-listed** (the mcp-manager's `validateFields`, which the admin
  backend lacks). Without it an unknown filter column leaks a raw Hibernate message, an unknown sort
  column is a 500, and *every* mapped attribute becomes filterable — which on the admin backend
  includes a CLOB, making `co` an exfiltration primitive.
- **`LIKE` wildcards are escaped** (the mcp-manager's `escapeLikeWildcards`), so a user-supplied `%`
  or `_` is a literal.
- **A null filter value is rejected as a 400** rather than NPEing inside `toLowerCase()`.
- **An empty `sorts` list defaults to `epochTimestampMs DESC`.** The references document "no default
  ordering" as a client hazard; offset paging over an unordered result set silently duplicates and
  skips rows. The default matches what the frontend sends anyway.

Errors use ADAS's `ErrorView` with stable machine codes (`invalid_filter_column`,
`invalid_filter_value`, `activity_not_found`, `revision_not_found`), not the references' HTTP reason
phrase — and per `DefaultExceptionHandler`'s existing rule, the 400 bodies name the offending column
without echoing an exception message. Signalling uses dedicated exceptions rather than the references'
`IllegalArgumentException`, for which ADAS has no handler at all.

### Enum constants are named as their wire values

`ActivityType` is `Create`/`Update`/`Delete` and `ActivityResourceType` is `Table`/`TableColumn` — the
Java constants are spelled exactly as the wire values, as the reference services do.

*Why:* the frontend compares with strict `===` against PascalCase and normalizes nothing — it renders
`activityType` raw into the grid and classifies `resourceType` through `Set.has()` on exact strings.
Uppercase values would display literally as "CREATE", fail every branch, and leave feed rows
non-clickable. Naming the constants as the wire values also keeps three things in agreement that
would otherwise need translation between them: `@Enumerated(EnumType.STRING)` storage, JSON
serialization, and the filter `value` compared against the stored column.

This does break ADAS's own convention of uppercase enum constants, which is a real cost paid
knowingly. ADAS's checkstyle configuration registers no `ConstantName`/`EnumConstantName` module, so
the build does not object.

**Naming the constants is not sufficient, and that was discovered during implementation.**
`JsonMapperConfiguration` enables `EnumFeature.WRITE_ENUMS_TO_LOWERCASE` for the whole application —
which is right for ADAS's own vocabulary (`"source"`, `"pending"`, `"upsert_by_key"`) and would have
silently emitted `"create"` and, worse, `"tablecolumn"`. Both enums therefore carry an explicit
`@JsonValue` returning `name()`, which pins the wire spelling against that global setting. An
integration test asserts the spelling on the wire, because this is the kind of thing a future mapper
tweak would break invisibly.

### The audit surface speaks camelCase, unlike the rest of ADAS

`{pageNumber, pageSize, sorts, filters}` in, `{total, totalPages, data}` out, and
`activityType`/`resourceType`/`resourceId`/`epochTimestampMs`/`initiatedAuthor`/`parentActivityId` in
each entry. ADAS's own DTOs are snake_case, declared per field with `@JsonProperty` (there is no global
naming strategy) — so this capability is visibly inconsistent with its neighbours.

*Why anyway:* the contract is adopted, not designed. It is what the sibling control planes serve and
what `ai-dial-admin-frontend` already parses, and the filter vocabulary is welded to JPA attribute names
(`epochTimestampMs` is the attribute), so renaming half of it to snake_case would produce a contract
that is neither ADAS's nor the reference's, and would still need a translation table for the filter
column names. Consistency with the client that exists beats consistency with the neighbours here, and
the inconsistency is confined to one capability's DTOs.

### Schema: `revinfo` + `*_aud` + `audit_activity`, PostgreSQL-native

`V27__audit_trail.sql` — `V25` and `V26` are taken by the denormalization and the rule read source —
creates `revinfo` (`id INTEGER GENERATED BY DEFAULT AS IDENTITY`, `timestamp BIGINT`, `author`,
`email`), the two `*_aud` tables mirroring the live column types as of `V26` — `jsonb` stays `jsonb`
and `text[]` stays `text[]`, which after `V25` means `table_definition_aud` carries two array columns
(`ordering_key` and `tag_order`) plus `grain_key`, and `column_mapping_aud` carries `element_type` —
and `audit_activity` keyed by
`activity_id VARCHAR(36)` holding a time-ordered UUIDv7 (`com.github.f4b6a3:uuid-creator`, the
dependency the references use), with a nullable `parent_activity_id` and a FK to `revinfo`.

Four physical facts, established by generating the DDL Hibernate expects rather than by assumption.
Each fails at *runtime* rather than at startup, because `ddl-auto` is `none`:

- **Audited non-id columns must be nullable**, even where the live column is `NOT NULL`. A delete row
  writes NULL into every non-id column; a `NOT NULL` there breaks every delete of that entity. `V25`
  added one more instance of exactly this trap: live `ordering_key` is `NOT NULL DEFAULT '{}'`, and
  copying that into the mirror would fail every table delete at commit.
- **`revtype` is `SMALLINT`** on PostgreSQL, and the delete marker is `2` (`ADD=0`, `MOD=1`).
- **The `_aud` primary key is `(rev, <id columns>)`** — the revision is part of the key.
- **Id components are plain columns with the base table's names.** `originalId` is a mapping-level
  component name that never appears in SQL, so `column_mapping_aud` carries plain `table_name` /
  `source_name`. No `original_id` prefix anywhere in the DDL.

`revinfo` uses an identity column rather than Envers' default sequence, whose allocation size is 50 —
a hand-written `increment by 1` against the default strategy would collide. A custom `@RevisionEntity`
with an explicit `GenerationType.IDENTITY` sidesteps the question.

`audit_activity` is indexed on `(resource_type, resource_id)`, on `epoch_timestamp_ms`, and on
`parent_activity_id` — the three access paths the feed's filters produce.

### Snapshots live on the entity controllers, and Envers can express them

`GET /v1/tables/{name}/revision/{revision}` — the reference layout, and the shape the frontend already
builds paths for. `TableMapper.toDto` already takes a `TableView` — the `(definition, columns)`
aggregate — rather than reaching into repositories itself, so no factoring is needed: the snapshot
path builds a `TableView` out of Envers results and hands it to the same mapper the live read uses.
The live path feeds it from the repositories; the snapshot path from `AuditReader`:

```java
auditReader.createQuery()
        .forEntitiesAtRevision(ColumnMappingEntity.class, revision)
        .add(AuditEntity.property("tableName").eq(tableName))
        .getResultList();
```

This is confirmed working, not assumed: `AuditEntity.property` on an `@IdClass` component is rewritten
to `originalId.tableName` by `CriteriaTools.determinePropertyName`, and a probe against a mirror of
`ColumnMappingEntity` produced the correct correlated-`max(rev)` SQL and excluded a deleted column.
`AuditEntity.id().eq(...)` is *not* the answer — it requires the whole composite id and silently
returns zero rows for a partial one. No native-SQL fallback is needed.

Snapshots are authorized exactly as the live read of the same resource, since they return the same
DTO: authenticated, like `GET /v1/tables/{name}` is today.

One consequence of reusing the mapper is worth stating rather than discovering: `TableDto` carries a
`permissions` member that `TableMapper` resolves from the definition in hand. On a snapshot that is
the *historical* `access` list, so the field answers "what could this caller have done then", not
"now". That is the honest reading of a point-in-time DTO and is consistent with every other field in
it; it is called out because `permissions` is the one member a reader might otherwise assume is
evaluated live. It is not an authorization decision — the route's own gate is the live one.

A `find` returning null, or a revision id that does not exist, is a 404 `revision_not_found`; one code
for both, because "there is no such revision" and "the resource did not exist then" are the same
answer to the caller — no resource state to return at that point in time.

### The feed inherits the catalog read bar — an application role, not `FULL_ADMIN`

`POST /v1/activities` and `GET /v1/activities/{id}` carry no security annotation at all: they fall
under `OidcSecurityConfiguration`'s `/v1/**` rule, which requires `FULL_ADMIN` **or**
`READ_ONLY_ADMIN`. That is precisely what `GET /v1/tables/{name}` requires.

*Correction to an earlier draft of this design,* which asserted that ADAS "already lets any
authenticated caller read `GET /v1/tables/{name}`" and set the feed at bare authentication to match.
Reading `OidcSecurityConfiguration` shows otherwise: only three per-table *mutating* routes are
`.authenticated()` at the URL layer (they are authorized afterwards by per-table access), and every
catalog **read** falls through to the application-role rule. Setting the feed at bare authentication
would therefore have made a table's history readable by callers who cannot read the table — the exact
inversion this section exists to prevent.

*What is deliberate and unchanged:* not requiring `FULL_ADMIN`. The feed exposes no field a
`READ_ONLY_ADMIN` cannot already read — columns' `sensitive` flags and a table's `access` lists are
part of the table read — so what it adds is *history* and *authorship*, and gating the description
above the thing described would be inconsistent.

This is also a reason the saved-query coverage question was answered "no": that data is not readable at
this bar, and auditing it under this authorization would have leaked it. Any capability added to the
trail later must be re-checked against the bar rather than inheriting it.

*One thing this does not do, stated so nobody mistakes it for protection:* every entry carries the
author's email, and the feed is fully pageable, so anyone who may read it may read every email in it.
Leaving `initiatedEmail` out of the filter allow-list is not a mitigation for that — it only means
nothing needs to search by it. Making emails less readable than the rest of the feed would take field
masking by role, and a decision that the trail should be less readable than the tables it describes.

## Risks / Trade-offs

- **A hand-written `*_aud` schema can disagree with what Envers expects, and `ddl-auto: none` means
  nothing catches it at startup** — the first failure would be a caller's mutation failing at commit,
  i.e. auditing breaking the thing it audits. The nullable-column and `revtype`-width traps above are
  exactly this class. → The integration suite performs a real create/update/**delete** of *every*
  audited entity against a Testcontainers PostgreSQL running the real migrations. This is the single
  largest remaining risk and the tests are sized to it.
- **The trail's correctness on a schema re-define rests on Envers' work-unit merge**, not on anything
  ADAS does — see the re-define section above. It is the kind of framework-internal behavior that a
  version bump could change silently, and if it did, the symptom would be a primary-key violation on a
  re-define rather than a wrong feed entry. → An integration test re-posts a `FAILED` table's schema
  and asserts both legs (unchanged column silent, changed column recorded once).
- **The Envers probe ran on 7.2.12 against H2; production is 7.2.19 on PostgreSQL.** `CriteriaTools`
  is byte-identical from 6.6.49 through 7.2.12, so the extrapolation is sound but not proven. → The
  snapshot integration test closes it on the real version and the real database.
- **PascalCase enum constants break ADAS's own convention.** → Accepted for frontend compatibility;
  confined to two enums in `service/audit/`, and recorded here so a reviewer reads it as a decision.
- **The trail covers only the table catalog, so it answers half of some questions.** A rule's
  behavior depends on catalog state this change audits (a repointed `version_column`, a renamed grain
  key) but the rule's own edits are invisible, so a reader sees the cause without the effect. →
  Accepted for this increment: the alternative is freezing an audit contract against an unstable
  surface. When rules are added, the `rule.target_enrichment` cascade noted above must be closed at
  the same time.
- **Every feed request issues a second `COUNT(*)`** with the same predicate — inherent to the
  `{total, totalPages}` envelope, with no wire opt-out. → Accepted as the price of contract parity;
  the table is indexed on the filtered columns.
- **Unbounded growth.** Nothing prunes `audit_activity` or the `*_aud` tables. → Accepted for now;
  the volume is administrative, and the one entity that would have made it non-administrative
  (`pipeline`) is deliberately excluded.
- **The wire vocabulary is welded to JPA field names**, since `column` is used verbatim as an
  attribute name. Renaming an audited field is a breaking API change. → Inherent to the adopted
  contract; the allow-list at least makes the coupling explicit and reviewable in one place.
- **`@Audited` on `TableDefinitionEntity`, which the query path reads.** Envers hooks
  post-insert/update/delete only, so reads are unaffected. → Verified by the existing query
  integration tests continuing to pass unchanged.

## Migration Plan

**The pre-existing catalog has no history, and the snapshot surface says so rather than guessing.** A
table that already existed at deployment acquires audit rows only for what is touched afterwards — and
its *columns* only as they are individually changed. Reconstructing such a table would therefore return
its definition beside an empty column list: not merely incomplete but self-contradicting (an
`orderingKey` naming columns the same response omits), and a client diffing it against the present
would read it as "every column was dropped". So a snapshot is served only for a table whose `Create` is
itself in the trail; every other table answers 404 at every revision. The same rule covers a revision
number beyond the newest, which Envers would otherwise resolve to the current state — a revision number
is not evidence that a state existed at it.

Both were found by running the service rather than by the test suite, which only ever sees tables it
created itself.

Additive and single-step. `V27__audit_trail.sql` creates only new tables — no existing table changes
shape, so there is no backfill and no window in which old and new code disagree about the catalog.
Deploying the new version starts the trail; history before deployment does not exist and is not
reconstructable, which the feed represents honestly by simply having no entries before that point.

Rollback: redeploy the prior version. The audit tables remain and are ignored — no compensating
migration is required, and no data written by the new version is unreadable to the old one, since the
old version reads none of these tables. Dropping them is optional cleanup, not part of a rollback.
