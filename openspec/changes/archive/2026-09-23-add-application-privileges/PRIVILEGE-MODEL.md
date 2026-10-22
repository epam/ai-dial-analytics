# ADAS privilege model — working sketch

Proposal only; no code or spec written. Today: two roles (`FULL_ADMIN`, `READ_ONLY_ADMIN`) + per-table
`write`/`modify`. Reviewed once against the code (design + security passes); findings folded in below,
and the two live defects they turned up are in **Prerequisites**.

## 16 atoms

Naming rule: every atom is `<OBJECT>_<VERB>`, with one qualifier exception —
`TABLE_READ_SENSITIVE` is `<OBJECT>_<VERB>_<QUALIFIER>`.
Objects: `TABLE`, `REGISTRY`, `AUDIT`, `SAVED_QUERY`, `DATA_SYNC`.
Verbs: `LIST`, `GET`, `READ`, `WRITE`, `MODIFY`, `CREATE`, `DELETE`, `GRANT`, `PUBLISH`, `OPERATE`,
`ADMIN` (= author **or** execute, for an object class with no finer split; only `REGISTRY_ADMIN`).

Key rule is one-directional: every **table-scoped** atom's `access` key is its name minus `TABLE_`.
The converse does not hold — `TABLE_CREATE`, `TABLE_DELETE` and `TABLE_GRANT` are global-only.

| atom | scope | what |
|---|---|---|
| `TABLE_LIST` | table | see a table exists — name, status, physical layout, `column_count` |
| `TABLE_GET` | table | its column inventory (names, types, tags, `sensitive`/`heavy` flags) + grain |
| `TABLE_READ` | table | execute data reads (DSL, SQL, translate, Flight) — non-sensitive columns |
| `TABLE_READ_SENSITIVE` | table | same reads, all columns incl. `sensitive` (top rung, implies `READ`) |
| `TABLE_WRITE` | table | insert rows, OTLP ingest, rule write-back |
| `TABLE_MODIFY` | table | schema + description (**but not the `sensitive` flag** — see the ladder) |
| `TABLE_CREATE` | global | create table / sink |
| `TABLE_DELETE` | global | delete table / sink |
| `TABLE_GRANT` | global | `PUT .../access`, and `sensitive` flag changes |
| `REGISTRY_READ` | global | evaluators, rules + sync feed, pipelines, sinks, backfill runs |
| `REGISTRY_ADMIN` | global | author **or execute** a stored declaration |
| `AUDIT_READ` | global | activity trail |
| `DATA_SYNC_OPERATE` | global | advance ingestion (backfill only) |
| `SAVED_QUERY_READ` | global | read own + `common` saved queries |
| `SAVED_QUERY_WRITE` | global | create / update / delete own saved queries |
| `SAVED_QUERY_PUBLISH` | global | write at `common` scope |

Scope `table` = holdable **globally** (all tables) *or* granted per table; effective access is the union.

Two planes. For a table the object plane is `CREATE`/`MODIFY`/`DELETE` and the data plane is
`READ`/`WRITE`. A saved query has no object plane worth gating — not because it lacks identity (it has
an id, is enumerated by `GET /v1/saved-queries`, and is replaced by `PUT /{id}`), but because the
`owner_id` visibility predicate already does all the object-plane work, so finer atoms buy nothing.
`TABLE_WRITE` is insert-only because no row-delete endpoint exists.

**Note `TABLE_LIST` is not "no column names".** `TableMapper.toListItemDto` omits `columns` and `grain`
but still emits `ordering_key`, `partition_by.column`, `ttl.column`, `identity_column` and
`version_column` — 3–6 physical column names per table. Open decision: strip them from the list item,
or accept that `LIST` names them.

## Read ladder

`LIST` (it exists) → `GET` (its schema) → `READ` (its non-sensitive rows) → `READ_SENSITIVE` (all
columns). Grant the **highest** rung you want; lower ones follow. `READ_SENSITIVE` is a rung, **not**
a modifier — a role listed there alone gets a full read, so there is no inert state and no
"set one or the other" ambiguity.

