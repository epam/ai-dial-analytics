## ADDED Requirements

### Requirement: Encoded path separators and firewall refusals

The service SHALL accept a percent-encoded slash (`%2F`, `%2f`) and a percent-encoded percent sign (`%25`) inside a
path segment on every path it serves, in both security modes, where an earlier build refused the request `400`. A `/v1`
path variable carrying them SHALL be decoded once and answered as the endpoint answers that decoded value — an unknown
table named `a/b`, for one, is `404 not_found` — and the role segment of `/v1/tables/{name}/access/{role}` relies on
it (see `table-access-control`, "Role-keyed access API").

Which handler and which privilege check apply SHALL NOT change: no encoded character SHALL act as a path separator
when a request is matched to a handler or to an authentication rule, so a request under a public operational path
(such as `/actuator/health/**`), or under the API-docs paths when they are public, reaches only that path's handlers,
and a `/v1` request is authorized for the handler it matched. Every other rule of the request firewall SHALL stay: a
path carrying `;`, `\`, `//` (encoded or not), an encoded `.`, a NUL, carriage-return or line-feed character, U+2028 or
U+2029, or a segment that is exactly `.` or `..`, SHALL still be refused.

A request the service's request firewall refuses SHALL be answered as `ErrorView` `400 bad_request`, in both security
modes. A request the servlet container rejects before the service sees it (an encoded NUL, for one) gets the
container's own `400` response, which is not an `ErrorView`; the service promises only HTTP 400 for those.

#### Scenario: An encoded slash names an unknown resource

- **WHEN** a caller holding `TABLE_GET` globally reads `GET /v1/tables/a%2Fb`
- **THEN** the response is `404 not_found` for an unknown table, not `400`

#### Scenario: An encoded traversal stays under its public path

- **WHEN** an anonymous caller in `oidc` mode requests `/actuator/health/%2F..%2Fenv`
- **THEN** it is answered as an unknown health component is, `404`, and never by another endpoint or as a server
  error

#### Scenario: A firewall refusal is an ErrorView

- **WHEN** a caller requests a `/v1` path carrying `%3B` (an encoded `;`) in a path segment
- **THEN** the response is an `ErrorView` with `status` 400 and `error` `bad_request`

## MODIFIED Requirements

### Requirement: Application privileges and bundles

