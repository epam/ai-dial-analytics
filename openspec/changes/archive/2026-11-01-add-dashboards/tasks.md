## 0. Privilege implication

- [x] 0.1 Add the direct edges `SAVED_QUERY_WRITE → SAVED_QUERY_READ` and `SAVED_QUERY_PUBLISH → SAVED_QUERY_READ` to `PrivilegeImplications`, and rewrite its class Javadoc for five edges and the second admission ground (a privilege editing documents by whole-document replace implies their read, provided no read check consults it), keeping the reason `TABLE_WRITE` implies nothing; the Javadoc and the matching comment in `PrivilegeModelTest` state rules and their reasons only
- [x] 0.2 Drop `SAVED_QUERY_READ` from the declared sets of `READ_ONLY_ADMIN`, `READ_ONLY_ANALYTIC` and `READ_ONLY_SENSITIVE_ANALYTIC` in `RoleBundle`, so they stay minimal, and add to its class Javadoc that a mapping naming either saved-query write without `SAVED_QUERY_READ` now confers the read, and that `FULL_ADMIN` no longer sees other users' `personal` saved queries
- [x] 0.3 Update `PrivilegeModelTest`: five direct edges, `closureOf(SAVED_QUERY_WRITE)` is `{WRITE, READ}` and `closureOf(SAVED_QUERY_PUBLISH)` is `{PUBLISH, READ}`, bundle closures unchanged, and the minimal declared set without `SAVED_QUERY_READ`; add a mapping-parser case where a role mapped to either write alone also holds the read
- [x] 0.4 Saved-query integration tests, in `SavedQueryAuthIntegrationTest`'s style, with a provider whose `roles-mapping` names `["SAVED_QUERY_WRITE"]` and `["SAVED_QUERY_WRITE","SAVED_QUERY_PUBLISH"]`, so that the closure under test is the parser's rather than authorities injected directly: a caller mapped to `SAVED_QUERY_WRITE` alone lists and reads its own `personal` and every `common` saved query and reads a `common` saved query's revisions, and is still refused `403` writing a `common` one; neither that publishing mapping nor `FULL_ADMIN` reads another caller's `personal` saved query by id or at a revision; existing saved-query tests stay green
- [x] 0.5 Remove `SavedQueryAccess.maySeeEverySavedQuery`, the publisher bypass of the owner rule, from `SavedQueryController.requireCurrentlyVisible` and from both narrowings in `AuditActivityService`, so a `personal` saved query and a `private` dashboard are their owner's alone everywhere and a deleted one's activities are visible to no one; update `AuditTrailAuthIntegrationTest` to match

## 1. Schema and entity

- [x] 1.1 Claim the next free Postgres migration version after the newest file in `db/migration/postgres`, and add `V<n>__dashboard.sql`: `dashboard` (`id` PK, `name`, `description`, `visibility` with a `CHECK` over the Java constant names, `owner_id`, `owner_email`, `time_intent jsonb`, `comparison` with a `CHECK` over the Java constant names allowing null, `widgets jsonb NOT NULL`, `generation`, `created_at`, `updated_at`, plus an index serving the visible-list predicate) and `dashboard_aud` mirroring every audited column like `saved_query_aud`, with no `generation` column. The header is one line linking `openspec/specs/dashboards/spec.md`
- [x] 1.2 Add `service/dashboard/`:
  - the token enums `DashboardVisibility` (`private`, `public`), `DashboardCompare` (hyphenated wire tokens), `WidgetView`, `ChangeDirection` and `InlineResultView` (`table`, `chart`, the saved-query tokens, stored as they are);
  - each enum has a `@JsonValue` token and a `String`-only `@JsonCreator` that matches the tokens case-insensitively and throws on anything else, so neither `1` nor `"1"` binds by position;
  - the widget, layout and inline-query value records used for JSON conversion.
