## Context

A blob key is `<sync.path-prefix>/<date directory>/<file name>`. `configure-blob-date-layout` made the
middle segment configurable (`sync.date-path-pattern`) and left the file name alone, on the reported
belief that every producer named files identically. They do not:

```
date-prefixed   logs/2026/08/30/date=2026-08-30 1788132935 1788133535 -<uuid>.log.gz
                                                ^^^^^^^^^^ start epoch
bare-timestamp  your_dir/2026-08-31/            1788169446            -<uuid>.log.gz
                                                ^^^^^^^^^^ epoch (10-digit UTC seconds; 09:44:06Z, agrees with the directory)
```

Only one consumer of the file name exists in the service. `BlobPrefixResolver.bandPrefix` builds

```java
basePrefix + dateSegment(datePath, day) + "/date=" + KEY_DATE.format(day) + startEpochDigits
```

so bounded (`band`) listing — the poller default — resolves prefixes that match nothing for a bare-timestamp producer. The
poller then reports successful cycles with zero discovery every minute; only the daily reconciliation
run, which lists whole days, ingests anything. Nothing else is coupled to the key: `dayPrefix` (the
scheduled run, the operator backfill, and the poller's `day` fallback) stops at the date directory,
`LogParser` and `BlobIngestor` are content-driven, and the only column fed from a key is
`dial_usage_log._source_file` (`$sourceFile`), which stores it verbatim and is not part of the dedup
key. So this is again a key-building change, one segment further right.

Three constraints carry over from the previous design and still bind. `BlobPollingService` is
`@ConditionalOnProperty(poll.enabled)`, so nothing validated on every deployment can live there.
`band` mode's correctness argument rests on a fixed-width decimal timestamp sitting at a fixed offset
in the file name — a text prefix of a fixed-width decimal is a numeric range filter — so the timestamp
itself must stay outside what an operator can configure. And a misconfiguration here cannot be
detected from the `band` path itself: when the prefix is wrong the listing is empty, which is
indistinguishable from an idle environment.

Scale, which shapes what is worth building: a busy deployment can write on the order of **thousands of
blobs/day**, a small one only **tens/day**. `band` mode exists for the high-volume case; at tens a day
a whole-day listing every minute would be free. So the goal is not the small deployment's listing cost — `POLL_LISTING_MODE=day` already covers that with no code — but removing the
hardcoded assumption and, above all, removing its silence.

## Goals / Non-Goals

**Goals:**

- Make the file-name text preceding the embedded timestamp configurable with one property, defaulting
  to today's behavior byte-for-byte.
- Keep one component owning the whole key layout, so no discovery path can drift.
- Fail startup on a head that cannot work, instead of running and listing nothing.
- Make a head that is *valid but wrong* visible without waiting for a human to correlate a zero-blob
  poll cycle against a successful backfill.
- Show an operator a rendered band prefix at startup, so both layout assumptions can be checked against
  a real key.

**Non-Goals:**

- Making the embedded timestamp configurable — unit, width, position, or an ISO-8601 rendering instead
  of an epoch (see D5).
- Auto-detecting either layout by probing the container. Unchanged from the previous design; the shape
  check in D3 performs no listing of its own.
- Supporting more than one layout or container per instance.
- A file-name include filter or suffix match. Discovery still ingests every blob under the prefix.
- Validating `poll.listing-mode`, whose unrecognized values silently select `day` — correct behavior,
  only slower. Adjacent, but a separate concern.
- Changing how the target date, the watermark, the band width or the backfill range are chosen.

## Decisions

### D1 — A date-format pattern for the head, consistent with `sync.date-path-pattern`

`sync.file-name-prefix-pattern` / `SYNC_FILE_NAME_PREFIX_PATTERN`, default `'date='yyyy-MM-dd`,
interpreted as a `java.time.format.DateTimeFormatter` pattern rendered for the day the prefix covers
and placed between the date directory and the band digits.

```
date-prefixed  (default)   logs/2026/06/24/date=2026-06-241782297
bare-timestamp (blank)     your_dir/2026-06-24/1782297
```

The head must be a *date* pattern rather than a plain literal because the default head contains the date
itself and therefore changes daily; `'date='` is a quoted literal and `yyyy-MM-dd` renders the band's
day. It is the same mechanism `date-path-pattern` already uses, which keeps one concept in the
configuration surface instead of two.

- **Rejected: an enum** (`DATE_PREFIXED` / `BARE`). Cheapest to validate, but a third producer shape
  becomes another code change and another release — the thing this line of work exists to stop.
- **Rejected: a template with a `{epoch}` placeholder** (`'date='yyyy-MM-dd{epochSeconds}`). Band
  prefixing requires the timestamp at a fixed offset from the start of the file name, so its position
  is already fully determined by the head's length; the placeholder would add a language for zero
  gain. Its one real benefit — letting an operator declare "this producer has no timestamp" so `band`
  mode could refuse at startup — duplicates `POLL_LISTING_MODE=day`, which already expresses exactly
  that.
- **Rejected: one combined key template** spanning base path, date directory and head. Same reason as
  the previous change: it gives two ways to express the base path and an obvious way to configure them
  into disagreement.

The `application.yml` line is `file-name-prefix-pattern: ${SYNC_FILE_NAME_PREFIX_PATTERN:'date='yyyy-MM-dd}`
— a plain YAML scalar starting with `$`, so the embedded single quotes need no escaping, and the
default contains no `:` or `}` to confuse placeholder parsing.

### D2 — Blank is legal for the head, unlike the date directory

A blank head means "the embedded timestamp begins the file name" — the bare-timestamp case — and is accepted.

The asymmetry with `date-path-pattern`, where blank aborts startup, is deliberate and rests on what
each blank would do. A blank *date directory* would widen every whole-day listing (the scheduled run
and the backfill list by directory) to the entire base path. A blank *head* widens nothing: the band
prefix still carries the date directory and the timestamp digits, so it stays exactly as selective —
the head is a constant for a given day and contributes no selectivity at all. Over-matching that a
shorter number in the same directory could cause is harmless: the poller's in-memory
last-modified check makes selection exact over whatever candidates the prefix returns.

The operational cost of "blank" is that the bare-timestamp configured value is the **empty string**, which
deployment tooling that prunes empty environment variables would silently replace with the
default. That is a real footgun, and it is the main reason D3 is in scope rather than deferred.

### D3 — Report a mismatch from keys already listed, not from a probe

`BlobPrefixResolver` gains `checkBandShape(LocalDate day, List<String> keys)`, called from
`DataSyncService.ingestDate` immediately after the `blobClient.list(prefix)` it already performs. For
each key it asserts the file name begins with the rendered head followed by a digit. Non-matching
keys increment `sync.blobs.band.shape.mismatch` on **every** run, and the first occurrence in a
process logs one WARN naming a real offending key, the expected shape, and both remedies.

This is the only place the condition can be observed. The `band` path cannot detect its own failure —
a wrong prefix returns an empty listing, which is what an idle environment also returns — but the
scheduled reconciliation run and therefore **every operator backfill** already list whole days, and a
`startsWith` per key costs nothing beside ingesting those same keys. It converts migration step 3 (the
single-day smoke backfill) from "infer it from `filesProcessed`" into an explicit statement naming the
property to fix.

- **Why not a fallback rather than a warning?** Falling back to a day listing whenever `band` mode
  discovers nothing would self-heal, but it fires on every genuinely idle cycle: at
  `POLL_CRON=0 * * * * *` that is up to 1440 full-day listings a day over thousands of blobs — exactly
  the cost `band` mode exists to avoid. A once-per-startup probe was the bounded version of that idea,
  and it is strictly worse than this one: it costs an extra listing and only covers process start.
- **Why not in the poller's `day` branch as well?** The scheduled run and the backfill already cover
  every deployment, and a per-cycle check would repeat one warning it has already logged. Deployments
  on `day` mode are also the ones that have already worked around the problem.
- **Why count every run but log once?** A counter that stops incrementing looks resolved to
  monitoring; a WARN that repeats every run is what makes people filter the logger out. The split
  keeps the condition alerting for as long as it persists without becoming noise.
- **Why is it diagnostic only?** The mismatch narrows *discovery*, it does not corrupt anything: the
  whole-day paths ingest those blobs regardless, so changing ingestion here would fix nothing and
  would put a new behavior behind a heuristic.

The check inherits one honest weakness from D2: with a blank head it asserts only "the file name
starts with a digit", which is a weaker statement than the default's "starts with `date=<ISO date>` then a
digit". It is the strongest assertion available from the configuration alone.

### D4 — Validation in the resolver's constructor, two checks not four

The head is validated in `BlobPrefixResolver`'s constructor, the same home and the same fail-the-
context stance as the date pattern (and for the same reason: `BlobPollingService` is conditional, so a
scheduled-only deployment would skip a check that lived there). But only two of the date pattern's
four checks apply:

1. **Parses** — `DateTimeFormatter.ofPattern(pattern, Locale.ROOT)`. `Locale.ROOT` for the same reason
   as before: a head using textual fields (`MMM`) must render identically on every host.
2. **Formats a `LocalDate`** — a head naming a time field (`HH`) parses fine and throws
   `UnsupportedTemporalTypeException` only at format time, i.e. on the first poll cycle, which is the
   "starts healthy, ingests nothing" outcome this work exists to prevent.

The **distinctness sweep is deliberately absent**, and so is the blank refusal. A head that renders the
same text for every date (`'usage-'`, or blank) is a perfectly good producer shape: the band prefix's
day is pinned by the date directory in front of it, so a head that does not vary by day collapses
nothing. Applying the date pattern's check here would refuse the exact value a bare-timestamp producer needs.

**Normalization** trims surrounding whitespace in `SyncProperties` (as `datePathPattern` does) and
strips a **leading** `/` from the *rendered* head, so a head written `/date=…` cannot double the
separator after the date directory. A trailing `/` is preserved — a producer nesting one level deeper
legitimately ends its head with one — which is the opposite of `dateSegment`, where both ends are
stripped because the resolver supplies the separator itself.

### D5 — The embedded timestamp stays the producer's

`bandPrefixes` keeps deriving the digits as `floor(epochSecond / bandWidthSeconds)` and appending them
verbatim. Nothing about the timestamp becomes configurable: not its unit, not its width, not its
position.

The reason is the correctness argument itself. Band listing works only because a text prefix of a
fixed-width decimal selects a contiguous numeric range; that property belongs to the producer's
encoding, and an operator who could change the unit or width could silently turn a time filter into
an arbitrary string filter that still returns *something*. Two useful consequences fall out of
leaving it alone:

- A producer writing **millisecond** epochs needs no work at all. The leading digits of `1788169446123`
  are the leading digits of `1788169446`, so the same prefix selects the same time range — decimal
  prefix semantics are scale-free at fixed width.
- Whether a bare-timestamp producer's number is the file's **start** or its **finalization** epoch does not matter. The band
  slice reaches back `poll.finalization-lag` below the cutoff, which covers a start-named key; an
  end-named key tracks last-modified more tightly still.

A producer whose file names carry no sortable timestamp, or one carrying an ISO-8601 timestamp rather
than an epoch, is out of scope: `POLL_LISTING_MODE=day` serves it today, and generalizing bands to
"any lexicographically sortable rendered timestamp" is a different discovery model and a separate
change if such an environment appears.

### D6 — One extra constructor argument, and the fallout it carries

`BlobPrefixResolver` takes `MeterRegistry` for the mismatch counter, and `DataSyncService` gains one
call. That is the whole wiring. `BlobPollingService`, `DataBackfillService`, `BlobIngestor`,
`BlobClient` and every write path are untouched.

The mechanical fallout is worth naming so it is not discovered mid-implementation: the new
`SyncProperties` component reaches **9** `new SyncProperties(...)` sites in `src/test`, and the new
constructor argument reaches **7** `new BlobPrefixResolver(...)` sites. All of them pass the default
head, which is what makes "no behavior change at the default" a mechanical check rather than an
assertion — every existing expected prefix string in `BlobPrefixResolverTest` must survive unchanged.

`PollProperties` was considered as a second injection so the warning could be phrased differently
under `POLL_LISTING_MODE=day` (where it is less actionable). Rejected: it couples sync-side discovery
to poll configuration for a wording nuance, and the message names both remedies anyway.

## Risks / Trade-offs

- **A head that is valid but wrong** (`'dt='yyyy-MM-dd` where the producer writes `date=`) passes both
  checks and matches nothing → unlike the date pattern, this now has a detector: the mismatch counter
  and warning fire on the first whole-day listing, and the startup log states the resolved band prefix.
  The residual is a deployment that never runs a backfill and whose first scheduled run is a day away.
- **The bare-timestamp value is the empty string** and tooling that prunes empty environment variables would restore
  the default silently → the startup band-prefix example shows it immediately, and the mismatch
  report catches it on the first run. Documented in `docs/configuration.md`.
- **The shape check is weaker with a blank head** ("starts with a digit") → accepted; it is the
  strongest assertion the configuration supports, and it still catches a head configured when none was
  wanted, or a non-timestamped file appearing in the directory.
- **A first `band` cycle after a fix still relies on the watermark** rather than re-listing history → a
  restart resets the watermark to `now − poll.restart-lookback`, and the daily reconciliation run lists
  whole days, so nothing is lost; discovery simply resumes forward.
- **The refactor touches the poller's hot path** → pure key building with no I/O. The acceptance bar is
  that every existing default-pattern assertion in `BlobPrefixResolverTest`, `BlobPollingServiceTest`
  and `DataSyncServiceTest` keeps its current expected string, and all three build fixtures on the real
  jclouds `filesystem` provider, so the bare-timestamp cases are actual ingests rather than string
  assertions.
- **`_source_file` values now differ in shape between environments.** Nothing in ADAS parses them (the
  column is ingest provenance, `system`-tagged, and not part of the dedup key), but a hand-written query
  or dashboard filtering on `date=` would need updating. Called out because it is invisible from the
  code.

## Migration Plan

1. Ship with `sync.file-name-prefix-pattern` unset everywhere. Every existing deployment takes
   the `'date='yyyy-MM-dd` default and resolve byte-identical prefixes; the only visible differences are
   one extra startup log line and a counter that stays at zero.
2. Confirm on a default-layout deployment that `poll.blobs.discovered` / `poll.rows.inserted` stay non-zero and
   `sync.blobs.band.shape.mismatch` stays at 0 — the latter is a direct assertion that its real keys
   match the default head.
3. On a bare-timestamp deployment set the four variables together — `BLOB_BUCKET=your_container`, `SYNC_PATH_PREFIX=your_dir`,
   `SYNC_DATE_PATH_PATTERN=yyyy-MM-dd`, `SYNC_FILE_NAME_PREFIX_PATTERN` **empty** — and check the startup
   log reads `your_dir/<yyyy-MM-dd>/` with a band example of `your_dir/<date>/<digits>` carrying no
   `date=`. If the example still shows `date=`, the empty variable was dropped by the deployment
   tooling.
4. Smoke-test with a **single-day** backfill (`POST /v1/data-sync/backfill`, `from` = `to` = a date known
   to have blobs) and check `GET /v1/data-sync/backfill/{runId}` reports `filesProcessed > 0` with no
   band-shape warning in the log and the counter still at zero.
5. Then let the poller run on the default `band` mode and watch `poll.blobs.discovered` /
   `poll.rows.inserted` become non-zero. If discovery stays at zero while the backfill found blobs, the
   warning from step 4 names what to fix; `POLL_LISTING_MODE=day` remains the fallback.
6. **Rollback** is unsetting `SYNC_FILE_NAME_PREFIX_PATTERN` (or reverting the build): it is read only at
   startup and nothing persists a prefix, so there is no state to unwind.

## Open Questions

- Is a bare-timestamp producer's leading number the log file's **start** or its **finalization** epoch? Intervals between
  consecutive names are 600–860 s, consistent with either a timer roll plus idle gaps or with several
  writer replicas. Does not block implementation or affect correctness (D5), but it decides whether
  `POLL_FINALIZATION_LAG` is doing real work there or is merely over-provisioned.
- Do a bare-timestamp producer's blob **contents** carry the same DIAL usage-log record shape as the default's? Independent of this
  change — discovery is what is being fixed — but it is the next thing that can make such an ingest report
  zero rows, and step 4's backfill answers it at the same time.
