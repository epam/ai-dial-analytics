## 1. Configuration property

- [x] 1.1 Add `date-path-pattern: ${SYNC_DATE_PATH_PATTERN:yyyy/MM/dd}` to the `sync:` block of
  `src/main/resources/application.yml` (bare `key: ${ENV:default}` line, no comment).
- [x] 1.2 Add the `datePathPattern` component to `SyncProperties` with its Javadoc `@param` line, and
  whitespace-trim it in the compact constructor. Do **not** strip `/` from the pattern string here —
  a quoted literal may legally contain one (`'logs/'yyyy/MM/dd`); slashes come off the rendered
  segment instead (2.3).
- [x] 1.3 Update the 7 `new SyncProperties(...)` construction sites in `src/test` for the new
  component, passing `yyyy/MM/dd` so existing expectations are unchanged.

## 2. Single resolver owning the layout

- [x] 2.1 Rename `PollPrefixResolver` to `BlobPrefixResolver`, make it a package-private
  `@Component` taking `SyncProperties`, and hold the pattern's `DateTimeFormatter` (built with
  `Locale.ROOT`) as an instance field. Update the class Javadoc: it now owns the layout for every
  discovery path, not only band listing.
- [x] 2.2 Validate the pattern in the constructor per design D4: it parses, it formats a probe
  `LocalDate`, and it distinguishes a base probe date from three others differing in **only** the
  year, only the month, and only the day (four distinct renderings — probes varying several fields at
  once would wrongly admit `MM/dd`). A blank pattern is refused. Throw `IllegalArgumentException`
  naming both `sync.date-path-pattern` and `SYNC_DATE_PATH_PATTERN`, since Spring will surface it
  wrapped in a `BeanCreationException` and only the cause message is ours.
- [x] 2.3 Add `dayPrefix(LocalDate)` returning the trailing-slash whole-day prefix (the behavior of
  the two `buildPrefix` methods being removed), and convert `bandPrefixes` from static to an instance
  method reading the instance formatter. Strip leading/trailing `/` from the **rendered** date segment
  in both, so a pattern written `yyyy-MM-dd/` cannot produce a double slash. Keep the band logic — the
  power-of-ten check, the `MAX_BANDS` cap, the midnight-straddle handling, and the verbatim
  `date=<ISO date>` + epoch digits file-name portion — unchanged.
- [x] 2.4 Log the resolved prefix template once at construction (e.g.
  `blob discovery prefix template: your_dir/<yyyy-MM-dd>/`).

## 3. Wire the discovery paths

- [x] 3.1 `DataSyncService`: inject `BlobPrefixResolver`, replace `buildPrefix` and the
  `PREFIX_DATE` constant with `resolver.dayPrefix(targetDate)`, and drop the now-unused
  `DateTimeFormatter` import (`-Werror` fails on it).
- [x] 3.2 `BlobPollingService`: inject `BlobPrefixResolver`, use it for both listing modes in
  `activePrefixes`, and remove `buildPrefix` plus the `PREFIX_DATE` constant and its import. Update
  the three `{@link PollPrefixResolver}` Javadoc references that the rename breaks — the class comment
  (:49), the `BAND_WIDTH_SECONDS` comment (:66), and `activePrefixes` (:228).
- [x] 3.3 Confirm `DataBackfillService` needs no change (it discovers through
  `DataSyncService.runForDate`) and that no other caller of the removed methods remains.

## 4. Tests

- [x] 4.1 Rename `PollPrefixResolverTest` to `BlobPrefixResolverTest` (class name and its `{@link}`
  Javadoc): keep every existing expected prefix string unchanged under the default pattern — that is
  the "no behavior change at the default" check — and add cases for `yyyy-MM-dd` (band prefixes and
  `dayPrefix`), a blank `path-prefix` with a non-default pattern, `yyyyMMdd`, and a pattern written
  with a trailing `/` producing no double slash.
- [x] 4.2 Add validation tests: an unparsable pattern, a pattern naming a time field (`yyyy/MM/dd/HH`),
  a pattern omitting the **day** (`yyyy/MM`), a pattern omitting the **year** (`MM/dd` — the case a
  naive probe set admits), a purely literal pattern, and a blank pattern each fail construction with a
  message naming the property. (Checkstyle test-method naming: no `aFooBar`-style second-character
  capital and no two consecutive capitals.)
- [x] 4.3 `DataSyncServiceTest` / `BlobPollingServiceTest`: pass the resolver into the constructors and
  keep the existing default-layout cases green. Both build fixtures on the real jclouds `filesystem`
  provider, so add a genuine end-to-end case each under `path-prefix` = `your_dir` + pattern
  `yyyy-MM-dd`: for the sync service a blob at `your_dir/2026-06-18/data.log.gz` that gets ingested,
  and for the poller a real-format key via `writeEpochBlob` (`date=<ISO><epochs>`) discovered in
  **band** mode — the one case that proves the directory change and the untouched file-name portion
  compose. This means parameterizing those tests' own `yyyy/MM/dd` fixture constants
  (`DataSyncServiceTest:61`, `BlobPollingServiceTest:71`, `:225`, `:382`) rather than adding a second
  hardcoded layout.
- [x] 4.4 `DataBackfillServiceFilesystemTest`: parameterize its `DATE_PATH` constant (:47) and add an
  end-to-end case writing `yyyy-MM-dd`-shaped fixtures via `writeDay` — keys of the form
  `your_dir/2026-06-18/data.log.gz`, since this fixture's file names are immaterial to the backfill
  path — with `path-prefix` = `your_dir` and pattern `yyyy-MM-dd`, asserting the backfill ingests the
  rows. This proves the layout reaches discovery through `runForDate` with no backfill-specific
  change.

## 5. Documentation and specs

- [x] 5.1 `docs/configuration.md`: add the `sync.date-path-pattern` / `SYNC_DATE_PATH_PATTERN` row to
  the `sync.*` table; update the `sync.path-prefix` row's description and the two prose passages that
  spell the layout out as fixed (the data-sync intro paragraph and the `day`-mode sentence in the poll
  section) to say the date directory is configurable, defaulting to `{yyyy}/{MM}/{dd}`. Note the
  startup-fatal validation.
- [x] 5.2 `README.md`: change the data-sync paragraph's `<SYNC_PATH_PREFIX>/{yyyy}/{MM}/{dd}/` to name
  the configurable date directory and link to `docs/configuration.md` (no env-var table in README).
- [x] 5.3 Confirm the shipped behavior matches the delta specs in
  `openspec/changes/configure-blob-date-layout/specs/` — `data-sync` (discovery form, uniform layout,
  startup validation) and `data-sync-backfill` (defers to the configured layout) — and state
  explicitly that the `context:` section of `openspec/config.yaml` needs no change (it records no blob
  path layout, and property descriptions belong in `docs/configuration.md`).

## 6. Verification

- [x] 6.1 `./gradlew clean build`
  passes (compile + checkstyle + tests; integration tests need the Docker daemon).
- [x] 6.2 `bootRun` with no `SYNC_DATE_PATH_PATTERN` set logs the prefix template
  `logs/<yyyy/MM/dd>/`, and with `SYNC_DATE_PATH_PATTERN=yyyy/MM/dd/HH` fails startup with the
  property-naming message. Local `bootRun` needs the catalog DB reachable — set
  `POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DATABASE`/`POSTGRES_DATASOURCE_USERNAME`/`POSTGRES_DATASOURCE_PASSWORD`
  (those exact names; a wrong one silently falls back to the default and hits a real shared database).
