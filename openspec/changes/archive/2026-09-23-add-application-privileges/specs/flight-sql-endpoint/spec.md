## ADDED Requirements

### Requirement: The Flight surface authorizes per RPC by privilege

The Flight SQL surface SHALL authorize each RPC by the privilege that RPC requires, rather than by
testing the caller for an application role. Statement execution (`GetFlightInfo` and `DoGet`) SHALL
require `TABLE_READ` on every table the statement resolves. The metadata RPCs (`GetTables`,
`GetCatalogs`, `GetDbSchemas`, `GetTableTypes`, `GetSqlInfo`) SHALL apply the same per-table filter
as the HTTP discovery surface and SHALL omit tables the caller may not read.

Because Flight is an execution protocol, it SHALL NOT advertise a table that a subsequent `DoGet`
would refuse: `GetTables` SHALL be filtered by `TABLE_READ`, not by `TABLE_GET`, even though it
returns schema information. A caller holding only `TABLE_GET` on a table therefore sees that table
over HTTP and not over Flight.

The gate SHALL NOT test the caller for an application role or for any particular privilege, because a
caller authorized entirely by per-table grants holds none globally and is exactly the caller those grants
exist for. It SHALL reject only a caller that could match nothing at all — one holding no privilege
**and** carrying no provider role — rather than serving it an empty catalog on every call. Refusal SHALL
use the Flight surface's existing error mapping.

#### Scenario: A grant-only caller reaches Flight

- **GIVEN** a credential that maps to no privilege but carries a provider role named in a table's
  `read` list
- **WHEN** it authenticates to the Flight surface
- **THEN** it is admitted, and `GetTables` returns that table

#### Scenario: A caller that can match nothing is refused

- **WHEN** a credential that maps to no privilege and carries no provider role authenticates
- **THEN** the call is refused, because no table is reachable for it by any route

#### Scenario: Metadata is filtered to readable tables

- **GIVEN** two tables, one of which grants the caller's provider role `read`
- **WHEN** the caller calls `GetTables`
- **THEN** only the granted table is returned

#### Scenario: A statement over an unreadable table is refused

- **WHEN** the caller executes a statement naming the other table
- **THEN** the statement fails as referring to an unknown entity, without revealing that it exists

#### Scenario: A get-only grant does not appear over Flight

- **GIVEN** a table whose `get` list contains the caller's provider role and whose `read` list does
  not
- **WHEN** the caller calls `GetTables`
- **THEN** that table is absent, while `GET /v1/tables/{name}` over HTTP returns it

#### Scenario: Flight honours per-table grants for an API-key caller

- **GIVEN** a DIAL API key whose Core role appears in a table's `read` list, and which holds no
  global read privilege
- **WHEN** the key is used to execute a statement over Flight against that table
- **THEN** the statement resolves and executes, whichever surface first introspected the key

## MODIFIED Requirements

### Requirement: Flight authentication mirrors service security mode
When `security.mode=oidc`, the Flight endpoint SHALL require authentication on every RPC — the
`Handshake` RPC included — and SHALL accept three client-presented credential forms, plus the marked
bearer envelope specified further below that the endpoint itself hands out in answer to a `Basic`
handshake:

1. `authorization: Bearer <token>`, validated by the same multi-issuer JWT/introspection machinery as
   HTTP — except for a value carrying the `adas-api-key:` marker, which is routed to the API-key path
   instead and never presented to an identity provider;
2. `Api-Key: <key>`, validated by the same DIAL Core introspection with the same cache;
3. `authorization: Basic base64(<username>:<password>)`, where `<username>` SHALL be the fixed sentinel
   `api-key` (compared case-insensitively) and `<password>` SHALL be a DIAL API key, validated by that
   same Core introspection and cache.

Precedence SHALL be `Bearer` → `Api-Key` → `Basic`: a call carrying more than one form SHALL be
authenticated by the first present in that order and the others SHALL NOT be introspected. A `Basic`
credential SHALL be validated on **every** call that presents it, exactly as an `Api-Key` header is, and
the endpoint SHALL NOT issue a session token: no server-side credential exists that could outlive the
API key's revocation, and a revoked key SHALL stop working within the same
`security.api-key.cache-ttl-seconds` bound that governs the `Api-Key` header path.

A `Basic` credential whose username is anything other than the sentinel SHALL be rejected with
UNAUTHENTICATED and a message naming the accepted username, and SHALL NOT be introspected — it SHALL NOT
be silently ignored in favour of another credential form or of the password alone, because the
authenticated principal comes from introspecting the key and a caller who supplied a different username
must not be left believing they authenticated as that identity. A `Basic` value that is not valid base64,
or that decodes to a string containing no colon, SHALL be rejected with UNAUTHENTICATED without
disclosing which part was malformed.

So that clients which authenticate once per connection can work at all, a call authenticated by the
`Basic` form SHALL carry the response header `authorization: Bearer adas-api-key:<the same API key>`,
and the endpoint SHALL accept `authorization: Bearer adas-api-key:<key>` as a credential form equivalent
to `Api-Key: <key>`, routed to the same introspection path and stripped of the marker before validation.
This value SHALL NOT be a minted token: it carries the presented API key unchanged, has no independent
lifetime, is never stored server-side, and is re-introspected on every call that presents it. Absent
this response, Arrow-derived clients that authenticate with username/password retain no credential for
subsequent calls, and the Arrow Flight SQL ODBC drivers construct a gRPC metadata entry with a
zero-length key and abort the client process.

