## 1. Caller values on the caller identity

- [x] 1.1 Add `logging-key`, `logging-salt`, `obfuscate-user-email` (default `true`) to the per-provider configuration and to the API-key configuration; fail startup when a key is set without a salt while obfuscation is on
- [x] 1.2 Implement the user-hash derivation (lowercase hex SHA-256 of salt + claim, raw claim when obfuscation is off; absent for a missing, non-single-string or blank claim) with a unit test against fixed invented fixtures
- [x] 1.3 Carry the user hash and the project on the caller identity for Bearer JWT, opaque token and HTTP API key, and expose both through the security-claims accessor
- [x] 1.4 Build the Flight API-key identity with the same claims, project and caller values as the HTTP one, and test that a key first seen on either surface yields the same caller values on both
- [x] 1.5 Verify the salt appears in no log line and no endpoint response

## 2. Caller values in row policies

- [x] 2.1 Admit `param` names `caller.user_hash` and `caller.project` (exact, case-sensitive) in the policy scope check, only as a direct operand of `eq` or `ne` against a `string` field; keep refusing every other `param` and every other placement
- [x] 2.2 Rewrite before translation: a string value node when the value is known; the whole comparison replaced by an always-`NULL` predicate when it is unknown, blank or longer than 512 bytes; at save time use a 512-byte placeholder for the 16 KiB check
- [x] 2.3 Resolve caller values from the request's identity on each read in the row policy filter, and confirm nothing caches a rendered predicate across callers
- [x] 2.4 Log at debug level and set a span attribute naming each unknown caller value a confining policy references
- [x] 2.5 Tests: store and read back a policy with a caller value; refuse other param names; refuse a non-string field, a function argument, a `case` branch, an `in` item and a comparison with a literal; a query's `param` named `caller.user_hash` is refused like any other and not bound to the caller

## 3. Behaviour against ClickHouse

- [x] 3.1 Integration tests for the spec scenarios: own rows only, unpolicied role lifts, the combined `OR` policy for user and project callers, no value reads nothing (including rows with an empty `user_hash`), negation of an unknown value, blank value
- [x] 3.2 Pin against ClickHouse what an unknown value does: it matches no row under `eq`, `ne` and `not`, including rows storing an empty string, so it is neither `IS NULL` nor an empty-string comparison
- [x] 3.3 Confirm the enrichment columns of `dial_usage_log_payload` are confined through the source policy: an integration test over the structured query with a payload column and over ad-hoc SQL on the source; the CSV and Flight SQL paths run the same executor and are covered by the existing surface tests
- [x] 3.4 Measure on a local ClickHouse whether the `user_hash` skip index is used through `additional_table_filters` with `FINAL`, and the cost of a confined enrichment read with no filter of its own; record the outcome in `design.md`. Outcome: no planner change

## 4. Specs and docs

- [x] 4.1 Add six rows to `docs/configuration.md`, one line each, marking the salts as credentials
- [x] 4.2 Confirm the delta specs match the shipped behaviour, including error codes, and that no `docs/*.md` contradicts them
- [x] 4.3 Run `openspec validate add-row-policy-caller-values --strict`

## 5. Verification

- [ ] 5.1 `./gradlew clean build` passes
