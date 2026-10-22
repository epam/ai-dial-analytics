## 1. Remove the guard from the write path

- [x] 1.1 In `service/query/saved/SavedQueryBodyResolver.java`: drop the `SensitiveLiteralGuard` field and
      both `sensitiveLiteralGuard.check(...)` calls, leaving `resolveStructured` as `requireModel` +
      `queryTranslation.toSql` and `resolveSql` as translate + `requireModel`.
- [x] 1.2 In the same file, rewrite the two method javadocs that exist only to explain the guard's ordering
      (`resolveStructured`'s "entity resolution → sensitive-literal guard → translation" and `resolveSql`'s
      "translation necessarily comes first") so they describe what the methods now do.
- [x] 1.3 Delete `service/query/saved/SensitiveLiteralGuard.java`.
- [x] 1.4 Delete `exception/SensitiveLiteralNotAllowedException.java`.
- [x] 1.5 In `web/handler/DefaultExceptionHandler.java`: remove the
      `handleSensitiveLiteralNotAllowed` handler and its now-unused import, retiring the
      `sensitive_literal_not_allowed` machine code.
- [x] 1.6 Confirm no reference remains: `grep -rn "SensitiveLiteral\|sensitive_literal_not_allowed" src/`
      returns nothing.

## 2. Update documentation on the surviving classes

- [x] 2.1 In `web/controller/query/SavedQueryController.java`: fix the class javadoc clause claiming a body
      binding a literal to a `sensitive` column "is refused" and that no personal data is stored — state
      instead that a body is stored as authored, the metadata database being inside the same trusted
      perimeter as the data store.
- [x] 2.2 Verify `service/query/saved/SavedQueryService.java` needs no edit: its "sensitive-column rules"
      reference is about read-side column entitlement, which this change does not touch. Leave it as is.

## 3. Tests

- [x] 3.1 Delete `src/test/java/com/epam/aidial/analytics/service/query/saved/SensitiveLiteralGuardTest.java`
      (it tests only the deleted walker).
- [x] 3.2 In `integration/SavedQueryAuthIntegrationTest.java`, invert the three rejection cases
      (`anAdminCannotSaveLiteralBoundToSensitiveColumn`, the `sql`-body case, and
      `rejectsSensitiveLiteralReachedThroughSelectAlias`) to assert success: created status, and — where the
      test reads the row back — a stored body that still contains the literal verbatim. Rename each method
      to state the new expectation.
- [x] 3.3 Keep `anAdminCanSaveSensitiveColumnComparedToAnotherColumn` and the non-entitled-caller case
      (`400 bad_request`, no existence signal) unchanged — these assert behavior this change must preserve.
- [x] 3.4 Add a replace-path case: replacing an existing saved query with a body binding a literal to a
      `sensitive` column succeeds and stores the submitted body (covers the delta spec scenario that has no
      counterpart in the old tests).

## 4. Specs

- [x] 4.1 Edit the `## Purpose` section of `openspec/specs/saved-queries/spec.md` directly: the paragraph
      asserting "**No data observed in the data store is copied here.** That is the boundary this capability
      defends" is no longer true. Replace it with the trusted-perimeter premise, keeping the surrounding
      statements about what a saved query stores and the two-scope sharing model intact. (A delta cannot
      restate the Purpose of an existing capability — see design.md.)
- [x] 4.2 Verify the delta spec at
      `openspec/changes/allow-sensitive-literals-in-saved-queries/specs/saved-queries/spec.md` matches what
      was implemented; adjust it if implementation revealed anything different.
- [x] 4.3 Run `openspec validate allow-sensitive-literals-in-saved-queries` and resolve any finding.

## 5. Verify

- [x] 5.1 `./gradlew clean build` passes (compile + checkstyle + tests).
- [x] 5.2 Runtime check against the local stack: create a saved query whose filter binds a literal to a
      `sensitive` column and confirm it is created rather than rejected with `422`, then read it back and
      confirm the literal is returned unredacted.

## 6. Review follow-up (round 1): the read-side consequence

- [x] 6.1 Record the actual behavior instead of an overstated safety claim: the write gate bounded only
      *who may write* a sensitive literal, so proposal.md's "only reachable by callers already entitled to
      that column" is corrected to say so, and the `common`-scope read consequence is named as a second,
      separately signed-off decision in Impact.
- [x] 6.2 Add a design.md decision (**Leave the read path ungated, and say so in the spec**) with the two
      rejected alternatives — gating the read, redacting literals — and a Risks entry naming the
      `common`-scope read by a caller denied that column.
- [x] 6.3 Delta spec: state the `common` read consequence in the requirement text and add the scenario
      *A common sensitive literal is readable by a caller not entitled to the column*; note it in the
      REMOVED requirement's migration text. Correct the `openspec/specs/saved-queries/spec.md` Purpose,
      whose "Nothing about a saved query widens read entitlement" is no longer true.
- [x] 6.4 Fix the stale javadoc on `web/dto/query/saved/SavedQueryDto.java`, which still justified the
      unredacted read path with "no literal bound to a sensitive column can be stored in the first place",
      and extend `SavedQueryController`'s class javadoc with the read-side consequence.
- [x] 6.5 Cover the behavior by test: `commonSensitiveLiteralIsReadableByAnyApplicationRole` in
      `integration/SavedQueryAuthIntegrationTest.java` — the same `READ_ONLY_ADMIN` that gets a `400
      bad_request` for its own body over that column reads the admin's `common` body with the literal
      intact.
