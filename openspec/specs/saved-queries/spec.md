# saved-queries Specification

## Purpose

Make an authored Query Builder state a first-class, addressable object: named, tagged, reusable, and —
for the definitions a team standardises on — shared. A saved query stores authored **intent** only: a
name, an optional description and tag, either a structured query or SQL, the author's time intent, and
how they last rendered the result. It stores no catalog data and nothing a run produces.

**A body is stored as authored, whatever it contains.** The metadata database that holds saved queries
sits inside the same trusted perimeter as the queried data store — the same operational controls, the
same access boundary — so a value carried in a body crosses no perimeter and this capability defends no
write-side data boundary. A literal bound to a `sensitive` column is therefore persisted like any other
literal, and stored bodies are returned unredacted. The author's own identity — their principal, and
their email for display — is likewise recorded, because a shared object has to say who authored it, and
that is the same pair the platform's other services record for audit authorship.

What this capability does not relax is **who may reference what**. A caller's body is resolved against
their own view of the entity, so a `sensitive` column they are not entitled to see is unresolvable to
them here exactly as it is on the query endpoints, and indistinguishable from a column that does not
exist; see [authentication](../authentication/spec.md).

What a saved query *does* widen is narrower and deliberate: a `common` body is readable by every caller
holding an application role and is returned unredacted, so a literal a `FULL_ADMIN` bound to a `sensitive`
column reaches callers who could not have read that column themselves. The bound on that is the write rule
— `common` writes require `FULL_ADMIN`, the one role entitled to `sensitive` columns — so such a value is
published deliberately by a caller entitled to it, into the scope whose meaning is "shared with everyone".
A `personal` saved query is readable only by its owner and discloses nothing.

Two scopes carry the sharing model. `personal` saved queries are visible only to their owner;
`common` ones are readable by any caller with an application role and writable only by `FULL_ADMIN`.
A saved query the caller may not see is reported as absent rather than forbidden, so the existence of
another person's saved query is never disclosed — the row-level counterpart of the sensitive-column
indistinguishability rule in [authentication](../authentication/spec.md).

Every stored body is validated by the same translation its execute endpoint would run, so a saved query
is always executable exactly as stored, and execution itself stays on the
[structured-query](../structured-query/spec.md) and [rest-sql-endpoint](../rest-sql-endpoint/spec.md)
surfaces.
## Requirements
### Requirement: Saved query storage model

The system SHALL persist a saved query as a named, reusable Query Builder state holding **authored
intent only**. A saved query SHALL consist of:

- `id` — server-assigned, opaque, prefixed `sq_`, and not guessable from any other saved query's id;
- `name` — required, non-blank after trimming;
- `description` — optional free text;
- `tag` — optional, at most one;
- `scope` — `personal` or `common`;
- `owner_id` — server-assigned from the calling identity, `null` when there is no authenticated caller;
- `owner_email` — the author's email at creation, server-assigned, for display only and often absent;
- `source` — a non-empty array of every entity the body reads from, server-derived (see **Saved query
  body validation**), sorted alphabetically. A `query`-kind body's `source` always has exactly one
  element, because the structured-query DSL has no join or derived-table representation; a `sql`-kind
  body's `source` has one element per entity referenced, directly or through a derived table,
  subquery, or join;
- exactly one of `query` (a structured query body) or `sql` (a SQL string);
- `time` — the caller's *time intent*, or `null`;
- `result_view` — `table` or `chart`;
- `chart` — the chart configuration, or `null`;
- `generation` — a counter incremented on every mutation;
- `created_at` / `updated_at` — server-assigned timestamps.

The system SHALL NOT persist, as part of a saved query: catalog data (the entity's fields or available
functions), the editor mode the client used, any run outcome (`last_run_at`, run counts, result rows),
or a relative time period resolved into absolute instants.

Saved queries SHALL be stored in the metadata database (PostgreSQL), independently of the queried data
store, and deleting a catalog table SHALL NOT delete saved queries that reference it.

#### Scenario: A created saved query is returned with its server-assigned fields

- **WHEN** a caller creates a saved query with a name and a structured `query` body
- **THEN** the response carries a server-assigned `id` beginning with `sq_`, the derived `source`,
  `generation`, `created_at`, and `updated_at`, and the stored body is returned unchanged

#### Scenario: Server-assigned fields are rejected in a request body

- **WHEN** a create or replace request body contains `id`, `owner_id`, `owner_email`, `source`,
  `generation`, `created_at`, or `updated_at`
- **THEN** the response is `422` with machine code `validation_error` and the request is not persisted
  — the field is rejected, not silently ignored

#### Scenario: A blank name is rejected

- **WHEN** a create or replace request omits `name` or supplies a name that is blank after trimming
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: A relative period is stored as intent, not as instants

