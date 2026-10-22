## ADDED Requirements

### Requirement: Application privileges and bundles

The service SHALL define a closed set of **application privileges** as the unit of authorization,
each named `<OBJECT>_<VERB>` over the object classes `TABLE`, `REGISTRY`, `AUDIT`, `SAVED_QUERY` and
`DATA_SYNC`: `TABLE_LIST`, `TABLE_GET`, `TABLE_READ`, `TABLE_READ_SENSITIVE`, `TABLE_WRITE`,
`TABLE_MODIFY`, `TABLE_CREATE`, `TABLE_DELETE`, `TABLE_GRANT`, `REGISTRY_READ`, `REGISTRY_ADMIN`,
`AUDIT_READ`, `DATA_SYNC_OPERATE`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` and
`SAVED_QUERY_PUBLISH`. Six of them — `TABLE_LIST`, `TABLE_GET`, `TABLE_READ`,
`TABLE_READ_SENSITIVE`, `TABLE_WRITE` and `TABLE_MODIFY` — are **table-scoped**: they MAY be held
globally, over every table, or checked on a single table (see `table-access-control`), and a
caller's effective access SHALL be the union of the two. Five of those six SHALL be **grantable** on a
single table. `TABLE_LIST` SHALL NOT be: held globally it lists every table, and on one table it SHALL
be held only by implication from a grant that already shows the table. The remaining privileges are
global-only.
A `TABLE_` prefix SHALL NOT itself imply table scope.

The service SHALL additionally define **bundles** — reserved names that expand to a privilege set:

| Bundle | Privileges |
|---|---|
| `FULL_ADMIN` | every privilege |
| `READ_ONLY_ADMIN` | `TABLE_READ`, `REGISTRY_READ`, `AUDIT_READ`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` |
| `READ_ONLY_ANALYTIC` | `TABLE_READ`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` |
| `READ_ONLY_SENSITIVE_ANALYTIC` | `TABLE_READ_SENSITIVE`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` |

A bundle SHALL be expanded when configuration is parsed, and SHALL NOT be observable by any
authorization check thereafter: no endpoint, filter or evaluator SHALL branch on a bundle name.
`FULL_ADMIN` and `READ_ONLY_ADMIN` SHALL expand to exactly the authority the corresponding
application role carries today, with **one declared exception**: `GET /v1/tables/{name}/access` now
requires `TABLE_GRANT`, which `READ_ONLY_ADMIN` does not hold, so a caller that could read grant
lists under that bundle receives `403`. The grant lists disclose which provider roles exist and what
each may do, which is not a catalog read (see `table-access-control`). Apart from that endpoint, an
unmodified `roles-mapping` changes nothing observable. Bundle names SHALL NOT be valid grantees in a
table's access lists, which name raw provider roles.

Every `roles-mapping` (`security.defaults.roles-mapping`, `providers.<name>.roles-mapping`,
`security.api-key.roles-mapping`) SHALL accept bundle names and privilege names in the same list.
A token that is neither SHALL **fail startup** with an error naming the offending value and the
property that carried it; it SHALL NOT be silently dropped.

A bundle's declared set SHALL be **stored minimally and closed under implication** when it is
expanded, so the table above lists what each bundle declares rather than everything it confers:
`READ_ONLY_ANALYTIC` declares `TABLE_READ` and thereby also confers `TABLE_GET` and `TABLE_LIST`.
Storing the closure instead would duplicate the ladder in four places and let a bundle drift out of
step with it.

#### Scenario: A bundle expands to its privileges, closed under implication
- **WHEN** a provider role is mapped to `READ_ONLY_ANALYTIC` and a caller presents a token carrying
  that role
- **THEN** the caller holds `TABLE_READ`, `SAVED_QUERY_READ` and `SAVED_QUERY_WRITE` as declared,
  plus `TABLE_GET` and `TABLE_LIST` by implication, and no other privilege

