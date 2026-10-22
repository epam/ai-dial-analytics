## ADDED Requirements

### Requirement: Rule-scoped incremental scan endpoint

The system SHALL expose `POST /v1/rules/{id}/scan` accepting a body `{cursor?, limit?}` and
returning `{rows[], nextCursor, hasMore, lagSeconds}`. The system SHALL resolve the rule's
stored, already-validated source, filter, referenced columns, and grain from the `rule` /
`enrichment_grain` records; the caller SHALL NOT supply a table name, filter, or column list.
The scan SHALL require the rule to **exist** but NOT to be `enabled` (a paused rule can still be
backfilled). An unknown rule SHALL return HTTP 404 `unknown_rule`. When `limit` is omitted the
system SHALL apply a configured default; an over-limit request SHALL be clamped or rejected
consistent with the existing query-limit policy.

#### Scenario: Scan resolves the rule and returns a bounded page

- **WHEN** `POST /v1/rules/{id}/scan` is called for an existing rule with a valid or omitted `cursor`
- **THEN** the system runs the rule's governed scan and returns up to `limit` rows plus
  `nextCursor`, `hasMore`, and `lagSeconds`

#### Scenario: Scan of a disabled-but-existing rule succeeds

- **WHEN** `POST /v1/rules/{id}/scan` is called for a rule that exists but is `enabled=false`
- **THEN** the scan proceeds normally (enablement gates only the live recurring schedule, not backfill)

#### Scenario: Scan of an unknown rule is rejected

- **WHEN** `POST /v1/rules/{id}/scan` is called with an `id` that does not resolve to a rule
- **THEN** the request is rejected with HTTP 404 and error code `unknown_rule`

### Requirement: Exactly-once keyset scan semantics

The scan SHALL return each source row **at most once** across pages by advancing an opaque
cursor over a **unique total order** — the tuple `(_ingested_at, event_id)` for `dial_usage_log`.
The scan SHALL order by that tuple and apply a keyset (seek) predicate `(_ingested_at, event_id)
> (:c1, :c2)` rather than offset pagination, so concurrent inserts never shift a page boundary.
The scan SHALL advance by **ingest/version order**, not the source sort key, so late-arriving
rows are never skipped. `nextCursor` SHALL encode the last returned row's tuple; an **empty page**
SHALL return the input cursor unchanged (or a start token when none was supplied) with
`hasMore=false`; a full page SHALL report `hasMore=true`. When `cursor` is omitted the scan SHALL
start from the source's beginning (enabling backfill full-replay); the server SHALL be stateless
about cursor position (it signs and decodes only — the caller persists the cursor).

#### Scenario: Each row is returned exactly once across pages

- **WHEN** a source is scanned across multiple pages, echoing each `nextCursor` as the next request's `cursor`
- **THEN** every matching row appears in exactly one page, with no gaps and no duplicates

#### Scenario: Colliding version values are disambiguated by the tuple tiebreaker

- **WHEN** multiple rows share the same `_ingested_at` value at a page boundary
- **THEN** the `event_id` component of the cursor tuple orders them deterministically so none is skipped or repeated

#### Scenario: Omitted cursor scans from the source start

- **WHEN** `POST /v1/rules/{id}/scan` is called with no `cursor`
- **THEN** the scan begins at the source's earliest rows (full-replay), and the server holds no per-caller cursor state

#### Scenario: Empty page returns the input cursor unchanged

- **WHEN** a scan matches no rows above the current cursor below the safety-lag horizon
- **THEN** the response has an empty `rows` array, `hasMore=false`, and `nextCursor` equal to the input cursor

### Requirement: Scan projection and version isolation

The scan query SHALL read the **base source only, with no enrichment LEFT JOINs**, and SHALL push
projection down to `{columns referenced by the rule's filter and input bindings} ∪ {grain id} ∪
{cursor columns}`. The scan SHALL read with `final=1` so only the latest version per source
identity is returned, independent of the projected columns.

#### Scenario: Scan omits enrichment-join columns

- **WHEN** the scanned source has one or more enrichments
- **THEN** the returned rows contain only source/projected columns — no auto-joined enrichment columns

#### Scenario: Projection is limited to referenced columns

- **WHEN** a rule references only a subset of the source's columns
- **THEN** the scan projects only those columns plus the grain and cursor columns

### Requirement: Scan safety-lag