- **WHEN** a saved query is created with `time` of `{"mode":"relative","period":"last_2d"}`
- **THEN** reading it back returns the same relative `time`, and no absolute `from`/`to` instants are
  stored or returned

#### Scenario: A saved query survives the deletion of its source table

- **WHEN** a catalog table named in a saved query's `source` array is deleted
- **THEN** the saved query remains readable and its stored body is returned unchanged

#### Scenario: Source lists every entity for a sql-kind body joining more than one entity

- **WHEN** a caller creates a `sql`-kind saved query whose body joins two entities
- **THEN** the response is `201` and `source` is a two-element array containing both entities, sorted
  alphabetically

#### Scenario: Source is a single-element array for a single-entity body

- **WHEN** a caller creates a `query`-kind saved query, or a `sql`-kind saved query whose body reads
  from exactly one entity (directly, through a derived table, or through a window function)
- **THEN** `source` is an array with exactly that one entity as its only element

### Requirement: Exactly one query body

A saved query SHALL carry exactly one of `query` (structured) or `sql`. A request supplying both, or
neither, SHALL be rejected with `422` and machine code `validation_error`. The constraint SHALL also be
enforced by the storage schema, so no code path can persist a row carrying both or neither.

A blank `sql` string SHALL count as **absent** — both when the rule is evaluated and when the row is
written. The two SHALL NOT diverge: were validation to read `""` as absent while persistence stored it,
a request carrying `query` and a blank `sql` would pass the rule and then fail the storage constraint,
turning a `422` into a `500`.

#### Scenario: Both bodies are rejected

- **WHEN** a create request supplies both `query` and `sql`
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: Neither body is rejected

- **WHEN** a create request supplies neither `query` nor `sql`
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: A blank sql alongside a structured body is stored as a single body

- **WHEN** a create request supplies a `query` body and a `sql` of `""`
- **THEN** the response is `201`, the stored `sql` is `null`, and no storage-constraint failure surfaces
  as a `500`

### Requirement: Saved query body validation

A saved query SHALL be storable only if it is **executable as stored**. On create and on replace the
system SHALL validate the body against the **calling caller's** view of the catalog by performing the
same translation the corresponding translation-only endpoint performs, without executing the query and
without contacting the data store:

- a `query`-kind body SHALL be validated as `POST /v1/queries/translate` validates it — both the
  structural constraints that endpoint enforces on the request body before translating (notably the
  required `mode`) and translation itself (entity resolution, field allowlist, function catalog, mode
  coherence, limit governance) — and `source` is a one-element array holding the body's `entity`. The
  structured-query DSL has no wire representation for a join or a derived table, so a `query`-kind
  body SHALL remain single-entity;
- a `sql`-kind body SHALL be validated by the same parse-and-plan front end
  `POST /v1/queries/execute-sql` uses — the full pipeline that endpoint runs (screening, parsing,
  catalog-schema validation, and planning), which accepts a composite statement (a join, a derived
  table, a CTE, or a subquery) as well as a single-block one. The system SHALL NOT inspect SQL by
  pattern matching. `source` is derived by collecting every entity the planned statement reads from,
  including one reached only through a derived table, a scalar subquery, or an `IN`/`EXISTS` subquery,
  and is returned as that set, sorted alphabetically. Every entity the plan reads from SHALL be
  resolved against the caller's own view of the catalog exactly as a single-entity body's entity is,
  so a `sql`-kind body referencing an entity the caller cannot resolve fails the same way regardless
  of how many entities the rest of the statement reads from.
  `POST /v1/queries/translate-sql`, which produces a structured-query DSL document rather than
  validating a save, is unaffected by this requirement and continues to reject a composite statement.

`source` SHALL be derived from the body and SHALL NOT be accepted from the request.

A body that translation rejects SHALL be reported with **the same error the corresponding query endpoint
returns for the same body** — `400` with machine code `bad_request` — at **every** scope and for
**both** kinds. A body the service cannot translate is one it cannot execute on any endpoint, so storing
it would persist a query that can never run.

Reporting translation failures unchanged, rather than re-coding them, is deliberate on two counts:
saving a body and running it give one answer for one mistake, and nothing has to inspect an exception's
message to tell an unresolvable column apart from other translation failures — a distinction that would
be fragile exactly where it matters, since an unresolvable column is how a restricted column must appear.

The serialized size of `query`, `sql`, `time`, and `chart` together SHALL be bounded by a configurable
maximum; a body exceeding it SHALL be rejected with `422` and machine code `validation_error`.

#### Scenario: Source is derived from the body

- **WHEN** a caller creates a saved query whose structured body has `entity` of `dial_usage_log`
- **THEN** the stored and returned `source` is `["dial_usage_log"]`

#### Scenario: An unresolvable field is an ordinary bad request