### Implication rule

An atom implies another **iff disclosure-monotone** — the implied atom reveals a strict subset of
what the granted one already reveals, so refusing it protects nothing. A *practical leak* is not
containment. **Direct edges only; implication is transitive.**

```
READ_SENSITIVE
      │
     READ        MODIFY        WRITE
      │            │          (isolated)
      └───→ GET ←──┘
             │
           LIST
```

| direct edge | why |
|---|---|
| `GET → LIST` | the schema names the table |
| `READ → GET` | the rows contain the columns. (`GET \ LIST = {columns, grain}`) |
| `READ_SENSITIVE → READ` | all columns ⊃ non-sensitive columns |
| `MODIFY → GET` | `PATCH` returns the full definition |

**No edge:** `WRITE → anything` — insert leaks column names via validation errors but does not
contain the inventory (`TableDataService` only confirms or denies a guessed name), and dropping the
edge keeps an ingest-only identity invisible in the catalog. Also `GET → READ`, `MODIFY → READ`, and
**`WRITE → READ`** — that last confusion is the `RuleAuthorizer` escalation.

`MODIFY` sits **on** the ladder via `MODIFY → GET`. Only `WRITE` is isolated.

### Second axis: what a privilege lets you *grant yourself*

Disclosure monotonicity asks what an atom reveals. It misses what an atom lets you **change** in
order to reveal more. One case exists and it is live today:

> `PATCH /v1/tables/{name}/schema` accepts `sensitive` as a merge-patch flag, and
> `TableSchemaService:198` guards it **only when setting it to `true`** (and only for cursor columns).
> Clearing it to `false` is unrestricted. So a per-table `modify` grantee can declassify any column
> and then read it.

Rule: **a `sensitive` flag change requires `TABLE_GRANT`**, not `TABLE_MODIFY`. (The weaker
alternative — `MODIFY` may set but never clear it — leaves declassification with no owner at all.)
Every future atom must be checked on both axes, not just on disclosure.

### Derivation

Derive at check time; never expand at grant time. Lists store exactly what was sent; the evaluator
computes `visible = list ∨ get ∨ read ∨ read_sensitive ∨ modify` and
`schemaVisible = get ∨ read ∨ read_sensitive ∨ modify` — `write` appears in neither.
Expanding into the JSONB would mutate operator intent and make revoke asymmetric. Derivation is
unavoidable regardless: a **global** `TABLE_READ` holder has no entry in any access list, so their
visibility must be computed.

Cost: removing `list` from a role that holds `read` is a silent no-op → `PUT .../access` should
return the effective permission matrix beside the stored lists.

## Grant keys (`access` JSONB, 6)

Key = the table-scoped atom minus the `TABLE_` prefix, lowercased.

```json
{ "list": [], "get": [], "read": [], "read_sensitive": [],
  "write": [], "modify": [] }
```

The first four are monotone rungs (grant the highest); `modify` also reaches `get`; only `write` is
independent of everything. Kept flat deliberately — nesting the ladder breaks the uniform array shape
and pushes the same ambiguity down a level.

Values = raw provider role names (same as `roles-mapping` keys). Empty list = only global holders
(symmetric with write/modify — no "empty means open" special case).

### PUT semantics — absent vs empty

`PUT /v1/tables/{name}/access` is a **full replace** today (`ReplaceAccessRequest`, `null → empty`).
Adding four keys means an existing client that sends `{write, modify}` would silently revoke every
read grant on each PUT — including the MCP `adas_*` tools and the flow documented in
`docs/claude-code-telemetry.md`.

Decision: **absent key = keep the current list; `[]` = revoke.** (The alternative is to version the
endpoint.) This also makes the system-table rule below workable.

### System tables — contract change

