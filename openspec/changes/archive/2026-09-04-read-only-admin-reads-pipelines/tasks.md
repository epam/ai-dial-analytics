## 1. Widen the registry reads

- [x] 1.1 Remove `@FullAdminOnly` from `PipelineController.listPipelines`, `PipelineController.get`,
  and `PipelineController.snapshot`; leave it on `create`, `patch` and `delete`. Verify the
  `FullAdminOnly` import is still used before touching it.
- [x] 1.2 Rewrite the `PipelineController` class javadoc: replace the "Every endpoint is
  `FULL_ADMIN`-only … which is why the read is not a read-only-admin surface either" paragraph with
  the split — mutations `FULL_ADMIN`, reads any mapped application role, and `preview`/`refresh` on
  `PipelineDataController` still `FULL_ADMIN` because they execute a query or write rows.
- [x] 1.3 Correct the comment block in `OidcSecurityConfiguration` that names the registry endpoints
  as deliberately falling through to the app-role rule *and* staying `FULL_ADMIN`-gated: after 1.1
  only `preview` and `refresh` do. Do not change any matcher — the four data-plane matchers and their
  ordering stay exactly as they are.
- [x] 1.4 Fix the `@Operation` description on `snapshot`, which states "Gated the same as the live
  read: FULL_ADMIN only." The first clause stays true and the second does not.

## 2. Follow the read on the audit surface

- [x] 2.1 Remove `pipelineNarrowing()` from `AuditActivityService` and its use in both the feed
  specification and the single-activity `get()`. Leave `savedQueryNarrowing()` untouched.
- [x] 2.2 Drop the now-unused `Pipeline`-narrowing paragraph from the `AuditActivityService` class
  javadoc (the "two narrowings it inherits" sentence becomes one) and from
  `ActivityResourceType.Pipeline`'s javadoc if it repeats the bar.
- [x] 2.3 Confirm no role check remains on the pipeline snapshot path outside `PipelineController`.
  Verified during planning: `AuditPipelineSnapshotService`, `AuditSnapshotGuards` and
  `AuditSnapshotService` contain none, so 1.1 is the only gate to remove. Re-check rather than trust
  this note if those files have moved.

## 3. Tests

- [x] 3.1 In `AuditTrailAuthIntegrationTest`, invert
  `readOnlyAdminDoesNotSeePipelineActivitiesInTheFeedButFullAdminDoes` to assert the read-only admin
  now *does* see the `Pipeline` activity, and add the resource-type-filtered case.
- [x] 3.2 In the same test, invert `thePipelineSnapshotRouteRequiresFullAdmin` (rename it to state
  the new rule) and `singlePipelineActivityIsNotDirectlyReadableByReadOnlyAdmin` — the latter should
  now expect 200 with `resourceType: Pipeline` instead of `activity_not_found`. Keep
  `refusesAnAuthenticatedCallerWithNoApplicationRole` passing unchanged.
- [x] 3.3 Add registry read-authorization coverage: a read-only admin gets 200 from
  `GET /v1/pipelines`, `GET /v1/pipelines/{name}` and the revision snapshot; a roleless caller still
  gets 403; an unauthenticated caller still gets 401. Put it where the other auth integration tests
  live, following `TableAuthIntegrationTest`'s shape.
- [x] 3.4 Add the negative half in the same test: a read-only admin gets 403 from
  `POST /v1/pipelines`, `PATCH`, `DELETE`, `POST …/preview` and `POST …/refresh`. This is the
  assertion that stops a later refactor from widening the operator surface with the read.
- [x] 3.5 Assert the response is role-invariant for a pipeline with no `sensitive` reference: read the
  same pipeline as `FULL_ADMIN` and as `READ_ONLY_ADMIN` and compare the bodies field for field,
  including `state.cursor_identity` and `state.last_error`. This is the executable form of D3.
