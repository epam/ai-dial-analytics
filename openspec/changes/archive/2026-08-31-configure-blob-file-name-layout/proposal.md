## Why

`configure-blob-date-layout` made the **date directory** of a blob key configurable and deliberately
kept the blob **file name** out of scope, on the assumption that all producers share one file-name shape. That
assumption was wrong. Two shapes are confirmed from real keys:

| | key |
| --- | --- |
| default layout (all sampled `_source_file` values match) | `logs/2026/08/30/date=2026-08-30`​`1788132935`​`1788133535`​`-<uuid>.log.gz` |
| alternative layout (storage-account listing of `your_container/your_dir/2026-08-31/`) | `your_dir/2026-08-31/`​`1788169446`​`-<uuid>.log.gz` |

The alternative layout's leading number is a 10-digit UTC epoch-**seconds** value (`1788169446` = 2026-08-31 09:44:06Z)
that agrees with its own date directory — which also settles the previous change's open question about
whether that layout buckets by UTC: it does. The end-epoch segment is simply absent. One difference is
material: **the `date=<ISO date>` head preceding the epoch digits is one producer's convention, not a universal
one.**

What that breaks is narrow but severe. `BlobPrefixResolver.bandPrefix` hardcodes
`… + "/date=" + ISO_LOCAL_DATE.format(day) + <bandDigits>`, so on the alternative layout every bounded (`band`) listing
prefix matches nothing. `band` is the **poller default**, so a deployment with that layout and a correct
`SYNC_DATE_PATH_PATTERN` polls every minute, discovers zero blobs, and reports healthy cycles — the
silent-zero failure mode the previous change exists to prevent, reappearing one segment further right
in the key. Everything else already works: whole-day discovery (scheduled reconciliation, operator
backfill, `POLL_LISTING_MODE=day`) never reads the file name, `LogParser` is content-driven, and the
only place a key reaches a row is `$sourceFile`, which records whatever was listed.

For proportion: a busy deployment writes on the order of **a thousand blobs/day** (the reason `band` mode exists at all); a small one
writes **tens/day**, where a whole-day listing per cycle is trivially cheap. The value of this change is
therefore not a small deployment's listing cost — `POLL_LISTING_MODE=day` would cover that with no code — but that
the file-name shape stops being a hardcoded assumption, and that getting it wrong stops being silent.

## What Changes

- **Add one configuration property, `sync.file-name-prefix-pattern`** (`SYNC_FILE_NAME_PREFIX_PATTERN`,
  default `'date='yyyy-MM-dd`), naming the literal head of the blob file name that precedes the embedded
  epoch digits, as a `java.time` date pattern rendered for the band's day. The alternative layout then runs unchanged code
  with the value set **empty**: `your_dir/2026-08-31/1788169` instead of
  `logs/2026/08/31/date=2026-08-311788169`.
- **A blank value is legal here**, unlike `sync.date-path-pattern`. A blank head means "the epoch digits
  start the file name"; the date directory still bounds the listing, so nothing widens. A blank *date
  path* stays refused.
- **The band digits stay computed, never configured.** `floor(epochSeconds / band-width)` is still
  appended verbatim; the new property covers only the constant text in front of it.
- **Validate the head at startup, fail-fast** — two checks, not the date pattern's four: it must parse,
  and it must be able to *format* a date (a head naming a time field such as `HH` parses fine and throws
  only at format time, i.e. on the first poll cycle). No distinctness sweep and no blank refusal: a
  purely literal head and an empty head are both legitimate producer shapes, and it is the date
  directory — not the head — that must distinguish days.
- **Report a file-name shape mismatch instead of listing nothing.** Every whole-day listing the service
  already performs (the scheduled reconciliation run and therefore every operator backfill) checks each
  listed key's file name against the configured head plus a digit, increments a counter for
  non-matching keys, and logs one WARN per process naming a real offending key and both remedies. No
  extra listing is performed, so this is not container probing — it is an assertion over keys already in
  hand, and it is what turns "band mode discovered nothing" from an inference into a statement.
- **Log a rendered band-prefix example at startup** alongside the existing day-prefix template, so an
  operator can compare both assumptions against a real key before waiting for a cycle.
- **No behavior change at the default.** `'date='yyyy-MM-dd` reproduces today's band prefixes
  byte-for-byte on every existing deployment. **Not** a breaking change.
- **Documentation**: `docs/configuration.md` gains the property row and corrects the two passages that
  present the `date=<ISO date><startEpoch>` file-name portion as fixed.