#### Scenario: A read of grant lists is the one thing READ_ONLY_ADMIN loses
- **WHEN** a caller mapped to `READ_ONLY_ADMIN` reads `GET /v1/tables/{name}/access`
- **THEN** the response is `403 forbidden`, while every catalog, registry, audit and saved-query read
  that bundle reached before still succeeds

#### Scenario: Bundles and privileges mix in one mapping
- **WHEN** a provider role is mapped to `["TABLE_GET", "SAVED_QUERY_READ"]` and another to
  `["FULL_ADMIN"]`
- **THEN** both mappings are accepted and each caller holds exactly the union of what its roles name

#### Scenario: An unknown mapping value fails startup
- **WHEN** a `roles-mapping` value names neither a bundle nor a privilege
- **THEN** startup fails with an error naming the value and the property, and the service does not
  begin serving

#### Scenario: A caller with only per-table grants is authenticated
- **WHEN** a valid token maps to no privilege at all
- **THEN** the caller is **authenticated**, holds no global privilege, and MAY still be authorized
  for a specific table by a matching per-table grant

### Requirement: The table read ladder and privilege implication

The four table read privileges SHALL form a ladder — `TABLE_LIST`, then `TABLE_GET`, then
`TABLE_READ`, then `TABLE_READ_SENSITIVE` — in which holding a rung SHALL confer every rung below
it. `TABLE_READ_SENSITIVE` SHALL be a rung and not a modifier: a caller holding it SHALL be able to
read, and SHALL NOT require `TABLE_READ` to be granted alongside.

Implication SHALL be admitted only where it is **disclosure-monotone** — the implied privilege
reveals a strict subset of what the granting privilege already reveals — and SHALL be transitive.
The complete set of direct implications SHALL be: `TABLE_GET → TABLE_LIST`,
`TABLE_READ → TABLE_GET`, `TABLE_READ_SENSITIVE → TABLE_READ`, and `TABLE_MODIFY → TABLE_GET`.
`TABLE_WRITE` SHALL imply nothing: a caller holding only `TABLE_WRITE` on a table SHALL NOT be able
to list it, read its schema, or read its rows. In particular the service SHALL NOT admit
`TABLE_WRITE → TABLE_READ`.

`SAVED_QUERY_PUBLISH` SHALL imply nothing. A fifth edge `SAVED_QUERY_PUBLISH → SAVED_QUERY_READ` fails
the admission test on inspection: the claim that publishing already sees every saved query holds only for
the revision endpoint and the audit feed, while `GET /v1/saved-queries/{id}` and the listing resolve
visibility through a repository predicate with no such bypass. Admitting it would let a `PUBLISH`-only
caller read another user's `personal` saved query at `GET /{id}/revision/{rev}` while `GET /{id}` on the
same row still answered `404` — a disclosure, and an incoherent one.

Nor SHALL it imply `SAVED_QUERY_WRITE`, for the reason `TABLE_WRITE` implies nothing pointed the other
way: the two name different scopes rather than two rungs of one, and a write privilege is not a
disclosure. What makes `SAVED_QUERY_PUBLISH` authorize something on its own is that each saved-query
write endpoint defers its check to the point where the request body's `scope` is known (see
`saved-queries`).

Implication SHALL be **derived when a check runs**, never materialised into stored grant lists: a
grant list SHALL return exactly the grantees that were written to it.

#### Scenario: The top rung reads without a separate read grant
- **WHEN** a caller holds `TABLE_READ_SENSITIVE` on a table and nothing else
- **THEN** the caller can list the table, read its schema, and read all its rows including sensitive
  columns

#### Scenario: Write confers no read
- **WHEN** a caller holds only `TABLE_WRITE` on a table
- **THEN** the table is absent from catalog listings and query discovery for that caller, and any
  attempt to read it fails as though the table did not exist

#### Scenario: Publishing confers no read and no personal write
- **WHEN** a caller holds only `SAVED_QUERY_PUBLISH`
- **THEN** it may create, replace and delete `common` saved queries, while a read endpoint is refused
  `403 forbidden` because that requires `SAVED_QUERY_READ`, and a write at `personal` scope is refused
  because that requires `SAVED_QUERY_WRITE`

