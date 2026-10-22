## Why

The pipeline registry is the only catalog surface whose **reads** require `FULL_ADMIN`. Every other
catalog read — table list/get, schema discovery, access read, evaluator list/get/version, the audit
feed — is open to any mapped application role, so a read-only admin can see what tables exist and what
their columns mean, but cannot see what actually populates them. That makes the role unable to answer
the questions it exists for: why is this column stale, which process writes it, is it running.

The gate was justified by "the declaration names the columns a pipeline reads, including `sensitive`
ones" (`openspec/specs/pipeline-registry/spec.md:62-65`). That justification is weaker than it reads:
`GET /v1/tables/{name}` already returns every column with its `sensitive` flag to a read-only admin
(`table-management/spec.md:196` scopes sensitive *hiding* to the query/discovery surface only), and
evaluator reads — the prompts themselves — are already open to any application role
(`EvaluatorController`, reads carry no `@FullAdminOnly`). The registry read is the odd one out rather
than a coherent boundary.

Fine-grained roles would supersede this, but the two-role model should not keep
read-only admins from the registry in the meantime. The line this change draws is **declaration and
runtime state are readable; row data is not** — which is the line the rest of the service already
draws.

## What Changes

- Pipeline reads — `GET /v1/pipelines`, `GET /v1/pipelines/{name}`, and
  `GET /v1/pipelines/{name}/revision/{revision}` — require any mapped application role instead of
  `FULL_ADMIN`. A read-only admin sees the full declaration: `filter`, `expression`, measures,
  bindings, the inlined resolved evaluator (prompt included), and the names of `sensitive` columns the
  declaration references.
- Pipeline **mutations and operator actions** keep `FULL_ADMIN`: `POST /v1/pipelines`,
  `PATCH /v1/pipelines/{name}`, `DELETE /v1/pipelines/{name}`, and — because they execute a query and
  write rows rather than report state — `POST /v1/pipelines/{name}/preview` and
  `POST /v1/pipelines/{name}/refresh`. The four runner data-plane endpoints keep their existing
  per-target `WRITE`-grant authorization, unchanged.
- The audit trail's `Pipeline`-resource narrowing is removed: `Pipeline` activities appear in the feed
  for a read-only admin, are directly readable by id, and `GET /v1/pipelines/{name}/revision/{revision}`
  follows the live read. This is not an add-on — the audit spec derives that narrowing *from* the live
  read's authority, so leaving it would contradict its own stated rule.
- **No redaction is needed, and none is added.** The read's response is declaration, counts and
  timestamps throughout, with two fields that hold a literal input value: the identity-valued state
  fields (`state.cursor_identity` and its `materialized_through_` and `clamp` counterparts), and
  `state.last_error`. The identity fields are safe by an existing rule of another capability — a scan
  cursor column may **never** be marked `sensitive`, refused at metadata declaration and again on a
  metadata patch (`ScanMetadataRules.requireNotSensitive`, `table-management/spec.md:445,1496`),
  precisely so the scan can project and page by it. A sensitive value therefore cannot reach
  `cursor_identity`. `state.last_error` stays verbatim: a failure string is diagnostic output, the same
  strings already reach a read-only admin through the catalog and scan surfaces, and scrubbing it would
  remove the field's only purpose. Both facts are written into the spec as a stated invariant, so a
  future relaxation of the cursor-column rule is forced to revisit this read rather than silently open
  a hole.

- **One bug fixed, because the widening made it reachable.** `GET /v1/pipelines/{name}` answered
  **HTTP 500** whenever its freshness diagnostics failed to resolve: `PipelineFreshnessDiagnostics.of()`
  catches the failure, but the resolver it called is transactional and its throw had already marked the
  read's transaction rollback-only, so the commit failed. Pre-existing — it fires for a `FULL_ADMIN`
  over a drifted declaration too — but widening the read adds a far more reachable trigger, a caller not
  entitled to a `sensitive` column named in an `aggregate` pipeline's `filter`. Fixed by resolving the
  aggregate branch through the guard-free token twin the `enrich` branch already uses (and whose own
  javadoc names this report as an intended caller), which cannot throw. The read now degrades as
  documented and reports the same diagnostics to every role. See `design.md` D7.