`dial_usage_log` and `rate_analytics` are `system` (`V4__add_table_system_flag.sql`), and
`TableAccessService:35` calls `requireModifiable()`, so **no access list can be set on them at all**
today: `409 table_is_system`. That makes per-table read grants inapplicable to the primary analytics
table — the change's own motivating example ("this team may read the usage log but not the finance
enrichment") is unexpressible on the usage-log half.

It also contradicts the guard's stated intent: V4's own comment says a system table "stays fully
readable and queryable; only the modifying endpoints" are refused. The grant endpoint was swept in
only because every key *was* a modification permission.

**The refusal moves from the object to the keys:**

- read-side keys (`list`, `get`, `read`, `read_sensitive`) — always settable, system or not;
- `write` / `modify` — `409 table_is_system` when **non-empty** on a system table;
- **empty `write`/`modify` must pass**, so a six-key PUT succeeds.

`TableCallerPermissions.resolve` stops short-circuiting to all-false: for a system table `write` and
`modify` report hard `false` (no privilege overrides it, `FULL_ADMIN` included) while the read-side
flags are evaluated from the lists like any other table.

Untouched: `requireModifiable()` on schema define/patch, delete, row write and description;
`AggregateSpecValidator:306` refusing a system table as a pipeline target.

**This is a deliberate, stated behaviour change** — a client expecting `409` from any
`PUT .../access` on a system table will now get `200` for a read-only PUT.

## Bundles (reserved names in `roles-mapping`)

**Bundles, not atoms.** Expanded once at config-parse time; nothing downstream knows they exist.
An atom-level `FULL_ADMIN` would reinstate the short-circuits this model removes, and would need
excluding from `allOf()`, from the `access` keys and from effective `permissions`.

```
FULL_ADMIN                   = all 16
READ_ONLY_ADMIN              = TABLE_READ, REGISTRY_READ, AUDIT_READ,
                               SAVED_QUERY_READ, SAVED_QUERY_WRITE
READ_ONLY_ANALYTIC           = TABLE_READ,           SAVED_QUERY_READ, SAVED_QUERY_WRITE
READ_ONLY_SENSITIVE_ANALYTIC = TABLE_READ_SENSITIVE, SAVED_QUERY_READ, SAVED_QUERY_WRITE
```

(`TABLE_READ ⇒ TABLE_GET ⇒ TABLE_LIST` by the DAG, so every bundle above reads the catalog too.)

The two analyst bundles differ by **one atom** — `TABLE_READ_SENSITIVE` implies `TABLE_READ`, so
"all tables plus sensitive columns" is the same bundle one rung higher.

Deliberately excluded from both analyst bundles:

- `SAVED_QUERY_PUBLISH` — curating shared definitions is not analysis;
- `REGISTRY_READ` — an analyst has no business in evaluator prompts and rule SQL. Security payoff:
  registry DTOs name columns (open decision #2), so the sensitive-name leak does not apply to
  either analyst bundle;
- `AUDIT_READ` — an admin surface that names authors.

`READ_ONLY_SENSITIVE_ANALYTIC` is what actually closes **G6**: reading PII no longer requires the
authority to drop tables and rewrite access lists. And `READ_ONLY_ANALYTIC` is the correct target for
the commonest existing mapping — today anyone who needs to read tables is given `READ_ONLY_ADMIN`,
which drags in the registry and the audit trail. Migration guidance becomes "re-map analysts **down**",
not "compose atom lists".

Neither analyst bundle fixes open decision #1: both hold *global* `TABLE_READ`, so per-table read
grants stay ineffective against them. A restricted team needs a role with **no** global read atom —
`{SAVED_QUERY_READ, SAVED_QUERY_WRITE}` and grants only. Open: ship that as a fourth bundle, or leave
it to an explicit atom list?

Predefined roles are a **product surface**, not only compatibility shims: the analyst personas are
universal to ADAS, so they ship in code (cf. Kubernetes `view`/`edit`/`admin`, GCP's predefined
roles). Deployment-specific roles stay in config.

Store minimal, let the DAG close it, assert every bundle's closure in a test. Deliberately excluded
from `READ_ONLY_ADMIN`, matching today: `TABLE_READ_SENSITIVE` and `TABLE_WRITE` — a
`READ_ONLY_ADMIN` whose *provider role* is in a table's `write` list can already insert today, and
that still works without an atom in the bundle.

⚠ Not exactly today in one place: `REGISTRY_READ` covers pipeline reads, which are `@FullAdminOnly`
now. Either accept the widening or add a `PIPELINE_READ` atom — can't be both non-breaking and
consistent.

Config rules: values accept a bundle name **or** an atom name, mixed; unknown token → **fail fast at
startup**; bundle names are **not** valid in a table's `access` lists (different namespace — raw
provider role names). Applies to `IdentityProviderUtils`, `ApiKeyProperties`, and the resolver map.
These are predefined roles that stay forever (cf. `pg_read_all_data`), not deprecated legacy.

