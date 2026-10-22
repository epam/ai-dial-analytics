## 1. Configuration and mapping attribute

- [x] 1.1 Add the `InlineFilesMode` enum (`KEEP`, `DROP`) and a `sync.inline-files-mode` component on `SyncProperties`, defaulting to `KEEP` when unset; add `inline-files-mode: ${SYNC_INLINE_FILES_MODE:keep}` under `sync:` in `application.yml` (a bare line, no comment); test that an unknown value fails binding
- [x] 1.2 Add the `inline-files` attribute (`body`: request|response, `uri`: dotted path) to `ColumnMapping` as a small record, `null` when absent
- [x] 1.3 Parse and validate it in `MappingFileLoader`: reject it on derived, `$sourceFile`, non-`string` or `transform`-declaring columns; reject a missing or invalid `body`; reject a missing, blank or `$`-prefixed `uri`. Add loader tests for each rejection and for a valid entry
- [x] 1.4 Declare `inline-files` on `request_body` (`body: request`) and on `response_body` and `assembled_response` (`body: response`), all with `uri: request.uri`, in `src/main/resources/mapping/dial-usage-log-payload.yaml`; replace the "Stored verbatim: ingest never parses…" comment with one that names the mode
- [x] 1.5 Add the `SYNC_INLINE_FILES_MODE` row to the Data sync table in `docs/configuration.md` (one line: `keep|drop`, default `keep`, and that `drop` removes inline file payloads from the payload body columns and loses them)

## 2. Inline-file locator

- [x] 2.1 Add the API classifier: path from the URI with the query string removed, matched in order against anchored patterns copied from Core's `RouteTemplate` (chat via `/openai/deployments/{id}/chat/completions` and `/openai/v1/chat/completions`, embeddings, Responses, Anthropic incl. `count_tokens`, toolset and deployment MCP), else none. Keep the patterns in one class with a comment pointing to Core's `RouteTemplate`/`ControllerSelector`. Unit-test:
  - each pattern, with a query string, `//` leading slashes, and an application id containing `/` and `%2F`;
  - that `/claude_code_router/v1/messages`, `/v1/deployments/app/route/v1/messages`, `/v1/deployments/app/route/x/chat/completions`, legacy `/openai/deployments/m/completions` and a null URI give none;
  - that `/v1/deployments/app/route/x/mcp` gives MCP, as in Core
- [x] 2.2 Add the location table per API and side as compiled `*`-patterns, with the conditions (data URL with an escaped-prefix unescape, binary type, DIAL binary attachment, base64 source, MCP `type`, MCP binary `mimeType`), exactly as the spec's table lists them
- [x] 2.3 Implement the Jackson 3 (`tools.jackson`) char-based streaming locator for a JSON document and for each single-line SSE `data:` event:
  - path matching, and pending candidates decided at `END_OBJECT`;
  - start offset from the token location; end offset from a raw scan for the next unescaped quote;
  - no `getText()` on candidates;
  - on a parse error, keep the candidates found so far and stop;
  - never throw
- [x] 2.4 Pin with a test that, for a body cut inside a string at a file location, the open `VALUE_STRING`'s start offset and path are available before the parse error (design D4). If they aren't, report it before going on: the cut-back design depends on it
- [x] 2.5 Implement the stripper:
  - copy the kept ranges into one `StringBuilder`; a data URL keeps its prefix, any other value becomes `""`;
  - detect truncation as `>>` plus a UTF-8 length ≥ 4,194,306, computing the length only past the char-count lower bound;
  - scan a truncated body with the `>>` marker removed, so the marker is never read as part of the value open at the cut, and put the marker back on the output. Test that a 4,194,304-byte body ending in `>>` (not truncated) is scanned as a normal body;
  - cut back to before an open inline file and keep `>>`, otherwise leave the cut as is;
  - return the counts (files, payload bytes, cut back, unscanned)
- [x] 2.6 Locator/stripper tests:
  - every location per API and side, and a location of one API placed in another API's body (not removed);
  - conditions: DIAL `text/markdown` kept, binary type after `data` removed, Anthropic `source.type: text` kept, MCP `text/plain` blob kept, embedding number array kept;
  - base64 in `content`, tool arguments and unknown fields kept;
  - data URL parameters, an escaped prefix, `\"` and `\/` inside and before values, non-ASCII text before a file;
  - SSE: CRLF, `data:` without a space, `[DONE]`, a multi-line event left unscanned, an MCP `event: message` stream;
  - truncation: cut inside a file, inside text, inside a data URL prefix, with a condition sibling after the cut (a data-URL DIAL attachment included, open or in an unclosed object), and a trailing U+FFFD;
  - non-JSON and malformed bodies unchanged and counted unscannable;
  - output identical on repeated runs

## 3. Engine wiring and metrics

- [x] 3.1 In `MappingEngine`, for a column with `inline-files` and mode `DROP` (or, since 5.1, `DROP_TRUNCATED`): resolve the URI from the attribute's path on the record, rewrite the coerced string value, then apply the usual fallback. Under `KEEP`, call nothing at all
- [x] 3.2 Pass the mode, the target table and the `MeterRegistry` through `MappingConfiguration`. Register the `ingest.inline.files.removed`, `ingest.inline.bytes.removed`, `ingest.inline.bodies.cut` and `ingest.inline.bodies.unscanned` counters (tags `table`, `column`) once per declaring column, and only under `DROP` (or, since 5.1, `DROP_TRUNCATED`)
- [x] 3.3 `MappingEngineTest`:
  - `KEEP` stores the body byte for byte;
  - `DROP` rewrites only declaring columns, and `dial_usage_log`'s `request_body_bytes`/`response_body_bytes` derivers still see the original sizes;
  - the nullable fallback still yields `NULL` for an absent body;
  - the counters increment as the spec's scenarios state
- [x] 3.4 An ingest-level test (`BlobIngestor` over a gzip NDJSON fixture) with real-shaped records for chat (image data URL), Anthropic (base64 source), MCP (image + PDF blob), embeddings (base64 vector) and a truncated chat body: check the stored `dial_usage_log_payload` values and the unchanged `dial_usage_log` row

## 4. Specs, docs and verification

- [x] 4.1 Confirm the `data-sync` delta spec matches the shipped behaviour (locations, conditions, truncation rule, counter names), and adjust the delta where the implementation settled a detail differently
- [x] 4.2 Run `openspec validate payload-inline-file-drop --strict`
- [x] 4.3 Optional local runtime check: `bootRun` with `SYNC_INLINE_FILES_MODE=drop` against a throwaway local ClickHouse, ingest a fixture blob, and read back `request_body` through `POST /v1/queries/execute-sql` — NOT RUN (optional): the ingest path is covered end to end by `InlineFileIngestTest`
- [x] 4.4 Run `./gradlew clean build` (compile, checkstyle, tests) and make sure it passes

## 5. Follow-up: drop-truncated

- [x] 5.1 Add `InlineFilesMode.DROP_TRUNCATED` (bound from `drop-truncated`): cut a truncated body back over the half file at the cut, keep every complete file, and return any body Core did not cut without parsing it
- [x] 5.2 Update the data-sync spec (main and this delta), design D6, the proposal, `docs/configuration.md`, `docs/column-mapping.md` and the payload mapping comment
- [x] 5.3 Tests: stripper (cut inside a file, cut inside text, a body that was not cut), binding of `drop-truncated`, engine counters; `./gradlew clean build`
- [x] 5.4 Make `drop-truncated` the default (`application.yml`, `SyncProperties` when unset, docs, specs, binding tests)