- **WHEN** a create request's body references a field the caller cannot resolve
- **THEN** the response is `400` with machine code `bad_request`, and the response does not reveal
  whether the field does not exist or merely is not visible to this caller

#### Scenario: Untranslatable SQL is rejected at every scope

- **WHEN** a caller creates a `sql`-kind saved query whose SQL the translation front end rejects
- **THEN** the response is `400` with machine code `bad_request` — the same error
  `POST /v1/queries/execute-sql` gives for that SQL — whether the requested scope is `personal` or
  `common`, and nothing is persisted

#### Scenario: A structured body that would not execute is rejected

- **WHEN** a caller creates a `query`-kind saved query naming an entity that is not queryable
- **THEN** the response is `400` with machine code `bad_request` and nothing is persisted

#### Scenario: A structured body missing a required member is rejected

- **WHEN** a caller creates or replaces a `query`-kind saved query whose body omits `mode`
- **THEN** the response is `400` with machine code `bad_request` — the same error
  `POST /v1/queries/execute` gives for that body — and nothing is persisted or modified

#### Scenario: An oversized body is rejected

- **WHEN** a create request's serialized body exceeds the configured maximum size
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: A single-entity composite SQL body is saved with a one-element source

- **WHEN** a caller creates a `sql`-kind saved query whose body aggregates one entity into a derived
  table and computes window functions (a moving average, a running total) over that derived table
- **THEN** the response is `201`, `source` is a one-element array naming that one entity, and the
  stored `sql` is returned unchanged

#### Scenario: A join across two entities is saved with both entities in source

- **WHEN** a caller creates a `sql`-kind saved query whose body joins two queryable entities
- **THEN** the response is `201`, `source` is a two-element array with both entities sorted
  alphabetically, and the stored `sql` is returned unchanged

#### Scenario: A subquery against a different entity counts toward the entity set

- **WHEN** a caller creates a `sql`-kind saved query whose body filters one entity with an `IN`
  subquery against a different entity
- **THEN** the response is `201` and `source` is a two-element array containing both entities, exactly
  as for an explicit join of the two

#### Scenario: A composite body referencing an entity the caller cannot resolve is rejected

- **WHEN** a caller creates a `sql`-kind saved query that joins an entity the caller may query with one
  the caller cannot resolve
- **THEN** the response is `400` with machine code `bad_request`, and the response does not reveal
  whether the unresolvable entity does not exist or merely is not visible to this caller, and nothing
  is persisted

#### Scenario: A translate-sql request still rejects a composite statement

- **WHEN** a caller posts the same joining SQL from the previous scenario to
  `POST /v1/queries/translate-sql`
- **THEN** the response is still `400` with machine code `bad_request` — that endpoint's behavior is
  unchanged by this capability's widened save path

### Requirement: A saved query is not a template

A saved query SHALL be a complete query, not a parameterized template. The system SHALL NOT accept or
store declared parameters, and SHALL reject a body containing a runtime parameter expression with `422`
and machine code `validation_error`. The rejection SHALL be raised by this capability's own validation
before translation, so the caller receives a message about parameters being unsupported for saved
queries rather than an internal translator message.

This follows from execution: the query translator rejects parameter expressions, so a parameterized
body could not be executed on any endpoint and storing one would persist a query that can never run.

A saved query SHALL NOT execute itself. Execution remains on `POST /v1/queries/execute` and
`POST /v1/queries/execute-sql`, to which the client posts the stored body unchanged.

#### Scenario: A parameter expression in a structured body is rejected

- **WHEN** a create or replace request's `query` body contains a runtime parameter expression
- **THEN** the response is `422` with machine code `validation_error` and nothing is persisted

#### Scenario: Declared parameters are not an accepted field

- **WHEN** a create request supplies a `params` field
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: A stored body is accepted by the query surface unchanged

- **WHEN** a caller reads a saved query and posts its stored `query` body to the query surface with no
  substitution step
- **THEN** the query surface accepts it — the body is executable exactly as stored

#### Scenario: There is no execute endpoint for a saved query

- **WHEN** a caller posts to a saved query's id with an execute path segment
- **THEN** the response is `404` with machine code `not_found` — the capability exposes no execution
  endpoint

### Requirement: Time intent validation

A saved query's `time` SHALL be `null`, or a relative intent `{"mode":"relative","period":"<token>"}`
whose token is a bounded lowercase alphanumeric/underscore identifier, or an absolute intent
`{"mode":"absolute","from":<instant>,"to":<instant>}` whose `from` is not after its `to`. Any other
shape SHALL be rejected with `422` and machine code `validation_error`. The system SHALL NOT interpret
a relative period's meaning; it SHALL only bound its shape.

