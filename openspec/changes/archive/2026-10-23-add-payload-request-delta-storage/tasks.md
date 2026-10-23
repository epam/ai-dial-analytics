## 1. Schema

- [x] 1.1 Add the next ClickHouse migration: `ALTER TABLE dial_usage_log_payload ADD COLUMN` for `request_scope`, `request_path`, `request_parent`, `request_depth`, `request_ancestors`, `request_envelope`, `request_delta`, `request_cache_markers` (types and codecs per design D8), plus `ADD INDEX idx_request_path … TYPE bloom_filter(0.001) GRANULARITY 1`; migration comment explains why (public-repo hygiene: no environment names or measured figures)
- [x] 1.2 Add the next Postgres catalog migration: three `column_mapping` rows on `dial_usage_log_payload` (`request_envelope`, `request_delta`, `request_cache_markers`; `string`, nullable, sensitive, heavy, descriptions, display names, ordinals after the existing columns), with a self-check like V38's
- [x] 1.3 Add `request_scope` to `src/main/resources/mapping/dial-usage-log-payload.yaml` (`derive: hash-uuid` over `project.id`, `deployment`, `user.id`) and update the mapping's header comment, including the "ingest never parses a payload" note
- [x] 1.4 Migration tests: columns and index exist after migrate, old rows read the defaults, the catalog exposes the three new qualified columns as sensitive + heavy and hides the chain columns

## 2. Configuration

- [x] 2.1 Add `sync.request-body-mode: ${SYNC_REQUEST_BODY_MODE:whole}` to `application.yml` and an enum-bound property (`whole` | `delta`); any other value fails startup
- [x] 2.2 Add the one-line `SYNC_REQUEST_BODY_MODE` row to `docs/configuration.md`
- [x] 2.3 Test: default is `whole`; an unknown value fails startup naming the accepted values

## 3. Request-body encoder (pure logic)

- [x] 3.1 Classifier + splitter: chat request iff JSON object with a message array (every element an object) under the first of `messages`, `input`, `contents` holding an array; history = present top-level system keys (`system`, `instructions`, `system_instruction`, `systemInstruction`, in that order), then the array; envelope = rest plus the `__adas_history` key record; everything else is "store whole" (MCP, embeddings, unparseable/truncated, other shapes)
- [x] 3.2 Normalizer: remove `cache_control` recursively from history elements only, recording `(element index, JSON Pointer, value)`; canonical JSON (sorted keys, no whitespace)
- [x] 3.3 Prefix hashing: `p0 = H(request_scope, UTC day)`, `pk = H(p(k-1), canonical(hk))`, SHA-256 truncated to 128 bits
- [x] 3.4 Rebuild helper (test-only for now): ancestors' deltas + own delta + envelope history keys + own markers → JSON-equal to the received body
- [x] 3.5 Unit tests for every scenario of "A chat request body is stored as an envelope, a delta and a parent link", "Cache markers are kept per event" and "A request body that is not a chat request is stored whole", including the moved-marker case, equal-text-different-history, Anthropic `system`, Responses `instructions`/`input`, Gemini `system_instruction`/`contents`, identical retry, and a property-style round-trip test over generated conversations

## 4. Parent resolution in the payload sink

- [x] 4.1 Path cache: bounded LRU of linked entries `(path, parentEntry)` (design D5), per instance
- [x] 4.2 Flush-time encoder in the payload sink (under `whole` it only stamps the path): sort chat rows by `request_time`, resolve against same-flush paths, then the cache, then one ClickHouse lookup per flush (day partitions + `request_path IN` deepest `TABLE_CANDIDATES` prefixes per unresolved row, chunked, capped per flush, no `FINAL`), then rewrite the row's request columns and null `request_body`
- [x] 4.3 Add flushed paths to the cache only after the INSERT succeeds; a failed flush leaves the cache untouched and keeps the existing failure propagation and flush order (payload before `dial_usage_log`)
- [x] 4.4 Make sure `maxBatchBytes` accounting and the inline-file rewrite still apply before encoding
- [x] 4.5 Testcontainers ingest tests for the scenarios of "A parent never crosses a UTC day or a user scope": consecutive turns across two flushes and within one flush, midnight crossing, two users with identical histories, child before parent, re-ingest after the parent is stored (deduplicated read returns one row that rebuilds correctly), `whole` mode unchanged, switching `delta` → `whole`
- [x] 4.7 Keep flush memory near one batch under `delta`: hold only prefix hashes while resolving, re-parse each body when writing its row, render the envelope without keeping a tree
- [x] 4.6 Encoder throughput test on a large synthetic agent session (thousands of messages), recorded in the test, not in docs

## 5. Specs and docs

- [x] 5.1 Check the delta specs against the shipped behaviour (`data-sync`, `table-management`); update any query-surface spec text that describes `request_body` as always holding the full body
- [x] 5.2 Note in the query-function catalog examples or docs that `request_body` may be `NULL` for delta rows and point to `request_envelope` / `request_delta` (only where such examples exist)
- [x] 5.3 Public-repo hygiene sweep of every touched tracked file (`git grep -nIP` per CLAUDE.md)

## 6. Verification

- [x] 6.1 `./gradlew clean build` passes
