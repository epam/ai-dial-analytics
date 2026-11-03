## Context

See proposal.md, "Why". The requirements are in `specs/dashboards/spec.md` and the `audit-trail` delta; this
document records how they are met and which alternatives were turned down.

Saved queries are the closest existing object, and most of what a dashboard needs already exists there:

- `SavedQueryAccess` decides visibility and write authority from `SavedQueryScope` and the three
  `SAVED_QUERY_*` privileges, and fails open in `none` mode.
- `SavedQueryRepository.findVisible` applies visibility in the lookup, so an invisible row and a missing
  row share one `404` path.
- Body checking is split in two. `SavedQueryBodyValidator` covers everything checkable without the
  catalog: the one-body rule, blank-`sql` normalisation, parameter rejection, the size cap, and binding
  `time` from a raw `JsonNode` so a malformed intent is the capability's `422` rather than Jackson's `400`.
  `SavedQueryBodyResolver.resolveSource` runs the translation the execute endpoint would run and derives
  `source`. `SavedQueryService` calls them in the order validate, then `requireWritable`, then resolve.
- `SavedQueryEntity` keeps JSON payloads as `String` columns typed `SqlTypes.JSON` and converts them with
  the application's Jackson 3 mapper (`SavedQueryJson`), because Hibernate's JSON format mapper is
  Jackson 2 and cannot read the DSL's `FilterNode` wiring.
- `AuditActivityService` narrows `SavedQuery` feed rows with a Criteria `EXISTS` subquery against the
  saved query's current state.

What saved queries lack is a concurrency check: their `generation` is a change counter, not a token.

## Goals / Non-Goals

**Goals:**

- Mirror saved queries closely enough that one reading of `SavedQueryAccess` explains dashboard
  authorization, with no new privilege and no new config property.
- Make `PUT` safe under concurrent editors without holding a database lock while inline bodies are
  translated.
- Keep the audit trail complete from the first dashboard row.

**Non-Goals:**

- A dashboard snapshot endpoint, catalog export/import of dashboards, and a "rows confined by policy" flag
  on query results — each is a separate change.
- Any server-side execution, result caching or saved-query dereferencing for a dashboard.
- Enforcing widget non-overlap or checking `value_column` / `view` against the query.

## Decisions

### D1. Widgets are one JSON document on the dashboard row

`dashboard.widgets` is a `jsonb` column holding the widget array, mapped as `String` with
`@JdbcTypeCode(SqlTypes.JSON)` and converted by the application mapper, as `SavedQueryEntity.query` is.

*Rejected: a `dashboard_widget` child table.* Admin writes the whole document on every save, and nothing
queries a single widget. A child table would turn each replace into a diff-and-reconcile of rows, add a
second audited entity whose activities would need parent links, and record a widget-level trail nobody
asked for. The spec states that a widget change is one `Update` of the dashboard.

*Rejected: mapping `widgets` as a typed `List<WidgetModel>` through Hibernate's JSON mapper.* An inline
`query` is a `StructuredQuery`, which only the Jackson 3 application mapper reads correctly. Hibernate's
Jackson 2 mapper would bind it without error and lose the `FilterNode` subtype wiring, which is the reason
saved queries hold their payloads as text.

`time` is a separate `jsonb` column (`time_intent`) with the same mapping. `visibility` and `compare` are
text columns; the latter is named `comparison`, so the column name is not a word SQL might reserve. The table has a `CHECK` on `visibility` and on `compare`, following the convention that an
enum-like text column carries one. Both are `@Enumerated(EnumType.STRING)`, so the columns hold the Java
constant names (`PRIVATE`, `PREVIOUS_PERIOD`), as the existing enum columns do, and the `CHECK`s list those
names; the hyphenated wire tokens exist only in JSON. Because every JSON payload is a `String`, nothing on
the entity needs `@EqualsAndHashCode` to avoid the phantom-`Update` hazard that JSON value classes on
audited entities carry.

### D2. Optimistic locking with `@Version` on `generation`

`generation` is a `@Version Long`. After binding, `replace` runs the spec's ordered checks:

1. `requireAnyWriteAuthority` (`403`);
2. loads the row through the visibility lookup (`404`);
3. checks write authority for the stored and requested visibility (`403`);
4. compares the request's `generation` to the loaded one and throws `DashboardConflictException` on a
   mismatch (`409`);
