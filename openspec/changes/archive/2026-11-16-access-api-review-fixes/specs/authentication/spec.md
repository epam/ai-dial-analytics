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
(`GET /v1/tables/{name}/access` and `GET /v1/tables/{name}/access?role=<role>`) requires `TABLE_GRANT`, which
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
bundle name SHALL be refused as the role of `PUT /v1/tables/{name}/access?role=<role>` (`422 validation_error`). An entry
stored under a bundle name by an earlier build, which matches a raw provider role of that name, SHALL keep applying as
stored until it is removed with `DELETE /v1/tables/{name}/access?role=<role>` (see `table-access-control`, "Role-keyed
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

#### Scenario: A read of access entries is the one thing READ_ONLY_ADMIN loses
- **WHEN** a caller mapped to `READ_ONLY_ADMIN` reads `GET /v1/tables/{name}/access` or
  `GET /v1/tables/{name}/access?role=<role>`
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

## ADDED Requirements

### Requirement: Encoded path separators are refused

The service SHALL refuse with HTTP 400 a request whose path carries a percent-encoded slash (`%2F`, `%2f`) or
percent-encoded percent sign (`%25`) in a path segment, on every path it serves and in both security modes, so no
encoded character acts as a path separator when a request is matched to a handler or to an authentication rule, and
none reaches a handler inside a path variable. No `/v1` endpoint carries a value that would need one in a path
segment: a value that may contain any character, such as the role of a table access entry (see
`table-access-control`, "Role-keyed access API"), travels as a query parameter. Every other rule of the request
firewall SHALL stay: a path carrying `;`, `\`, `//` (encoded or not), an encoded `.`, a NUL, carriage-return or
line-feed character, U+2028 or U+2029, or a segment that is exactly `.` or `..`, SHALL be refused.

A request the service's request firewall refuses SHALL be answered as `ErrorView` `400 bad_request`, in both security
modes. A request the servlet container rejects before the service sees it (an encoded slash or an encoded NUL, for
two) gets the container's own `400` response, which is not an `ErrorView`; the service promises only HTTP 400 for
those.

#### Scenario: An encoded slash in a path segment is refused

- **WHEN** a caller holding `TABLE_GET` globally reads `GET /v1/tables/a%2Fb`
- **THEN** the response is HTTP 400, in either security mode

#### Scenario: An encoded traversal never leaves its public path

- **WHEN** an anonymous caller in `oidc` mode requests `/actuator/health/%2F..%2Fenv`
- **THEN** the response is HTTP 400, and the request reaches no endpoint

#### Scenario: A firewall refusal is an ErrorView

- **WHEN** a caller requests a `/v1` path carrying `%3B` (an encoded `;`) in a path segment
- **THEN** the response is an `ErrorView` with `status` 400 and `error` `bad_request`

## REMOVED Requirements

### Requirement: Encoded path separators and firewall refusals

**Reason**: The role of a table access entry is now a query parameter, so no endpoint needs a percent-encoded `/` or
`%` inside a path segment, and accepting them on every path widened the request firewall for nothing.

**Migration**: Send the role as `?role=<URL-encoded role>`; a path carrying `%2F` or `%25` is refused `400` again.
Firewall refusals keep their `ErrorView` `400 bad_request` shape (see "Encoded path separators are refused").
