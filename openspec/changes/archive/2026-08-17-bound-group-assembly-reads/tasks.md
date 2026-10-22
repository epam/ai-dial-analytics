## 1. Cross-repo gate

- [x] 1.1 Confirm the Enrichment Runner tolerates an unknown rule property in the sync feed: inspect its rule DTO deserialization (`FAIL_ON_UNKNOWN_PROPERTIES` / `@JsonIgnoreProperties`) in the Enrichment Runner repository and record the finding in this change's design under Open Questions — **cleared**: `JsonMapperConfiguration:37` disables `FAIL_ON_UNKNOWN_PROPERTIES` on the `@Primary` `JsonMapper`, and `AdasRegistryClient` parses the feed with that mapper
- [x] 1.2 If it does not tolerate one, open the runner-side change first and note here that ADAS release is gated on it — **not applicable**, 1.1 cleared

## 2. Rule model and persistence

- [x] 2.1 Add `V28__rule_member_select.sql` (PostgreSQL) adding a nullable `member_select` column to the rule table, following `ready_when`'s storage pattern
- [x] 2.2 Add the `MemberSelect` model (`prefer_sql`, `order_by` as an ordered list of `{column, direction}`, `limit`) under `service/enrichment/model/`, with the same JSON conversion approach `ReadyWhen` uses
- [x] 2.3 Add the `memberSelect` field to `RuleEntity` with its converter
- [x] 2.4 Add `member_select` to the rule request and response DTOs (snake_case `@JsonProperty`, per-field) and to the rule mapper, so it round-trips through `POST /v1/rules`, `PUT /v1/rules/{id}`, `GET /v1/rules`, `GET /v1/rules/{id}` and the sync feed

## 3. Validation

- [x] 3.1 Reject `member_select` on a rule whose `trigger_kind` is not `group` in `RuleGovernanceValidator`, with 422 `rule_validation_failed`
- [x] 3.2 Validate `prefer_sql` through `FilterSqlValidator` against the rule's **read source** (closed grammar, column resolution, boolean result), reusing the sensitive-column reattribution `filter_sql` already goes through
- [x] 3.3 Validate every `order_by` column resolves on the read source and every direction is ascending or descending
- [x] 3.4 Validate `limit` is at least 1 and at most the configured group-fetch maximum row count
- [x] 3.5 Unit-test each rejection returns 422 `rule_validation_failed`, and that a `member_select` referencing a sensitive column yields 403 `sensitive_column_not_entitled` for a non-entitled caller

## 4. Two-phase group assembly

- [x] 4.1 Expose the read source's ordering key through `ScanSourceMetadata` so the assembly can address a member by every ordering-key column
- [x] 4.2 Move the sensitive-column check in `GroupFetchService` so it covers the rule's bound input bindings, its filter and its `member_select` together, and runs before any read is issued
- [x] 4.3 Implement the selection phase: project the group key, cursor columns, filter columns, `member_select` columns and the source's full ordering key; apply the safety lag; apply `prefer_sql` with the fall-back-to-all-members rule; order by `order_by` then the source's total order descending; limit to the effective row cap
- [x] 4.4 Compute the effective row cap as the minimum of the caller's `limit`, the applicable configured default or maximum, and `member_select.limit`
- [x] 4.5 Implement the projection phase: fetch the bound input columns for the selected members by their full ordering-key values (single tuple-`IN` or per-member reads — decide by measurement per design), keeping `final = 1`
- [x] 4.6 Treat a member present in phase one but absent in phase two as excluded rather than an error, and let `truncated` reflect the shortfall
- [x] 4.7 Keep the byte cap and the single-oversized-member exception, and set `truncated: true` when the caps trimmed the group **or** `member_select` narrowed it
- [x] 4.8 Return members ascending in the source's total order in every path
- [x] 4.9 Fall back to the existing single-phase read when the read source declares no ordering key, returning an identical response

## 5. Tests

- [x] 5.1 Integration test: a group rule binding a body column reads the bound columns only for the retained members, and the selection phase's projection excludes them — covered by `memberSelectLimitLowersTheEffectiveRowCap`, which asserts the retained member carries a populated body (so the second read found it) and that no helper column leaks into the response
- [x] 5.2 Integration test: `prefer_sql` narrows the candidate set; a group where no member matches falls back to all members and still returns a label
- [x] 5.3 Integration test: `order_by` picks the intended members, ties break by the source's total order descending, and re-assembling an unchanged group returns the same members
- [x] 5.4 Integration test: `member_select.limit` below the caller's `limit` and the configured cap wins, and the response sets `truncated: true`
- [x] 5.5 Integration test: a rule with no `member_select` retains the newest members, bounded by the configured caps, exactly as before (regression over `scope-group-rule-reads`' scenarios) — covered by the pre-existing `groupsFetchRowCapRetainsTheNewestMembersInOrder` and `groupsFetchByteCapRetainsTheNewestMember`, both unchanged and passing
- [x] 5.6 Integration test: a source with no ordering key is assembled in one phase with an identical response — retargeted: a source with **no ordering key cannot be created** (`TableSchemaCreationService:109` rejects an empty `ordering_key`), so the reachable form of this path is a rule with no bound input columns, which every existing group test exercises. Spec scenario updated to match
- [x] 5.7 Integration test: entitlement is refused with 403 `sensitive_column_not_entitled` before either phase reads, for a rule binding a sensitive column and for a rule whose `member_select` references one
- [x] 5.8 Integration test: an empty or unknown group still returns HTTP 200 with `rows: []` and `truncated: false` — covered by the pre-existing `unknownGroupReturnsAnEmptyPage`, unchanged and passing

## 6. Documentation

- [x] 6.1 Document `member_select` in the rule API documentation alongside `ready_when` and `filter_sql`, including the fallback semantics and that a declared limit lowers the effective row cap — the `@Schema` descriptions on `MemberSelectDto`/`MemberOrderDto` and the rule request/response DTOs are this repo's rule API documentation; there is no rules doc page
- [x] 6.2 No new config key is introduced — confirm `docs/configuration.md` needs no change, and update it if implementation adds one after all — one description DID need widening: `enrichment.group-fetch.max-rows` now also bounds a declared `member_select.limit` (422 at registration), updated in `docs/configuration.md`

## 7. Verification

- [x] 7.1 `openspec validate bound-group-assembly-reads --strict` passes
- [x] 7.2 `./gradlew clean build` passes (Docker required for the integration tests)