- [x] 3.6 Assert the one documented exception (D7): for an `aggregate` pipeline whose `filter`
  references a `sensitive` column, a `READ_ONLY_ADMIN` read returns 200 with the full declaration and
  an empty `state.unclamped_reads`, while the `FULL_ADMIN` read reports the unclamped enrichment.
  Assert the read is **not** refused — the point is that it degrades rather than 403s. Name the test
  so it is obviously the spec's entitlement clause, since the follow-up in D7 will delete both.
- [x] 3.7 Add a test that marking a scan cursor column `sensitive` is still refused (422), tied by
  name to the pipeline-read invariant it protects, so the cross-capability dependency in the spec has
  a test that fails if the rule is relaxed. `ScanMetadataRules.requireUsable` (declaration) and
  `requireNotSensitive` (patch) are the two enforcement points; check the existing
  `ScanMetadataRules`/`TableSchemaService` tests and extend rather than duplicate.
- [x] 3.8 Run `PipelineDataAuthorizationIntegrationTest` unmodified and confirm it passes. If it needs
  a change, stop — the widening reached the data plane, which it must not.

## 4. Docs and spec

- [x] 4.1 Update `README.md:174` — it states "a pipeline's activity and snapshot require
  `FULL_ADMIN`". Restate it as following the live read, and leave the saved-query sentence alone.
- [x] 4.2 Check `docs/` for any tracked page that describes the registry as full-admin-only
  and update it. `docs/configuration.md:391` is about the scheduler's system identity and is
  unaffected — do not touch it. No config property is added or renamed by this change, so
  `docs/configuration.md` needs no new row.
- [x] 4.3 No OpenAPI snapshot test exists in this repo (verified during planning: `src/test/resources`
  holds only `clickhouse/`), so nothing to regenerate. The generated spec changes only in the three
  operations' descriptions.
- [x] 4.4 Confirm the three delta specs still match what shipped — `pipeline-registry` (read
  authority + the no-row-values invariant), `audit-trail` (the `Pipeline` clause), `authentication`
  (the endpoint enumeration) — and run `openspec validate read-only-admin-reads-pipelines`.

## 5. Verification

- [x] 5.1 Run `./gradlew clean build` and confirm it passes (checkstyle included). Integration tests
  need Docker.
- [x] 5.2 Smoke-check in `oidc` mode with a read-only token: `GET /v1/pipelines` returns the registry,
  `POST /v1/pipelines/{name}/refresh` returns 403 `forbidden` with the `ErrorView` shape. **Covered by
  `PipelineRegistryAuthIntegrationTest` rather than by hand** — it asserts exactly these two calls
  against a real Spring context with `security.mode=oidc`, two token issuers, Postgres and ClickHouse.
  A `bootRun` pass would add only a live OIDC issuer, which `JwtTestTokens` stands in for, so it was
  not run separately.

## 6. Blocker found during apply — freshness degradation is broken

`GET /v1/pipelines/{name}` answers **500**, not a degraded 200, when
`PipelineFreshnessDiagnostics.of()` catches a resolution failure. `of()` is
`@Transactional(readOnly = true)` and the resolver it calls
(`AggregateSpecValidator.referencedEnrichments(String, String, AggregateSpec)`) is transactional too,
so the resolver's throw marks the shared transaction rollback-only; `of()` swallows the exception and
then its own commit fails with `UnexpectedRollbackException`. Reproduced by
`PipelineRegistryAuthIntegrationTest.freshnessDiagnosticsDegradeForNonEntitledCaller`.

**Pre-existing, not introduced here**: the same 500 fires for a `FULL_ADMIN` whose stored declaration
has drifted, which is the other case the class javadoc promises to degrade. This change adds a second,
much more reachable trigger (non-entitled caller + sensitive filter), which is why it surfaced now.

- [x] 6.1 Decide the fix (see `design.md` D7 — the options are the guard-free token twin, or a
  `REQUIRES_NEW` seam so the catch actually works) and implement it.
- [x] 6.2 Re-run `PipelineRegistryAuthIntegrationTest` and reconcile the `pipeline-registry` delta:
  the entitlement clause is deleted if the fix removes the role dependence, kept if it does not.
