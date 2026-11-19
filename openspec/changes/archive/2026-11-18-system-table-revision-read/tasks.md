## 1. Snapshot service

- [x] 1.1 In `AuditSnapshotService.snapshot`, load the definition with `reader.find` before the creation guard. Keep
  the 404 when it is `null`. Run `requireCreationWithinTheTrail` only when the definition is not `system`.
- [x] 1.2 For a `system` definition, build the `TableView` with `null` columns and skip `columnsAtRevision`. Keep the
  `life` and enrichment `sourceLife` resolution unchanged. Update the class and record Javadoc (`TableView`,
  `TableSnapshot`) to say a `null` column list means "not tracked".

## 2. Mapping and permissions

- [x] 2.1 In `TableMapper.toDto(TableView, ColumnVisibility, …)`, map a `null` `view.columns()` to `null` `columns`
  instead of filtering it. Every live read still passes a list.
- [x] 2.2 In `TableCallerPermissions.hides`, treat a `null` `ownColumns` as untracked and return
  `!visibility.isAll()`. Document it on `resolve(definition, visibility, ownColumns)`.
- [x] 2.3 Update the `@Operation` description of `GET /{name}/revision/{revision}` in `TableController`. A system table
  answers at every revision from its first recorded state, with no `columns`. Any other table whose creation predates
  the trail still answers 404.

## 3. Tests

- [x] 3.1 Add integration coverage, one test per new scenario in `specs/audit-trail/spec.md`. The Postgres container
  is shared by every test class and other classes write `dial_usage_log`'s access, so no test may assume what the
  seeded table's trail held before it ran. Each test reads only revisions it recorded itself:
  - on `dial_usage_log`, two access `PUT`s: role `Y` with `read` at `R0`, then role `X` with `read` and a row policy
    at `R`. Both snapshots answer 200; `R` carries `roles.X`, and `R0` carries `roles.Y` and no entry for `X`;
  - at `R` or `R0`: no `columns`;
  - at `R` or `R0`: a `TABLE_GET`-only caller gets neither `roles` nor `columns`;
  - at a revision the test recorded: `columns_restricted` is `true` for a confined caller and `false` for an
    unconfined one;
  - before the first recorded state, on a throwaway system table seeded through JDBC (`system = true`, no audit rows),
    so its first recorded state is known whatever ran first. Record a revision with an audited write to another
    entity (such as an access `PUT` on `dial_usage_log`), then `PUT` an entry with `read` only on the throwaway table,
    and assert `404 revision_not_found` at the first revision and 200 at the `PUT`'s. Remove the throwaway table's
    live and audit rows through JDBC afterwards;
  - on the same throwaway table, seeded with `ordering_key` `{k}`: `["k"]` at the `PUT`'s revision, still `["k"]`
    after a JDBC `UPDATE` of the live row to `{m}`, and `["m"]` at the next access `PUT`.

  Use role names no other test class uses. Clean up every entry the tests add.
- [x] 3.2 Add a unit test for `TableCallerPermissions` covering a `null` column list under a confining and a
  non-confining visibility.
- [x] 3.3 Keep a non-system pre-trail table at 404. Seed a user table's definition and one definition-only audit row
  with no `ADD` row through JDBC, and assert `revision_not_found` at that revision.
- [x] 3.4 In `AuditTrailAuthIntegrationTest.theSnapshotRouteMatchesTheLiveReadsAuthorization`, probe revision `0`
  instead of `1`, so the `revision_not_found` assertion no longer depends on whether revision 1 wrote
  `dial_usage_log`. Reword its comment.

## 4. Docs

- [x] 4.1 Add a section to `docs/release-0.24-upgrade.md` for history clients such as the Admin UI. Revisions of
  `dial_usage_log`, `rate_analytics` and `dial_usage_log_payload` now answer 200 with `roles` and no `columns`, and the
  access edits already recorded become readable. List it in that guide's summary. No `docs/configuration.md` change,
  because no property is added.
- [x] 4.2 Confirm the merged `audit-trail` and `table-access-control` specs match the shipped behaviour, and that `docs/row-level-security.md`'s
  "revision snapshots included" line still holds (a system snapshot shows no columns at all).

## 5. Verification

- [ ] 5.1 Run `./gradlew clean build` and confirm it passes.