```json
{ "admin":             ["FULL_ADMIN"],
  "analytics-viewers": ["READ_ONLY_ANALYTIC"],
  "analytics-pii":     ["READ_ONLY_SENSITIVE_ANALYTIC"],
  "finance-team":      ["SAVED_QUERY_READ", "SAVED_QUERY_WRITE"],
  "catalog-browsers":  ["TABLE_GET"],
  "runner":            ["REGISTRY_READ"] }
```

Bundle names and atom names mix freely. `finance-team` holds no global read atom, so its table access
comes entirely from per-table grants — the shape open decision #1 is about.

## Operations → atom

| surface | operation | atom |
|---|---|---|
| query | `POST /v1/queries/execute`, `/execute-sql` | `TABLE_READ` on every resolved table |
| query | `POST /v1/queries/translate`, `/translate-sql` | `TABLE_GET` — never contacts ClickHouse |
| query | `GET /v1/queries/entities` | `TABLE_LIST` (filtered) — returns names only |
| query | `GET /v1/queries/entities/schema/{name}` | `TABLE_GET` — same door as the catalog get |
| query | `GET /v1/queries/functions` | — (authenticated) |
| flight | `GetFlightInfo` / `DoGet` | `TABLE_READ` |
| flight | `GetTables`, `GetCatalogs`, `GetSqlInfo`, … | `TABLE_READ` (filtered) — open decision |
| tables | `GET /v1/tables` | `TABLE_LIST` (filtered) |
| tables | `GET /v1/tables/{name}`, `/revision/{rev}` | `TABLE_GET` (else 404) |
| tables | `GET /v1/tables/{name}/access` | `TABLE_GRANT` — discloses grantee role names |
| tables | `POST /v1/tables` · `DELETE /{name}` | `TABLE_CREATE` · `TABLE_DELETE` |
| tables | `POST`/`PATCH /{name}/schema` · `PUT /{name}` | `TABLE_MODIFY` (`sensitive` → `TABLE_GRANT`) |
| tables | `POST /{name}/rows` | `TABLE_WRITE` |
| tables | `PUT /{name}/access` | `TABLE_GRANT` |
| saved | `GET /v1/saved-queries`, `/{id}` | `SAVED_QUERY_READ` (+ `owner_id` filter) |
| saved | `POST`, `PUT /{id}`, `DELETE /{id}` | `SAVED_QUERY_WRITE` (+ `SAVED_QUERY_PUBLISH` if `common`) |
| audit | `POST /v1/activities` · `GET /{activityId}` | `AUDIT_READ` (+ table filter) |
| registry | `GET` evaluators / rules / pipelines / sinks / backfill run | `REGISTRY_READ` |
| registry | `POST`/`PUT`/`PATCH`/`DELETE` evaluators / rules / pipelines | `REGISTRY_ADMIN` |
| registry | `POST /v1/pipelines/{name}/refresh`, `/preview` | `REGISTRY_ADMIN` |
| registry | `POST /v1/otel/sinks` · `DELETE /{name}` | `TABLE_CREATE` · `TABLE_DELETE` |
| sync | `POST /v1/data-sync/backfill` | `DATA_SYNC_OPERATE` |
| rule data | `/scan`, `/group-rows`, `/materialize`, `/rows` | `TABLE_READ` on **source** ∧ `TABLE_WRITE` on **target** |
| otlp | `POST /v1/otel/sink/{s}/v1/{logs,traces,metrics}` | `TABLE_WRITE` on landing table |
| public | actuator health/info/prometheus, swagger, api-docs | — |

