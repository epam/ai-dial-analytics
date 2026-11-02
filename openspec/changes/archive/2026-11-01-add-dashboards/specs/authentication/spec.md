## ADDED Requirements

### Requirement: Saved-query privileges also govern dashboards

The `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` and `SAVED_QUERY_PUBLISH` privileges SHALL also authorize the
dashboards surface (see [dashboards](../dashboards/spec.md)): `SAVED_QUERY_READ` its reads,
`SAVED_QUERY_WRITE` a `private` dashboard's writes, and `SAVED_QUERY_PUBLISH` a `public` dashboard's writes
and every visibility change. No dashboard-specific privilege SHALL exist, and the closed privilege set is
unchanged.

The object class keeps its name because the authority is the same one: a `public` dashboard publishes the
query bodies its widgets hold, so curating shared dashboards and curating shared saved queries are one
responsibility. As a consequence every bundle holding `SAVED_QUERY_WRITE` — `READ_ONLY_ADMIN`,
`READ_ONLY_ANALYTIC` and `READ_ONLY_SENSITIVE_ANALYTIC` — may keep `private` dashboards, as it may keep
`personal` saved queries, and only a holder of `SAVED_QUERY_PUBLISH` (by default the `FULL_ADMIN` bundle) may
publish one.

#### Scenario: An analyst bundle keeps private dashboards

- **WHEN** a caller holding the `READ_ONLY_ANALYTIC` bundle creates a `private` dashboard
- **THEN** the dashboard is created

#### Scenario: An analyst bundle cannot publish a dashboard

- **WHEN** a caller holding the `READ_ONLY_ANALYTIC` bundle creates a `public` dashboard
- **THEN** the response is `403 forbidden`
## MODIFIED Requirements

### Requirement: Application privileges and bundles