The service SHALL define a closed set of **application privileges** as the unit of authorization,
each named `<OBJECT>_<VERB>` over the object classes `TABLE`, `REGISTRY`, `AUDIT`, `SAVED_QUERY`,
`DATA_SYNC` and `SETTINGS`: `TABLE_LIST`, `TABLE_GET`, `TABLE_READ`, `TABLE_SENSITIVE`, `TABLE_WRITE`,
`TABLE_MODIFY`, `TABLE_CREATE`, `TABLE_DELETE`, `TABLE_GRANT`, `REGISTRY_READ`, `REGISTRY_ADMIN`,
`AUDIT_READ`, `DATA_SYNC_OPERATE`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE`,
`SAVED_QUERY_PUBLISH`, `SETTINGS_READ` and `SETTINGS_WRITE`. `SETTINGS_READ` reads and `SETTINGS_WRITE`
replaces the instance settings (see `instance-settings`). `TABLE_SENSITIVE` is the clearance (see **Sensitive column authorization**),
not a verb, and it replaces the former `TABLE_READ_SENSITIVE`. There SHALL be no
`TABLE_READ_SENSITIVE` privilege.

Six of them — `TABLE_LIST`, `TABLE_GET`, `TABLE_READ`, `TABLE_SENSITIVE`, `TABLE_WRITE` and
`TABLE_MODIFY` — are **table-scoped**: they MAY be held globally, over every table, or checked on a
single table (see `table-access-control`), and a caller's effective access SHALL be the union of the
two. Five of those six SHALL be **grantable** on a single table. `TABLE_LIST` SHALL NOT be: held
globally it lists every table, and on one table it SHALL be held only by implication from a grant that
already shows the table. The remaining privileges are global-only.
A `TABLE_` prefix SHALL NOT itself imply table scope.

The service SHALL additionally define **bundles** — reserved names that expand to a privilege set:

| Bundle | Privileges |
|---|---|
| `FULL_ADMIN` | every privilege |
| `READ_ONLY_ADMIN` | `TABLE_READ`, `REGISTRY_READ`, `AUDIT_READ`, `SAVED_QUERY_WRITE`, `SETTINGS_READ` |
| `READ_ONLY_ANALYTIC` | `TABLE_READ`, `SAVED_QUERY_WRITE`, `SETTINGS_READ` |
| `READ_ONLY_SENSITIVE_ANALYTIC` | `TABLE_READ`, `TABLE_SENSITIVE`, `SAVED_QUERY_WRITE`, `SETTINGS_READ` |

A bundle SHALL be expanded when configuration is parsed, and SHALL NOT be observable by any
authorization check thereafter: no endpoint, filter or evaluator SHALL branch on a bundle name.
`FULL_ADMIN` and `READ_ONLY_ADMIN` SHALL expand to exactly the authority the corresponding
application role carries today, with **declared exceptions**. First, reading a table's access entries
(`GET /v1/tables/{name}/access` and `GET /v1/tables/{name}/access/{role}`) requires `TABLE_GRANT`, which
`READ_ONLY_ADMIN` does not hold, so a caller that could read a table's access under that bundle receives `403`. The
entries disclose which provider roles exist and what each may do, which is not a catalog read (see
`table-access-control`). `READ_ONLY_ADMIN` and
`READ_ONLY_ANALYTIC` hold no clearance, so under them the catalog read surface omits sensitive
columns (see `table-management`). A second declared exception narrows `FULL_ADMIN`: it no longer sees
another caller's `personal` saved query anywhere, the saved-query revision endpoint and the activity feed
included (see `saved-queries` and `audit-trail`), and it never sees another caller's `private` dashboard. A third concerns mappings rather
than bundles. A `roles-mapping` that names `SAVED_QUERY_WRITE` or `SAVED_QUERY_PUBLISH` without
`SAVED_QUERY_READ` confers `SAVED_QUERY_READ` by implication, with the reach **The table read ladder and
privilege implication** states. No other bundle's authority changes. A fourth is additive: `READ_ONLY_ADMIN`, `READ_ONLY_ANALYTIC` and
`READ_ONLY_SENSITIVE_ANALYTIC` gain `SETTINGS_READ`, and `FULL_ADMIN`, being every privilege, gains
`SETTINGS_READ` and `SETTINGS_WRITE`. A `roles-mapping` that names raw privileges rather than a bundle gains
neither, and must name `SETTINGS_READ` for its holders to read the settings. Apart from those four, an
unmodified `roles-mapping` changes nothing observable. A table's access entries are keyed by raw provider roles, so a
bundle name SHALL be refused as the role of `PUT /v1/tables/{name}/access/{role}` (`422 validation_error`). An entry
stored under a bundle name by an earlier build, which matches a raw provider role of that name, SHALL keep applying as
stored until it is removed with `DELETE /v1/tables/{name}/access/{role}` (see `table-access-control`, "Role-keyed
access API").

Every `roles-mapping` (`security.defaults.roles-mapping`, `providers.<name>.roles-mapping`,
`security.api-key.roles-mapping`) SHALL accept bundle names and privilege names in the same list.
A token that is neither SHALL **fail startup** with an error naming the offending value and the
property that carried it; it SHALL NOT be silently dropped. `TABLE_READ_SENSITIVE` is such a token:
there SHALL be no alias for it.

A bundle's declared set SHALL be **stored minimally and closed under implication** when it is
expanded, so the table above lists what each bundle declares rather than everything it confers:
`READ_ONLY_ANALYTIC` declares `TABLE_READ`, `SAVED_QUERY_WRITE` and `SETTINGS_READ`, and thereby also
confers `TABLE_GET`, `TABLE_LIST` and `SAVED_QUERY_READ`. Storing the closure instead would duplicate the
implication edges in four places and let a bundle drift out of step with them.

#### Scenario: A bundle expands to its privileges, closed under implication
- **WHEN** a provider role is mapped to `READ_ONLY_ANALYTIC` and a caller presents a token carrying
  that role
- **THEN** the caller holds `TABLE_READ`, `SAVED_QUERY_WRITE` and `SETTINGS_READ` as declared, plus
  `TABLE_GET`, `TABLE_LIST` and `SAVED_QUERY_READ` by implication, and no other privilege

#### Scenario: The sensitive analytic bundle pairs the verb with the clearance
- **WHEN** a provider role is mapped to `READ_ONLY_SENSITIVE_ANALYTIC`
- **THEN** its holder holds `TABLE_READ`, `TABLE_SENSITIVE` and `SETTINGS_READ` globally, plus `TABLE_GET`
  and `TABLE_LIST` by implication, and reads every table's sensitive values

#### Scenario: A read of grant lists is the one thing READ_ONLY_ADMIN loses
- **WHEN** a caller mapped to `READ_ONLY_ADMIN` reads `GET /v1/tables/{name}/access` or
  `GET /v1/tables/{name}/access/{role}`
- **THEN** the response is `403 forbidden`, while every catalog, registry, audit and saved-query read
  that bundle reached before still succeeds

#### Scenario: Only the full administrator bundle writes settings
- **WHEN** callers are mapped, one each, to `FULL_ADMIN`, `READ_ONLY_ADMIN`, `READ_ONLY_ANALYTIC` and
  `READ_ONLY_SENSITIVE_ANALYTIC`
- **THEN** every one of them holds `SETTINGS_READ`, and only the `FULL_ADMIN` caller holds `SETTINGS_WRITE`

#### Scenario: Bundles and privileges mix in one mapping
- **WHEN** a provider role is mapped to `["TABLE_GET", "SAVED_QUERY_READ"]` and another to
  `["FULL_ADMIN"]`
- **THEN** both mappings are accepted and each caller holds exactly the union of what its roles name

#### Scenario: An unknown mapping value fails startup
- **WHEN** a `roles-mapping` value names neither a bundle nor a privilege
- **THEN** startup fails with an error naming the value and the property, and the service does not
  begin serving

#### Scenario: The removed privilege name fails startup
- **WHEN** a `roles-mapping` value names `TABLE_READ_SENSITIVE`
- **THEN** startup fails with an error naming that value and the property that carried it

#### Scenario: A caller with only per-table grants is authenticated
- **WHEN** a valid token maps to no privilege at all
- **THEN** the caller is **authenticated**, holds no global privilege, and MAY still be authorized
  for a specific table by a matching per-table grant

### Requirement: The table read ladder and privilege implication

The three table read privileges SHALL form a ladder — `TABLE_LIST`, then `TABLE_GET`, then
`TABLE_READ` — in which holding a rung SHALL confer every rung below it. The clearance
`TABLE_SENSITIVE` SHALL NOT be a rung: it SHALL imply nothing, and nothing SHALL imply it.

Implication SHALL be admitted only where it is **disclosure-monotone** — the implied privilege
reveals a strict subset of what the granting privilege already reveals — or where the granting
privilege authorizes **editing documents by whole-document replace**, which presupposes reading them,
and **no read check consults the granting privilege**. Then, whatever else a caller holds, the granting
privilege adds exactly the implied read to what it may see. It SHALL be transitive.
The complete set of direct implications SHALL be: `TABLE_GET → TABLE_LIST`,
`TABLE_READ → TABLE_GET`, `TABLE_MODIFY → TABLE_GET`, `SAVED_QUERY_WRITE → SAVED_QUERY_READ`,
`SAVED_QUERY_PUBLISH → SAVED_QUERY_READ`, and `SETTINGS_WRITE → SETTINGS_READ`.
`TABLE_WRITE` SHALL imply nothing: a caller holding only `TABLE_WRITE` on a table SHALL NOT be able
to list it, read its schema, or read its rows. In particular the service SHALL NOT admit
`TABLE_WRITE → TABLE_READ`.

`SAVED_QUERY_WRITE → SAVED_QUERY_READ` and `SAVED_QUERY_PUBLISH → SAVED_QUERY_READ` are admitted on the
second ground. Saved queries and dashboards are edited by whole-document replace, so an editor has to
read the document it replaces, and a dashboard replace carries the `generation` that read returned (see
`dashboards`). Without the read, a write grant could create and delete, but could edit only what its
holder remembered. The proviso holds because every saved-query and dashboard read — the live read, the
saved-query revision endpoint and the activity feed — decides visibility from scope, visibility and owner
alone, and no read check consults either write privilege. A `personal` saved query and a `private`
dashboard SHALL be visible to their owner only, whatever privileges another caller holds. Each edge
therefore discloses exactly what `SAVED_QUERY_READ` does on its own. That is:
- `common` saved queries;
- their revisions at `GET /v1/saved-queries/{id}/revision/{rev}`, including revisions from before a query
  became `common`;
- the holder's own `personal` saved queries and `private` dashboards;
- `public` dashboards.

The edges widen a `roles-mapping` that names `SAVED_QUERY_WRITE` or `SAVED_QUERY_PUBLISH` without
`SAVED_QUERY_READ` to that reach. No bundle's authority widens, since every bundle holding either write
already held the read. `TABLE_WRITE` does not qualify on the same ground: a row insert appends and
replaces nothing, so it edits no document.

`SETTINGS_WRITE → SETTINGS_READ` is admitted on the same second ground. A setting is written by
whole-document replace (see `instance-settings`), so an author has to read what it replaces, and the read
check consults `SETTINGS_READ` alone. A setting is one instance-wide document with no owner or visibility,
so the edge discloses exactly what `SETTINGS_READ` does on its own: every stored setting.

`SAVED_QUERY_PUBLISH` SHALL NOT imply `SAVED_QUERY_WRITE`, nor the reverse, for the reason `TABLE_WRITE`
implies nothing pointed the other way: the two name different scopes rather than two rungs of one, and a
write privilege is not a disclosure. What makes either authorize a write on its own is that each
saved-query and dashboard write endpoint defers its check to the point where the request body's `scope`
or `visibility` is known (see `saved-queries` and `dashboards`).

Implication SHALL be **derived when a check runs**, never materialised into stored access entries: a
role's entry SHALL return exactly the privileges that were written to it (see `table-access-control`, "Role-keyed
access API").

#### Scenario: The top rung reads without a separate read grant
- **WHEN** a caller holds `TABLE_READ`, now the top rung, on a table and nothing else
- **THEN** the caller can list the table, read its non-sensitive schema, and read its non-sensitive
  rows, with no `get` grant alongside

#### Scenario: The clearance does not read without a verb
- **WHEN** a caller holds `TABLE_SENSITIVE` on a table and nothing else
- **THEN** the caller cannot list the table, read its schema, or read its rows

#### Scenario: Write confers no read
- **WHEN** a caller holds only `TABLE_WRITE` on a table
- **THEN** the table is absent from catalog listings and query discovery for that caller, and any
  attempt to read it fails as though the table did not exist

#### Scenario: A saved-query write grant reads
- **WHEN** a caller holds only `SAVED_QUERY_WRITE`
- **THEN** it may list and read its own `personal` saved queries, every `common` saved query and that
  query's revisions, its own `private` dashboards and every `public` dashboard, while a write at
  `common` scope or `public` visibility is still refused `403 forbidden` because that requires
  `SAVED_QUERY_PUBLISH`

#### Scenario: Publishing confers the read but no personal write
- **WHEN** a caller holds only `SAVED_QUERY_PUBLISH`
- **THEN** it may create, replace and delete `common` saved queries and read every `common` saved query
  and `public` dashboard, while a write at `personal` scope is refused `403 forbidden` because that
  requires `SAVED_QUERY_WRITE`

#### Scenario: A settings write grant reads the settings
- **WHEN** a caller holds only `SETTINGS_WRITE`
- **THEN** it may read and replace the stored settings, and holds no other privilege

#### Scenario: No privilege reads another caller's personal saved query
- **WHEN** a caller holding every privilege reads another caller's `personal` saved query by id, at a
  revision, or through the activity feed
- **THEN** the read answers `404 not_found`, or the feed omits its activities

#### Scenario: Modify confers schema visibility
- **WHEN** a caller holds only `TABLE_MODIFY` on a table
- **THEN** the caller can read that table's definition and its non-sensitive column inventory, but
  not its rows

#### Scenario: A stored grant list is returned as written
- **WHEN** a role's entry on a table carries `read` only, and the caller then reads that role's entry
- **THEN** the entry carries `privileges` `["read"]`, with no derived `get`

### Requirement: Sensitive column authorization

A catalog column MAY be marked `sensitive`. Sensitivity SHALL be governed by one table-scoped
**clearance**, `TABLE_SENSITIVE`, that acts on the table verbs rather than being one. On its own it
SHALL confer nothing. A verb SHALL reach a table's sensitive columns only when the caller holds
**both that verb and `TABLE_SENSITIVE` on the table that owns the column**. Each SHALL be resolved
independently as the union of what the caller holds globally and what that table grants it, so a
global verb combined with a per-table clearance qualifies, and so does the reverse. For a column of an enrichment,
`TABLE_READ` and `TABLE_SENSITIVE` SHALL be resolved on the enrichment's source, `TABLE_GET` on the source or by
implication from `modify` held on the enrichment, and `TABLE_MODIFY` on the enrichment itself (see
`table-access-control`, "An enrichment's read-side access is its source's").

The clearance SHALL modify exactly three verbs:

- `TABLE_READ`: sensitive **values** SHALL be readable only with the clearance.
- `TABLE_GET`: sensitive columns SHALL appear in the catalog read surface (`GET /v1/tables`,
  `GET /v1/tables/{name}`) only with the clearance.
- `TABLE_MODIFY`: sensitive columns SHALL be changeable, and any column's `sensitive` flag
  declarable or changeable, only with the clearance (see **Changing sensitivity requires the
  clearance**).

It SHALL NOT modify `TABLE_WRITE`, a blind insert, or any global-only privilege.

In `oidc` mode, for any caller lacking the clearance on the owning table, that table's sensitive
columns SHALL be filtered out of the resolved query model before it is used. They SHALL therefore be
absent from schema discovery and SHALL NOT be referenceable in any clause of a structured or SQL query
(`select`, `filter`, `group_by`, `having`, `sort`). They SHALL also be absent from the catalog read
surface and from the column set a schema mutation resolves against (see `table-management`).
Because an enrichment's clearance is its source's, one flattened entity SHALL have one clearance: a caller holding
the clearance on the source SHALL hold it on every enrichment's sensitive columns, and one that does not SHALL see
none of them. Column policies still decide which of those columns a cleared caller sees (see
[table-access-control](../table-access-control/spec.md), "Column visibility by caller role").

A caller lacking the clearance SHALL NOT be able to distinguish a sensitive column from a non-existent
one on the query surface or the catalog read surface. Referencing it in a query SHALL fail as an
ordinary unknown-field validation error (HTTP 400), and naming it in a schema mutation SHALL fail as an
unknown column (HTTP 422).

Hiding a column is a **working view, not a secrecy guarantee for its name**. The audit trail, the
registry surfaces, a table's structural fields (`ordering_key`, `partition_by`, `grain_key`), and a
name collision on a schema `add` or `rename` MAY still name a hidden column. Only its values, and its
presence in the catalog, discovery and query views, SHALL be withheld.

When security is disabled (`security.mode=none`) the filter SHALL fail open: all columns, including
sensitive ones, SHALL be visible. The filter SHALL be applied at a single chokepoint in the
catalog-backed query model, so it SHALL cover schema discovery, `POST /v1/queries/execute`,
`POST /v1/queries/execute-sql`, the translation endpoints, the Flight SQL surface and the
empty-projection ("select all") expansion uniformly. The catalog read surface and the schema mutation
surface SHALL apply the same per-table predicate.

#### Scenario: Non-admin cannot see or reference a sensitive column

- **WHEN** a caller holding `TABLE_READ` but not `TABLE_SENSITIVE` requests schema discovery for
  an entity that has a sensitive column, and separately submits a query that references that column
- **THEN** the sensitive column is absent from the discovery response, and the query is rejected with
  HTTP 400 as an unknown field — the response does not reveal that the column exists

#### Scenario: The catalog read surface still names the column

- **WHEN** a caller holding `TABLE_GET` and `TABLE_SENSITIVE` on the owning table reads that table's
  definition through `GET /v1/tables/{name}`
- **THEN** the sensitive column is present in the returned column inventory with `sensitive: true`
- **AND WHEN** the caller from the previous scenario, which lacks the clearance, reads the same
  definition
- **THEN** the sensitive column is absent from the returned column inventory

#### Scenario: FULL_ADMIN sees and can reference a sensitive column

- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the owning table, as `FULL_ADMIN`
  does globally, requests schema discovery and queries a sensitive column
- **THEN** the sensitive column appears in the discovery response and the query resolves and executes
  normally

#### Scenario: The clearance alone confers nothing

- **WHEN** a caller holds `TABLE_SENSITIVE` on a table and no verb on it, globally or by grant
- **THEN** the table is absent from the caller's catalog listing and discovery, and a query naming it
  fails as though the table did not exist

#### Scenario: Verb and clearance combine across scopes

- **WHEN** a caller holds `TABLE_READ` globally and is granted `sensitive` on one table only
- **THEN** it reads that table's sensitive values, and every other table's sensitive columns stay
  hidden from it

#### Scenario: The privilege is evaluated per owning table

- **GIVEN** a source and one of its enrichments that both carry sensitive columns
- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the source queries the flattened entity
- **THEN** both the source's and the enrichment's sensitive columns resolve
- **AND WHEN** a caller holding `TABLE_READ` on the source without the clearance queries it
- **THEN** neither table's sensitive columns resolve

#### Scenario: Security disabled shows sensitive columns

- **WHEN** `security.mode=none` and a caller requests schema discovery or queries a sensitive column
- **THEN** the sensitive column is visible in discovery and resolvable in queries (the filter fails
  open when authentication is not enforced)