Unauthenticated or rejected credentials SHALL yield gRPC UNAUTHENTICATED; an unreachable introspection
backend SHALL yield UNAVAILABLE. When `security.mode=none`, the endpoint SHALL be open and SHALL accept
no credential form, matching HTTP behavior — no authenticator is registered, so `Basic` is neither
required nor inspected and no `authorization` response header is emitted. Authorization SHALL mirror the
HTTP `/v1/**` rule for every credential form alike, and that rule is now authentication plus a per-RPC
privilege check rather than a role test. The gate SHALL admit any authenticated principal that could be
authorized for something — one holding some privilege, or carrying some raw provider role a grant could
name — and SHALL yield gRPC PERMISSION_DENIED only for a principal holding no privilege **and** carrying
no provider role, for which no table is reachable by any route. Each RPC is then authorized by the
privilege it requires (see **The Flight surface authorizes per RPC by privilege**).

Testing the principal for an application role here is what this replaces, and it had to go: a caller
authorized entirely by per-table grants holds no global privilege, and it is exactly the caller — a BI
tool presenting a DIAL API key — that those grants exist for.

Because every credential form crosses the wire in a request header, an `oidc`-mode deployment SHALL
terminate TLS in front of the Flight listener.

#### Scenario: Missing credentials rejected in oidc mode
- **WHEN** `security.mode=oidc` and a client calls getFlightInfo with no auth headers
- **THEN** the call fails with UNAUTHENTICATED and the message names all three accepted credential forms

#### Scenario: Valid api-key accepted
- **WHEN** `security.mode=oidc` and a client presents an `Api-Key` header that DIAL Core resolves to a mapped role
- **THEN** the query proceeds

#### Scenario: Basic credential with the sentinel username is accepted
- **WHEN** `security.mode=oidc` and a client presents `authorization: Basic base64("api-key:<valid key>")` whose Core introspection resolves to a mapped role
- **THEN** the caller is authenticated as the key's principal with that principal's roles and the query proceeds, identically to presenting the same key in an `Api-Key` header

#### Scenario: Sentinel username is case-insensitive
- **WHEN** a client presents the same valid key with the username `API-KEY`, or `Api-Key`, instead of `api-key`
- **THEN** the credential is accepted exactly as the lowercase form is

#### Scenario: Wrong username is rejected, not ignored
- **WHEN** a client presents `authorization: Basic base64("someone@example.com:<valid key>")`
- **THEN** the call fails with UNAUTHENTICATED, the message names `api-key` as the accepted username, the key is not introspected, and the caller is not authenticated as the key's principal

#### Scenario: Invalid key in a Basic credential fails like the header path
- **WHEN** a client presents `authorization: Basic base64("api-key:<key Core rejects>")`
- **THEN** the call fails with UNAUTHENTICATED with the same message the `Api-Key` header path produces for a rejected key

#### Scenario: Basic-authenticated principal without an application role is denied
- **WHEN** a client presents a valid key via `Basic` that Core resolves with **no roles at all**, so the
  principal holds no privilege and carries no provider role
- **THEN** the call fails with PERMISSION_DENIED, as it does for the same key in an `Api-Key` header
- **AND WHEN** the key instead resolves to roles that map to no privilege
- **THEN** the call is admitted, because one of those raw roles may name it in a table's grant list

#### Scenario: Bearer wins over Basic
- **WHEN** a call carries both `authorization: Bearer <valid token>` and a `Basic` credential
- **THEN** the bearer token authenticates the call and the API key in the `Basic` credential is not introspected

#### Scenario: Handshake answers a Basic credential with the marked bearer value
- **WHEN** a client authenticates the `Handshake` RPC with the `Basic` form
- **THEN** the response carries `authorization: Bearer adas-api-key:<the presented key>`, and a subsequent call presenting that value authenticates as the same principal

#### Scenario: Marked bearer value is revalidated on every call
- **WHEN** a client authenticates with `authorization: Bearer adas-api-key:<key>` and the key subsequently stops introspecting successfully
- **THEN** calls made after the cached introspection expires fail with UNAUTHENTICATED — no session credential keeps the connection working

#### Scenario: Basic-authenticated call does not weaken the introspection cache
- **WHEN** the same valid key is presented repeatedly via `Basic` within the cache TTL
- **THEN** DIAL Core is introspected at most once for those calls, sharing the cache entry the `Api-Key` header path would use for that key

#### Scenario: Open in none mode
- **WHEN** `security.mode=none`
- **THEN** Flight RPCs succeed without credentials, a `Basic` header is neither required nor inspected, and no `authorization` response header is emitted

### Requirement: Catalog metadata honors the caller's column visibility

Catalog RPCs SHALL resolve the catalog under the Flight caller's identity, exactly as the statement
path does, so a column hidden from the caller by sensitive-column filtering is absent from the
metadata as well as from query results. A caller without `TABLE_READ_SENSITIVE` on the table that owns the column SHALL NOT be able to learn
from
any catalog RPC that a `sensitive` column exists. When `security.mode=none` the filtering fails open,
matching the query path.

#### Scenario: Sensitive columns absent from a read-only caller's table schema

- **WHEN** `security.mode=oidc` and a caller holding `TABLE_READ` but not `TABLE_READ_SENSITIVE` calls
  `CommandGetTables` with
  `include_schema=true` for `dial_usage_log`, whose `jwt_claims` and `request_tags` columns are
  marked `sensitive`
- **THEN** the returned Arrow schema carries neither `jwt_claims` nor `request_tags`, while a
  caller entitled to that table's sensitive columns receives a schema for the same entity carrying both
