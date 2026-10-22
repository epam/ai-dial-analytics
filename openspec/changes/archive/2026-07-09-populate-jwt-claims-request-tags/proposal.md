## Why

The `jwt_claims` and `request_tags` columns exist in `dial_usage_log` (ClickHouse `JSON` type) but have
never been populated — the mapping and spec deliberately left them as "blocked" columns pending this
work. Clients need the authenticated caller's JWT claims and the request headers for per-record
attribution and tagging. Today every row stores the empty-JSON default, so this data is lost even
though the producer emits it (as top-level `claims` and `headers` objects).

## What Changes

- Populate `jwt_claims` from the record's top-level `claims` object and `request_tags` from the
  top-level `headers` object, storing each as a JSON object (verbatim passthrough of the object
  subtree).
- Add an **object** extraction kind to the mapping engine so a column can carry a whole JSON object
  subtree into a ClickHouse `JSON` column (all existing kinds coerce scalars or a string array).
- Teach the startup schema validator to recognize the ClickHouse `JSON` type as a first-class family
  (today it is "unknown → warned-and-allowed") and to map the new object kind to it.
- `request_tags` captures headers **verbatim** — no allowlist/denylist in this service. Filtering out
  transport/SDK noise (e.g. `X-Stainless-*`, `Content-Length`) is a DIAL Core producer-side concern.
- Update the `data-sync` spec: `jwt_claims`/`request_tags` are no longer "blocked"; only
  `operation_duration_ms` remains unpopulated.
- Fix-forward only: pre-existing rows keep their empty-JSON default. Backfilling, if needed, is out of
  scope and handled via the separate backfill endpoint.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `data-sync`: `jwt_claims` and `request_tags` become populated columns (removed from the blocked
  set); add an object-passthrough extraction kind; the startup validator recognizes the `JSON` type
  family.

## Impact

- `src/main/resources/mapping/dial-usage-log.yaml` — add `jwt_claims` (source `claims`) and
  `request_tags` (source `headers`) with the new `object` kind.
- `src/main/java/com/epam/aidial/analytics/sync/mapping/ColumnKind.java` — add `OBJECT` kind.
- `src/main/java/com/epam/aidial/analytics/sync/mapping/MappingEngine.java` — `coerce`/`zero`/`isBlank`
  handling for the object kind (pass the `JsonNode` through; empty object `{}` as the non-nullable
  zero value).
- `src/main/java/com/epam/aidial/analytics/sync/mapping/SchemaValidator.java` — add a `JSON` type
  family and `expectedFamily(OBJECT)`.
- Tests: `MappingEngineTest`, `SchemaValidatorTest`, and a `UsageLogWriter` serialization assertion
  that a `JsonNode` object serializes into the JSON column via `JSONEachRow`.
- `openspec/specs/data-sync/spec.md` — via the delta in this change.
- Runtime: newly ingested rows carry real `jwt_claims`/`request_tags`; no change to dedup, the sort
  key, or any other column. Note the PII surface — `claims` and full `headers` may carry sensitive
  values; retention is bounded by the existing TTL and column access controls.
