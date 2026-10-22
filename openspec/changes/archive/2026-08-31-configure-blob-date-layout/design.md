## Context

Blob discovery resolves a key prefix from a UTC date. Today that prefix is
`<sync.path-prefix>/<yyyy>/<MM>/<dd>/`, and the date portion comes from a
`DateTimeFormatter.ofPattern("yyyy/MM/dd")` constant duplicated in three places:

| Site | Role |
| --- | --- |
| `DataSyncService:41` (`buildPrefix`, :184) | scheduled reconciliation run, and — via `runForDate` — the operator backfill |
| `BlobPollingService:61` (`buildPrefix`, :254) | poller, `day` listing mode |
| `PollPrefixResolver:32` (`prefix`, :105) | poller, `band` listing mode (the default) |

Some producers bucket by a single `yyyy-MM-dd` directory, so all three miss every blob. Nothing
downstream of discovery is path-coupled: `BlobIngestor` derives row values from blob *content*, and
the only place a key reaches a row is the `$sourceFile` token, which records whatever key was listed.
So this is entirely a key-building change.

Two constraints shape the design. First, `BlobPollingService` is `@ConditionalOnProperty(poll.enabled)`,
so anything that must be validated on every deployment cannot live there. Second, `band` mode's
correctness argument (documented at length in `PollPrefixResolver`'s class Javadoc) rests on the *file
name* continuing `date=<ISO date><fixed-width start epoch>…`; that portion is the producer's, not
ADAS's, and must stay outside whatever becomes configurable.

## Goals / Non-Goals

**Goals:**

- Make the date-directory layout configurable with one property, defaulting to today's behavior
  byte-for-byte.
- Have one component own the layout, so the scheduled, `band`, `day` and backfill paths cannot drift.
- Fail startup on a layout that cannot work, instead of running and listing nothing.
- Give an operator a way to see what will be listed without waiting for a cron cycle.

**Non-Goals:**

- Making the blob *file name* configurable (see Context; `POLL_LISTING_MODE=day` already serves an
  environment whose file names differ).
- Auto-detecting the layout by probing the container.
- Supporting more than one layout or container per instance.
- Changing how the target date itself is chosen (`sync.day-offset`, the poller's watermark, the
  backfill range) — untouched.

## Decisions

### D1 — A date-format pattern, not a path template or a layout enum

`sync.date-path-pattern` / `SYNC_DATE_PATH_PATTERN`, default `yyyy/MM/dd`, interpreted as a
`java.time.format.DateTimeFormatter` pattern and rendered between `path-prefix` and the file name.

- **Rejected: an enum** (`NESTED` / `FLAT`). Cheapest to validate, but a third producer layout —
  `yyyyMMdd`, `yyyy/MM-dd`, a `'dt='`-prefixed segment — means another code change and another
  release. The whole point of this change is that layout differences stop being code changes.
- **Rejected: a full path template** such as `{prefix}/{yyyy}-{MM}-{dd}/`. It duplicates
  `path-prefix`, giving two ways to express the base path and an obvious way to configure them into
  disagreement, and it needs a placeholder language of its own where `DateTimeFormatter` already
  exists and is what the code uses today.

A pattern also keeps the default *literally* the existing constant, which is what makes "no behavior
change at the default" reviewable rather than asserted.

### D2 — One resolver bean owns the layout; the three constants go away

`PollPrefixResolver` is renamed `BlobPrefixResolver` and turned from a static utility into a
package-private `@Component` holding the validated formatter, exposing both:

- `dayPrefix(LocalDate)` — the trailing-slash prefix for a whole day (scheduled run, `day` mode)
- `bandPrefixes(Instant lo, Instant hi, long bandWidthSeconds)` — unchanged band logic, now reading
  the instance formatter

`DataSyncService` and `BlobPollingService` take it as a constructor argument (both already have
explicit constructors) and drop their `PREFIX_DATE` constants and `buildPrefix` duplicates. The
rename carries fallout worth naming up front: three `{@link PollPrefixResolver}` references in
`BlobPollingService`'s Javadoc (the class comment, the `BAND_WIDTH_SECONDS` comment, and
`activePrefixes`) and the test class plus its Javadoc all move with it.

`DataBackfillService` needs no change at all — it reaches discovery through
`DataSyncService.runForDate`.