#### Scenario: Modify confers schema visibility
- **WHEN** a caller holds only `TABLE_MODIFY` on a table
- **THEN** the caller can read that table's definition and column inventory, but not its rows

#### Scenario: A stored grant list is returned as written
- **WHEN** a grant list names a role for `read` only, and the caller then reads the access lists
- **THEN** the response lists that role under `read` alone, with no derived entry under `get`

### Requirement: Privileges that permit self-elevation are gated at the grant level

A privilege SHALL be evaluated on two axes: what it **discloses**, and what it lets its holder
**change in order to disclose more**. Where a privilege would let its holder widen its own access,
the widening operation SHALL require `TABLE_GRANT` rather than the privilege that performs the
change.

Accordingly, changing a column's `sensitive` flag — in either direction — SHALL require
`TABLE_GRANT`. `TABLE_MODIFY` SHALL permit every other metadata and schema change but SHALL NOT
permit setting or clearing `sensitive`. A request that attempts it SHALL be rejected with `403` and
the `ErrorView` error code `forbidden`.

#### Scenario: A modify grantee cannot declassify a column
- **WHEN** a caller holding `TABLE_MODIFY` on a table submits a schema patch clearing `sensitive` on
  one of its columns
- **THEN** the request is rejected with `403` and error code `forbidden`, and the column remains
  sensitive

#### Scenario: A modify grantee cannot classify a column either
- **WHEN** the same caller submits a schema patch setting `sensitive` on a column
- **THEN** the request is rejected with `403` and error code `forbidden`

#### Scenario: A grant holder may change the flag
- **WHEN** a caller holding `TABLE_GRANT` submits a schema patch changing `sensitive`
- **THEN** the change is applied, subject to the existing restriction that a cursor column may not
  be made sensitive

### Requirement: Endpoint authorization by privilege

In `oidc` mode every functional endpoint under `/v1` SHALL require a named privilege, and the URL
layer SHALL NOT grant access on the strength of an application role. The URL layer SHALL require
authentication only; the privilege SHALL be checked per endpoint, and per table where the required
privilege is table-scoped. Any request that reaches no privilege check SHALL be denied.

Table-scoped checks SHALL resolve the table from the request. Where a request resolves several
tables, the caller SHALL hold the required privilege on **every** one of them.

Refusal SHALL preserve non-disclosure: where the missing privilege is a read privilege on a table the
caller named, the response SHALL report the table as absent (`404 not_found` for a catalog read, an
unknown-entity `400 bad_request` on the query path) rather than `403`. Where the missing privilege is
global or concerns a write, the response SHALL be `403 forbidden`.

There is one carve-out, and it is not an exception to the rule but a consequence of it. Where the table
is named by a **stored declaration** rather than by the request — the pipeline-scoped data plane, whose
read source comes from the pipeline the caller addressed — refusal SHALL be `403` and MAY name the table
(`pipeline_access_denied`, see `enrichment-runtime`). Reporting that table absent would disclose nothing
the caller does not already have: it addressed a pipeline it is entitled to see, and a pipeline read
already exposes its declaration including the read source's name. The rule protects the caller's
*guesses* about what exists, and there is no guess here.

#### Scenario: A read endpoint requires its privilege rather than an application role
- **WHEN** a caller holding no privilege at all lists saved queries
- **THEN** the service responds `403` with the `ErrorView` body and error code `forbidden`

#### Scenario: A filtered collection answers empty rather than forbidden
- **WHEN** that same caller requests a catalog listing, whose required privilege is table-scoped
- **THEN** the service responds `200` with no entries rather than `403`, because refusing a filtered
  collection would itself disclose that the caller is entitled to nothing (see `table-management`)

#### Scenario: An unreadable table is reported absent, not forbidden
- **WHEN** a caller holding `TABLE_READ` on one table requests another table it has no read
  privilege on
- **THEN** the service responds `404 not_found`, and the response does not reveal that the table
  exists

#### Scenario: A multi-table query requires the privilege on every table
- **WHEN** a caller submits a query resolving two tables and holds `TABLE_READ` on only one
- **THEN** the query is rejected as referring to an unknown entity with `400 bad_request`