This SHALL hold for **every** malformed shape, including those a typed binding cannot represent at all —
an unrecognised `mode` token, a `from`/`to` that is not a parseable instant, or a `time` that is not an
object. Such a request SHALL NOT be answered with the `400 bad_request` a request-body binding failure
otherwise produces: the time-intent rule is this capability's, so its rejection is this capability's
`422`. Only the members this rule accepts SHALL be persisted; any other key inside `time` SHALL be
dropped rather than stored.

#### Scenario: A relative intent round-trips

- **WHEN** a saved query is created with `{"mode":"relative","period":"last_2d"}`
- **THEN** it is stored and returned unchanged

#### Scenario: An absolute intent with an inverted range is rejected

- **WHEN** a saved query is created with an absolute `time` whose `from` is after its `to`
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: A malformed time is rejected

- **WHEN** a saved query is created with a `time` that is neither `null` nor a well-formed relative or
  absolute intent
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: An unbindable time is rejected as a validation error, not a bad request

- **WHEN** a saved query is created with `time` of `{"mode":"weekly"}`, of `"yesterday"`, or of an
  absolute intent whose `from` is not a parseable instant
- **THEN** the response is `422` with machine code `validation_error`, not `400 bad_request`

### Requirement: Owner identity

`owner_id` SHALL be the calling identity's principal as resolved by the service's existing claim
extraction: the configured principal claim for a token-authenticated caller, the user id for a
JWT-rooted DIAL API key, and the project name for a plain DIAL project key. The system SHALL store it
opaquely and SHALL NOT parse or interpret it.

Consequently `personal` scope isolates per **credential identity**, not per person: saved queries
created under a shared project key are owned by that project and are `personal` to every holder of the
key.

An authenticated caller whose principal does not resolve — a token that carries no value for the
configured principal claim — SHALL NOT be treated as an unauthenticated caller. The owner filter SHALL
be dropped only because security is disabled, never because the principal came back absent: such a
caller owns no addressable row and SHALL therefore see an **empty** `personal` list, the same answer
the single-row lookup already gives it. The two paths SHALL apply the same predicate.

While security is enabled, the system SHALL **refuse** to store a `personal` saved query whose owner
would be null, on create and on any replace that would leave one so — including demoting a `common`
saved query whose owner is already null. Such a row would be permanently unreachable: visibility is
decided by `owner_id = :author`, which no caller can satisfy against a null owner, so nobody could
read, replace, or delete it, not even its creator. The refusal SHALL be reported as `500` with machine
code `principal_unavailable`, because the token authenticated — the service already accepted it — and a
principal claim that yields nothing is a deployment misconfiguration the caller cannot correct.

`common` saved queries SHALL NOT be refused on this ground. They remain readable by every caller
whatever their owner, so an unresolved principal costs only the "Saved by" attribution and not
reachability, and refusing them would break a deployment that curates shared definitions under tokens
carrying no principal claim.

#### Scenario: The principal becomes the owner

- **WHEN** an authenticated caller creates a saved query
- **THEN** the stored `owner_id` is that caller's principal, and the caller can subsequently read the
  row at `personal` scope

#### Scenario: A personal save is refused when the principal does not resolve

- **GIVEN** security is enabled
- **WHEN** a caller whose token carries no value for the configured principal claim creates a
  `personal` saved query
- **THEN** the response is `500` with machine code `principal_unavailable` and nothing is persisted

#### Scenario: A common save is allowed when the principal does not resolve

- **GIVEN** security is enabled
- **WHEN** a `FULL_ADMIN` caller whose token carries no value for the configured principal claim creates
  a `common` saved query
- **THEN** the saved query is created, with no `owner_id` and therefore no attributed author

#### Scenario: A caller with no resolvable principal sees no personal rows

- **GIVEN** security is enabled and other callers own `personal` saved queries
- **WHEN** a caller whose token carries no value for the configured principal claim lists saved queries
  at `personal` scope
- **THEN** the response is `200` with an empty list — not every caller's personal rows

### Requirement: Author email for display

Alongside `owner_id` the system SHALL record `owner_email`, the author's email as resolved from the
configured email claim at the moment the saved query was created, and SHALL return it on every read so
a client can show who saved a query.

`owner_email` SHALL be **display only**. Ownership, visibility, and every authorization decision SHALL
be made from `owner_id` alone. An email is not an identity: it changes on a name change or a domain
migration and may be reassigned to another person, so comparing it would decide access from a mutable,
recyclable value.

The system SHALL take `owner_email` from the caller's own credential, never from the request body — a
supplied `owner_email` SHALL be rejected like any other server-assigned field, so no caller can author
a saved query that displays another person as its author.

`owner_email` SHALL be preserved across a replace exactly as `owner_id` is, so the pair always describes
one person and an edit by a different administrator does not relabel a `common` saved query's author. It
is therefore a snapshot taken at creation, not a live lookup, and MAY be out of date.

