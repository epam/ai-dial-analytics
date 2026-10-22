## Why

Some DIAL deployments write their usage logs under a **single-segment date directory** —
`your_container/your_dir/2023-06-12/…` — instead of the three-segment form ADAS was built against,
`logs/logs/2026/04/24/date=2026-04-24<startEpoch><endEpoch>-<uuid>.log.gz`. The container, base
folder and date directory are observed from the storage-account listing on both sides; the blob *file
names* under them are **reported** identical but observed only for the three-segment layout, which is why this change keeps
the file-name portion of the key out of scope and design records confirming one real single-segment key as a
pre-deployment check.

Two of the three differences are already env-configurable (`BLOB_BUCKET` for the container,
`SYNC_PATH_PREFIX` for the base folder). The date directory is not: `DateTimeFormatter.ofPattern("yyyy/MM/dd")`
is a hardcoded constant repeated in three discovery sites — `DataSyncService:41`,
`BlobPollingService:61`, and `PollPrefixResolver:32`. Because all three ingestion paths (poller,
scheduled reconciliation, operator backfill) resolve their prefix through those constants, **ADAS
currently cannot ingest a single blob from a single-segment layout under any configuration**, and there is no workaround:
the date segments are always appended after `path-prefix`, so the layout cannot be smuggled in
through it, and a prefix broad enough to match (`your_dir/`) defeats both band listing (the key
prefix no longer matches) and day listing (every cycle would list the whole container).

## What Changes

- **Add one configuration property, `sync.date-path-pattern`** (`SYNC_DATE_PATH_PATTERN`, default
  `yyyy/MM/dd`), naming the date-directory layout between the base `path-prefix` and the blob file
  name. Such a deployment then runs unchanged code with `BLOB_BUCKET=your_container`, `SYNC_PATH_PREFIX=your_dir`,
  `SYNC_DATE_PATH_PATTERN=yyyy-MM-dd`.
- **Apply it at all three discovery sites through one shared resolver**, so the scheduled run, the
  poller (both `band` and `day` modes) and the backfill cannot drift from one another. The
  triplicated `yyyy/MM/dd` constant is removed; the layout is stated once.
- **Validate the pattern at startup, fail-fast.** A pattern that cannot be parsed, that cannot
  *format* a date (e.g. one naming a time field like `HH`), that omits any date field, or that is
  blank aborts the context with a message naming the property and its env var. The third check is
  load-bearing and easy to get wrong: a layout omitting the **year** (`MM/dd`) looks fine for a year
  and then collides, collapsing distinct dates onto one prefix — so each run lists far more than its
  target date while reporting success. Design D4 pins the probe dates that catch it.
- **No behavior change at the default.** `yyyy/MM/dd` reproduces today's prefixes byte-for-byte on
  every path, so existing deployments are unaffected. **Not** a breaking change.
- **Documentation**: `docs/configuration.md` (the `sync.*` table plus the two prose passages that
  spell the layout out) and the `README.md` data-sync paragraph stop presenting `{yyyy}/{MM}/{dd}`
  as fixed.

Deliberately **not** in scope:

- **The blob file name.** Band listing still assumes the key continues `date=<ISO date><fixed-width
  start epoch>…` — reported identical for the single-segment layout, and the one thing worth confirming against a real key
  before the poller is switched on. An environment whose file names differ is served today by the
  existing `POLL_LISTING_MODE=day` fallback and would need its own change.
- **Auto-detecting the layout** by probing the container. One env var per deployment is cheaper to
  reason about than a discovery heuristic that can pick wrong and then silently ingest nothing.
- **Multiple simultaneous layouts** in one instance (e.g. ingesting two containers with different layouts at once).
  `blob.bucket` is already single-valued, so this would be a much larger change with no demand.
- **A container with no date directory at all.** A blank layout is refused rather than quietly
  supported: `band` listing would in fact work (the date is in the file name), but the daily
  reconciliation run and the backfill list a whole day *by directory* and would degrade to listing the
  entire base path per date. Serving such a producer means deriving the day prefix from the file name
  instead, which is a different discovery model.
- **A UTC-vs-local check on the directory date.** ADAS derives the directory and the file's `date=`
  from the same UTC day; if a producer bucketed a blob by local time, a midnight-adjacent blob could
  fall in a directory ADAS does not list for that day. This change neither introduces nor fixes
  that, and it is the same for the default layout today. It is called out in design as a pre-deployment check
  against real single-segment keys.

## Capabilities

### New Capabilities

None. This widens the configuration surface of two existing capabilities.

### Modified Capabilities

- `data-sync`: the *Date-derived prefix file discovery* requirement currently pins the prefix to
  "the form `logs/{yyyy}/{MM}/{dd}/`". It becomes a base path plus a **configurable** date-directory
  layout, defaulting to `{yyyy}/{MM}/{dd}`, with a new requirement that the layout is validated at
  startup and that every discovery path (scheduled, poller `band`, poller `day`) resolves it
  identically.
- `data-sync-backfill`: the *Operator-triggered date-range backfill* requirement names the literal
  prefix `<path-prefix>/{yyyy}/{MM}/{dd}/`. It is restated to defer to `data-sync`'s configured
  layout — which is what "the same discovery logic as the scheduled path" already intends, so this
  pins existing behavior rather than changing it.

## Impact

**Surfaces**

- One new configuration key, `sync.date-path-pattern` / `SYNC_DATE_PATH_PATTERN`. No `/v1` request
  or response shape changes, no new endpoint, no catalog or migration change.
- Startup gains a fatal failure mode: an invalid date-path pattern. This is deliberate — the
  alternative is an instance that lists a prefix matching nothing and reports healthy zero-row runs.

**Code**

- `src/main/java/com/epam/aidial/analytics/configuration/SyncProperties.java` — one new record
  component (~7 test construction sites follow).
- `src/main/java/com/epam/aidial/analytics/service/PollPrefixResolver.java` — renamed
  `BlobPrefixResolver` (it is no longer poll-specific) and made the single owner of the layout: an
  injectable component exposing both the day prefix and the band prefixes, validating the pattern in
  its constructor.
- `src/main/java/com/epam/aidial/analytics/service/DataSyncService.java`,
  `.../BlobPollingService.java` — drop their private `PREFIX_DATE` constants and `buildPrefix`
  duplicates in favour of the resolver.
- `DataBackfillService` is untouched: it already reaches discovery through
  `DataSyncService.runForDate`, so it inherits the configured layout for free.
- `BlobIngestor`, `BlobClient`, the mappings and every ClickHouse write path are untouched — ingest
  is content-driven, and the only place a blob key reaches a row is the `$sourceFile` token, which
  records whatever key was listed.
- `docs/configuration.md`, `README.md`.

**Operational**

- A single-segment deployment becomes ingestible with a three-variable deployment change and no code fork. Existing
  deployments need no configuration change at all.
- The resolved discovery prefix template is logged once at startup so an operator can confirm what
  ADAS will list before waiting a cron cycle to find out — accurately, unlike the `security.mode`
  precedent where the startup log can disagree with the effective value.

**Risk**

Low. The change is additive configuration over a pure key-building function; the default value
reproduces current behavior exactly, and the failure mode of a misconfigured pattern is a refused
startup rather than a quiet zero-row ingest. The residual risk is environmental, not code:
confirming that real single-segment keys carry the same file-name shape and the same UTC day in directory and
`date=` segment (see design).
