## 1. Storage

- [x] 1.1 Add `src/main/resources/db/migration/clickhouse/V8__rate_analytics_rate_raw.sql`:
  `ALTER TABLE rate_analytics ADD COLUMN IF NOT EXISTS rate_raw LowCardinality(Nullable(String)) CODEC(ZSTD(1)) AFTER rate`.
  Comment the file the way V7 comments `assembled_response`: why the column exists (boolean `false` and
  numeric `0` both store `rate = 0`), that existing parts read null because the raw form was never
  captured — never inferred — and the `SchemaValidator` ordering invariant (this DDL ships with the
  mapping entry; an operator on `clickhouse.migrations.enabled=false` applies it **before** the new build
  or startup aborts).
- [x] 1.2 Confirm the migration is metadata-only: no part rewrite, and it does **not** use the
  `AES_256_GCM_SIV` codec, so it adds no server-side-encryption-key prerequisite (unlike `comment`).

## 2. Ingest — mapping only, no Java

- [x] 2.1 Add the `rate_raw` column entry to `src/main/resources/mapping/rate-analytics.yaml`:
  `derive: json-extract-string`, `sources: [request.body]`, `args: { path: rate }`, `kind: string`,
  `nullable: true`, placed after `rate` so the file order still matches the ClickHouse DDL.
  **No new deriver** — `json-extract-string` already yields the verbatim scalar text (design D3).
- [x] 2.2 Leave `jsonExtractRate` and the `rate` column **completely unchanged**; the two columns read
  the same node through independent deriver invocations (design D4).
- [x] 2.3 Update the mapping file's header comment, which currently documents only the `rate`
  normalization, to describe both columns, why both exist, and that `rate_raw` deliberately reuses the
  string extractor.
- [x] 2.4 Extend `DeriverRegistry.jsonExtractString`'s javadoc: it currently reasons only about string
  and non-scalar members ("every path used today resolves to scalars"), which no longer describes its
  callers. State that a boolean member reads as `true`/`false` and a numeric one as its literal text,
  and that `rate_analytics.rate_raw` depends on exactly that. Javadoc only — no behavior change.

## 3. Query catalog

- [x] 3.1 Add `src/main/resources/db/migration/postgres/V31__seed_rate_analytics_rate_raw.sql`, modelled
  on `V29__seed_assembled_response_column.sql`: one `INSERT INTO column_mapping (table_name,
  source_name, name, type, element_type, nullable, description, display_name, tag, sensitive, heavy,
  ordinal)`. Values: type `string`, `nullable = TRUE`, `sensitive = FALSE`, `heavy = FALSE`, tag
  `request`, a display name, and a description stating it is the verbatim submitted value, that
  `true`/`false` mark a boolean submission, and that null means the event predates the column.
  Check the version number does not collide with a concurrently added migration — it landed as `V31`
  after a collision with another change's `V30`.
- [x] 3.2 Ordinal: append at **9**, following `V16`'s precedent for `event_id` ("the registration is
  additive; the eight already-exposed columns are untouched"). The trade-off is explicit — this table's
  catalog order is a curated presentation order that already diverges from the physical DDL, so
  appending puts `rate_raw` at the end of a wildcard projection rather than beside `rate`. Renumbering
  ordinals 2–8 to seat it at 2 is the alternative; it reads better and touches seven rows that no other
  migration has needed to move. Pick one deliberately and say why in the migration comment.
- [x] 3.3 No `tag_order` change: `request` is already in `rate_analytics`'s seeded `tag_order`
  (`V10__add_table_tag_order.sql`). Confirmed, not assumed.
- [x] 3.4 Verify the column appears in schema discovery and is filterable/groupable — and that it is
  **not** gated behind `FULL_ADMIN`, since it is deliberately non-sensitive (design D2).

## 4. Tests

- [x] 4.1 `DeriverRegistryTest`: add cases pinning `json-extract-string`'s scalar-text behavior, which is
  what `rate_raw` now rests on — boolean `true`/`false`, numeric `0`/`1`/`-1`, a JSON string, an absent
  field, a blank value, and object/array inputs. This is a new assertion about an existing deriver, not a
  test of new code.
- [x] 4.2 Assert the **pair** explicitly somewhere the relationship is visible: boolean `false` yields
  `rate = 0` with `rate_raw = false`, numeric `0` yields `rate = 0` with `rate_raw = 0`. That pairing is
  the whole point of the change, and neither deriver's own test states it.
- [x] 4.3 `MappingEngineTest` (or `MappingFileLoaderTest`): the updated `rate-analytics.yaml` loads and
  both rate columns resolve to their derivers. Include the non-scalar case end to end through
  `applyFallback` — an object at `rate` must reach the column as null, not as `""`.
- [x] 4.4 `RateAnalyticsIntegrationTest`: assert a boolean-`false` row and a numeric-`0` row both store
  `rate = 0` while `rate_raw` distinguishes them, end to end through ClickHouse, plus a third row with
  no `rate_raw` standing in for pre-column history. Added a second test running the published negative
  count for real (including a `-2` row, which a `rate_raw = '-1'` match would miss).
  **Deviation from the written task:** rows are seeded with SQL rather than ingested from a blob. The
  integration suite has no blob-ingest harness — the repo splits derivation (`MappingEngineTest`,
  against the real `rate-analytics.yaml`) from storage/read (these tests), and building an ingest
  harness for this one column was out of proportion. Derivation from an actual record is covered by
  4.1–4.3.
  `SchemaValidator` against the real `LowCardinality(Nullable(String))` column is exercised implicitly
  by every test in this class (context startup), and explicitly by the new `SchemaValidatorTest` cases.
- [x] 4.5 `QuerySchemaDiscoveryIntegrationTest`: `rate_raw` is present in the exposed `rate_analytics`
  column set, and the hidden physical columns are still absent.
- [x] 4.6 Name every new test method to satisfy Checkstyle: no second character in upper case
  (`aFooBar` fails) and no two consecutive capitals. This breaks `build`, not just the lint task.

## 5. Specs and docs

- [x] 5.1 Confirm the delta specs in `openspec/changes/preserve-raw-rate-value/specs/` still match what
  was implemented — in particular that the exposed-column list in the `rate-analytics` delta names
  exactly what shipped, and that the `data-sync` delta reads as a clarification of `json-extract-string`
  rather than as a new registry entry.
- [x] 5.2 No `docs/configuration.md` change: this adds no configuration property. Confirm rather than
  assume, so the omission is deliberate.
- [x] 5.3 `docs/column-tags.md` **does** need an edit: its `request` row enumerates member columns and
  names "the user-submitted `rate`/`comment` on `rate_analytics`" — add `rate_raw` there.
  `docs/heavy-columns.md` needs none: it names only `rate_analytics.comment`, and `rate_raw` is not
  heavy.
- [x] 5.4 Note in the change (not in code) that pre-existing rows read null, and that
  repopulating them would need a `data-sync-backfill` re-ingest — contingent on those blobs still being
  retained, which is unverified (design Open Questions).

## 6. Verification

- [x] 6.1 Run `./gradlew clean build` and confirm it passes — compile, Checkstyle, and tests. Integration
  tests need Docker.
- [x] 6.2 Run `openspec validate preserve-raw-rate-value` and confirm it reports valid.
- [ ] 6.3 Against a running deployment, verify the first ingested rate event carries a non-null `rate_raw` consistent
  with its `rate`, and record the exact negative count expression
  (`rate_raw = 'false' OR (rate_raw IS NOT NULL AND rate < 0)`) wherever the figure is published.
