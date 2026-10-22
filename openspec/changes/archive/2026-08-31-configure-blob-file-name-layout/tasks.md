## 1. Configuration property

- [x] 1.1 Add `file-name-prefix-pattern: ${SYNC_FILE_NAME_PREFIX_PATTERN:'date='yyyy-MM-dd}` to the
  `sync:` block of `src/main/resources/application.yml` (bare `key: ${ENV:default}` line, no comment).
- [x] 1.2 Add the `fileNamePrefixPattern` component to `SyncProperties` with its Javadoc `@param` line,
  and whitespace-trim it in the compact constructor. Do **not** strip `/` from the pattern string —
  slashes come off the rendered head instead (2.4).
- [x] 1.3 Update the 9 `new SyncProperties(...)` construction sites in `src/test` for the new component,
  passing `'date='yyyy-MM-dd` so every existing expectation is unchanged.

## 2. The head in `BlobPrefixResolver`

- [x] 2.1 Hold the head's `DateTimeFormatter` (built with `Locale.ROOT`) as an instance field, and take
  `MeterRegistry` as a second constructor argument for the mismatch counter (design D6).
- [x] 2.2 Validate the head in the constructor per design D4: it parses, and it formats a probe
  `LocalDate`. Deliberately **no** distinctness sweep and **no** blank refusal — a blank or purely
  literal head is a legitimate producer shape, and it is the date directory that separates days.
  Throw `IllegalArgumentException` naming both `sync.file-name-prefix-pattern` and
  `SYNC_FILE_NAME_PREFIX_PATTERN`, since Spring surfaces only the cause message.
- [x] 2.3 Replace the hardcoded `"/date=" + KEY_DATE.format(day)` in `bandPrefix` with the rendered
  head, and remove the now-unused `KEY_DATE` constant. Keep everything else about band building
  unchanged: the power-of-ten check, the `MAX_BANDS` cap, the midnight-straddle handling, and the
  `floor(epoch / bandWidth)` digits appended verbatim.
- [x] 2.4 Strip a **leading** `/` from the *rendered* head so a head written `/date=…` cannot double the
  separator after the date directory; preserve a trailing one (a producer nesting one level deeper
  legitimately ends its head with `/`). Note this is deliberately not `dateSegment`'s both-ends strip.
- [x] 2.5 Extend the single startup log line with a rendered band-prefix example alongside the existing
  whole-day template, e.g. `band prefix example: your_dir/2026-08-31/1788169`.
- [x] 2.6 Update the class Javadoc: the band-listing paragraph currently states the `date=<ISO date>`
  head as a fact of the producer, and the `<date-path>` paragraph should name the head as the second
  configured segment.

## 3. Band-shape mismatch report

- [x] 3.1 Add `checkBandShape(LocalDate day, List<String> keys)` to `BlobPrefixResolver`: for each key,
  assert the file name begins with the rendered head followed by at least one digit. Increment
  `sync.blobs.band.shape.mismatch` by the number of non-matching keys on **every** call, and log one
  WARN per process naming a real offending key, the expected shape, and both remedies (correct
  `sync.file-name-prefix-pattern` / `SYNC_FILE_NAME_PREFIX_PATTERN`, or set `POLL_LISTING_MODE=day`).
  Perform no listing of its own and change no ingestion outcome (design D3).
- [x] 3.2 Call it from `DataSyncService.ingestDate` immediately after
  `List<String> blobs = blobClient.list(prefix)`, so the scheduled reconciliation run and every
  operator backfill are covered. Deliberately **not** wired into `BlobPollingService`'s `day` branch.
- [x] 3.3 Update the 7 `new BlobPrefixResolver(...)` construction sites in `src/test` for the new
  constructor argument, and confirm no other caller of the changed methods remains.

## 4. Tests

- [x] 4.1 `BlobPrefixResolverTest`: keep every existing expected prefix string unchanged under the
  default head — that is the "no behavior change at the default" check — and add cases for a blank head
  (`your_dir/2026-06-24/1782297`, with `path-prefix` = `your_dir` and pattern `yyyy-MM-dd`), a purely
  literal head, and a head rendering a leading `/` producing no double slash.
- [x] 4.2 Add head-validation tests: an unparsable head and a head naming a time field each fail
  construction with a message naming the property; a blank head and a purely literal head each construct
  successfully — the two cases the date pattern's checks would wrongly refuse. (Checkstyle test-method
  naming: no `aFooBar`-style second-character capital and no two consecutive capitals.)