### Requirement: Authorization coverage is fail-closed

Because the URL layer no longer authorizes by application role, an endpoint that carries no
privilege check SHALL be unreachable rather than open. The service SHALL deny any request to a path
that matches no explicit authorization rule, and the build SHALL verify that **every** request
mapping under `/v1` declares exactly one authorization requirement. A request mapping that declares
none, or more than one, SHALL fail the build.

An endpoint that carries no tenant data at all MAY declare that it requires **authentication only**,
which counts as its one requirement. This SHALL be an explicit declaration rather than an absence, so
it is visible in review and an unannotated mapping still fails the build. The only endpoint that
qualifies is `GET /v1/queries/functions`, which describes the query language (see
`query-schema-discovery`).

The declaration SHALL also be checked for coherence: a table-scoped privilege SHALL name the request
parameter carrying the table, or declare that the per-table decision is taken downstream where every
resolved table is in hand; a global-only privilege SHALL do neither.

#### Scenario: An endpoint with no privilege requirement fails the build
- **WHEN** a request mapping under `/v1` is added without a privilege requirement
- **THEN** the build fails, naming the offending handler

#### Scenario: An unmatched path is denied
- **WHEN** a request arrives for a `/v1` path that matches no authorization rule
- **THEN** the service denies it rather than serving it

### Requirement: Uniform caller identity across authentication entry points

Every authentication entry point — Bearer JWT, opaque-token introspection, DIAL API key over HTTP,
and DIAL API key over the Flight surface — SHALL construct the caller's authorities identically:
the expanded privilege set **and** the caller's raw provider role names under the reserved prefix.
An entry point SHALL NOT omit the raw provider roles, because per-table grants match on them.

Where two entry points share an introspection cache entry for the same credential, the cached
identity SHALL be independent of which surface populated it: the same API key SHALL yield the same
privileges and the same provider roles whether it is first presented over HTTP or over Flight.

#### Scenario: An API key yields the same identity on both surfaces
- **WHEN** the same DIAL API key is presented first over the Flight surface and then over HTTP
- **THEN** both requests resolve the same privileges and the same raw provider role names, and a
  per-table grant naming one of those roles authorizes both

#### Scenario: Flight carries provider roles
- **WHEN** a caller authenticates to the Flight surface with an API key whose Core role is listed in
  a table's `read` grant list, and holds no global read privilege
- **THEN** the caller can read that table over Flight

## MODIFIED Requirements

### Requirement: Security mode selection

The service SHALL support a configurable security mode via the `security.mode` property with two
values: `oidc` and `none`. The default SHALL be `none`. When the mode is `none`, all endpoints are
accessible without authentication and method-level authorization is not enforced. When the mode is
`oidc`, the service SHALL enforce Bearer-JWT authentication and privilege-based authorization as
specified below.

The mode SHALL be **resolved once**, from a single component, and every authorization decision —
the HTTP filter chain, method-level checks, the Flight authenticator, API-key authentication, the
per-table grant evaluator, the sensitive-column filter, saved-query ownership and rule
authorization — SHALL consult that one resolution. No component SHALL re-derive the mode from the
raw property.

A `security.mode` value that is neither `oidc` nor `none` SHALL **fail startup** with an error
naming the offending value. Matching SHALL be case-insensitive, so `OIDC` selects `oidc` rather
than leaving a partially-secured service in which one surface enforces authentication and another
does not.

#### Scenario: Default mode is none
- **WHEN** the service starts with no `security.mode` configured
- **THEN** the mode is `none` and all `/v1/**` endpoints respond without requiring a token

#### Scenario: OIDC mode requires configuration
- **WHEN** the service starts with `security.mode=oidc` and no valid identity provider configured
- **THEN** startup fails fast with an error indicating no identity providers are configured

#### Scenario: An unknown mode fails startup
- **WHEN** the service starts with `security.mode=disabled`
- **THEN** startup fails with an error naming the value, and the service does not begin serving