`owner_email` MAY be absent — when security is disabled, when the token carries no email claim
(`security.require-email` defaults to false), or when the caller is a DIAL project key, which has no
person behind it. A client SHALL fall back rather than assume it is present.

#### Scenario: The author's email is recorded and returned

- **WHEN** an authenticated caller whose token carries an email creates a saved query
- **THEN** the response carries that email as `owner_email` beside the principal as `owner_id`

#### Scenario: A supplied author email is rejected

- **WHEN** a create or replace request body contains `owner_email`
- **THEN** the response is `422` with machine code `validation_error` and nothing is persisted

#### Scenario: A replace by another administrator does not relabel the author

- **GIVEN** a `common` saved query created by one administrator
- **WHEN** a different administrator replaces it
- **THEN** the stored and returned `owner_id` and `owner_email` are still the original author's

#### Scenario: The author email is absent when there is none to record

- **WHEN** a saved query is created with security disabled, or by a caller whose credential carries no
  email
- **THEN** `owner_email` is absent from the response rather than present with a placeholder

### Requirement: Fail open when security is disabled

In `none` security mode there is no authenticated caller. The system SHALL store `owner_id` as `null`,
SHALL apply no owner filter when reading, and SHALL permit every write including at `common` scope —
mirroring how the sensitive-column filter fails open in the same mode. The system SHALL NOT substitute
a synthetic owner identity, and SHALL NOT fail on the absent identity.

#### Scenario: Saved queries are open in none mode

- **WHEN** the service runs in `none` mode and a caller creates saved queries at both scopes
- **THEN** every create succeeds, the stored `owner_id` is `null`, and listing at either scope returns
  them all

### Requirement: List response size

The list response SHALL NOT be bounded by withholding fields. A projection would withhold nothing —
the same caller may read each row individually — so it is a bandwidth optimization, not access
control, and it costs the client a round trip for every row it selects: which editor a saved query
opens in is derived from its body, and its period and chart type come from `time` and `chart`.

The response size is therefore bounded only by the per-row body cap (see **Saved query body
validation**) multiplied by the number of saved queries visible at the requested scope, and the row
count is **not** currently bounded. Should that need bounding, it SHALL be bounded by limiting the
number of rows — paging the list, or capping saved queries per owner — and never by reintroducing
field projection.

#### Scenario: Every visible saved query is returned in full

- **WHEN** a caller with several saved queries at the requested scope lists them
- **THEN** every one is present, each carrying its complete body

### Requirement: Saved query API

The system SHALL expose saved queries under `/v1/saved-queries`, returning the `ErrorView` contract on
every failure:

- `GET /v1/saved-queries?scope=personal|common` — list, defaulting to `personal` when the parameter is
  absent, most recently updated first. The response SHALL be an object containing a list of the
  **full** saved queries — every field `GET /v1/saved-queries/{id}` returns, bodies included. There
  SHALL be one read shape: a list entry and a single read of the same saved query SHALL be identical.
  The response SHALL NOT be bounded by projecting fields away; see **List response size**.
- `POST /v1/saved-queries` — create.
- `GET /v1/saved-queries/{id}` — read one, returning the full saved query including its body.
- `PUT /v1/saved-queries/{id}` — full replace of the caller-supplied fields; `generation` SHALL be
  incremented and `updated_at` refreshed, while `id`, `owner_id`, `owner_email`, and `created_at`
  SHALL be preserved.
- `DELETE /v1/saved-queries/{id}` — delete, responding `204` on success.

An unrecognised `scope` query parameter SHALL be rejected with `422` and machine code
`validation_error`.

#### Scenario: List defaults to personal scope

- **WHEN** a caller requests `GET /v1/saved-queries` with no `scope` parameter
- **THEN** the response contains that caller's `personal` saved queries only

#### Scenario: List returns the full saved query

- **WHEN** a caller lists saved queries
- **THEN** each entry carries every field the single read returns, including the `query` or `sql` body
  and the `time` and `chart` values, and is byte-for-byte what `GET /v1/saved-queries/{id}` returns for
  that saved query

#### Scenario: List omits null members rather than emitting them

- **WHEN** a caller lists a saved query with no `sql`, no chart `y_field`, and no owner
- **THEN** those members are absent from the entry rather than present with a `null` value, exactly as
  on the single read

#### Scenario: List is ordered by most recently updated

- **GIVEN** two saved queries, the older of which is then replaced
- **WHEN** a caller lists saved queries
- **THEN** the replaced one precedes the other — the ordering is by update, not by creation

#### Scenario: Read returns the full saved query

- **WHEN** a caller reads a saved query it may read by id
- **THEN** the response carries the full object including its `query` or `sql` body