- **Rejected: passing a `DateTimeFormatter` into the existing static methods.** It threads the
  property through but leaves three separate prefix-assembly implementations, which is the actual
  defect here: the layout is a single fact currently stated three times, and a future producer quirk
  would again need fixing in three places, with only one of them (band mode) covered by a
  layout-specific test.

This refactor stays inside ADAS-specific sync services; `BlobClient` and the rest of `blob/` are
untouched, so nothing carried over from deployment-manager moves.

### D3 — Validation lives in the resolver's constructor, not in bean-validation annotations

The resolver constructor validates the pattern and throws `IllegalArgumentException` on failure,
which fails context refresh. The message names both `sync.date-path-pattern` and
`SYNC_DATE_PATH_PATTERN`, because whoever hits it is reading a deployment manifest, not the YAML.

- **Why not a Jakarta constraint on `SyncProperties`?** The meaningful check is not "matches a regex"
  but "can actually format a `LocalDate`", which requires running the formatter — a constraint
  annotation would either duplicate that work or approximate it badly.
- **Why not validate in `BlobPollingService`?** It is `@ConditionalOnProperty(poll.enabled)`; a
  deployment running scheduled-only ingestion would skip the check entirely and then quietly ingest
  nothing.

The precedent being deliberately avoided is `security.mode`, where nothing validates the value and a
typo yields a service with no way in and a startup log that misreports the effective setting.

### D4 — Three checks and one refusal, because only one failure mode is a parse error

1. **Parses** — `DateTimeFormatter.ofPattern(pattern, Locale.ROOT)`. `Locale.ROOT` is explicit so a
   pattern using textual fields (`MMM`) renders the same on every host.
2. **Formats a date** — format a probe `LocalDate`. A pattern naming a time field (`HH`) parses fine
   and throws `UnsupportedTemporalTypeException` only at format time, i.e. on the first sync run,
   which is exactly the "starts healthy, ingests nothing" outcome this change exists to prevent.
3. **Distinguishes any two dates** — format a base probe date plus three more differing from it in
   **only** the year, **only** the month, and **only** the day, and require all four renderings to be
   distinct. The probes have to isolate one field each: a set that varies several fields at once
   (2024-01-02, 2025-03-04, 2026-05-06) renders distinctly under `MM/dd`, which is broken — every
   year would collide in the same directory — so it would pass a check built that way. Isolated
   probes reject a pattern missing *any* date field, and a purely literal one. The symptom this
   prevents is not an error: such a layout collapses many dates onto one prefix, so the daily run and
   the poller list a far larger set than intended while reporting success.

**Normalization** trims surrounding whitespace from the configured value, and strips leading/trailing
`/` from the **rendered** segment rather than from the pattern string — stripping the pattern would
corrupt a quoted literal (`'logs/'yyyy/MM/dd` is a legal pattern), while stripping the output is
total.

A **blank pattern is refused** rather than read as "no date directory". It is tempting because it is
half-viable: with the date carried in the file name, `band` mode would still work
(`your_dir/date=2026-06-18…`), but the daily reconciliation run and the backfill both list a *whole
day* by directory, and with no date segment they would list the entire `path-prefix` subtree for
every date in range. Serving a genuinely flat container means deriving the day prefix from the file
name's `date=` segment instead of a directory — a different discovery model, and a separate change if
such an environment appears.

The failure surfaces as an aborted context refresh: Spring wraps the constructor's
`IllegalArgumentException` in a `BeanCreationException`, so the operator reads our message as the
cause rather than the top line. That is acceptable — what matters is that it names the property — and
it is the reason the message must be self-contained rather than relying on surrounding log context.

The resolver logs its resolved prefix template once at construction (e.g.
`blob discovery prefix template: your_dir/<yyyy-MM-dd>/`), so the effective layout is visible at
startup.

### D5 — The configured layout covers the directory only

`bandPrefixes` keeps appending `date=<ISO date>` plus the band's epoch digits verbatim. That
substring is the producer's file name and the basis of band listing's server-side time filter; making
it configurable would put the one piece of the key whose *ordering* semantics ADAS depends on under
operator control. File names are assumed to match the default shape across producers — observed for
one layout only, hence the open question below — so nothing is needed here; an environment where they do not match is served by
`POLL_LISTING_MODE=day` and would be its own change.