Endpoint coverage verified against all 16 controllers: no material misses.

`SystemIdentity` has exactly **one** caller, `AggregateScheduler:101`, and needs global
`{TABLE_READ, TABLE_READ_SENSITIVE}`. The blob poller and the data-sync job reference no security
context at all — they write ClickHouse directly and sit outside the model. Note aggregate refresh
writes its target with no write gate today; if service-layer writes are ever gated, the system
principal needs `TABLE_WRITE` too.

## Composite entities — source + enrichments

The queryable entity is the **source** flattened with its ACTIVE enrichments
(`CatalogQueryService.buildModel`); `findModel` requires `type == SOURCE`, so an enrichment is never
an entity of its own. Visibility is therefore decided **per owning table**, at the exact line where
the sensitive filter already sits (`addColumn`, one signature change at 7 call sites).

| caller holds | gets |
|---|---|
| read on source + enrichment | the full flat entity |
| read on source only | the entity with that enrichment's columns absent; **row counts identical** |
| read on enrichment only | nothing — no entity names it; the grant is inert |

Hiding is safe: the grain join is a 0..1 LEFT JOIN that never inflates rows, so dropping an
enrichment changes no cardinality, and a reference to `enrichment.column` fails as an ordinary
unknown field. An entity is visible iff its **source** is.

**Do not** serve an enrichment-only read through the source entity. The grain key is the *source's*
column and the `FROM` is the source table, so returning enrichment rows that way discloses the
source's row population and its grain-key values (usually identifiers). Enrichment-only read through
the flat entity is a source read in disguise.

So: **catalog visibility is per physical table; query visibility is per entity.** `LIST`, `GET`,
`WRITE` and `MODIFY` all work on an enrichment standalone; only `READ` has the asymmetry. For v1 an
enrichment-only `read` grant is declared inert, and `PUT .../access` says so:
*"`read` on `finance_enrichment` has no effect — callers also need `read` on its source
`dial_usage_log`."* Making enrichments independently queryable is a separate feature.

## Not expressible as an atom

- **filters** — the sensitive projection, unreadable-table hiding, `owner_id` on personal saved queries
- **object invariants, per key** — a `system` table refuses `write`/`modify` for everyone including
  `FULL_ADMIN`, while its read-side keys are ordinary grants (see the contract change above)
- **declaration-time rules** — `AggregateSpecValidator:900-905` enforces sensitive→sensitive for
  aggregates; there is **no analogue** in `service/enrichment/`, so a `FULL_ADMIN`-registered Class-1
  rule can copy a sensitive column into a non-sensitive enrichment column readable by all

## Prerequisites

Two live defects, exploitable today and independent of this model:

1. **`modify` can declassify** — see "Second axis" above. A caller with any application role plus a
   `modify` grant can strip `sensitive` and read the column.
2. **`security.mode` opens Flight** — nine derivations in four semantics. `security.mode=OIDC`
   (mis-cased) or any unknown value → no Flight authenticator bean and no API-key beans, so Flight
   serves unauthenticated, while `SensitiveColumnAccess` fails open and hands sensitive columns to
   anonymous Flight callers. One `SecurityMode` bean that fails fast on unknown values fixes it, and
   the same bean replaces the five independent `none`-mode fail-open checks.