#### Scenario: Mode matching is case-insensitive
- **WHEN** the service starts with `security.mode=OIDC`
- **THEN** the service enforces `oidc` on every surface, including the Flight port and API-key
  authentication

### Requirement: Sensitive column authorization

A catalog column MAY be marked `sensitive`. In `oidc` mode the service SHALL restrict sensitive
columns to callers holding `TABLE_READ_SENSITIVE` **on the table that owns the column**: for any
caller lacking it, that table's sensitive columns SHALL be filtered out of the resolved query model
before it is used, so they are absent from schema discovery, from the catalog read surface, and
cannot be referenced in any clause of a structured or SQL query (`select`, `filter`, `group_by`,
`having`, `sort`). Ownership is decided per physical table, so within one flattened entity a caller
MAY see the source's sensitive columns and not an enrichment's, or the reverse.

A caller lacking the privilege SHALL NOT be able to distinguish a sensitive column from a
non-existent one **on the query surface**: referencing it SHALL fail as an ordinary unknown-field
validation error (HTTP 400), and no error path SHALL confirm the column's existence.

The table-management read surface SHALL remain deliberately unfiltered at column granularity, as
specified in `table-management`: it returns every column with its `sensitive` flag so an operator can
audit and manage sensitivity. `TABLE_GET` therefore exposes a sensitive column's **name and flag**,
and `TABLE_READ_SENSITIVE` governs only its **values**. Column names are consequently not treated as
secret anywhere: the audit trail and the registry surfaces name columns too.

When security is disabled (`security.mode=none`) the filter SHALL fail open: all columns, including
sensitive ones, SHALL be visible. The filter SHALL be applied at a single chokepoint in the
catalog-backed query model, so it SHALL cover schema discovery, `POST /v1/queries/execute`,
`POST /v1/queries/execute-sql`, the translation endpoints, the Flight SQL surface and the
empty-projection ("select all") expansion uniformly.

#### Scenario: Non-admin cannot see or reference a sensitive column

- **WHEN** a caller holding `TABLE_READ` but not `TABLE_READ_SENSITIVE` requests schema discovery for
  an entity that has a sensitive column, and separately submits a query that references that column
- **THEN** the sensitive column is absent from the discovery response, and the query is rejected with
  HTTP 400 as an unknown field — the response does not reveal that the column exists

#### Scenario: The catalog read surface still names the column

- **WHEN** the same caller reads that table's definition through `GET /v1/tables/{name}`
- **THEN** the sensitive column is present in the returned column inventory with `sensitive: true`,
  and only its values remain unreachable

#### Scenario: FULL_ADMIN sees and can reference a sensitive column

- **WHEN** a caller holding `TABLE_READ_SENSITIVE` on the owning table requests schema discovery and
  queries a sensitive column
- **THEN** the sensitive column appears in the discovery response and the query resolves and executes
  normally

#### Scenario: The privilege is evaluated per owning table

- **WHEN** a caller holds `TABLE_READ_SENSITIVE` on a source table and only `TABLE_READ` on one of
  its enrichments, and both carry sensitive columns
- **THEN** the flattened entity exposes the source's sensitive columns and omits the enrichment's

#### Scenario: Security disabled shows sensitive columns

- **WHEN** `security.mode=none` and a caller requests schema discovery or queries a sensitive column
- **THEN** the sensitive column is visible in discovery and resolvable in queries (the filter fails
  open when authentication is not enforced)

### Requirement: API-key authentication

In `oidc` mode, when `security.api-key.enabled` is `true`, the service SHALL additionally accept an
API key presented in the `Api-Key` request header. The service SHALL validate the key by
introspecting it against DIAL Core's `/v1/user/info` endpoint (base URL from
`security.api-key.core-url`), map the roles Core returns onto privileges through the same
`roles-mapping` parse a Bearer caller uses, and authorize the request under the same per-endpoint
privilege rules. An API-key caller whose Core roles map to **no** privilege SHALL be authenticated and
SHALL carry its raw Core role names, so a per-table grant naming one of them authorizes it — a
deployment whose keys are authorized entirely by grants is legitimate, and startup SHALL NOT require any
mapping to yield a privilege. When `security.api-key.enabled` is `false` (the default), the `Api-Key` header
SHALL be ignored. API-key authentication SHALL NOT be active in `none` mode.

