## 1. Body hash and lookup

- [x] 1.1 Add the body hash to `PathHash` under its own seed (scope, day, text), with a test that it differs from the chat prefix hash of the same text as a first history element
- [x] 1.2 Extend `ParentLookup` with a carrier lookup taking the days and the body hashes and returning each carrier found as hash, `event_id` and `request_time` in epoch milliseconds, defaulting to "none found"; implement it in `ClickHouseParentLookup` as its own statement: `FINAL`, deduplicated inside the partition, `use_skip_indexes_if_final` and `use_skip_indexes_if_final_exact_mode` set to 1, chunked and time-bounded like the chat lookup; adapt the test `Table` lookup

## 2. Ingest

- [x] 2.1 In `RequestDeltaEncoder`, make a body eligible when it holds at least 65,536 UTF-16 code units, is scoped, has a `request_time` that parses to a UTC instant, fails the chat parse, and also fails a token-stream read to the end of input with duplicate detection off and read limits lifted; ineligible rows stay as today
- [x] 2.2 Under `whole-with-path`, stamp an eligible row with its body hash
- [x] 2.3 Under `delta`, resolve an eligible row against earlier rows of the flush and the carrier lookup (not the path cache), dropping every returned carrier whose `event_id` belongs to any row of the batch and comparing epoch milliseconds: strictly earlier carrier → copy (empty body, parent and ancestors = hash, no `request_hash`), else carrier; bound the body hashes of a flush by the candidate cap; count copies under their own `outcome`
- [x] 2.4 Keep eligible rows out of the path cache
- [x] 2.5 Record a link for a copy made within the flush, and on a split batch re-root a copy whose carrier lies in another part: body restored, `request_hash` = hash, no parent
- [x] 2.6 Unit tests in `RequestDeltaEncoderTest` for the ingest scenarios of "A large request body equal to an earlier one is stored once", plus: a lookup that returns the row's own event leaves it a carrier, whatever time it reports; a returned carrier whose event is an ineligible row of the batch makes no copy; a valid JSON value followed by trailing text is eligible; a failed carrier lookup leaves carriers and is counted

## 3. Read

- [x] 3.1 In `RequestBodyRebuild`, before any JSON is read: an empty stored body whose chain is exactly one row with no parent returns that row's body as `rebuilt`; an empty body with any other chain is `partial`/`invalid`; an absent carrier is `partial`/`missing`. Unit tests for the three
- [x] 3.2 Integration test on ClickHouse: carrier and copy ingested in separate flushes; the copy reads back whole with `rebuilt`, and `resolvable` when `request_body_rebuild` is projected alone; a carrier re-ingested under `delta` keeps both readable; a carrier re-ingested without its hash, its replaced version still unmerged, does not make a new copy; the carrier statement run through `EXPLAIN` uses the hash skip index

## 4. Docs, catalog and spec

- [x] 4.1 Update the class notes of `RequestDeltaEncoder`, `ChatRequest.parse`, `ParentLookup`, `ClickHouseParentLookup`, and `RequestBodyRebuild`, the field descriptions in `ComputedFields`, and the comment in `mapping/dial-usage-log-payload.yaml`, where they say a non-chat body is always stored whole or describe `partial` as the stored delta
- [x] 4.2 Add a PostgreSQL migration at the next free version rewording the `request_body` description to name the empty body of a copy, only while it still equals the text the earlier migration seeded; update the test that pins the description
- [x] 4.3 Update `docs/heavy-columns.md`: the delta section and the `request_body_rebuild` status list
- [x] 4.4 Add to `docs/release-0.24-upgrade.md`: under `delta` a repeated large non-JSON request body is stored with an empty `request_body`, read it through `request_body_full`; re-ingest a whole UTC day on one release and one configuration; with copies stored, roll back to `whole-with-path` on this release, since an earlier release reads a copy as `partial` with an empty body
- [x] 4.5 Run `openspec validate dedup-equal-request-bodies --strict` and confirm the delta spec matches what shipped

## 5. Verification

- [ ] 5.1 `./gradlew clean build` passes