Three pieces of groundwork this model needs before it is meaningful:

3. **One token-building function for all four entry points.** `FlightHeaderAuthenticator`
   `authenticateApiKey` builds its token from mapped app roles only — no `PROVIDER_ROLE_*` — while
   the three HTTP paths add them, and both share one `ApiKeyCache` entry keyed by sha256(key).
   Whichever surface introspects first decides whether per-table grants can match at all. The BI-tool
   persona (Flight + DIAL API key) is exactly who read grants are for.
4. **A fail-closed authorization guard.** The `/v1/**` `hasAnyAuthority` rule must become
   `.authenticated()`, so ~25 read endpoints lose their only gate and a forgotten annotation becomes
   "open to any authenticated caller". Require an ArchUnit/reflection test that every
   `@RequestMapping` method carries exactly one authorization annotation, plus a `denyAll` default.
   Flight needs per-RPC checks in `AdasFlightSqlProducer.asCaller`, not a role test in `authorized()`.
5. **Four `FULL_ADMIN` short-circuits, not one** — `TableAccessEvaluator:41`,
   `TableCallerPermissions:40`, `SensitiveColumnAccess:31`, `SavedQueryAccess:104`, plus
   `FlightHeaderAuthenticator:173-184` and the URL rule.

## Open decisions

1. **Narrowing is usually one ADAS config edit, not IdP surgery.** Effective access is a union, so
   global `TABLE_READ` beats any grant list. But `roles-mapping` is *ADAS* config: remap
   `analytics-viewers` from `["READ_ONLY_ADMIN"]` to `["SAVED_QUERY_READ"]` and add grants. An IdP
   change is needed only when one provider role must be **split** into global and restricted readers.
   Two blockers to clear: `ApiKeyProperties.validate:79-87` refuses startup unless some mapping
   yields an app role (a grant-only deployment is legitimate under this model), and
   `UserRolesResolver.resolve:38-41` logs a WARN **per request** for role-less callers.
2. **Are column names secret?** The top rung hides sensitive *values* on the query path, but names
   still leak through the catalog get (pre-existing), the audit feed (`resourceId` is `table:column`,
   and `sensitive` flips are recorded as `Update` activities) and registry reads (`RuleDto.filter_sql`,
   `member_select`, `group_by`; `EvaluatorVersionDto.request_template`). Either accept "names are not
   secret" and drop the claim, or filter all three. **Recommend accept** — filtering SQL text is a
   losing game.
3. **Flight metadata**: filtered by `TABLE_READ` (anything advertised must be runnable) or by
   `TABLE_GET` with `DoGet` refusing (serves a BI picker). `GetTables(include_schema)` is already a
   schema read, which argues for `TABLE_GET`.
4. **Audit feed visibility** must be an `IN (visible tables)` predicate inside the query — paging
   happens in SQL, so a post-filter breaks pages. First place ADAS needs "the set of tables this
   caller can GET" as a list rather than a per-table boolean.
5. **Laundering subset rule** for per-table `read_sensitive`: target's list ⊆ source's, enforced at
   declaration *and* on `PUT .../access`. Needed in the same change or the feature is a regression.
6. **Pipeline reads widen** to `READ_ONLY_ADMIN` (today `FULL_ADMIN`-only) — deliberate.
7. **Strip physical column names from the list item**, or accept that `TABLE_LIST` names 3–6 of them?
8. **Cross-table grant audit.** Six lists per table and no cross-table view: "what can role X read?"
   is a scan of every table's `/access`. At minimum add `access` to `GET /v1/tables` for
   `TABLE_GRANT` holders, or a `?role=` filter.
9. **Grant values are provider-specific identifiers.** `providers.*` is a map; Azure emits group
   GUIDs, Keycloak emits names, Core project keys emit Core role names. Raw names can collide across
   providers and are unreadable as GUIDs. Decide now — grant values are persisted.
