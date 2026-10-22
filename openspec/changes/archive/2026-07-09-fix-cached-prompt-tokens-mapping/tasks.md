## 1. Fix the mapping

- [x] 1.1 In `src/main/resources/mapping/dial-usage-log.yaml`, change the `cached_prompt_tokens`
  `source` from `token_usage.prompt_token_details.cached_tokens` to
  `token_usage.prompt_tokens_details.cached_tokens`.

## 2. Fix the test to guard the real behavior

- [x] 2.1 In `src/test/java/com/epam/aidial/analytics/sync/mapping/MappingEngineTest.java`, correct the
  fixture key from `prompt_token_details` to `prompt_tokens_details` so the test exercises the
  producer's real path.
- [x] 2.2 Keep an assertion that a reported cached prompt token count (e.g. 42) is stored, and an
  assertion that an absent count yields NULL, covering both scenarios in the spec delta.

## 3. Verify

- [x] 3.1 Run `./gradlew clean build` and confirm compile + checkstyle + tests pass.
- [x] 3.2 Confirm no other mapping entry, test, or fixture still references `prompt_token_details`
  (search the repo).