- [x] 1.3 Add `DashboardEntity` (`@Audited`, enums as `@Enumerated(EnumType.STRING)`, JSON payloads as `String` with `@JdbcTypeCode(SqlTypes.JSON)`, `@Version Long generation`) and `DashboardRepository` with a `findVisible` lookup by id (`visibility = PUBLIC OR owner_id = :author`, as for saved queries) and a visible-list constructor projection without `widgets` but with `owner_id`, ordered by `updated_at DESC`. Both drop the owner filter only when security is disabled
- [x] 1.4 Add `DashboardJson`, which converts `widgets` and `time_intent` with the application's Jackson 3 mapper, following `SavedQueryJson`

## 2. Validation

- [x] 2.1 Add body-level entry points to `SavedQueryBodyValidator` and `SavedQueryBodyResolver`, and have the existing DTO-taking methods delegate to them without changing saved-query behaviour. Existing saved-query tests must stay green. The entry points are:
  - a body entry point on the validator: the one-body rule, blank-`sql` normalisation, parameter rejection, and the size cap over body and chart plus an `extraBytes` argument; the saved-query path passes its serialized `time` as `extraBytes`, so its cap is unchanged;
  - a time entry point on the validator: it takes a raw `time` `JsonNode`, binds it and then runs the `validateTime` rules;
  - a resolver entry point: translation and source derivation.
- [x] 2.2 Add `DashboardValidator`, covering:
  - **Envelope:** `name` and `description` bounds, with `name` stored trimmed; `visibility` present; `widgets` present and non-null; the serialized `widgets` document at most 1 MiB; server-assigned and `permissions` members rejected on create and replace.
  - **`generation`:** forbidden on create. On replace it is required and must be an integral `JsonNode` within `Long` range; `null`, `3.0`, `"3"` and oversized values are rejected like an absent one.
  - **Widget count and members:** at most 100 widgets; widget id rules and uniqueness; widget text bounds.
  - **Integers:** `row_limit` and every `layout` coordinate as integral `JsonNode`s, never coerced; `row_limit` within 1–1000; the grid fit, with `x`/`w` bounded before the sum.
  - **Sources and inline queries:** exactly one query source; inline `name` required and `result_view` defaulting to `table`; blank `saved_query_id` and blank inline `sql` normalised to absent.
  - **Delegated checks:** the time intent through the 2.1 time entry point; each inline body through the 2.1 body entry point with `extraBytes` `0`, its message prefixed `widget <id>: `.
  - **Ownerless withdrawal:** withdrawing a `public` dashboard whose `owner_id` is null is a `422` naming the missing owner.
- [x] 2.3 Unit tests for every validation rule in `specs/dashboards/spec.md`, covering both boundaries: `x + w = 12` accepted and `13` rejected, the overflow coordinate rejected, `row_limit` 1 and 1000 accepted and 0 and 1001 rejected, `row_limit` and `x` of `2.5`, `2.0` and `"5"` rejected, the 1 MiB `widgets` bound

## 3. Access and service

- [x] 3.1 Add `DashboardAccess`: current author and email, `requireAttributable`, `requireAnyWriteAuthority`, `requireWritable(visibility)` (`PRIVATE` → `SAVED_QUERY_WRITE`, `PUBLIC` → `SAVED_QUERY_PUBLISH`), `permissions(entity)` → `{write, publish}`, and `listPermissions()` → `{create_private, create_public}`. The flags also respect the unresolved-principal refusal, and all of them fail open in `none` mode
- [x] 3.2 Add `DashboardService` with `list`, `get`, `create`, `replace` and `delete`:
  - **`create`:** a `dash_` + random id and the owner from claims. Checks run in the order any-write-authority → requested-visibility authority → validation and translation → `requireAttributable`.
  - **`replace`:** runs in the spec's five-step order and preserves id, owner and `created_at`.
  - **`delete`:** the any-write-authority check runs before the lookup.
  - **Translation errors:** a rejection is wrapped in `InlineQueryRejectedException(widgetId, cause)`.
  - **Flushing and conflicts:** `replace` uses `saveAndFlush`, and `delete` calls `delete` then `flush`. Each catches `ObjectOptimisticLockingFailureException` inside the transaction and rethrows `DashboardConflictException`.