## Risks / Trade-offs

- **A pattern that is valid but wrong** (`yyyy/MM-dd` where the producer writes `yyyy-MM-dd`) passes
  all three checks and matches nothing → the startup log states the resolved template, and the smoke
  step in the migration plan is a single-day backfill whose status must report
  `filesProcessed > 0`; a zero-file run is the signal. Accepted: no validation can confirm a layout
  without listing the container, which this change deliberately does not do (see Non-Goals).
- **A producer's directory day may not be its key's UTC day.** ADAS derives both from the same UTC date; a
  producer bucketing by local time would leave a midnight-adjacent blob in a directory ADAS does not
  list for that date. Unchanged by this design (the default layout has the same exposure) → verify against real
  keys before enabling; the daily reconciliation run does not rescue it, since it resolves the same
  prefix, so this would need its own change if real.
- **File names could differ from the default shape** despite the assumption that they match → confirm one
  real key before deployment; if they differ, `band` mode lists nothing while `day` mode still works,
  so the fallback exists and the failure is visible in `poll.blobs.discovered`.
- **The refactor touches the poller's hot path** → it is pure key building with no I/O, and the
  existing `PollPrefixResolverTest` / `BlobPollingServiceTest` / `DataSyncServiceTest` pin the
  default-pattern prefixes; the acceptance bar is that those assertions keep their current expected
  strings unchanged. Helpfully, all three build fixtures on the real jclouds `filesystem` provider
  rather than mocking the listing, so an alternate-layout case is an actual ingest, not an assertion
  about a string.
- **The layout is duplicated test-side too** — five more `yyyy/MM/dd` literals across
  `DataSyncServiceTest:61`, `DataBackfillServiceFilesystemTest:47`, and `BlobPollingServiceTest:71`,
  `:225`, `:382`. They are fixture builders, not assertions, so they can stay hardcoded; but a test
  that needs the alternate layout has to parameterize its own key building, and a test left on the
  default while its `SyncProperties` says otherwise fails confusingly (empty listing, not a mismatch
  error). Parameterize the fixture helper in the same commit as the property.
- **A new fatal startup condition.** Deliberate, and it can only fire for a deployment that sets the
  new variable — an unset variable takes the default, which is validated by construction.

## Migration Plan

1. Ship with `sync.date-path-pattern` unset everywhere. Every existing deployment takes the
   `yyyy/MM/dd` default and behave identically; the only visible difference is one extra startup log
   line.
2. On a deployment with a `yyyy-MM-dd` layout, set the three variables together — `BLOB_BUCKET=your_container`, `SYNC_PATH_PREFIX=your_dir`,
   `SYNC_DATE_PATH_PATTERN=yyyy-MM-dd` — and confirm the startup log reads
   `your_dir/<yyyy-MM-dd>/`.
3. Smoke-test with a **single-day** backfill (`POST /v1/data-sync/backfill` with `from` = `to` = a
   date known to have blobs), and check `GET /v1/data-sync/backfill/{runId}` reports
   `filesProcessed > 0` and rows inserted. A one-day range keeps the first attempt against an
   unverified layout cheap.
4. Then let the poller run and watch `poll.blobs.discovered` / `poll.rows.inserted` become non-zero.
   If band mode discovers nothing while a backfill for the same day found blobs, the file-name shape
   differs — set `POLL_LISTING_MODE=day` and raise it as a follow-up.
5. **Rollback** is unsetting `SYNC_DATE_PATH_PATTERN` (or reverting the build): the property is read
   only at startup and nothing persists a prefix, so there is no state to unwind.

## Open Questions

- Do real keys of a `yyyy-MM-dd` layout carry the same file-name shape as the default — `date=<ISO date><start epoch><end epoch>-<uuid>.log.gz`
  with the epoch digits at fixed width? Assumed identical; confirming one key decides whether
  such a deployment can run the default `band` mode or needs `POLL_LISTING_MODE=day`. Does not block implementation.
- Does such a producer's directory date equal the `date=` value inside its file names (i.e. is the bucketing UTC)?
  Checking one blob near 00:00 UTC settles it. Does not block implementation, but does decide whether
  a follow-up change is needed.