Deliberately **not** in scope:

- **Making the embedded timestamp itself configurable** — its unit, width, position, or replacing the
  epoch with an ISO-8601 rendering. Only two producer shapes exist and both put a fixed-width epoch
  immediately after the head. A producer whose file names carry no sortable timestamp at all is served
  by `POLL_LISTING_MODE=day`, as today, and would need its own change. (Millisecond epochs happen to
  need no work: a fixed-width decimal's leading digits select the same time range at any scale.)
- **A sentinel value meaning "no timestamp in the file name"** that would refuse `band` mode at startup.
  It duplicates `POLL_LISTING_MODE=day` with a second way to say the same thing.
- **A file-name include filter or suffix match.** Discovery still ingests every blob under the prefix;
  no environment is known to mix content, and jclouds listing already excludes folder placeholders.
- **Validating `poll.listing-mode`.** An unrecognized value silently selects `day` today, which is
  correct behavior, only slower.
- **Wiring the shape check into the poller's `day` branch.** The scheduled run and the backfill already
  cover every deployment; a per-cycle check would only repeat the same warning.

## Capabilities

### New Capabilities

None. This widens the configuration surface of one existing capability.

### Modified Capabilities

- `data-sync`: the *Uniform date-directory layout across discovery paths* requirement currently states
  that the blob file name — "including the `date=<ISO date>` segment and the embedded start timestamp
  that bounded (`band`) listing relies on" — is produced by the log producer and is **NOT** governed by
  configuration, and that an environment whose file names differ "SHALL remain served by the full-day
  (`day`) listing fallback". That paragraph is replaced: the head preceding the embedded timestamp
  becomes configurable, while the timestamp itself stays the producer's. Two new requirements are added
  — startup validation of the head, and the band-shape mismatch report over already-listed keys.

`data-sync-backfill` needs no delta: the backfill reaches discovery through
`DataSyncService.runForDate`, only ever uses the whole-day prefix, and so inherits both the property and
the new warning with no backfill-specific behavior change.

## Impact

**Surfaces**

- One new configuration key, `sync.file-name-prefix-pattern` / `SYNC_FILE_NAME_PREFIX_PATTERN`. No `/v1`
  request or response shape changes, no new endpoint, no catalog or migration change.
- One new metric, `sync.blobs.band.shape.mismatch` (counter, incremented per non-matching listed key on
  every whole-day run).
- Startup gains a fatal failure mode: an unusable file-name head. Deliberate — the alternative is an
  instance whose poller lists prefixes matching nothing and reports healthy zero-blob cycles.

**Code**

- `configuration/SyncProperties.java` — one new record component (16 construction sites across main and
  test follow).
- `service/BlobPrefixResolver.java` — the substantive change: hold the head's formatter, validate it,
  render it in `bandPrefix` in place of the hardcoded `date=<ISO date>`, add the shape check, and extend
  the startup log. Takes `MeterRegistry` as a second constructor argument for the mismatch counter.
- `service/DataSyncService.java` — one call, after the listing it already performs.
- `BlobPollingService`, `DataBackfillService`, `BlobIngestor`, `LogParser`, `BlobClient`, the mappings and
  every ClickHouse write path are untouched. `dayPrefix` and the band digits are unchanged, so both
  listing modes and the backfill keep their current key building except for the head.
- `docs/configuration.md`, plus one metric name in `README.md`'s observability list. `README.md` needs no
  configuration change — it describes the date path but never the file name, and env-var descriptions
  belong in `docs/configuration.md`.

**Operational**

- The alternative layout becomes ingestible on the default `band` mode with a four-variable deployment change
  (`BLOB_BUCKET`, `SYNC_PATH_PREFIX`, `SYNC_DATE_PATH_PATTERN`, `SYNC_FILE_NAME_PREFIX_PATTERN` empty)
  and no code fork. Existing deployments need no configuration change at all.
- A misconfigured head is now reported by the first scheduled run or backfill rather than inferred from
  a zero-blob poll cycle.

**Risk**

Low. The change is additive configuration over a pure key-building function whose default reproduces
current behavior exactly, plus a diagnostic that performs no I/O of its own and changes no ingestion
outcome. The main residual is operational: the alternative layout's value is the *empty string*, and deployment tooling
that drops empty environment variables would silently restore the default — which is precisely what
the startup band-prefix example and the mismatch report exist to surface.