5. validates the body, including the ownerless-withdrawal refusal (`422`), then translates each inline body
   (`422` / the query surface's status);
6. applies the fields and calls `saveAndFlush`. Hibernate's `UPDATE … WHERE generation = ?` closes the race
   between steps 4 and 6.

A replace keeps the stored owner, so it can never need `requireAttributable`'s `500`. The only way it can
produce an ownerless `private` row is by withdrawing a `public` dashboard whose `owner_id` is null, and
that is a `422` about the dashboard, not a `500` about the caller. `create` alone checks `requireAttributable`.

`@Version` also versions the delete (`DELETE … WHERE id = ? AND generation = ?`). This is why the spec says
a delete and a replace that race do not both succeed, and why the loser answers `409`. `delete` therefore
calls `delete` and then `flush`.

A concurrent winner surfaces as `ObjectOptimisticLockingFailureException`. That is a `DataAccessException`,
which `DefaultExceptionHandler` answers today as `503 postgres_unavailable`. `DashboardService` catches it
around the flushing call, inside the transaction, and rethrows `DashboardConflictException`, which answers
`409 dashboard_conflict`. The explicit flush is what makes the failure surface there rather than at
commit, outside the method. No global handler maps the locking exception, so a later `@Version` entity is
not mislabelled as a dashboard conflict. This is accepted,
not worked around: an unversioned delete would need a path outside the persistence context, which Envers
would not see.

*Rejected: `SELECT … FOR UPDATE` in the lookup.* It would hold a row lock across inline-body translation,
which plans SQL against the catalog, and it serialises readers that only want the lock for writing.

*Rejected: a conditional `@Modifying` update.* A bulk update bypasses the persistence context, so Envers
would record nothing. The project's audit rules forbid that on an audited write path.

*Rejected: `If-Match` / `ETag`, alone or alongside the body field.* One mechanism is enough, and a body
field needs no header plumbing through a client or CORS.

Because `@Version` treats a null version as "new", `generation` is a `Long` wrapper and is left null until
first persist, so `save` on a fresh entity inserts it rather than merging it. The first stored value is `0`
under Hibernate's default. The spec promises only that every mutation increments it.

### D3. A `DashboardAccess` beside `SavedQueryAccess`, sharing its privileges

`service/dashboard/DashboardAccess` holds the same four decisions as `SavedQueryAccess` (current author,
`requireAttributable`, `requireAnyWriteAuthority`, `requireWritable(visibility)`), with `PRIVATE` mapped to
`SAVED_QUERY_WRITE` and `PUBLIC` to `SAVED_QUERY_PUBLISH`. It adds `permissions(entity)` and
`listPermissions()`, which compute the response flags from those same methods so they cannot drift from
enforcement.

The flag rules follow directly. `write` requires the privilege for the stored visibility. `publish`
requires `SAVED_QUERY_WRITE` and `SAVED_QUERY_PUBLISH` together. `create_private` requires
`SAVED_QUERY_WRITE`, and `create_public` requires `SAVED_QUERY_PUBLISH`. In `none` mode all of them are
`true`.

*Rejected: generalising `SavedQueryAccess` over a "scoped object" interface.* It has two callers, and its
Javadoc is written about saved queries. A small sibling that delegates claim lookups is cheaper to read
than an abstraction serving two users.

*Rejected: a `DASHBOARD_PUBLISH` privilege.* See the spec's reasoning: publishing a dashboard publishes its
inline query bodies.

The flags call `requireAttributable`'s predicate as well, so `create_private` and `publish` are `false`
where the unresolved-principal refusal would fire. They do not run body validation: the list projection
does not load `widgets`, and re-translating every inline body to compute `write` would make a read as
expensive as a replace. The spec says so.

The visibility lookup is the saved-query one, `visibility = PUBLIC OR owner_id = :author`, with no
privilege term. Every caller that reaches it may see `public` dashboards: reads are gated on
`SAVED_QUERY_READ`, and a write passes `requireAnyWriteAuthority` first, so its caller holds
`SAVED_QUERY_PUBLISH` or `SAVED_QUERY_WRITE`, both of which now imply `SAVED_QUERY_READ`. Before those
edges, a caller mapped to `SAVED_QUERY_WRITE` alone got `403` for a `public` id and `404` for an absent one,
which told it which ids existed, even though it could not read either. Saved queries had the same gap, and
no bundle exposed it, because every bundle holding `SAVED_QUERY_WRITE` also held `SAVED_QUERY_READ`.

### D3a. Both saved-query writes imply `SAVED_QUERY_READ`, and `personal` is the owner's alone

Each edge is one more entry in `PrivilegeImplications.DIRECT`. Closure happens once, when a mapping is
resolved: `RolesMappingParser` closes each role's set, and `SecurityClaimsExtractor.hasPrivilege` then
tests exact containment. Every authority source (OIDC, API key, the defaults mapping) goes through the
parser, and `SystemIdentity` closes its own set. So the edges reach every `hasPrivilege(SAVED_QUERY_READ)`
call, and saved queries pick them up with no code change of their own. The three bundles that declared
both `SAVED_QUERY_READ` and `SAVED_QUERY_WRITE` now declare only the write, because bundles are stored
minimally and `PrivilegeModelTest` asserts the minimal form. Their closures are unchanged.

The edges do not fit the existing admission rule. A write-only or publish-only caller does not already
see `common` saved queries or `public` dashboards, so neither edge is disclosure-monotone. The
authentication delta therefore adds a second ground: a privilege that edits documents by whole-document
replace implies the read privilege for those documents, because writing a document presupposes reading
it, provided no read check consults the granting privilege. The ground is stated as policy, not as
"cannot be exercised without": create and delete need no read, and a saved-query replace carries no
generation. Editing is what a write grant is for, and a dashboard replace cannot even be formed without
the `generation` a read returned.

The proviso is what makes the edge safe for every combination of privileges, not only for the write and
the read held together. If no read check consults the write, then for any set of privileges a caller
holds, adding the write adds exactly the read to what it may see. `TABLE_WRITE` edits no document,
because inserts append, so the ground does not reach it.

Before this change the proviso failed for `SAVED_QUERY_PUBLISH`: `SavedQueryAccess.maySeeEverySavedQuery`
let a publisher past the owner rule on the saved-query revision endpoint and in the activity feed, so a
`FULL_ADMIN` caller read other users' `personal` history there while `GET /{id}` on the same row answered
`404`. The maintainers treat a `personal` saved query as visible to its owner alone, so that bypass was a
disclosure in its own right, not a property of the edge. It is removed: the revision endpoint applies the
live read's predicate, and both audit narrowings apply theirs, with no privilege term. With it gone, no
read check consults either write privilege, and both edges pass the proviso.

A deleted saved query or dashboard has no current row to resolve scope and owner from. Its activities and
snapshots were visible to publishers only; without the bypass they are visible to no caller in `oidc`
mode. *Rejected for now: resolving a deleted resource's visibility from its last audited state.* It would
keep a shared resource's deletion visible to readers and a personal one's to its former owner, at the
cost of a subquery on the audit mirror; the maintainers chose the simpler rule first.

The edges widen one kind of deployment: a `roles-mapping` that names `SAVED_QUERY_WRITE` or
`SAVED_QUERY_PUBLISH` without `SAVED_QUERY_READ` starts reading `common` saved queries and `public`
dashboards. It also starts reading the revisions of every currently-`common` saved query at
`/v1/saved-queries/{id}/revision/{rev}`, because `requireCurrentlyVisible` resolves visibility against
current state, including revisions from before the query was published. That is what every
`SAVED_QUERY_READ` holder sees today, the intended reading of such a mapping. The bypass removal narrows
`FULL_ADMIN`. The proposal states both as behaviour changes.

Ownership of a `private` dashboard is enforced by the lookup predicate (`owner_id = :author`), not by
`requireWritable`. This is why a publisher may withdraw another caller's `public` dashboard: the result is
the owner's `private` dashboard, which the publisher then cannot see. Saved-query demotion behaves the same.

### D4. Inline-body checks reuse the saved-query validator and resolver through body-level entry points

Both classes' public APIs take a `SavedQueryRequestDto` today. The change adds body-level entry points:

- the validator, taking `(StructuredQuery query, String sql, SavedQueryChart chart, int extraBytes)`: the
  one-body rule, blank-`sql` normalisation, parameter-expression check, and the size cap from
  `saved-queries.max-body-bytes`. The cap is applied to the body and chart plus `extraBytes`. The
  saved-query DTO method passes its serialized `time` as `extraBytes`, so a saved query's cap still counts
  `time` as `docs/configuration.md` documents. A dashboard passes `0`, because its inline queries have no
  `time`;
- the validator, taking the raw `time` `JsonNode`: it binds the intent as `timeIntent(SavedQueryRequestDto)`
  does today, and then runs the private `validateTime` rules (mode required, period pattern,
  `from <= to`). Today those rules run only inside `validate()`, so binding alone would accept intents a
  saved query rejects;
- the resolver, taking `(StructuredQuery query, String sql)` and returning the derived source after
  translation.

The existing DTO-taking methods delegate to the new ones, so saved queries keep their behaviour and their
tests. The dashboard validator calls the validator entry point for every inline widget during envelope
validation. The dashboard service calls the resolver entry point for every inline widget after authorization
and the generation check. Both prefix the message with `widget <id>: `.

A translation rejection cannot be rethrown as its own type with a new message, because some types, such as
`IllegalFinalException`, build their own message. Rethrowing a supertype instead would turn
`400 illegal_final` into `bad_request`. The service therefore wraps the rejection in
`InlineQueryRejectedException(widgetId, cause)`. Its `DefaultExceptionHandler` method looks up the
handler for the cause with Spring's `ExceptionHandlerMethodResolver` over `DefaultExceptionHandler` itself
(`resolveMethodByThrowable`), and invokes it with the request and the cause. Every handler there returns
an `ErrorView` carrying its own status and `error`. The wrapper's method returns a `ResponseEntity` with
that status and a new `ErrorView` whose message is prefixed. The status and code are then exactly the
query surface's, whatever the type, and no handler is duplicated.

An inline query's `result_view` defaults to `table` when absent, the default `SavedQueryService` applies.

A dashboard's whole `widgets` document is also capped at 1 MiB serialized (`422`). The per-body cap alone
would allow 100 bodies each at the saved-query maximum: a row of several MiB, re-planned on every replace
and copied into `dashboard_aud` on every revision. The 1 MiB is a constant, like the 100-widget and
1000-character bounds, not a configuration property.

`@Valid` cascades from `DashboardRequestDto.widgets` → `WidgetDto.inline_query` → `InlineQueryDto.query`,
so a structured body missing `mode` is the same `400 bad_request` the query surface gives, as it is for a
saved query.

*Rejected: a separate dashboard copy of the validation.* "Executable as stored" is defined once. A second
copy would drift the first time the translator gains a rule.

### D5. Request DTOs follow the saved-query rejection pattern

`DashboardRequestDto` declares `id`, `owner_id`, `owner_email`, `created_at`, `updated_at` and
`permissions` as hidden `JsonNode`/`String` members. Supplying any of them, on create or on replace, is
therefore a `422` raised by the validator, not a silent drop and not a `400` from strict binding.
`InlineQueryDto` declares no such members. A saved query's `time`, `tag`, `scope`, `source` or owner
copied into a widget is an unrecognised field, which is the strict reader's `400` naming it, as the spec
states. `generation` is declared as
`JsonNode` and is checked by operation: on create its presence is rejected, and on replace it is required
and must be an integral number. One DTO serves both operations, and the controller tells the validator
which one is running.

`time` is a raw `JsonNode` passed to the D4 time-intent entry point, so a malformed or invalid intent is
`422`. A blank `saved_query_id` and a blank inline `sql` are normalised to absent before storage, as
`SavedQueryBodyValidator.sqlBody` normalises a saved query's `sql`. `name` is stored trimmed, as
`SavedQueryService` stores it. A `generation` member that is `null` binds to `NullNode` and is rejected
like an absent one. One that is a `DoubleNode` (`3.0`), a `TextNode`, or a `BigIntegerNode` outside
`Long` range is rejected too: only `JsonNode.canConvertToLong()` on an integral node passes.

The integer members — `row_limit` and the four `layout` coordinates — are declared as `JsonNode` as well,
and `DashboardValidator` requires `isIntegralNumber()` before it checks bounds. Neither mapper turns off
Jackson 3's scalar coercions: `ACCEPT_FLOAT_AS_INT` and `ALLOW_COERCION_OF_SCALARS` are on. Binding to
`int` would therefore store `2.5` as `2` and `"5"` as `5` rather than refuse them, and changing either
feature on the shared request mapper would change every other endpoint. A non-integer is therefore the
same `422` as an out-of-range value, one rule for one member.

The enum members bind to their enums, so an unknown token stays the service-wide `400 bad_request`. Plain
enum binding also accepts a position, though: `"visibility": 1` and even the string `"1"` both become
`PUBLIC`, and `@JsonProperty` names on the constants do not prevent it (`"compare": 1` binds to
`PREVIOUS_PERIOD`). Each dashboard enum is therefore written as a token enum:
- a `@JsonValue` token for output, where `WRITE_ENUMS_TO_LOWERCASE` would otherwise write
  `previous_period`, not the hyphenated wire form;
- a `@JsonCreator` taking a `String` for input. It matches the tokens case-insensitively, as
  `ACCEPT_CASE_INSENSITIVE_ENUMS` does, and throws `IllegalArgumentException` on anything else.

A number reaches the creator as text, matches no token, and fails binding as a
`ValueInstantiationException`, which `handleBadRequest` answers `400 bad_request` like any other binding
failure.

The rule applies to the four dashboard enums and to an inline query's `result_view`. The saved-query
`SavedQueryResultView` binds by position the same way, so the inline query declares `result_view` as a
dashboard-local token enum, `InlineResultView` (`table`, `chart`). It is also the stored model, so the
`widgets` document holds the same tokens a saved query's `result_view` does. Saved queries themselves are left as they are: their `scope` and `result_view` accept a position
today, and changing that is a saved-query change, not part of this one. `SavedQueryChart` holds only
strings, so it has nothing to bind by position.

### D6. Listing is one query, projected in the mapper

`DashboardRepository.findVisible(author, securityEnabled)` returns `private` rows owned by the caller plus
every `public` row, ordered by `updated_at DESC`. When security
is disabled it returns all rows. The list loads only the list-item columns, plus `owner_id` for the
`publish` flag, through a JPQL constructor projection, so `widgets` is never read for a listing. A row is
bounded by the spec's limits: 100 widgets, a 1 MiB `widgets` document,
1000-character text members, and each inline body under the saved-query body cap.

### D7. Audit: `@Audited`, `dashboard_aud`, and the saved-query narrowing predicate

`DashboardEntity` is `@Audited`. The migration creates `dashboard_aud` mirroring every audited column
(`rev`, `revtype`, then the entity columns), modelled on the `saved_query_aud` table.

`dashboard_aud` has no `generation` column. Envers leaves a `@Version` property out of the audit table
by default (`org.hibernate.envers.do_not_audit_optimistic_locking_field`), and the spec excludes it from
the preserved state.

*Rejected: auditing `generation` by setting that property to `false`.* A past token is useless to a client,
because a replace needs the current one. It is also redundant with the revision order, since each
mutation adds one and is audited. And the setting is global, so it would also apply to every later
`@Version` entity. Saved queries do audit their `generation`, but there it is a plain change counter, not
a version field. `ActivityResourceType`
gains `Dashboard`. `AuditResourceMapper` maps `DashboardEntity` to it with the id as resource id, and
`parentOf` returns empty. `AuditActivityService` adds a second `EXISTS` clause, the dashboard twin of the
saved-query one:

`resourceType <> Dashboard OR EXISTS (dashboard d WHERE d.id = resourceId AND (d.visibility = PUBLIC OR d.owner_id = :author))`

It is skipped only in `none` mode; no privilege skips it (D3a). A deleted dashboard has no current row, so
its activities are visible to no one, as a deleted saved query's are.

### D8. Error codes

| Condition | Status | `error` |
|---|---|---|
| stale or concurrently-lost `generation` | 409 | `dashboard_conflict` (new handler for `DashboardConflictException`; the service translates the optimistic-lock exception) |
| dashboard absent or invisible | 404 | `not_found` (new `DashboardNotFoundException`, same handler family as `SavedQueryNotFoundException`) |
| envelope rule violated | 422 | `validation_error` (new `DashboardValidationException`, or reuse of the saved-query one if its handler is generic) |
| private create with unresolved principal | 500 | `principal_unavailable` (reuse `UnattributableSavedQueryException`'s handler with a dashboard message) |
| withdrawal of an ownerless public dashboard | 422 | `validation_error` (`DashboardValidationException`, message naming the missing owner) |
| inline body untranslatable | the query surface's, usually 400 | its code, usually `bad_request` (`InlineQueryRejectedException` wrapping the cause) |
| write refused on a visible dashboard | 403 | `forbidden` (`AccessDeniedException`) |

## Risks / Trade-offs

- **[A public dashboard's inline bodies are shown to every reader unredacted]** → This is the same widening
  a `common` saved query already makes, and it is bounded the same way: only a `SAVED_QUERY_PUBLISH` holder
  can publish. Executing a body stays subject to the viewer's own grants and row policies.
- **[An editor whose catalog view is narrower than the author's cannot save the dashboard]** → Every replace
  revalidates every inline body against the editor's view, so a publisher without access to an entity named
  in another author's inline query gets `400` on any edit. This follows "executable as stored" and keeps a
  body the editor could not run from being re-saved under the editor's name. The spec states it as a
  scenario, so Admin can explain the error.
- **[A `saved_query_id` can point at nothing]** → This is intended. Admin already renders the widget as
  unavailable, and a check at write time would be stale a moment later anyway.
- **[`@Version` changes the first `generation` value from saved queries' convention]** → Nothing depends on
  the starting value. Tests assert the increment, not an absolute number.
- **[Unbounded dashboard count per caller]** → Same as saved queries today. The spec says paging, not field
  stripping, is the bound if one is needed.

## Verified behaviour

The design relies on these facts about existing code and libraries. Each was checked against source or by
running it, not assumed:

| Claim | How it was checked |
|---|---|
| Neither JSON mapper disables scalar coercion: `2.5` and `2.0` bind to `int` as `2`, and `"5"` as `5` | Run against Jackson 3.1.7 with the `JsonMapperConfiguration` builder and the strict rebuild |
| A plain enum binds `1` and `"1"` by position, and `@JsonProperty` constant names do not prevent it | Same run |
| A `String`-only `@JsonCreator` refuses `1`, `"pie"` and `true` with `ValueInstantiationException`, and `@JsonValue` writes the token unchanged under `WRITE_ENUMS_TO_LOWERCASE` | Same run |
| A `JsonNode` member is Java `null` when absent and `NullNode` when `null`; `3.0` is a `DoubleNode` whose `canConvertToLong()` is `true`, so `isIntegralNumber()` is needed as well | Same run |
| An unknown member is `UnrecognizedPropertyException`, which `handleBadRequest` names | Same run; `DefaultExceptionHandler.unknownProperty` |
| Envers leaves a `@Version` field out of the audit table by default | `hibernate-envers` 7.2 `Configuration`: `DO_NOT_AUDIT_OPTIMISTIC_LOCKING_FIELD` defaults to `true` |
| A null wrapper `@Version` makes `save` persist rather than merge an entity with an assigned id | `spring-data-jpa` 4.0 `JpaMetamodelEntityInformation.isNew` |
| A repository `delete` of a managed entity is `EntityManager.remove`, so the versioned `DELETE` runs at flush | `spring-data-jpa` 4.0 `SimpleJpaRepository.doDelete` |
| `ObjectOptimisticLockingFailureException` is a `DataAccessException`, answered today as `503 postgres_unavailable` | `DefaultExceptionHandler.handlePostgresUnavailable` |
| Every handler returns an `ErrorView` carrying its status, and Spring resolves a handler for a throwable | `DefaultExceptionHandler`; `ExceptionHandlerMethodResolver.resolveMethodByThrowable` in `spring-web` 7.0 |
| The privilege gate runs after body binding and bean validation | `PrivilegeAuthorizationManager` is an `AuthorizationManager<MethodInvocation>` |
| Privileges are closed when a mapping is parsed and then tested by exact containment | `RolesMappingParser` callers (`IdentityProviderUtils`, `ApiKeyProperties`, `ProviderRolePrivileges`); `SecurityClaimsExtractor.hasPrivilege` |
| The saved-query size cap counts `time` | `SavedQueryBodyValidator.validateSize` |
| Before this change, `SavedQueryAccess.maySeeEverySavedQuery` keyed on `SAVED_QUERY_PUBLISH` and was the only read check consulting a saved-query write privilege; the revision endpoint and both audit paths used it | `SavedQueryAccess`, `SavedQueryController.requireCurrentlyVisible`, `AuditActivityService.savedQueryNarrowing` |

Left to tests rather than checked here: the first `generation` value after persist, which the spec does not
fix, and the outcome of the race scenarios (task 6.3).

## Migration Plan

- One new PostgreSQL migration under `db/migration/postgres`, `V51__dashboard.sql`. It creates `dashboard` and `dashboard_aud`. Its comment is a
  single header line linking `openspec/specs/dashboards/spec.md`.
- No ClickHouse migration, no new credential, no new config key. `saved-queries.max-body-bytes` now also
  bounds each inline body, which `docs/configuration.md`'s existing row should say in its one-line
  description.
- Rollback: the endpoints are additive, and no existing client depends on them. Dropping the two tables
  reverses the schema.