10. **`common` saved-query bodies are unredacted**, and embed entity and field names. Filter or accept.
11. **`READ_ONLY_ADMIN` can write personal saved queries** today. Keep (compat) or fix?
12. **`POST /{name}/schema` and `PATCH /{name}/schema` are gated differently today** (app-role +
    `canModify` vs `.authenticated()` + `canModify`). Mapping both to `TABLE_MODIFY` is a reasonable
    behaviour change; record it.
13. **Table-owner persona.** Everything registry-side is global, so a team owning one domain cannot
    declare a rule or pipeline targeting *its own* table. `REGISTRY_ADMIN` can never become
    table-scoped; `RULE_WRITE`/`PIPELINE_WRITE` could. Naming decision with forward-compat cost.
14. **Delegated ownership.** Making `TABLE_GRANT` table-scoped (a `grant` key) would be
    `WITH GRANT OPTION`. Addable later without a rename; decide separately.
15. Deferred splits: `TABLE_READ_SQL`, `TABLE_DESCRIBE`, `TABLE_GRANT_READ`, `SINK_ADMIN`,
    `EVALUATOR/RULE/PIPELINE_ADMIN`, `RULE_RUN`.

## Why grant is an operation, not a resource

A resource can be **addressed, enumerated and versioned** independently. ADAS grants: no id, no
cross-table listing, no lifecycle (`PUT` replaces the whole list). Three no's → it's an attribute of
the table, matching the storage (JSONB column) and the package (`service/table/grant/`). GCP agrees:
`bigquery.tables.setIamPolicy` is a verb on the table. K8s models grants as resources and needs
special `escalate`/`bind` verbs because of it — a hazard that doesn't transfer, since ADAS grants are
bounded to one table either way.

**Trigger to revisit:** grant becomes a resource when grants are normalized into their own table —
rows of `(table, grantee, privilege)` selectable and revocable individually, replacing whole-list
`PUT`. Then it gains identity + enumeration and `GRANT_READ`/`GRANT_WRITE` become honest.

## Biggest wins

- **Enrichment Runner** drops from `READ_ONLY_ADMIN` — global read of every table, held only because
  the rule sync feed sits behind the app-role rule — to `{REGISTRY_READ}` + per-table grants.
- **`SystemIdentity`** drops from `FULL_ADMIN` to `{TABLE_READ, TABLE_READ_SENSITIVE}`.
- **The four `FULL_ADMIN` short-circuits** collapse: holding the atom globally *is* the bypass, so no
  hard-coded second path through any check (cf. `pg_read_all_data`).

The `RuleAuthorizer` escalation being fixed is wider than first described: `SensitiveProjectionGuard`
shows a scan projection may include *sibling* enrichments' columns of the same source, so `write` on
enrichment E today yields read of source S **plus every other active enrichment hanging off S**.

## Migration footprint

~35 main files, ~20 test files, 3 doc files, one migration, plus the Flight authorizer. `UserRole`
in 21 files; `@FullAdminOnly` at 21 sites across 9 controllers → ≥6 distinct atoms; 7 programmatic
`FULL_ADMIN` checks; `Permission` 2 → 6; `TableAccess` 2 lists → 6; `EffectivePermissions` and
`TableDto.PermissionsDto` 2 booleans → 6; `TableCallerPermissions.isGranted` hard-codes a
WRITE/MODIFY ternary; the V5 `access` column default `'{"write": [], "modify": []}'` needs a new
migration.

Not to forget: 18 test files name the two roles and 10 configure `roles-mapping`; OpenAPI strings in
`QuerySchemaFieldDto`, `SavedQueryRequestDto`, `CreateTableRequest`, `TableAccessController`,
`TableDto.PermissionsDto`; `docs/configuration.md` (9 passages — the only place property descriptions
may live), `README.md` (5), `docs/claude-code-telemetry.md` (6), `docs/heavy-columns.md`.

Not a "swap the enum" change.
