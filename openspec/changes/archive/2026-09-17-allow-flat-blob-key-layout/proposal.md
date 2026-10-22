## Why

Some DIAL deployments store their usage logs **flat**: every key is
`logs/date=2025-10-22<epoch>-<uuid>.log.gz`, with no date directory between the base path and the file
name. ADAS cannot express that layout at all.

`BlobPrefixResolver.validatedDatePath` **refuses a blank** `SYNC_DATE_PATH_PATTERN` outright, and
`dayPrefix` appends `/` unconditionally after stripping the rendered segment — so even the closest
configuration an operator can reach today, `SYNC_PATH_PREFIX=` plus
`SYNC_DATE_PATH_PATTERN='logs/date='yyyy-MM-dd`, resolves `logs/date=2025-10-22/` and matches nothing.
The shipped spec is explicit about why blank is refused: *"An empty date segment SHALL NOT be read as
'no date directory', because it would widen every whole-day listing to the entire base path."* That
objection is correct and stays correct — it is the *implementation* of the relaxation that has to
answer it, not the relaxation itself.

The consequence is concrete: the companion change adding AWS workload-identity credentials
(`support-s3-workload-identity`) makes ADAS able to *authenticate* against that bucket and then ingest
**zero rows**, every cycle, reporting success. The two ship together.

## What Changes

- **A blank `sync.date-path-pattern` becomes legal and means "no date directory".** In that mode the
  whole-day prefix absorbs the configured file-name head: `dayPrefix(day) = <path-prefix>/<head>`, with
  **no trailing separator** — `logs/date=2025-10-22`. It is *not* "omit the empty segment and its
  slash", which would leave `logs/` for every day; see design.md, D1.
- **The startup distinctness sweep moves from the rendered date segment onto the rendered whole-day
  prefix.** That single move keeps `yyyy/MM` refused in the normal case (the head is no part of the
  whole-day prefix while a date directory exists) and admits a blank layout *exactly* when the
  file-name head pins the day. A blank or dateless head under a blank date layout needs no refusal of
  its own — it collides with itself on the second day swept.
- **New refusal — prefix-freedom.** Without the trailing `/`, one day's prefix can be a proper prefix
  of another's: `'d='yyyy-M-d` renders `d=2025-1-1`, which prefix-matches `d=2025-1-10`…`-1-19`, so one
  whole-day listing would silently pull ten other days. Distinctness cannot catch it — the strings
  differ. The swept prefixes are sorted and the layout is refused when an entry is a proper prefix of
  its successor. Only reachable in blank-date mode; the trailing slash is what rules it out everywhere
  else, and dropping the slash has to be paid for.
- **Refusal messages keep naming what the operator set** — the rendered date segment, or the rendered
  head in blank mode — not the whole prefix.
- **Nothing changes for an existing deployment.** Where a date directory is configured, the whole-day
  prefix, every band prefix and the band-shape check are byte-identical to today's; the
  `application.yml` default stays `yyyy/MM/dd`.

**Not breaking.** A value that is refused today becomes accepted under a condition; no accepted value
changes meaning and no path changes shape.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`, six requirements:
  - *Date-derived prefix file discovery* — fixes the key form as `<path-prefix>/<date-path>/`, which
    the flat mode does not take; gains the head-absorbed form and an explicit refusal of the
    degenerate `<path-prefix>/`.
  - *Uniform date-directory layout across discovery paths* — the deployment-shape clause covers only
    "differ in their date directory"; and the two modes now compose differently without diverging.
  - *Date-directory layout is validated at startup* — its "it is blank" bullet is precisely what
    changes, the distinctness bullet now sweeps the whole-day prefix, and prefix-freedom is added.
  - *Configurable file-name head preceding the embedded timestamp* — says "this differs from the
    date-directory layout, where blank is refused" and carries a *Head applies to bounded listing
    only* scenario; both are falsified in blank-date mode and become conditional.
  - *File-name head layout is validated at startup* — "SHALL NOT require the layout to distinguish
    dates" becomes conditional on a date directory existing.
  - *A file name bounded listing cannot match is reported* — the check is necessarily weaker in the
    flat mode (the whole-day prefix already carries the head, so a wrong head yields an empty listing
    that no assertion over its results can distinguish from an idle day), and the remedy the warning
    offers — "fall back to whole-day listing, which is unaffected" — is false there, since the
    whole-day paths list under that same head.

## Impact

- **Code**: `service/BlobPrefixResolver.java` only — a `dateDirPrefix` extraction (mandatory:
  `bandPrefix` and `checkBandShape` are written against `dayPrefix + head` today and would otherwise
  double the head), `validatedDatePath` becoming an instance method, the two refusals, and the startup
  log template. `configuration/SyncProperties.java` javadoc. **No caller changes** —
  `DataSyncService`, `DataBackfillService` and `BlobPollingService` only pass `dayPrefix` to
  `blobClient.list`, which lists by prefix with `recursive()` and no delimiter, so the absent trailing
  separator is load-bearing nowhere.
- **Tests**: of the 43 in `BlobPrefixResolverTest`, `blankLayoutIsRefused` and `nullLayoutIsRefused`
  pass the day-pinning default head and so must be re-pointed at a blank head;
  `refusalNamesTheCollidingDates` must keep passing unchanged. The other 40 stand — every one
  configures a non-blank date layout, where the date-directory prefix equals the whole-day prefix.
- **Config**: no new property, no default change, so `docs/configuration.md` gains **no row**; the
  `SYNC_DATE_PATH_PATTERN` description line changes, since blank is now meaningful rather than refused.
- **Cross-service**: none. No endpoint, no contract version, no migration, no credential.
