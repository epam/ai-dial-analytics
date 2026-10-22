## 1. Trim the message

- [x] 1.1 In `SqlQueryFrontEnd.rootMessage()`, cut the parser message at Calcite's `Was expecting one of:`
      marker and keep the diagnosis that precedes it
- [x] 1.2 Pass the message through unchanged when the marker is absent, so an unfamiliar failure shape or
      a reworded Calcite version degrades to today's behaviour rather than to a mangled message
- [x] 1.3 Log the full untrimmed parser output at debug level at the same site, so the two never drift
      apart in a later refactor — `SqlQueryFrontEnd` has no logger today and needs one
- [x] 1.4 Confirm the two non-parse callers of `rootMessage` (validation, plan conversion) are unaffected:
      their messages carry no expectation marker, so the anchor leaves them alone without a guard

## 2. Tests

- [x] 2.1 Unit test: a malformed predicate yields a message containing the offending token and position
      and **not** containing `Was expecting one of:`
- [x] 2.2 Unit test for the fallback: a parser message with no marker is returned verbatim
- [x] 2.3 Unit test: a validation failure (unknown column, unsupported construct) is untouched — this is
      the boundary the change must not cross
- [x] 2.4 Assert the status code and stable `error` code are unchanged for a parse failure

## 3. Confirm all four surfaces

- [x] 3.1 Verify the trimmed message reaches `POST /v1/queries/execute-sql` and
      `POST /v1/queries/translate-sql`
- [x] 3.2 Verify it reaches the enrichment rule filter path (`POST /v1/rules`) and the aggregate pipeline
      filter path (`POST /v1/pipelines`), each still reporting under its own capability's error code
      rather than a borrowed one

## 4. Spec and close-out

- [x] 4.1 Confirm the shipped behaviour matches `specs/sql-query-translation/spec.md` in this change
- [x] 4.2 Run `./gradlew clean build` (Docker required for integration tests) and confirm it is green
