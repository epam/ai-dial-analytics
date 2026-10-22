# Tasks: readable-table-access

## 1. Implementation

- [x] 1.1 Remove `@FullAdminOnly` from `TableAccessController.get` (URL rule keeps it
      application-role-gated); keep it on `replace`. Update the controller and
      `TableAccessService` javadoc and the `@Operation`/`@Tag` wording.

## 2. Tests

- [x] 2.1 Update `TableAccessIntegrationTest`: read-only caller `GET`s access → 200 with lists;
      the same caller's `PUT` stays 403; a roleless (provider-role-only) caller's `GET` → 403.

## 3. Specs & verify

- [x] 3.1 Delta spec matches shipped behavior; `openspec validate readable-table-access` passes.
- [x] 3.2 `./gradlew clean build` passes.
