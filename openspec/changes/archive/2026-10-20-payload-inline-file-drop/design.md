## Context

For the motivation, see proposal.md (Why). This section covers only the state and constraints that shape the design.

- **What ADAS receives.** DIAL Core writes each body into the usage-log line as an escaped JSON string, cut at
  4 MiB (`MAX_BODY_SIZE_BYTES`). When it cuts, it appends `>>` inside the string and decodes the bytes with
  `new String(bytes, UTF_8)`, so a character cut in half becomes U+FFFD. `LogParser` turns each line into a
  Jackson 3 tree, so `request.body` reaches the mapping as a `String` holding the original body text. The log
  line's own escaping, which includes Core turning `/` into `\/`, is gone by then. A `\/` left in the body
  is one the client itself wrote.
- **Where values are resolved.** `MappingEngine.toRow` resolves each column from the parsed record, one engine
  per target. Derivers on `dial_usage_log` (`byte-length`, `json-array-length`, `json-extract-*`) read the same
  record, not the stored value.
- **Where the bytes are**, estimated on a sample of `dial_usage_log_payload` rows with a compression ratio close
  to ClickHouse's own. Compared with storing every body as sent:
  - The Chat Completions, Responses and Anthropic Messages file locations remove more than half of the stored
    `request_body` bytes, and nothing from `response_body`.
  - Adding the MCP and embeddings response locations (D3) removes most of the stored `response_body` bytes.
  - A rule that removes any long base64 string, wherever it is, would save only a few percent more on either side.
  - Leaving routes out (D2) gives back only a small share of the request-side saving.

  Truncated bodies are rare, a tiny fraction of rows. Every one seen was exactly 4,194,306 bytes and ended with
  `>>`, and no other body ended with `>>`. Most request cuts fell inside an `image_url` data URL, a few inside text;
  response cuts fell inside an `embedding`, an MCP SSE `blob`, or SSE framing and ids.
- **Scan cost** of a Jackson 3 streaming scan plus removal: a little less than the `readTree` that `LogParser`
  already runs, and hundreds of MB/s on the largest bodies.

## Goals / Non-Goals

**Goals:**
- Under `keep`, ingest is byte for byte as today, and no body is parsed.
- Under `drop`, the work is bounded by the body: one streaming pass, one copy, and no tree.
- A file is decided by its location alone, so text in any other field is stored as it is, however it is
  encoded.
- Removal never fails ingest. Every failure falls back to storing the value unchanged.

**Non-Goals:**
- Extracting files to blob storage (a possible later step, deferred). The design leaves room for a later
  `extract` value (D6), but builds nothing for it.
- A per-file index or a removed-files column. The counters cover operations, and an extract step's refs would be
  the real index.
- Thinking signatures (`signature`, `thought_signature`, `encrypted_content`). They are not files, and they are
  a small part of what the pattern rule would add.
- MCP `resources/read` results (`result/contents/*/blob`). They are rare in usage logs. Adding them later is one
  row of the location table.
- Rewriting rows already stored.

## Decisions

### D1. The rewrite runs inside `MappingEngine`, for columns that declare `inline-files`

The engine already has what the rewrite needs: the record, which holds the request URI, and the resolved column
value. A mapping attribute decides which columns are rewritten, so no column name or source path is hard-coded
(the "Configurable column mapping" rule). The value is resolved and coerced to `string`, then rewritten, and the
absent/blank fallback applies last, as for any column.

Alternatives rejected:
- **A transform.** A transform sees only the value, never the URI, so it can't tell which API's locations apply.
- **A deriver** (`derive: strip-inline-files`, `sources: [request.body, request.uri]`).
  - It would work for `drop`, but it turns three mapped columns into derived ones.
  - Derivers take only static `args`, so the mode would have to be injected into `DeriverRegistry`.
  - A later `extract` mode needs a step over the whole row (uploads plus a second refs column), which a deriver
    can't host.
- **A hook in `BlobIngestor` after `toRow`.** It would need to know which row keys are bodies: either hard-coded
  names, or the same mapping attribute read from outside the engine. That is the right place for an extract
  step's side effects, and it can move there then.
- **Rewriting the record before mapping.** That shrinks `request_body_bytes` and `response_body_bytes` on
  `dial_usage_log`, and changes every deriver that reads a body.

### D2. Files are found by location, and the API by Core's own route patterns

The location table in the spec is the one from the declined DIAL Core change, split into request and response
sides and extended by D3.

