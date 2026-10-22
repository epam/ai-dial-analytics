## Why

The Query Builder UI lets a caller assemble a structured query, a time window, and a chart
configuration, but nothing of that survives the page. Every recurring question — "top 20 chats by
turn count", the Monday review deck — is re-authored from scratch, and there is no way for a team
to agree on one canonical definition of a metric.

Saved queries make an authored Query Builder state a first-class, addressable object: named,
tagged, reusable, and — for the definitions a team standardises on — shared. Doing this now, while
the query surface is still additive, keeps the storage model honest before ad-hoc client-side
persistence (localStorage, a dashboard blob) sets a shape we would have to live with.

## What Changes

- **New CRUD surface** `/v1/saved-queries` (list, create, read, replace, delete) under the existing
  `/v1` conventions and the `ErrorView` error contract.
- **A saved query stores authored intent only** — a name, an optional description and tag, the
  source entity, either a structured `query` body or a `sql` string (exactly one), the user's *time
  intent* (relative period or absolute range, or nothing), and the result-view / chart preferences.
  It stores no catalog data, nothing a run produces, and no personal data.
- **A saved query must be executable as stored.** Both body kinds are validated at save time by the
  translation the corresponding execute endpoint would perform — the structured body through
  `QueryTranslationService` (what `POST /v1/queries/translate` runs), the SQL body through
  `SqlQueryFrontEnd` (what `POST /v1/queries/translate-sql` runs). A body that will not translate is
  rejected rather than stored as a query that can never run.
- **No parameterization in this change.** A saved query is a complete, runnable query, not a
  template. Runtime parameter expressions are rejected at save time — the query translator rejects
  them at execution too, so a stored parameterized body could never have been run.
- **Two scopes.** `personal` rows are visible only to their owner; `common` rows are visible to any
  caller with an application role and may only be created, replaced, or deleted by `FULL_ADMIN`.
  A caller asking for another caller's `personal` row gets `404 not_found`, never `403` — the
  existence of another person's saved query is itself not disclosed.
- **A persistence-time privacy guard.** A saved query may not bind a literal to a column the catalog
  marks `sensitive`; such a request is rejected `422 sensitive_literal_not_allowed`. Saved queries
  live in the metadata database (PostgreSQL); personal data lives in the data store behind its own
  controls and retention. A literal like `user_email = 'someone@example.com'` written into a saved
  query copies a person out of that perimeter, and read-side access control does not undo the copy.
  With parameterization out of scope, such a query simply cannot be saved in this change.
- **Saved queries do not execute themselves.** Execution stays on `POST /v1/queries/execute` and
  `POST /v1/queries/execute-sql`; the client posts the stored body there unchanged. This keeps a run
  a read, keeps the saved row free of run state, and adds no new execution path to govern.
- **New Flyway migration** `V24__saved_query.sql` creating the `saved_query` table.
- **New configuration** for the stored-body size cap, documented in `docs/configuration.md`.

Not breaking: this is additive. No existing endpoint, contract, or migration changes.

## Capabilities

### New Capabilities

- `saved-queries`: named, reusable Query Builder state — its storage model, the `personal`/`common`
  scope and authorization rules, the persistence-time sensitive-literal rejection, and the
  `/v1/saved-queries` CRUD contract.

### Modified Capabilities

- `authentication`: the "Mutating endpoints require FULL_ADMIN" requirement enumerates which
  endpoints are gated on `FULL_ADMIN`. Writing a `common`-scope saved query joins that set, while
  writing a `personal`-scope saved query requires only an application role. The sensitive-column
  authorization requirement itself is unchanged — this change relies on it and adds a
  persistence-time counterpart, but does not alter how the filter behaves.

## Impact

**New code** (`com.epam.aidial.analytics`):

- `web/controller/query/SavedQueryController` — the five endpoints.
- `web/dto/query/saved/` — request/response DTOs (`SavedQueryDto`, `SavedQuerySummaryDto`,
  `SavedQueryRequestDto`, `SavedQueryTimeDto`, `SavedQueryChartDto`, `SavedQueryListDto`) plus the
  `scope`/`result_view` enums.
- `service/query/saved/` — `SavedQueryService`, `SavedQueryEntity`, `SavedQueryRepository`,
  `SavedQueryAccess` (scope/ownership predicate), `SavedQueryBodyValidator` (envelope shape,
  exactly-one-of, size cap), `SavedQueryBodyResolver` (translation-backed validation and `source`
  derivation), `SensitiveLiteralGuard`.
- `exception/` — `SavedQueryNotFoundException`, `SavedQueryValidationException`,
  `SensitiveLiteralNotAllowedException`.

**Modified code**:

- `web/handler/DefaultExceptionHandler` — `@ExceptionHandler`s for the three new exceptions
  (`404 not_found`, `422 validation_error`, `422 sensitive_literal_not_allowed`).
- `src/main/resources/application.yml` — the new size-cap binding (bare `key: ${ENV:default}`).
- `docs/configuration.md` — the authoritative description of that property.

**Reused, not modified**:

- `SecurityClaimsExtractor.getAuthor()` for the owner identity; `UserRole.FULL_ADMIN` /
  `@FullAdminOnly` for the `common` gate.
- `CatalogQueryService` to resolve a body against the caller's (already sensitive-filtered) view of the
  catalog, whose `baseSchema` carries the `sensitive` flags the literal guard reads.
- `QueryTranslationService.toSql` and `SqlQueryFrontEnd.translate` — the `query-translation-endpoint`
  capability's two halves — as the save-time validators for the two body kinds. No regex over SQL on a
  security boundary.

**Storage**: one new PostgreSQL table, `saved_query`. No ClickHouse change, no change to any existing
table. Flyway version `V24` — authored as `V23`, then renumbered when the default branch claimed that slot
during review (see the Migration Plan in `design.md`).

**Out of scope**: frontend work; run history; pin-to-dashboard; scheduling; **parameterized saved
queries**; and role-scoped sharing between `personal` and `common`.

Two of those are deliberately left room rather than merely deferred:

- *Parameters* would need a server-side binding path before they mean anything — the query translator
  rejects `param` expressions today, so a parameterized body cannot execute. When that lands, saved
  queries gain a `params` column and relax the rejection; nothing shipped here has to change shape.
- *Role-scoped sharing* would add a `shared_with` list of provider role names — the shape
  `table-access-control` already stores — widening the read predicate to *mine, OR common, OR shared
  with a role I hold*, without disturbing the two scopes shipped here.