- [x] 3.3 Add `DashboardNotFoundException`, `DashboardConflictException`, `DashboardValidationException` (or reuse the saved-query validation exception if its handler is generic) and `InlineQueryRejectedException`, and map them in `DefaultExceptionHandler`:
  - `404 not_found`;
  - `409 dashboard_conflict` for `DashboardConflictException` only; there is no global handler for the optimistic-lock exception, which would otherwise fall to `503 postgres_unavailable`;
  - `422 validation_error`;
  - `500 principal_unavailable` for a `private` create by an unattributable caller;
  - for `InlineQueryRejectedException`, the `ErrorView` the cause's own handler produces (found with `ExceptionHandlerMethodResolver.resolveMethodByThrowable` over `DefaultExceptionHandler`), returned with that view's status and its message prefixed. A test covers an `IllegalFinalException` cause keeping `illegal_final`, and a `SqlTranslationException` cause keeping `bad_request`.

## 4. Web layer

- [x] 4.1 Add DTOs under `web/dto/dashboard/`: `DashboardRequestDto` (server-assigned members as hidden `JsonNode`/`String`, `generation`, `time`, `row_limit` and `layout` coordinates as `JsonNode`, `@Valid` cascade into widgets → `inline_query` → `query`), `DashboardDto`, `DashboardListItemDto`, `DashboardListDto` and `PermissionsDto` variants, all snake_case with `@JsonProperty` and nulls omitted
- [x] 4.2 Add `DashboardMapper` (entity ↔ DTO, list projection without widgets, permissions filled from `DashboardAccess`)
- [x] 4.3 Add `DashboardController` under `/v1/dashboards`: reads `@RequiresPrivilege(SAVED_QUERY_READ)`, writes `@RequiresPrivilege(value = SAVED_QUERY_WRITE, deferred = true)`, `201` on create and `204` on delete, with OpenAPI annotations; add `DashboardController#create`, `#replace` and `#delete` to `DEFERRED_GLOBAL_ALLOWLIST` in `EndpointAuthorizationCoverageTest`, naming `DashboardAccess.requireWritable` as the chokepoint, and confirm the test passes

## 5. Audit trail

- [x] 5.1 Add `ActivityResourceType.Dashboard`, map `DashboardEntity` in `AuditResourceMapper` (resource id = dashboard id, no parent), and add the dashboard `EXISTS` narrowing clause in `AuditActivityService` for both the feed and single-activity retrieval, bypassed for `SAVED_QUERY_PUBLISH` and in `none` mode
- [x] 5.2 Integration tests:
  - create, replace and delete record `Create`, `Update` and `Delete` for `Dashboard`, and `dashboard_aud` has no `generation` column;
  - a `409` or a read records nothing;
  - owner, non-owner (a full admin included), public and deleted-dashboard visibility (visible to no one), both in the feed and in single-activity retrieval, where an invisible activity answers as absent.

## 6. Integration tests for the API

- [x] 6.1 `@SpringBootTest` Postgres integration tests:
  - **CRUD and list:** CRUD; list contents and ordering; list items without widgets; null omission.
  - **Round-tripping:** widgets, `time` and `compare`; `name` stored trimmed; blank `saved_query_id` and `sql` not round-tripped.
  - **Binding:** a missing `widgets` member answers `422`; `visibility` of `1` and `"1"`, `compare` of `1`, `view` of `2`, `view` of `"pie"` and inline `result_view` of `1` answer `400`; `row_limit` of `2.5` and `"5"` answers `422`; `compare` round-trips as `previous-period`.
  - **Saved-query references:** a loose `saved_query_id` (an absent target is accepted, and deleting a referenced saved query leaves the dashboard unchanged).
  - **Inline queries:**
    - an untranslatable or unresolvable inline body answers `400` with the `widget <id>: ` prefix;
    - an inline body binding a literal to a `sensitive` column is accepted;
    - a replace by an editor whose catalog view cannot resolve a stored inline body answers `400`;
    - deleting a table an inline body reads leaves the dashboard unchanged.