#### Scenario: Valid project-key with a mapped role is authenticated
- **WHEN** a request to a protected `/v1/**` endpoint presents an `Api-Key` header whose Core
  introspection returns a `project` and a role that maps to a privilege
- **THEN** the service authenticates the caller and processes the request under that privilege

#### Scenario: API-key auth ignored when disabled
- **WHEN** `security.api-key.enabled` is `false` and a request presents only an `Api-Key` header
- **THEN** the header is not used for authentication and the request is treated as unauthenticated
  (rejected `401` on protected endpoints)

### Requirement: API-key key shapes

The service SHALL support the two response shapes DIAL Core returns from `/v1/user/info`. When the
response contains a non-blank `project`, the caller is a project-key caller and the returned roles
SHALL be mapped using `security.api-key.roles-mapping`. When the response contains `userClaims`, the
caller is a JWT-rooted per-request key; the principal and email SHALL be read from the claims named
by `security.defaults.principal-claim` and `security.defaults.email-claim`, and the returned roles
SHALL be mapped using `security.defaults.roles-mapping`. A response containing neither `project` nor
`userClaims`, or a `userClaims` response missing the configured principal claim, SHALL be rejected.

#### Scenario: Project-key roles use the api-key mapping
- **WHEN** Core returns `{"project":"acme","roles":["admin"]}` and
  `security.api-key.roles-mapping` maps `admin` to `FULL_ADMIN`
- **THEN** the caller is granted every privilege the `FULL_ADMIN` bundle expands to, and its raw Core
  role `admin` alongside them; no authority named after the bundle is ever granted

#### Scenario: JWT-rooted key roles use the defaults mapping
- **WHEN** Core returns a `userClaims` object carrying the configured principal claim and a role
  that `security.defaults.roles-mapping` maps to a privilege
- **THEN** the caller is authenticated with that privilege and the principal/email taken from the
  configured claims

#### Scenario: Malformed Core response is rejected
- **WHEN** Core returns a body containing neither `project` nor `userClaims`
- **THEN** the service responds `401` with the `ErrorView` body and error code `unauthorized`

### Requirement: API-key credential envelopes on the Flight surface

In `oidc` mode, when `security.api-key.enabled` is `true`, the Flight SQL surface SHALL accept a DIAL API
key in two additional envelopes beyond the `Api-Key` header:

- `authorization: Basic base64(<username>:<password>)`, where `<username>` SHALL be the fixed sentinel
  `api-key` (compared case-insensitively) and `<password>` SHALL be the API key. This exists for clients
  that can express only a username and a password and can set neither a custom header nor a bearer token.
- `authorization: Bearer adas-api-key:<key>`, a marked bearer value whose marker `adas-api-key:` SHALL be
  stripped before validation.

Both envelopes SHALL resolve to the **same** validation path as the `Api-Key` header — introspection
against DIAL Core's `/v1/user/info`, the same SHA-256-keyed introspection cache, the same mapping onto privileges — and SHALL produce, **on the Flight surface**, an authenticated
caller indistinguishable from one that presented the same key in an `Api-Key` header on that surface:
same principal, same email, same authorities. A caller in either envelope SHALL be authorized under the
same rules as any other Flight caller. Neither envelope SHALL be accepted when
`security.api-key.enabled` is `false`, and neither SHALL be active in `none` mode.

The equivalence is deliberately scoped to the Flight surface: the token the Flight path builds for a
validated key is not identical to the one the HTTP filter builds for that same key (the HTTP token
additionally carries the raw provider-role authorities and the project/claims identity used for
per-table grant matching).

These envelopes SHALL be confined to the Flight SQL surface. HTTP `/v1/**` SHALL continue to accept only
a `Bearer` token or an `Api-Key` header, and SHALL NOT accept a `Basic` credential.