The scan SHALL exclude rows whose version column exceeds `now64(3) − lag` (configurable, default
5 seconds) so a scan never enters the still-mutating recent window. This relies on the version
column (`_ingested_at`) being assigned at ingest and never backdated. `lagSeconds` in the
response SHALL be `server_now − max(_ingested_at)` among rows available below the safety-lag
horizon, so a caller can emit a lag metric without decoding the cursor.

#### Scenario: Too-recent rows are hidden by the safety-lag

- **WHEN** a row's `_ingested_at` is newer than `now64(3) − lag`
- **THEN** the scan does not return that row until it falls below the safety-lag horizon

### Requirement: Opaque, signed, rotatable cursor

The cursor SHALL be opaque to callers: `base64url(format-version ∥ key-id ∥ entity ∥ tuple)`
authenticated with an HMAC over a configured secret. Decoding SHALL verify the HMAC and that the
encoded entity matches the rule's source; a tampered, mismatched, or malformed cursor SHALL be
rejected with HTTP 400 `invalid_cursor`. The HMAC secret SHALL be a **keyring** keyed by
`key-id`, so the secret can rotate without invalidating outstanding cursors (dual-key window); an
unknown or retired `key-id` SHALL be rejected with HTTP 400 `invalid_cursor`.

#### Scenario: Tampered or cross-entity cursor is rejected

- **WHEN** a scan is called with a cursor whose HMAC fails or whose entity does not match the rule's source
- **THEN** the request is rejected with HTTP 400 and error code `invalid_cursor`

#### Scenario: Key rotation keeps previously-issued cursors valid

- **WHEN** the HMAC keyring adds a new key while retaining a prior key, and a caller echoes a cursor signed by the prior key
- **THEN** the cursor still verifies and the scan proceeds

#### Scenario: Unknown key-id is rejected

- **WHEN** a cursor carries a `key-id` not present in the keyring
- **THEN** the request is rejected with HTTP 400 and error code `invalid_cursor`

### Requirement: Rule-scoped versioned write-back endpoint

The system SHALL expose `POST /v1/rules/{id}/rows` accepting a non-empty `rows` array, symmetric
with the scan endpoint. The system SHALL resolve the rule's `target_enrichment` and validate that
each row's column-set is a subset of {the rule's declared output columns} ∪ {the grain key} ∪
{permitted provenance columns}; a row naming any other column SHALL be rejected with HTTP 422
`write_column_not_allowed`. The caller SHALL NOT name a table and SHALL NOT be able to write
outside its rule's declared columns. Provenance columns (e.g. `evaluator_version`, `model`,
`enriched_at`) supplied by the caller SHALL be written as ordinary user columns; the version
column `_updated_at` SHALL be stamped by ClickHouse and SHALL NOT be accepted as caller input.
After validation the write SHALL delegate to the existing materialized-table write path. An
unknown rule SHALL return HTTP 404 `unknown_rule`.

#### Scenario: A well-formed write is accepted and collapses by grain

- **WHEN** `POST /v1/rules/{id}/rows` is called with rows whose columns are all within the rule's outputs plus grain and provenance
- **THEN** the rows are written to the target enrichment and a subsequent `final=1` read returns the latest version per grain key

#### Scenario: A write naming an undeclared column is rejected

- **WHEN** a row references a column that is neither a declared output of the rule, the grain key, nor a permitted provenance column
- **THEN** the request is rejected with HTTP 422 and error code `write_column_not_allowed`

#### Scenario: Caller-supplied `_updated_at` is rejected

- **WHEN** a write row attempts to set the system version column `_updated_at`
- **THEN** the request is rejected (system column is not caller-settable) and ClickHouse stamps the version on insert

### Requirement: Rule-scoped scan and write authorization

The scan and write endpoints SHALL be authorized by the rule's **target-enrichment access**
(reusing the role-based `access` model), not by a global read/write authority. The scan SHALL
require the read/scan flavour and the write SHALL require the write flavour of that grant. A
caller lacking the required access on the rule's target enrichment SHALL be rejected with HTTP 403
`rule_access_denied`. `FULL_ADMIN` SHALL satisfy the requirement.

#### Scenario: Least-privilege scan is authorized by target access only

- **WHEN** a non-admin caller holding the target enrichment's read/scan access calls `POST /v1/rules/{id}/scan`
- **THEN** the scan is authorized without requiring any global read authority

#### Scenario: Missing target access is rejected

- **WHEN** a caller without the required target-enrichment access calls the scan or write endpoint
- **THEN** the request is rejected with HTTP 403 and error code `rule_access_denied`