- **Where Core decides the API.** ADAS has no `InterfaceType` in the log, so it reproduces the decision Core makes.
  Core picks the controller, and therefore the API, from anchored regexes in `RouteTemplate`, tried in
  `ControllerSelector`'s registration order, first match wins:
  - `POST_DEPLOYMENT`: `/openai/deployments/{id}/(completions|chat/completions|embeddings)`;
  - `LLM_CHAT_COMPLETIONS_API`, `LLM_RESPONSES_API(_UNVERSIONED)`, `LLM_MESSAGES_API(_COUNT_TOKENS)`;
  - `TOOL_SET_MCP_PROXY`, `APPLICATION_MCP_PROXY`.

  Everything else is a deployment route (`/v1/deployments/{id}/route/...`) or a global route (`/{path}`, with
  operator-chosen paths). ADAS uses the same anchored patterns in the same order, so it classifies a path exactly
  as Core does.
- **Why not by suffix**, as first drafted. Users and operators choose route paths, so a suffix says nothing about
  the body. A global route such as `/claude_code_router/v1/messages` has no deployment and no event kind, yet a
  suffix rule would scan it as Anthropic. A route can proxy any upstream, and an upstream may read `source.data`
  as text, which breaks the argument that a value at a file location reaches the model as a file. The declined
  Core change scanned no routes, for the same reason.
- **What leaving routes out costs.** Base64 sources sent through routes stay in the log. They are a small share
  of the request-side file bytes.
- **Names don't matter.** `{id}` spans `/` and percent-encoding, so an application with any name and folder path
  (`/openai/deployments/public/pg/test/agent__1.0.0/chat/completions`) is classified by its API path, as in Core.
- **One overlap is Core's too.** `/v1/deployments/{id}/mcp` also matches `.../route/x/mcp`, because `{id}` spans
  `/`. Core registers the MCP proxy before the deployment routes, so it serves that path as MCP, and ADAS
  classifies it the same way.
- **Coupling.** The patterns live in one class whose comment points to Core's `RouteTemplate`. A Core API path
  added later stays unscanned until ADAS adds it. That direction keeps more in the log, never less.
- **Not reused:** `classify-event-kind`. It matches with `contains` and knows neither `/messages` nor
  `/responses`.