The service SHALL NOT issue a credential of its own in exchange for either envelope. In particular the
value returned when a Flight `Basic` credential is accepted SHALL carry the presented API key unchanged,
SHALL have no independent lifetime, and SHALL NOT be recorded server-side — so that every call is
validated afresh and a revoked key ceases to work within the same
`security.api-key.cache-ttl-seconds` bound that governs the `Api-Key` header, with no session credential
able to outlive it.

A `Basic` credential whose username is not the sentinel SHALL be rejected with a message naming the
accepted username, and its password SHALL NOT be introspected. It SHALL NOT be ignored in favour of the
password alone: the authenticated principal comes from introspecting the key, so a caller who supplied a
different username must not be authenticated as the key's principal while believing they authenticated as
the identity they typed.

#### Scenario: Sentinel Basic credential authenticates as the key's principal
- **WHEN** a Flight call presents `authorization: Basic base64("api-key:<key>")` and Core introspection
  resolves that key to a principal with a mapped role
- **THEN** the caller is authenticated as that principal with that role, exactly as if the key had been
  presented in an `Api-Key` header

#### Scenario: Sentinel username is matched case-insensitively
- **WHEN** the username in the `Basic` credential is `API-KEY` or `Api-Key`
- **THEN** it is accepted as the sentinel

#### Scenario: Non-sentinel username is rejected without introspection
- **WHEN** a Flight call presents `authorization: Basic base64("someone@example.com:<valid key>")`
- **THEN** the call is rejected with a message naming `api-key` as the accepted username, DIAL Core is not
  called, and no caller is authenticated

#### Scenario: Marked bearer envelope is equivalent to the header
- **WHEN** a Flight call presents `authorization: Bearer adas-api-key:<key>`
- **THEN** the marker is stripped and the key is validated by the same introspection path and cache as the
  `Api-Key` header, yielding the same principal and authorities

#### Scenario: Envelopes share the introspection cache
- **WHEN** the same key is presented within the cache TTL in an `Api-Key` header, in a `Basic` credential,
  and in the marked bearer envelope
- **THEN** DIAL Core `/v1/user/info` is called at most once across those calls

#### Scenario: Revocation is not outlived
- **WHEN** a caller has authenticated with the `Basic` envelope and the key subsequently stops
  introspecting successfully
- **THEN** calls made after the cached introspection expires are rejected, because no server-issued
  credential exists that could keep the caller authenticated

#### Scenario: Basic is not accepted over HTTP
- **WHEN** a request to a protected `/v1/**` endpoint presents only `Authorization: Basic base64("api-key:<valid key>")`
- **THEN** the request is treated as unauthenticated and rejected `401` with the `ErrorView` body

#### Scenario: Envelopes are inert when API-key auth is disabled
- **WHEN** `security.api-key.enabled` is `false` and a Flight call presents the `Basic` envelope with an
  otherwise valid key
- **THEN** the call is rejected as unauthenticated

### Requirement: API-key failure modes

The service SHALL deny any request whose `Api-Key` is rejected by Core, yields a malformed
introspection response, or maps to no application authority. A key that is rejected or malformed
SHALL produce `401` with error code `unauthorized`; a key that authenticates but maps to no
application authority SHALL be denied `403` by the URL/method rules. When DIAL Core is unreachable or
times out, the service SHALL respond `503` with error code `service_unavailable`. All such responses
SHALL use the standard `ErrorView` contract.

#### Scenario: Invalid API key is rejected
- **WHEN** Core rejects the presented `Api-Key`
- **THEN** the service responds `401` with the `ErrorView` body and error code `unauthorized`

#### Scenario: Unmapped role is forbidden
- **WHEN** an `Api-Key` authenticates but none of its Core roles map to a privilege, and it requests an
  endpoint requiring a global privilege
- **THEN** the request is denied `403` with the `ErrorView` body
- **AND WHEN** the same caller requests a table its raw Core role is granted `read` on
- **THEN** the request succeeds; mapping to no privilege denies nothing a grant authorizes