No breaking change: this is a widening. No request or response shape changes for a `FULL_ADMIN`
caller, and no caller that is authorized today loses access.

## Capabilities

### New Capabilities

None. This narrows an existing authorization requirement rather than adding a surface.

### Modified Capabilities

- `pipeline-registry`: the read-authority requirement changes from `FULL_ADMIN` to any mapped
  application role, mutations stay `FULL_ADMIN`, and a new requirement records the response-shape
  invariant (declaration only, no row values) plus its load-bearing dependency on the
  `table-management` cursor-column rule.
- `pipeline-registry` (second requirement): the freshness diagnostics are stated as best-effort and
  caller-independent, with the resolution path that makes both true written down.
- `audit-trail`: the resource-specific narrowing requirement drops its `Pipeline` clause — pipeline
  activities and pipeline snapshots follow the live read, which is no longer full-admin-only. The
  `SavedQuery` clause is untouched.
- `authentication`: the read-endpoint enumeration in **Mutating endpoints require FULL_ADMIN** gains
  pipeline list/get/snapshot, so the one place that lists what an application role may read stays
  accurate.

## Impact

**Endpoints** (`/v1`):

| Endpoint | Today | After |
| --- | --- | --- |
| `GET /v1/pipelines` | `FULL_ADMIN` | application role |
| `GET /v1/pipelines/{name}` | `FULL_ADMIN` | application role |
| `GET /v1/pipelines/{name}/revision/{revision}` | `FULL_ADMIN` | application role |
| `POST/PATCH/DELETE /v1/pipelines[/{name}]` | `FULL_ADMIN` | unchanged |
| `POST /v1/pipelines/{name}/preview`, `/refresh` | `FULL_ADMIN` | unchanged |
| `POST /v1/pipelines/{name}/{scan,group-rows,materialize,rows}` | authenticated + `WRITE` grant | unchanged |
| `POST /v1/audit/activities` (feed), `GET /v1/audit/activities/{id}` | `Pipeline` rows hidden from read-only admin | `Pipeline` rows visible |

**Code**:

- `web/controller/pipeline/PipelineController` — drop `@FullAdminOnly` from `listPipelines`, `get`,
  `snapshot`; keep it on `create`, `patch`, `delete`. Class javadoc rationale rewritten.
- `web/controller/pipeline/PipelineDataController` — unchanged (`preview`/`refresh` stay gated).
- `service/audit/AuditActivityService` — remove `pipelineNarrowing()` and its use in the feed
  specification and the single-activity `get()`.
- `service/pipeline/PipelineFreshnessDiagnostics` — resolve the aggregate branch via the token twin
  (D7); `service/pipeline/PipelineDeclaration` gains an `of(PipelineEntity)` factory that
  `EnrichSpecValidator.projectionOf` now delegates to.
- `web/mapper/pipeline/PipelineMapper` — **no change**. The response shape is identical for every
  role, which is what keeps this change small: no new dependency on `SensitiveColumnAccess`, no
  per-caller branch in the mapper, no extra catalog lookup on the read path.
- `security/OidcSecurityConfiguration` — no rule change; the `/v1/**` application-role rule already
  admits these reads once the method annotation is gone. Its comment block naming the registry
  endpoints as deliberately full-admin needs correcting.

**Tests**: `AuditTrailAuthIntegrationTest` has two assertions that encode the old behavior
(`singlePipelineActivityIsNotDirectlyReadableByReadOnlyAdmin` and the feed-narrowing case) and must be
inverted. `PipelineDataAuthorizationIntegrationTest` covers the data plane and should keep passing
unchanged — it is the regression check that this change did not widen the runner endpoints.

**Docs**: `docs/` security/API notes that describe the registry as full-admin-only.

**Not affected**: query execution, sensitive-column hiding on the query/discovery surface, the Flight
surface, and the Enrichment Runner's own authorization — the runner is not a global `READ_ONLY_ADMIN`
and continues to be authorized per target.