- **Rejected: an operator-configured map from route paths to APIs** (for example, "this route speaks
  Anthropic"). It would win back that share, but it puts an unchecked claim about an upstream's format into the
  audit-safety argument. It can be added later without changing the default.
- **Alternative rejected:** pattern detection ("any base64 string ≥ 4 KB"). It would let a user keep
  prompt text out of the log by base64-encoding it, and it would save only a few percent more.

### D3. Three response locations beyond the Core change

Each value's format is fixed by its API's spec:
- MCP `ImageContent`/`AudioContent`: `result/content/*/data`, with `type` `image` or `audio`.
- MCP `BlobResourceContents`: `result/content/*/resource/blob`, with a binary `mimeType`. A `blob` is always
  base64 but can hold text, which is why the binary-type allowlist applies: `text/plain` blobs stay.
- OpenAI embeddings with `encoding_format: base64`: `data/*/embedding`.

Together they remove most of the stored `response_body` bytes. Without them the Core change's locations
remove nothing from responses. None of these values is text a user wrote.

### D4. A Jackson 3 streaming scan over the value's characters

The locator runs `tools.jackson.core.JsonParser` (char-based) over the body string, or over each SSE `data:` line.
It keeps:
- the path context (`streamReadContext().pathAsPointer()`, compared against the compiled location patterns);
- a list of pending candidates per object, decided at `END_OBJECT` once the condition siblings are known.
  An object the text ends inside (a cut or a parse error) is decided with the siblings read so far, and a
  sibling not yet read counts as unknown rather than missing: such a DIAL attachment is a file only on a binary
  `type` already read, never on "no `type`", so a text attachment whose `type` follows its data URL is kept.

Spans:
- **Start:** the value's opening quote is `currentTokenLocation().getCharOffset()`.
- **End:** found by scanning the raw characters for the next unescaped `"`.
- Candidate text is never read with `getText()`, so a multi-MB value is not copied again.
- The output is one `StringBuilder` that copies the kept ranges.

At a truncated body's cut, the parser has returned `VALUE_STRING` for the open value, but hasn't read it yet: it
reads the string lazily. So its start and path are known before the parse error. `InlineFileLocatorTest` pins
this, because the cut-back depends on it.

Alternatives rejected:
- **Jackson 2,** which Core uses. The warning in `TransformRegistry.lowercaseJsonKeys` is about mixing tree
  generations. The rewrite returns a `String`, but there is no reason to load the other line.
- **A databind tree** (`readTree` on the body). It doubles the memory, loses the raw spans needed for byte-exact
  removal, and can't read a truncated prefix.
- **Byte offsets.** Only an extract step's refs would need UTF-8 offsets. Char offsets into the `String` are exact for removal.

### D5. Truncation is detected by marker plus length, not by a parse failure

A value is truncated when it ends with `>>` and its UTF-8 length is at least 4,194,306 bytes: 4 MiB plus the
marker. A cut half character becomes U+FFFD (3 bytes), which only makes the value longer, so `≥` is exact. In
sampled usage logs, every value ending in `>>` met this test, and no other value did. The UTF-8 length is computed only when the
value ends with `>>` and has at least 1,398,102 chars (4,194,306 / 3).

A parse failure alone can't tell a cut from a malformed body, and SSE has no single parse that fails.

### D6. One enum property with three values

The property is `sync.inline-files-mode: ${SYNC_INLINE_FILES_MODE:drop-truncated}`, bound as an enum on `SyncProperties`, so
an unknown value fails startup through Spring binding. Spring's lenient enum binding maps `drop-truncated` to
`DROP_TRUNCATED`.
- **`drop-truncated` is its own value, not a second property.** It does what the Core change's
  `collectTruncatedInlineFiles=false` did alone: it cuts a body back over the half file at the 4 MiB cut and keeps every
  complete file. `drop` also cuts back, and `keep` never touches a body. A first draft folded the cut-back into `drop`
  only, because it saves little space, on the order of a percent of stored bodies. That was the wrong yardstick.
  The value is data hygiene: a half file cannot be decoded, and the trace view, an enrichment or an export would
  otherwise read a corrupt image or PDF, while the complete files stay useful. It costs almost nothing: only bodies
  Core cut are parsed, a tiny fraction of rows, and every other value is returned before it is even classified.
- **`drop-truncated` is the default** (the maintainers' choice). It removes only what no reader can use, so it
  is safe to apply everywhere; `keep` remains for an operator who wants every body exactly as Core logged it.
- **Naming.** The name sits in the `SYNC_*` group with the other ingest settings, rather than a separate
  `PAYLOAD_FILES_*` group. An extract step would add an `extract` value to the same enum.

### D7. What a removed file leaves behind, and the counters

- A data URL keeps its prefix, so analytics still sees the media type. Any other value becomes `""`, so the JSON
  stays valid and keeps its shape.
- There is no marker string. A user string that looked like a marker could never be told apart from one.
- Counters, each tagged `table` and `column`, registered once per declaring column when the engine is built:
  - `ingest.inline.files.removed`
  - `ingest.inline.bytes.removed`
  - `ingest.inline.bodies.cut`
  - `ingest.inline.bodies.unscanned`
- The `ingest.` prefix, not `sync.`/`poll.`/`backfill.`, says the counter doesn't depend on the ingest path,
  like `clickhouse.inserts`.

## Risks / Trade-offs

- **A provider adds a file field we don't list** → files stay in the log, the safe direction.
  Mitigation: the ratio of `ingest.inline.bytes.removed` to stored bytes shows a drop-off, and a new location is
  one row of the table plus a test.
- **An application serves an API under a non-standard path** → it is not scanned and its files stay.
  Same mitigation.
- **Readers lose the files under `drop`.** That covers the trace view showing images, enrichment evaluators that
  pass `request_body` to a model, and the audit of a picture of text.
  Mitigation: `drop` is opt-in (the default, `drop-truncated`, keeps every complete file), and `docs/configuration.md`
  says `drop` loses the files.
- **The setting changes and a blob is ingested again** (backfill, or a ledger miss) → the stored row is replaced by
  the new mode's result. A backfill run under `drop` removes files from the days it covers.
  Mitigation: stated in the spec ("Determinism"). The source blobs still hold the originals while they are kept.
- **Memory** → one transient `char[]` view and one `StringBuilder` per scanned body, at most about 8 MB each for a
  4 MiB body. A run maps one record at a time, so this is bounded.
- **The lazy-string assumption at the cut doesn't hold in Jackson 3** → the cut-back can't see the open value.
  Mitigation: `InlineFileLocatorTest` pins it. The fallback is to leave the body as cut, which is still correct, just without the
  cut-back.

## Migration Plan

1. Ship with the default `drop-truncated`. From deployment, a body Core cut inside a file is stored without that half
   file; every other body is stored as before. There is no migration.
2. To verify `drop`, set `SYNC_INLINE_FILES_MODE=drop`. A day later, compare that day's `request_body` and
   `response_body` bytes in `system.parts_columns` with a day ingested under `keep`.
   `ingest.inline.bodies.unscanned` should stay near zero.
3. **Rollback:** set `keep`. Rows written under `drop` keep their files removed until the payload TTL drops their
   partitions. Restoring them would mean a backfill under `keep` while the source blobs still exist.