#### Scenario: Core unavailable yields 503
- **WHEN** the introspection call to DIAL Core fails to connect or times out
- **THEN** the service responds `503` with the `ErrorView` body and error code `service_unavailable`

### Requirement: Custom role derivation for opaque providers

For opaque providers the service SHALL support selecting a named role converter instead of claim
paths, because some providers return no role information from their userinfo endpoint and roles have
to be derived from another source. When a provider's role-claims consist of exactly one entry prefixed
`fn:`, the service SHALL derive the caller's provider role names by invoking the named converter with
the caller's token and userinfo claims, rather than by reading claim paths. The derived role names
SHALL then be mapped onto privileges by the provider's (or the default) roles mapping, exactly
as claim-derived role names are.

The service SHALL provide the converter `fn:getGoogleWorkspaceGroups`, which resolves the caller's
direct Google Workspace group memberships and returns each group's id as a provider role name. It
requires the caller's email to be resolvable from the provider's email claims.

A provider naming a converter that does not exist SHALL fail startup.

#### Scenario: Google Workspace groups become provider roles
- **WHEN** an opaque provider sets its role-claims to `fn:getGoogleWorkspaceGroups` and a caller's token
  is introspected successfully
- **THEN** the caller's direct Google Workspace group ids are granted as provider role names and mapped
  onto privileges through the roles mapping

#### Scenario: Missing email fails the custom converter
- **WHEN** a provider selects `fn:getGoogleWorkspaceGroups` and the userinfo response carries none of the
  provider's email claims
- **THEN** the introspection fails and the request is rejected with `401` and the `ErrorView` body

#### Scenario: Unknown converter name fails startup
- **WHEN** the service starts in `oidc` mode with a provider whose role-claims name a `fn:` converter that
  is not registered
- **THEN** startup fails fast with an error naming the unknown converter

### Requirement: Opaque token introspection

In `oidc` mode the service SHALL also accept opaque (non-JWT) bearer tokens for providers configured
with a userinfo endpoint. The service SHALL determine per request whether a token is a JWT or opaque,
and for an opaque token SHALL introspect it by calling a configured provider's userinfo endpoint with
the token; the returned claims supply the principal, email, and provider roles, which are mapped onto
privileges as for JWTs. A token that no configured provider can introspect SHALL be rejected.

#### Scenario: Valid opaque token is accepted
- **WHEN** a request presents an opaque bearer token that a configured provider's userinfo endpoint
  accepts and returns claims for
- **THEN** the service authenticates the caller using the userinfo claims and processes the request

#### Scenario: Unintrospectable opaque token is rejected
- **WHEN** a request presents an opaque bearer token that no configured provider's userinfo endpoint accepts
- **THEN** the service responds `401` with the `ErrorView` body

## REMOVED Requirements

### Requirement: Role-based authorization

**Reason**: Authorization is no longer decided by the two application roles. The mapping step it
described survives, but it now resolves provider roles to **privileges** (directly, or through a
bundle name), as specified in **Application privileges and bundles**.

**Migration**: None for deployments that leave `roles-mapping` unchanged — `FULL_ADMIN` and
`READ_ONLY_ADMIN` remain accepted as bundle names expanding to the same authority. A deployment that
wants narrower access maps the provider role to a privilege list instead. The retention of raw
provider role names as prefixed authorities is unchanged and is restated in **Uniform caller
identity across authentication entry points**.

### Requirement: Mutating endpoints require FULL_ADMIN

**Reason**: Both halves of this requirement are superseded. `FULL_ADMIN` is no longer the unit of
authorization for catalog mutation, and read endpoints are no longer reachable by "any application
role" — every endpoint now names a privilege, and read endpoints are additionally filtered per
table.

**Migration**: The per-endpoint privilege mapping is specified in **Endpoint authorization by
privilege** here and in each surface's own capability. `FULL_ADMIN` continues to satisfy every one of
them. The saved-query scope rule moves to the `saved-queries` capability as
`SAVED_QUERY_WRITE` / `SAVED_QUERY_PUBLISH`, keeping the same body-dependent evaluation and the same
`403 forbidden` shape.