- [x] 4.3 Add `checkBandShape` tests: date-prefixed keys under the default head count nothing and log
  nothing; bare-timestamp keys under the default head are counted; a second call counts again without a
  second warning; bare-timestamp keys under a blank head count nothing.
- [x] 4.4 `BlobPollingServiceTest`: add the load-bearing end-to-end case — a bare-timestamp fixture written
  via a new `writeEpochBlob` helper (`<path-prefix>/<yyyy-MM-dd>/<epoch>-<uuid>.log.gz`) discovered in
  **band** mode on the real jclouds `filesystem` provider with `date-path-pattern` = `yyyy-MM-dd` and a
  blank head, proving the two properties compose. Add the negative that documents the bug being fixed:
  the same fixture under the **default** head discovers 0 in band mode while `day` mode still finds it.
- [x] 4.5 `DataSyncServiceTest`: a bare-timestamp key under the default head is still ingested (no
  behavior change) and increments `sync.blobs.band.shape.mismatch`.

## 5. Documentation and specs

- [x] 5.1 `docs/configuration.md`: add the `sync.file-name-prefix-pattern` /
  `SYNC_FILE_NAME_PREFIX_PATTERN` row to the `sync.*` table, including that a blank value is legal and
  means the timestamp begins the file name, that the value for such a deployment is the *empty string*
  (tooling that prunes empty variables restores the default), and the startup-fatal validation. Correct
  the `sync.date-path-pattern` row's sentence presenting the `date=<ISO date><startEpoch>` portion as
  wholly the producer's, and update the "How `band` mode lists only a recent slice" block (the ASCII key
  diagram) to show both producer shapes and name the property. Document the
  `sync.blobs.band.shape.mismatch` counter and the once-per-process warning.
- [x] 5.2 `README.md`: no configuration change is needed (it describes the date path but never the file
  name, and env-var descriptions belong in `docs/configuration.md`), but its observability list names every
  metric, so add `sync_blobs_band_shape_mismatch_total` there with a one-line gloss and a link.
- [x] 5.3 Confirm the shipped behavior matches the delta spec in
  `openspec/changes/configure-blob-file-name-layout/specs/data-sync/` — the modified date-directory
  requirement, the configurable head, its startup validation, and the mismatch report — and state
  explicitly that `data-sync-backfill` needs no delta (it discovers through `DataSyncService.runForDate`
  and only uses the whole-day prefix) and that the `context:` section of `openspec/config.yaml` needs no
  change (it records no blob key layout, and property descriptions belong in `docs/configuration.md`).

## 6. Verification

- [x] 6.1 `./gradlew clean build`
  passes (compile + checkstyle + tests; integration tests need the Docker daemon).
- [x] 6.2 Verify the startup surface. Done **without** a local `bootRun`: no local Postgres/ClickHouse was
  running, and a mistyped `POSTGRES_*` name silently reaches a real shared database, which is not a risk
  worth taking for a log line. Verified instead, and more durably:
  - Three binding tests in `SyncPropertiesTest` over the real `application.yml`
    (`ApplicationContextRunner` + `ConfigDataApplicationContextInitializer`, the idiom
    `SchedulingConfigurationTest` already uses): the `'date='yyyy-MM-dd` default survives YAML parsing and
    placeholder resolution with its quotes intact; a **set-but-empty** `SYNC_FILE_NAME_PREFIX_PATTERN`
    binds to `""` and beats the default (the crux of a bare-timestamp deployment); a set value is honoured. Note
    `withSystemProperties("KEY=")` cannot express this — `TestPropertyValues.Pair.of` discards a pair with
    an empty value — so the property is set directly around the run.
  - The resolver's own startup line, read back from the test XML across every layout the suite constructs:
    `logs/<yyyy/MM/dd>/, e.g. logs/2026/08/31/; band prefix example: logs/2026/08/31/date=2026-08-311788187`
    at the default, and `your_dir/<yyyy-MM-dd>/, e.g. your_dir/2026-08-31/; band prefix example:
    your_dir/2026-08-31/1788187` for the bare-timestamp layout — byte-identical band prefixes to today's at the default.
  - The startup **refusal** is covered at the constructor by `BlobPrefixResolverTest`; Spring's
    `BeanCreationException` wrapping is unchanged from the already-shipped date-pattern path.
  - Left uncovered by an assertion, deliberately: the two spec scenarios that rest on log *output* (the
    band example being logged, and the mismatch warning not repeating). This repo has no log-capture idiom
    in any test, and introducing one for two assertions reads worse than the surrounding code; the
    mismatch counter covers the observable half of the second scenario.
