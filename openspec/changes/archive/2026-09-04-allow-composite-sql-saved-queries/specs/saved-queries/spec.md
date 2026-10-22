## MODIFIED Requirements

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