#### Scenario: Replace preserves identity and bumps generation

- **WHEN** a caller replaces a saved query it may write
- **THEN** the response carries the same `id`, `owner_id`, `owner_email`, and `created_at`, a refreshed `updated_at`,
  and a `generation` greater than the previous one

#### Scenario: Delete removes the saved query

- **WHEN** a caller deletes a saved query it may write
- **THEN** the response is `204` and a subsequent read of that id responds `404 not_found`

#### Scenario: An unknown id is not found

- **WHEN** a caller reads, replaces, or deletes an id that exists in no scope
- **THEN** the response is `404` with machine code `not_found`

#### Scenario: An unrecognised scope parameter is rejected

- **WHEN** a caller lists saved queries with `scope=everything`
- **THEN** the response is `422` with machine code `validation_error`

### Requirement: A sensitive value may be persisted in a saved query body

A saved query body SHALL be accepted regardless of whether it binds a literal value to a column the
catalog marks `sensitive`. The system SHALL NOT apply any sensitivity-derived restriction to the
saved-query write path, on create or on replace, for `query`-kind or `sql`-kind bodies. Such a body SHALL
be subject to exactly the same validation as any other body — the translation described by *Saved query
body validation* — and to no additional check.

The metadata database that holds saved queries is inside the same trusted perimeter as the queried data
store, so persisting a value read from that store copies nothing across a boundary. There is
consequently no machine code for a sensitivity-related write refusal: the system SHALL NOT emit
`sensitive_literal_not_allowed` or any successor code for this condition.

Sensitive-column **read** entitlement is unaffected. Whether a caller can resolve a `sensitive` column at
all SHALL continue to be decided by the caller's resolved view of the entity, as
[authentication](../authentication/spec.md) defines it. A caller who may not see a `sensitive` column
therefore still fails at field resolution with the ordinary unresolvable-field error, and the system SHALL
NOT distinguish a column that is restricted from one that is absent.

A stored body SHALL be returned exactly as persisted. The read path SHALL NOT redact, mask, or omit any
part of a body on account of sensitivity, for any caller permitted to read that saved query.

This has a consequence at `common` scope that the system SHALL exhibit deliberately rather than by
oversight. A `common` saved query is readable by every caller holding `SAVED_QUERY_READ`, so a
literal
bound to a `sensitive` column in a `common` body SHALL be returned verbatim **including to a caller who is
not entitled to that column** — a caller for whom the same column is unresolvable on the query surfaces.
The system SHALL NOT apply a sensitivity check when reading a saved query, and SHALL NOT hide a saved query
because its body references a `sensitive` column: `common` rows stay readable by any
`SAVED_QUERY_READ` holder and
column names stay visible, as this capability's visibility rules already require. The bound on the
disclosure is the write rule, not the read rule: writing `common` requires `SAVED_QUERY_PUBLISH`.

**That bound is now weaker, and stating it is the point.** While `common` writes required
`FULL_ADMIN` — the only role entitled to sensitive columns — the author of a `common` saved query was
necessarily someone who could already read every value it could bind. `SAVED_QUERY_PUBLISH` is
separately grantable and implies no table privilege, so a deployment that maps a curator to it alone
creates an author who may publish a literal it could not itself have read from the table. Separating
curation from PII entitlement is the whole purpose of the split (see **Scope-based visibility and
privilege authorization**), so this is a consequence to accept knowingly rather than a regression: an
operator granting `SAVED_QUERY_PUBLISH` without `TABLE_READ` and `TABLE_SENSITIVE` should understand that the bound
literal, not the column, is what travels. The remaining structural bound is unchanged and does the real
work: the stored body carries *bound literals*, never rows, and executing it is still subject to the
reader's own `TABLE_READ` on every resolved table. A `personal` saved query remains readable only by its
owner and therefore discloses nothing to anyone else.

#### Scenario: A literal bound to a sensitive column is accepted

- **WHEN** a caller holding `SAVED_QUERY_PUBLISH`, `TABLE_READ` and `TABLE_SENSITIVE` creates a saved query whose
  filter compares a `sensitive` column to a literal value
- **THEN** the saved query is created and the stored body retains the literal exactly as submitted

#### Scenario: A SQL body binding a sensitive literal is accepted

- **WHEN** the same caller creates a `sql`-kind saved query whose `WHERE` clause compares a
  `sensitive` column to a literal
- **THEN** the saved query is created

#### Scenario: A sensitive literal reached through a select alias is accepted

- **WHEN** the same caller creates an aggregate saved query that projects an aggregate over a
  `sensitive` column under an alias and compares that alias to a literal in `having`
- **THEN** the saved query is created

#### Scenario: Replacing a body with a sensitive literal is accepted

