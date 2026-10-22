## Why

`dial_usage_log` stores its five verbatim-content columns — `request_body`, `response_body`,
`assembled_response`, `jwt_claims` and `request_tags` — in the same table, and therefore under the
same retention, as the small analytic columns. A single `TTL request_time + INTERVAL 1 YEAR` cannot
express "keep the metrics for a year, the payloads for a quarter", because a ClickHouse row-expiry TTL
is a property of the table, not of a column.

Two independent arguments point the same way. **Storage:** the three bodies are almost all of the
table's bytes, and nobody needs them a year later — they serve incident debugging and structural
extraction, both of which happen within days. **Disclosure:** `jwt_claims` and `request_tags` are
small, so they cost nothing to keep, but they are the two columns that can carry bearer tokens,
cookies and forwarded client IPs (see Postgres `V3` and `V12`), and a year is a long time to hold a
credential. One table, one shorter clock, serves both.

Measured before deciding the shape: a couple of months of rows already carry hundreds of GB of
uncompressed body content, most of it `request_body`, then `response_body`, then `assembled_response`.

## What Changes

- **New ClickHouse table `dial_usage_log_payload`** holding all five columns, keyed on
  `event_id`, partitioned daily on `request_time`, with `TTL toDateTime(request_time) + INTERVAL 90 DAY`
  and `ttl_only_drop_parts = 1`. `dial_usage_log` keeps its one-year TTL for everything else.
- **The five columns are dropped from `dial_usage_log`** and catalogued as a **system enrichment** of
  it (grain `event_id`, cardinality `0..1`), so they stay reachable from the same entity under the
  qualified flat names `"dial_usage_log_payload.request_body"`, `"…response_body"`,
  `"…assembled_response"`, `"…jwt_claims"`, `"…request_tags"` — in the DSL, in REST SQL, in
  translation and over Flight SQL. The grain `LEFT JOIN` is emitted only when a query names one.
- **BREAKING — the bare names no longer resolve.** A query, saved query, `enrich`/`aggregate` pipeline
  declaration or Class-1 `sql` expression naming `request_body`, `jwt_claims` (etc.) on
  `dial_usage_log` fails as an unknown field until it is repointed. There is deliberately no compatibility alias: the enrichment
  mechanism exists for exactly this shape, and a bare name that silently meant a 90-day column on a
  one-year table would be worse than one that fails.
- **The existing bodies are copied across first.** A migration copies every row inside the 90-day
  window into the new table before the drop, so no content inside the retention is lost. Bodies
  *older* than the window are not copied and are deleted with the columns — deliberately: they are
  past the retention this change exists to impose. Between the copy and the drop the content exists
  twice (tens of GB compressed for a few months of bodies); the drop releases it.
- **An `object` enrichment column becomes legal to read from a pipeline.**
  `EntityFields.absenceIsUnrepresentable` refused a non-nullable enrichment column as a value-producing
  read; `object` now joins `array` and `map` in the exemption, for the same two reasons — ClickHouse
  has no `Nullable(JSON)`, so the rule would refuse the type permanently, and the type default `{}` is
  indistinguishable from absence under every accessor. Without this the claim/header columns could not
  move at all. `TableColumnRules` correspondingly refuses `nullable: true` on an `object` column and
  resolves one to non-nullable, closing a divergence where the catalog could record a flag
  `ClickHouseTypeMapper` never renders.
- **Data-sync gains a third ingest target.** One parsed record emits a `dial_usage_log` row and a
  `dial_usage_log_payload` row, from a new mapping file, under the same match-all predicate and the
  same dedup-key drop rule.
- **Column metadata is carried over, not re-declared**: `sensitive`, `heavy`, nullability,
  descriptions, display names and tags move with the catalog rows, so `GET /v1/tables/…` and schema
  discovery report exactly what they reported before, under the new owner.
- New env vars `SYNC_PAYLOAD_TABLE` / `SYNC_PAYLOAD_MAPPING_FILE`, documented in
  `docs/configuration.md`.

## Capabilities

### New Capabilities

None. The change re-homes existing columns; every behavior it touches belongs to a capability that
already exists.

### Modified Capabilities

- `data-sync`: a second ingest target for the body columns; the new table's provisioning, its 90-day
  retention, and the removal of those columns from the `dial_usage_log` target.