The service SHALL define a closed set of **application privileges** as the unit of authorization,
each named `<OBJECT>_<VERB>` over the object classes `TABLE`, `REGISTRY`, `AUDIT`, `SAVED_QUERY` and
`DATA_SYNC`: `TABLE_LIST`, `TABLE_GET`, `TABLE_READ`, `TABLE_SENSITIVE`, `TABLE_WRITE`,
`TABLE_MODIFY`, `TABLE_CREATE`, `TABLE_DELETE`, `TABLE_GRANT`, `REGISTRY_READ`, `REGISTRY_ADMIN`,
`AUDIT_READ`, `DATA_SYNC_OPERATE`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` and
`SAVED_QUERY_PUBLISH`. `TABLE_SENSITIVE` is the clearance (see **Sensitive column authorization**),
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
| `READ_ONLY_ADMIN` | `TABLE_READ`, `REGISTRY_READ`, `AUDIT_READ`, `SAVED_QUERY_WRITE` |
| `READ_ONLY_ANALYTIC` | `TABLE_READ`, `SAVED_QUERY_WRITE` |
| `READ_ONLY_SENSITIVE_ANALYTIC` | `TABLE_READ`, `TABLE_SENSITIVE`, `SAVED_QUERY_WRITE` |

A bundle SHALL be expanded when configuration is parsed, and SHALL NOT be observable by any
authorization check thereafter: no endpoint, filter or evaluator SHALL branch on a bundle name.
`FULL_ADMIN` and `READ_ONLY_ADMIN` SHALL expand to exactly the authority the corresponding
application role carries today, with **declared exceptions**. First, `GET /v1/tables/{name}/access` now
requires `TABLE_GRANT`, which `READ_ONLY_ADMIN` does not hold, so a caller that could read grant
lists under that bundle receives `403`. The grant lists disclose which provider roles exist and what
each may do, which is not a catalog read (see `table-access-control`). `READ_ONLY_ADMIN` and
`READ_ONLY_ANALYTIC` hold no clearance, so under them the catalog read surface omits sensitive
columns (see `table-management`). A second declared exception narrows `FULL_ADMIN`: it no longer sees
another caller's `personal` saved query anywhere, the saved-query revision endpoint and the activity feed
included (see `saved-queries` and `audit-trail`), and it never sees another caller's `private` dashboard. A third concerns mappings rather
than bundles. A `roles-mapping` that names `SAVED_QUERY_WRITE` or `SAVED_QUERY_PUBLISH` without
`SAVED_QUERY_READ` confers `SAVED_QUERY_READ` by implication, with the reach **The table read ladder and
privilege implication** states. No other bundle's authority changes. Apart from those three, an unmodified
`roles-mapping` changes nothing observable. Bundle names SHALL NOT be valid grantees in a table's access lists, which name raw
provider roles.

Every `roles-mapping` (`security.defaults.roles-mapping`, `providers.<name>.roles-mapping`,
`security.api-key.roles-mapping`) SHALL accept bundle names and privilege names in the same list.
A token that is neither SHALL **fail startup** with an error naming the offending value and the
property that carried it; it SHALL NOT be silently dropped. `TABLE_READ_SENSITIVE` is such a token:
there SHALL be no alias for it.

A bundle's declared set SHALL be **stored minimally and closed under implication** when it is
expanded, so the table above lists what each bundle declares rather than everything it confers:
`READ_ONLY_ANALYTIC` declares `TABLE_READ` and `SAVED_QUERY_WRITE`, and thereby also confers
`TABLE_GET`, `TABLE_LIST` and `SAVED_QUERY_READ`. Storing the closure instead would duplicate the
implication edges in four places and let a bundle drift out of step with them.

#### Scenario: A bundle expands to its privileges, closed under implication
- **WHEN** a provider role is mapped to `READ_ONLY_ANALYTIC` and a caller presents a token carrying
  that role
- **THEN** the caller holds `TABLE_READ` and `SAVED_QUERY_WRITE` as declared, plus `TABLE_GET`,
  `TABLE_LIST` and `SAVED_QUERY_READ` by implication, and no other privilege

#### Scenario: The sensitive analytic bundle pairs the verb with the clearance
- **WHEN** a provider role is mapped to `READ_ONLY_SENSITIVE_ANALYTIC`
- **THEN** its holder holds `TABLE_READ` and `TABLE_SENSITIVE` globally, plus `TABLE_GET` and
  `TABLE_LIST` by implication, and reads every table's sensitive values

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
`TABLE_READ → TABLE_GET`, `TABLE_MODIFY → TABLE_GET`, `SAVED_QUERY_WRITE → SAVED_QUERY_READ`, and
`SAVED_QUERY_PUBLISH → SAVED_QUERY_READ`.
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

`SAVED_QUERY_PUBLISH` SHALL NOT imply `SAVED_QUERY_WRITE`, nor the reverse, for the reason `TABLE_WRITE`
implies nothing pointed the other way: the two name different scopes rather than two rungs of one, and a
write privilege is not a disclosure. What makes either authorize a write on its own is that each
saved-query and dashboard write endpoint defers its check to the point where the request body's `scope`
or `visibility` is known (see `saved-queries` and `dashboards`).

Implication SHALL be **derived when a check runs**, never materialised into stored grant lists: a
grant list SHALL return exactly the grantees that were written to it.

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

#### Scenario: No privilege reads another caller's personal saved query
- **WHEN** a caller holding every privilege reads another caller's `personal` saved query by id, at a
  revision, or through the activity feed
- **THEN** the read answers `404 not_found`, or the feed omits its activities

#### Scenario: Modify confers schema visibility
- **WHEN** a caller holds only `TABLE_MODIFY` on a table
- **THEN** the caller can read that table's definition and its non-sensitive column inventory, but
  not its rows

#### Scenario: A stored grant list is returned as written
- **WHEN** a grant list names a role for `read` only, and the caller then reads the access lists
- **THEN** the response lists that role under `read` alone, with no derived entry under `get`