- **WHEN** a caller replaces an existing saved query with a body that binds a literal to a `sensitive`
  column
- **THEN** the replacement succeeds and the stored body is the submitted one

#### Scenario: A caller who cannot see a sensitive column is still stopped at resolution

- **WHEN** a caller who may not see `sensitive` columns creates a saved query referencing one
- **THEN** the response is `400` with machine code `bad_request` as an unresolvable field, and the
  response does not reveal whether the column exists

#### Scenario: A common sensitive literal is readable by a caller not entitled to the column

- **GIVEN** an entitled caller has created a `common` saved query whose filter binds a literal to a
  `sensitive` column
- **WHEN** a caller holding `SAVED_QUERY_READ` but not `TABLE_SENSITIVE` — one for whom that column
  is unresolvable
  on the query endpoints — reads that saved query, by id or in the `common` list
- **THEN** the read succeeds and the body is returned verbatim, literal included: the read path applies no
  sensitivity check, and the saved query is neither hidden nor refused on account of the column it
  references

#### Scenario: A stored body is returned without redaction

- **WHEN** any caller reads a saved query it is permitted to read
- **THEN** the stored `query` or `sql` is returned exactly as persisted, including any literal bound to a
  `sensitive` column

### Requirement: A relative window lives in the body, and `time` stays uninterpreted