- `table-management`: a third seeded system table, seeded as an `ENRICHMENT` of `dial_usage_log`; the
  seeded `heavy` set and part of the `sensitive` set are re-owned by it; its retention stays
  migration-owned.
- `rest-sql-endpoint`: the star expansion and the heavy-column budget/warning cases address the body
  columns by their quoted qualified names.
- `flight-sql-endpoint`: the same, for the Arrow schema and the star.
- `sql-query-translation`: the bare body names become unknown columns; the star expansion is unchanged
  in rule and narrower in effect.
- `query-translation-endpoint`: translation round-trips of body-column projections.
- `enrichment-runtime`: the group-assembly sensitive-column check governs a sensitive **enrichment**
  column on the same terms as a source column.

Also modified:

- `table-management` additionally covers the `object` nullability rules: an `object` column resolves
  non-nullable on every table kind and an explicit `nullable: true` is refused, matching `array` and
  `map`.
- `class1-enrichment` / `enrichment-runtime`: an `object` enrichment column is now a legal
  value-producing read for a pipeline, where a non-nullable enrichment column of any other type is
  still refused.

Not modified: `structured-query` mentions the payload columns only as entity-agnostic illustrations
inside requirements about function and expression semantics; the illustrations are requalified but no
rule changes.

## Impact

- **ClickHouse migrations**: `V10` creates `dial_usage_log_payload`; `V11` copies the 90-day window
  into it; `V12` drops the five columns from `dial_usage_log`.
- **PostgreSQL migration**: `V38` inserts the `dial_usage_log_payload` definition (type `ENRICHMENT`,
  `source_table = dial_usage_log`, `grain_key = event_id`, `status ACTIVE`, `system = TRUE`) and
  re-keys the five `column_mapping` rows onto it, preserving every per-column attribute.
- **Ingest**: new `mapping/dial-usage-log-payload.yaml`; a third `sync.targets` entry;
  `mapping/dial-usage-log.yaml` loses the five columns and keeps every deriver — `response_id`,
  `reasoning_tokens`, `number_request_messages`, `mcp_method`, `mcp_tool_call_name` and both `*_bytes`
  columns read the **parsed record**, never a stored column.
- **Query path**: no engine change — the existing grain-join assembly covers it.
- **Enrichment governance**: `EntityFields.absenceIsUnrepresentable` exempts `object`;
  `TableColumnRules` gains `requireValidObjectColumn` and resolves an `object` column non-nullable on
  every table kind. Measured on ClickHouse 26.3, an unmatched `LEFT JOIN` over a non-nullable `JSON`
  column yields `{}`, and `toJSONString` → `'{}'`, subcolumn access → `NULL`, `json_extract_array` →
  `[]`, `json_extract_string_ci` → `''` — identical to a present document missing the key. The
  alternative, storing them as `Nullable(String)` to earn a real `NULL`, is unavailable:
  `JSONExtract` over a `Nullable(String)` fails with `Code 43: Nested type Array(String) cannot be
  inside Nullable type`, which would remove array extraction from the columns entirely.
- **Behaviour change for API-created tables**: an enrichment's `object` column previously defaulted to
  `nullable = true` — a value the DDL renderer never emits — and an explicit `nullable: true` was
  accepted. Both now resolve/refuse the way `array` and `map` already did.
- **Docs**: `docs/heavy-columns.md`, `docs/configuration.md`, `docs/column-tags.md`,
  `docs/sql-reference.md`, `README.md`.
- **Operators**: with `clickhouse.migrations.enabled=false`, apply **all three** ClickHouse migrations
  in order before the new build — `V10` because the payload mapping is validated against it, `V12`
  because the edited `dial_usage_log` mapping no longer declares the dropped columns. `V10` needs the
  server-side encryption key for `AES_256_GCM_SIV`, as `V1`/`V7` do. `V11` is the expensive step: a
  server-side copy of the heaviest columns that startup waits for, and whose failure mode is a failed
  Flyway migration needing `flyway repair` before the next start. An installation that cannot absorb
  the transient duplicate should run the three out of band, checking free space between them. The
  90-day TTL is changed via `ALTER TABLE dial_usage_log_payload MODIFY TTL …`, not through the API.
