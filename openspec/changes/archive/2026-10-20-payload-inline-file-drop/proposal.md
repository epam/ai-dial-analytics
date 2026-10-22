## Why

Inline base64 files (images, PDFs, audio, MCP tool files, base64 embedding vectors) make up most of what
`dial_usage_log_payload` stores. Text compresses well and base64 doesn't, so the files dominate the
stored bytes. A DIAL Core change that would have let operators keep files out of the analytics log was
declined there, on the grounds that the work belongs in ADAS. On a sample of typical traffic, removing the files at
known JSON locations cuts the stored body bytes by more than half, and `response_body` by most of its size.
These estimates already exclude custom-path routes, which are not scanned (see design D2).

## What Changes

- **New ingest setting `SYNC_INLINE_FILES_MODE`** (`sync.inline-files-mode`): `keep`, `drop-truncated` or `drop`,
  default `drop-truncated`. With `keep`, ingest is unchanged, byte for byte.
- **With `drop`, the payload of each inline file is removed** from the payload body columns before the row is
  written:
  - A plain base64 value becomes `""`.
  - A data URL keeps its prefix (`data:image/png;base64,`), so the media type stays visible.
- **Files are found by fixed JSON location per API, never by how a string looks**, so a user cannot keep
  prompt text out of the log by encoding it. The API is inferred from the record's request URI path, using the
  same fixed patterns DIAL Core routes by. Routes, including those with an operator-chosen path such as
  `/claude_code_router/v1/messages`, are never scanned.
  - The locations are those of the declined Core change: OpenAI Chat Completions (including DIAL `custom_content` attachments of a
    binary type), OpenAI Responses and Anthropic Messages.
  - Three response locations are added, each fixed by its API's spec:
    - MCP `result/content/*/data` of type `image`/`audio`;
    - MCP `result/content/*/resource/blob` with a binary MIME type;
    - Embeddings `data/*/embedding` (base64 vectors).
  - Every other path is never scanned.
- **With `drop` or `drop-truncated`, a body cut by Core's 4 MB limit inside a file is cut back to before that
  file.** The `>>` marker is kept, and a cut inside any other value is left as it is. `drop-truncated` does only this:
  it keeps every complete file and parses no body Core did not cut, so the half file that cannot be decoded is gone
  while every usable file stays.
- **New mapping attribute `inline-files`** on a mapped string column. It declares the column as a request or
  response body and names the source path of the request URI. Only columns that declare it are ever rewritten.
  The shipped payload mapping declares it on `request_body`, `response_body` and `assembled_response`.
- **New ingest counters:** files removed, bytes removed, bodies cut back, and bodies that could not be scanned.
- **Breaking: no API change, but the default changes what is stored.** The default is `drop-truncated`, so from
  deployment a body DIAL Core cut inside a file is stored without that half file; set `keep` to store every body
  as sent. Rows already stored are never rewritten, though a record ingested again replaces its row with the
  current mode's result.

Out of scope: moving files to blob storage and restoring them (a possible later step, deferred while no
consumer needs the files back); an index of removed files; any change to DIAL Core. Core still writes the files,
so blob and Vector traffic do not shrink.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`:
  - **New requirement:** inline files in payload bodies are removed under `drop`, with the location set per
    API, the cut-back rule and the scan/no-scan rules.
  - **"Body columns are captured only by the payload table's mapping"** and **"The merged assembled response is
    persisted":** bodies are stored verbatim *unless* `drop` is set.
  - **"Configurable column mapping":** the new `inline-files` column attribute and its load-time validation.
  - **"Ingest observability":** the new inline-file counters.

## Impact

- **Code:**
  - `sync/mapping/`:
    - `ColumnMapping` (new attribute);
    - `MappingFileLoader` (validation);
    - `MappingEngine` (applies the rewrite to declaring columns);
    - a new inline-file locator/stripper (Jackson 3 streaming, `tools.jackson`).
  - `configuration/`: `SyncProperties` (mode) and `MappingConfiguration` (wiring).
- **Config:**
  - `application.yml`: one line, `inline-files-mode: ${SYNC_INLINE_FILES_MODE:drop-truncated}`.
  - `docs/configuration.md`: one row in the Data sync table.
- **Mapping:** `src/main/resources/mapping/dial-usage-log-payload.yaml` declares `inline-files` on the three body
  columns, and its "stored verbatim" comment is updated.
- **API / catalog / schema:**
  - No `/v1` endpoint, catalog or ClickHouse schema change, and no migration.
  - `request_body_bytes` and `response_body_bytes` on `dial_usage_log` keep recording the original sizes, because
    their derivers read the record, not the rewritten value.
- **Consumers:** under `drop`, readers of the payload body columns see bodies without files. That covers the trace
  view, enrichment evaluators that read `request_body`, and SQL/DSL queries.
- **Cost:**
  - CPU: a little less than the `readTree` that ingest already does on the same lines, and milliseconds for a
    4 MB body.
  - ClickHouse inserts: substantially fewer raw bytes.