- [x] 6.2 `oidc`-mode authorization tests, with each caller's privileges set through `roles-mapping` properties as `SavedQueryAuthIntegrationTest` does, never as injected authorities:
  - **Visibility:** a private dashboard is invisible to others (`404` on read, replace and delete); a public one is readable with `SAVED_QUERY_READ`; `SAVED_QUERY_READ` is required for reads.
  - **Publish-only callers:** a caller holding only `SAVED_QUERY_PUBLISH` creates a public dashboard, replaces it with the generation from its create response, deletes another caller's and reads the public one it created, but does not see another caller's private dashboard; its withdraw attempt answers `403`.
  - **Write-only callers:** a caller mapped to `SAVED_QUERY_WRITE` alone lists and reads a public dashboard but gets `403` deleting it.
  - **Publishing:** public writes need `SAVED_QUERY_PUBLISH`; visibility changes in both directions need it; withdrawing another caller's public dashboard returns it to its owner; withdrawing a public dashboard with a null owner answers `422`.
  - **Write refusals:** callers with no write privilege get `403` before the lookup.
  - **Unresolved principals:** a caller with no resolvable principal lists public dashboards only and reports `create_private` false; an unattributable private create gives `500 principal_unavailable`.
  - **Bundles:** the `READ_ONLY_ANALYTIC` bundle creates a `private` dashboard and is refused `403` on a `public` one.
  - **Permissions:** every `permissions` flag combination from the spec.
- [x] 6.3 Generation tests:
  - the current generation succeeds and increments, and a stale one gives `409` and leaves the row unchanged;
  - a missing, `null`, string, `3.0` or fractional generation gives `422`;
  - an `If-Match` header is ignored;
  - a forbidden stale replace gives `403`, not `409`;
  - two concurrent replaces from one read give exactly one `200` and one `409`;
  - a write committed on another connection after a replace or delete has loaded the dashboard (forced from inside the authority check, not left to thread scheduling) makes the versioned `UPDATE` or `DELETE` at flush answer `409 dashboard_conflict`, change nothing and record no activity: replace losing to a replace, replace losing to a delete, and delete losing to a replace;
  - two concurrent deletes give one `204` and one `404` or `409`.
- [x] 6.4 `none`-mode test: both visibilities create with a null owner, the list returns all, and every permission flag is `true`

## 7. Docs and specs

- [x] 7.1 Update the one-line description of `SAVED_QUERIES_MAX_BODY_BYTES` in `docs/configuration.md` to say it also bounds each dashboard inline query; add no other configuration rows
- [x] 7.2 Mention `/v1/dashboards` (capability `dashboards`) in `README.md` where the saved-query surface is listed, and in the endpoint overview of `CLAUDE.md`'s "What this is" paragraph
- [x] 7.2a Update the Javadoc and `docs/configuration.md` for the new edge:
  - the `Privilege` class Javadoc: its "admitted only where it is disclosure-monotone" sentence, for the second admission ground;
  - the Javadoc of `Privilege.SAVED_QUERY_READ`/`WRITE`/`PUBLISH`: they also govern dashboards, and `SAVED_QUERY_WRITE` and `SAVED_QUERY_PUBLISH` each imply `SAVED_QUERY_READ`;
  - the "Bundle | Declares" table in `docs/configuration.md`: drop `SAVED_QUERY_READ` from its three rows and add no prose: the implication is stated in the authentication spec.

  `docs/release-0.2-upgrade.md` describes that release and stays as written.
- [x] 7.2b Add to the upgrade notes of the release this change ships in, following the existing `docs/release-*-upgrade.md` documents and creating the next one if it does not exist yet: the `SAVED_QUERY_WRITE → SAVED_QUERY_READ` and `SAVED_QUERY_PUBLISH → SAVED_QUERY_READ` widening (a mapping naming either write without `SAVED_QUERY_READ` gains the reads the authentication spec lists, and no bundle widens), and the narrowing: `FULL_ADMIN` and every `SAVED_QUERY_PUBLISH` holder no longer see other users' `personal` saved queries on the revision endpoint or in the activity feed, and a deleted saved query's or dashboard's activities are visible to no one
- [x] 7.3 Run `openspec validate add-dashboards --strict` and confirm the specs match the shipped behaviour
- [x] 7.4 Run the public-repo hygiene sweep from `CLAUDE.md` (`git grep -nIP` plus the multiline pass) over the changed files
- [x] 7.5 Run `./gradlew clean build` and confirm it passes