A saved query body that expresses its own window relative to execution SHALL be validated, stored and
returned exactly as any other body is. A body MAY do so by calling the clock and offset functions the
query surfaces accept (see [structured-query](../structured-query/spec.md), "Relative time
expressions"). Such a body
passes the same translation its execute endpoint would run, and the stored text SHALL be returned
unchanged. This holds for both kinds — a `query` body carrying a `date_sub`/`now` expression and a
`sql` body carrying `now() - INTERVAL 2 HOUR` are the same case — because a saved `query` body's time
filter is baked-in literals for exactly the reason a saved `sql` body's is, and neither surface can be
the one that gets this.

The system SHALL NOT resolve such an expression at save time or at read time. It SHALL NOT rewrite the
body into absolute instants, SHALL NOT record the instant the body would have evaluated to, and SHALL
NOT derive or update `time` from the body. A saved query is stored as authored intent; folding the
clock into an instant at save time would destroy the one property the author was reaching for.

The `time` member is unchanged by this and gains no new mode: it remains the caller's time-picker
state, whose relative period this system does not interpret (see "Time intent validation"). What
changes is only that a body need no longer disagree with it — a caller may now write a body whose
window moves the way `time` says it should, and the two are consistent without the service mediating
between them. A body carrying a relative expression and a `time` of `null` SHALL be accepted, as SHALL
one carrying an absolute `time` alongside a relative body: this capability draws no coherence rule
between the two, because `time` describes an editor widget and the body describes the query, and
inventing agreement between them would mean interpreting the period after all.

#### Scenario: A relative SQL body round-trips unchanged

- **WHEN** a caller creates a `sql`-kind saved query whose SQL is
  `SELECT count(*) AS c FROM dial_usage_log WHERE request_time >= now() - INTERVAL 2 HOUR`
- **THEN** the response is `201`, `source` is `["dial_usage_log"]`, and reading it back returns the
  SQL byte-for-byte as authored — no instant substituted for the clock

#### Scenario: A relative structured body round-trips unchanged

- **WHEN** a caller creates a `query`-kind saved query whose filter compares `request_time` against
  `date_sub('hour', 2, now())`
- **THEN** the response is `201` and the stored body is returned unchanged

#### Scenario: The stored relative body is executable as stored

- **WHEN** a caller reads either saved query above and posts its stored body to its execute endpoint
  with no substitution step
- **THEN** the query surface accepts it and the window it covers is measured from that execution

#### Scenario: A relative body does not populate or alter `time`

- **WHEN** a caller creates a saved query with a relative body and no `time`
- **THEN** the stored `time` is absent, and the service has neither derived one from the body nor
  recorded the instant the body would have resolved to

### Requirement: Scope-based visibility and privilege authorization

A saved query SHALL have one of two scopes, with these rules in `oidc` mode:

- **`personal`** — readable only by the caller whose identity equals its `owner_id`. Reading a
  `personal` saved query SHALL require `SAVED_QUERY_READ`; creating, replacing, and deleting one
  SHALL require `SAVED_QUERY_WRITE`, and SHALL be permitted only on the caller's own rows.
- **`common`** — readable by any caller holding `SAVED_QUERY_READ`. Creating, replacing, or deleting
  a `common` saved query SHALL require `SAVED_QUERY_PUBLISH`; a caller without it SHALL receive
  `403 forbidden`.

`owner_id` SHALL be the author in both scopes. Changing a saved query's scope SHALL require
satisfying the write rule for **both** the stored scope and the requested scope, so a caller without
`SAVED_QUERY_PUBLISH` can neither promote its own row into `common` nor demote an existing `common`
row.

Because the deciding input is the request body's scope rather than the path, this authorization SHALL
be evaluated during request handling and SHALL surface as `403 forbidden` through the same
`ErrorView` contract as any other denial.

A saved query the caller may not read SHALL be indistinguishable from one that does not exist: every
read, replace, and delete addressed to such an id SHALL respond `404 not_found`, never `403`. The
visibility rule SHALL be applied as part of the lookup rather than as a check after fetching, so the
two cases share one code path.

A stored query body MAY name entities and columns the reading caller cannot otherwise reach. The
service SHALL NOT redact stored bodies: consistent with column names not being secret, a `common`
saved query is returned as written, and executing it remains subject to the reader's own
`TABLE_READ` on every resolved table.

#### Scenario: Personal rows are invisible to other callers

- **GIVEN** a `personal` saved query owned by one caller
- **WHEN** a different authenticated caller reads it by id
- **THEN** the response is `404` with machine code `not_found`, not `403`

#### Scenario: Personal rows of others are absent from the list

- **WHEN** a caller lists saved queries with `scope=personal`
- **THEN** the response contains only saved queries whose `owner_id` is that caller

#### Scenario: Common rows are readable with SAVED_QUERY_READ

- **WHEN** a caller holding `SAVED_QUERY_READ` lists or reads a `common` saved query
- **THEN** the response is `200` and includes it, with `owner_id` identifying its author

#### Scenario: Writing common requires SAVED_QUERY_PUBLISH

- **WHEN** a caller holding `SAVED_QUERY_WRITE` but not `SAVED_QUERY_PUBLISH` creates, replaces, or
  deletes a saved query with scope `common`
- **THEN** the response is `403` with machine code `forbidden`

#### Scenario: Reading without SAVED_QUERY_READ is forbidden

- **WHEN** a caller holding `TABLE_READ` but no saved-query privilege lists saved queries
- **THEN** the response is `403` with machine code `forbidden`

#### Scenario: A non-publisher cannot promote its own row to common

- **GIVEN** a `personal` saved query owned by a caller without `SAVED_QUERY_PUBLISH`
- **WHEN** that caller replaces it with a body whose scope is `common`
- **THEN** the response is `403` with machine code `forbidden` and the stored scope is unchanged

#### Scenario: Deleting another caller's personal row is not found

- **WHEN** a caller deletes a `personal` saved query it does not own
- **THEN** the response is `404` with machine code `not_found` and the saved query is not deleted

#### Scenario: A stored body naming unreadable tables is returned but not executable

- **GIVEN** a `common` saved query whose body names a table the reading caller cannot read
- **WHEN** that caller reads the saved query and then executes its body
- **THEN** the read returns the body as written, and the execution reports an unknown entity

### Requirement: A saved-query write is authorized against the scope in its body

`POST`, `PUT` and `DELETE` on `/v1/saved-queries` SHALL require `SAVED_QUERY_WRITE` for a
`personal`-scope write and `SAVED_QUERY_PUBLISH` for a `common`-scope one. A replace that changes
scope SHALL satisfy the requirement for **both** the stored and the requested scope, so a caller
holding only `SAVED_QUERY_WRITE` can neither promote its own row into `common` nor demote an existing
`common` row to hide it.

Because scope arrives in the request body, the endpoint gate cannot decide which privilege applies.
The declaration on each of the three mappings SHALL therefore be a **deferred** one, with the check
made at the single service-layer chokepoint that has the scope in hand. That chokepoint SHALL be
total: it decides both scopes, not only `common`. Deciding only `common` left two holes at once — an
authenticated caller holding no saved-query privilege could create a `personal` row, and a
`SAVED_QUERY_PUBLISH` holder was refused at a gate demanding `SAVED_QUERY_WRITE`, so the privilege
whose entire purpose is curating the shared catalogue authorized nothing on its own.

#### Scenario: A publish-only caller curates the shared catalogue

- **WHEN** a caller holding only `SAVED_QUERY_PUBLISH` creates, replaces or deletes a `common` saved
  query
- **THEN** the request succeeds — and the same caller is still refused `403 forbidden` on every read
  endpoint, because `SAVED_QUERY_PUBLISH` confers no read (see `authentication`)

#### Scenario: A publish-only caller may not write a personal query

- **WHEN** the same caller creates a `personal` saved query
- **THEN** the response is `403 forbidden`, naming `SAVED_QUERY_WRITE`

#### Scenario: A caller holding no saved-query privilege cannot write

- **WHEN** an authenticated caller holding neither privilege creates a `personal` saved query
- **THEN** the response is `403 forbidden`; the deferred gate admits the call and the chokepoint
  refuses it
