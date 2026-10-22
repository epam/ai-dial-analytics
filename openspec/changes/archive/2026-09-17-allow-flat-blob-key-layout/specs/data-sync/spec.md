## MODIFIED Requirements

### Requirement: Date-derived prefix file discovery

The service SHALL determine which files to ingest by resolving a date-derived blob prefix and listing
all blobs under that prefix. The prefix SHALL take the form `<path-prefix>/<date-path>/`, where
`<path-prefix>` is the configurable base path within the container and `<date-path>` is the target date
rendered through a **configurable date-directory layout** whose default is `{yyyy}/{MM}/{dd}`. The
layout SHALL be expressible as a single value covering both the three-segment form
(`{yyyy}/{MM}/{dd}`, e.g. `2026/06/18`) and a single-segment form (e.g. `2026-06-18`), since different
DIAL deployments bucket their log blobs by either.

The date-directory layout SHALL additionally be permitted to be **blank**, which SHALL mean the
deployment stores no date directory at all. In that mode the whole-day prefix SHALL absorb the
configured file-name head and SHALL take the form `<path-prefix>/<file-name-head>` — the head rendered
for the target date, with **no trailing separator** — e.g. `logs/date=2025-10-22` for a base path of
`logs` and a head layout of `'date='yyyy-MM-dd`. The head is what pins the day in that mode, so the
prefix remains date-derived and each whole-day listing still covers exactly one date. A blank layout
SHALL be admitted only where that holds; see *Date-directory layout is validated at startup*.

An empty date segment SHALL NOT be read as "a date directory rendering to nothing", i.e. the prefix
SHALL NOT degrade to `<path-prefix>/`. Such a prefix is independent of the date, so every whole-day
listing — the scheduled run, the operator backfill and the poller's full-day fallback alike — would
cover the entire base path and re-ingest every day it holds.

The target date SHALL be the current date minus a configurable day offset (default 1, i.e. the previous
day). The service MUST NOT require an exact blob key, since blob names contain an unpredictable suffix.

#### Scenario: Prefix is resolved from the day offset

- **WHEN** a sync run starts with day offset 1 on 2026-06-19, with base path `logs` and the default
  date-directory layout
- **THEN** the service lists blobs under the prefix `logs/2026/06/18/`

#### Scenario: Prefix is resolved with a single-segment date layout

- **WHEN** a sync run starts with day offset 1 on 2026-06-19, with base path `your_dir` and a
  date-directory layout of `yyyy-MM-dd`
- **THEN** the service lists blobs under the prefix `your_dir/2026-06-18/`

#### Scenario: Prefix is resolved with no date directory

- **WHEN** a sync run starts with day offset 1 on 2025-10-23, with base path `logs`, a blank
  date-directory layout and a file-name head layout of `'date='yyyy-MM-dd`
- **THEN** the service lists blobs under the prefix `logs/date=2025-10-22`, carrying no trailing
  separator, and ingests the flat keys `logs/date=2025-10-22<epoch>-<uuid>.log.gz`

#### Scenario: A blank date layout does not widen the listing to the base path

- **WHEN** the date-directory layout is blank
- **THEN** no discovery path lists `<path-prefix>/` on its own, and a whole-day listing for one date
  does not return the keys of another date stored under the same base path

#### Scenario: All files under the prefix are processed

- **WHEN** the resolved prefix contains multiple blobs
- **THEN** the service processes every blob found under that prefix

#### Scenario: Empty prefix completes cleanly

- **WHEN** the resolved prefix contains no blobs
- **THEN** the run completes successfully having ingested zero rows and reports zero files processed

### Requirement: Uniform date-directory layout across discovery paths

Every ingestion path SHALL resolve its blob prefix through the configured date-directory layout, so
that the scheduled reconciliation run, both poller listing modes (`band` and the full-day fallback),
and the operator-triggered backfill agree on which keys belong to a given UTC date. A deployment
SHALL therefore become ingestible by configuration alone when its log blobs differ from the default
layout only in their date directory — **or carry no date directory at all** — with no code change and
no path left on the default.

The configured date-directory layout SHALL apply only to the date directory. The portion of the blob
**file name** that precedes the embedded timestamp is governed separately, by its own configured
layout (see *Configurable file-name head preceding the embedded timestamp*), because it too differs
between producers. Where the date-directory layout is **blank** the two compose differently but SHALL
NOT diverge: the file-name head is then the only date-bearing text there is, so the whole-day paths
carry it, and bounded (`band`) listing SHALL resolve to that same whole-day prefix extended by the
embedded-timestamp digits — never to the head twice.

Where a date directory **is** configured, bounded listing, the whole-day paths and the band-shape
check SHALL produce byte-identical prefixes to the ones they produced before a blank layout became
legal. Admitting the flat form SHALL be a widening of what is configurable, not a change to any
already-working deployment.

The embedded **timestamp** itself — its unit, its width and its position immediately after that head —
is produced by the log producer and SHALL NOT be governed by configuration; an environment whose file
names carry no such timestamp SHALL remain served by the full-day (`day`) listing fallback.

#### Scenario: Poller band listing uses the configured layout

- **WHEN** bounded (`band`) listing is in effect and the date-directory layout is `yyyy-MM-dd`
- **THEN** each listed band prefix places the date directory in the configured form, follows it with
  the configured file-name head (e.g. `your_dir/2026-06-18/date=2026-06-18…`), and leaves the
  embedded-timestamp digits of the key unchanged

#### Scenario: Poller full-day fallback uses the configured layout

- **WHEN** the full-day (`day`) listing fallback is in effect and the date-directory layout is
  `yyyy-MM-dd`
- **THEN** the poller lists the active day (and, within the grace window, the previous day) under
  prefixes carrying the configured date directory

#### Scenario: Default layout preserves existing prefixes

- **WHEN** no date-directory layout is configured
- **THEN** every path resolves exactly the prefixes it resolved before the layout was configurable,
  on the scheduled, poller and backfill paths alike

#### Scenario: Every path agrees under a blank date layout

- **WHEN** the date-directory layout is blank, the base path is `logs` and the file-name head layout
  is `'date='yyyy-MM-dd`
- **THEN** the scheduled run, the operator backfill and the poller's full-day fallback all list
  `logs/date=<date>`, each band prefix reads `logs/date=<date><band digits>` with the head appearing
  exactly once, and the band-shape check expects that same `logs/date=<date>` followed by a digit

### Requirement: Date-directory layout is validated at startup

The service SHALL validate the configured date-directory layout at startup and SHALL abort startup
when it is unusable, rather than starting and listing a prefix that matches no blob — or one that
matches far more than its target date. A layout is unusable when:

- it cannot be interpreted as a date layout;
- it cannot render a date — for example because it names a time-of-day field no date can supply;
- the **whole-day prefix** it produces renders any two distinct dates of a multi-year window
  identically. Such a layout collapses distinct dates onto one prefix, so each run lists far more than
  its target date while reporting success. The sweep SHALL be made over the rendered whole-day prefix
  rather than over the date segment alone, because the date segment is not always what pins the day:
  where the layout is blank the whole-day prefix is the rendered file-name head, and where a date
  directory is configured the head is not part of the whole-day prefix at all — so the same sweep
  keeps refusing `yyyy/MM` in the ordinary case and admits a blank layout exactly when the head pins
  the day. The check SHALL be made by rendering every date of the window, not a handful of probe
  dates: probes isolating the year, the month and the day catch a layout that omits a field (`MM/dd`
  collides across years) but admit one that merely weakens it (`yyyy/MM/E` carries a weekday where a
  day of the month belongs, and collides four times a month). The failure SHALL name a colliding pair
  of dates. The window SHALL span years rather than centuries, so a two-digit year stays a usable
  layout;
- one day's whole-day prefix is a **proper prefix of another day's**. A whole-day prefix that ends in
  a path separator cannot be, which is what rules this out wherever a date directory is configured; a
  blank date layout produces one that does not, and a variable-width head then collides —
  `'d='yyyy-M-d` renders `d=2025-1-1`, which prefix-matches `d=2025-1-10` … `d=2025-1-19`, so one
  whole-day listing would silently pull in ten other days while reporting success. Distinctness does
  not catch this, because the strings differ. The service SHALL sort the swept whole-day prefixes and
  SHALL refuse the layout when any entry is a proper prefix of its successor, naming the two days. A
  fixed-width head such as `'date='yyyy-MM-dd` passes.

A blank date-directory layout SHALL NOT be refused for being blank. It SHALL mean "no date directory",
and SHALL be admitted exactly when the checks above pass — that is, when the file-name head alone pins
the day and is prefix-free across days. A blank layout paired with a blank or non-date head therefore
still aborts startup, through the same whole-day sweep rather than through a rule of its own: both
days render `<path-prefix>/`, so they collide on the second date swept. `null` and blank SHALL be
treated alike, as the file-name head already treats them.

Every refusal SHALL name the text the operator actually configures — the rendered **date segment**,
or, where the date layout is blank, the rendered **file-name head** — rather than the whole prefix,
together with the offending configuration property and its environment variable.

The service SHALL log the resolved discovery prefix template once at startup, and that log SHALL
reflect the layout actually in effect — including the head-absorbed form where the date layout is
blank — so an operator can confirm what will be listed without waiting for a run.

#### Scenario: Unparsable layout aborts startup

- **WHEN** the configured date-directory layout is not a valid date layout
- **THEN** startup fails with an error naming the configuration property and its environment
  variable, and the service does not begin ingesting

#### Scenario: Layout naming a time field aborts startup

- **WHEN** the configured date-directory layout names a field a calendar date cannot supply (e.g. an
  hour field)
- **THEN** startup fails with an error naming the configuration property and its environment
  variable

#### Scenario: Layout omitting a date field aborts startup

- **WHEN** the configured date-directory layout omits a date field, so that two dates differing only
  in that field render the same text (e.g. a layout carrying month and day but no year)
- **THEN** startup fails with an error naming the configuration property and its environment
  variable

#### Scenario: Layout with a too-coarse date field aborts startup

- **WHEN** the configured date-directory layout names every date field but one of them is too coarse
  to separate days (e.g. a weekday in place of the day of the month, or a quarter in place of the
  month)
- **THEN** startup fails with an error naming the configuration property, its environment variable,
  and two dates the layout renders identically

#### Scenario: Blank layout whose head cannot pin the day aborts startup

- **WHEN** the configured date-directory layout is blank and the file-name head layout is blank too
  (or names no date field)
- **THEN** startup fails with an error naming the configuration property and its environment
  variable, and two dates whose whole-day prefixes are identical, rather than resolving a prefix that
  lists the entire base path

#### Scenario: Blank layout whose head pins the day is accepted

- **WHEN** the configured date-directory layout is blank and the file-name head layout is
  `'date='yyyy-MM-dd`
- **THEN** startup succeeds and the logged discovery prefix template shows the head-absorbed form
  (e.g. `logs/date=<date>`) with no empty date segment and no trailing separator

#### Scenario: A whole-day prefix that is a proper prefix of another day's aborts startup

- **WHEN** the configured date-directory layout is blank and the file-name head layout renders
  variable-width dates (e.g. `'d='yyyy-M-d`, which renders `d=2025-1-1` and `d=2025-1-10`)
- **THEN** startup fails with an error naming the configuration property, its environment variable,
  and the two days whose prefixes nest, rather than starting and listing ten days as one

#### Scenario: Refusal names the configured text, not the whole prefix

- **WHEN** a layout is refused for not distinguishing dates
- **THEN** the message quotes the rendered date segment — or, where the date layout is blank, the
  rendered file-name head — and the pair of dates it renders identically, so the operator reads back
  the value they set

#### Scenario: Valid layout is reported at startup

- **WHEN** the configured date-directory layout is valid
- **THEN** the service starts and logs the resolved discovery prefix template it will list under

### Requirement: Configurable file-name head preceding the embedded timestamp

The service SHALL resolve the text between the date directory and the blob file name's embedded
timestamp from a configured layout, so that a deployment whose producer names files differently
becomes ingestible on bounded (`band`) listing by configuration alone. The layout SHALL be
interpreted as a date layout rendered for the day the prefix covers, because a producer may embed the
date in that text (the default does). It SHALL default to the form the service resolved before it was
configurable, so no existing deployment's prefixes change.

A blank layout SHALL be accepted **where a date directory is configured**, and SHALL mean the embedded
timestamp begins the file name: an absent file-name head does not widen any listing there, because the
date directory still bounds it. Where the date-directory layout is itself blank the head is the only
text that can pin the day, so a blank head SHALL NOT be accepted in that combination — not by a rule of
its own, but because the whole-day distinctness sweep refuses it (see *Date-directory layout is
validated at startup*).

The head's reach SHALL likewise depend on the date-directory layout. Where a date directory is
configured, the head SHALL narrow bounded (`band`) listing only, and the whole-day prefixes used by the
scheduled reconciliation run, the operator backfill and the poller's full-day fallback SHALL be
unaffected by it. Where the date-directory layout is blank, the head is part of the whole-day prefix by
construction, so it SHALL govern every discovery path, and it SHALL still appear exactly once in a band
prefix.

The layout SHALL govern only that text. The digits of the embedded timestamp SHALL continue to be
derived from the requested time range, never configured.

#### Scenario: Default head preserves existing band prefixes

- **WHEN** no file-name head layout is configured
- **THEN** each band prefix carries the `date=<ISO date>` head it carried before the head was
  configurable (e.g. `logs/2026/06/24/date=2026-06-241782297`), on every band the range touches

#### Scenario: Blank head places the timestamp directly after the date directory

- **WHEN** the file-name head layout is blank, the base path is `your_dir` and the date-directory
  layout is `yyyy-MM-dd`
- **THEN** each band prefix reads `your_dir/<date directory>/<timestamp digits>` with no head between
  them (e.g. `your_dir/2026-06-24/1782297`)

#### Scenario: Head and date directory compose

- **WHEN** a blob whose file name begins with the embedded timestamp is stored under a
  single-segment date directory, the date-directory layout is `yyyy-MM-dd` and the file-name head
  layout is blank
- **THEN** bounded (`band`) listing discovers that blob and it is ingested

#### Scenario: Head applies to bounded listing only under a date directory

- **WHEN** a file-name head layout is configured and the date-directory layout is not blank
- **THEN** the whole-day prefixes used by the scheduled reconciliation run, the operator backfill and
  the poller's full-day fallback are unaffected by it

#### Scenario: Head governs every path under a blank date layout

- **WHEN** the date-directory layout is blank and the file-name head layout is `'data='yyyy-MM-dd`
- **THEN** the whole-day prefixes of the scheduled run, the backfill and the poller's full-day
  fallback all read `<path-prefix>/data=<date>`, and each band prefix reads
  `<path-prefix>/data=<date><band digits>` with the head appearing once, not twice

#### Scenario: A head rendering a leading separator does not double it

- **WHEN** the configured file-name head layout renders text beginning with a path separator
- **THEN** the resolved band prefix carries a single separator after the date directory

### Requirement: File-name head layout is validated at startup

The service SHALL validate the configured file-name head layout at startup and SHALL abort startup
when it is unusable, rather than starting and listing a prefix that matches no blob. A layout is
unusable when it cannot be interpreted as a date layout, or when it cannot render a date — for
example because it names a time-of-day field no date can supply, which is accepted as a layout and
fails only when a prefix is first resolved, i.e. on the first poll cycle.

Whether the head must **distinguish dates** SHALL depend on the date-directory layout, and SHALL NOT
be decided by this layout alone. Where a date directory is configured, the validation SHALL NOT
require the head to distinguish dates, and SHALL NOT refuse a blank or purely literal head: it is the
date directory, not this text, that must separate days, and refusing otherwise would refuse exactly
the value a producer writing `<epoch>-<uuid>.log.gz` needs. Where the date-directory layout is blank,
the head is the only text that can separate days, so it SHALL be required both to distinguish every
pair of dates and to be prefix-free across them — enforced by the whole-day checks in *Date-directory
layout is validated at startup*, whose failures name the rendered head.

The failure SHALL name the offending configuration property and its environment variable.

The service SHALL log a resolved band-prefix example once at startup, alongside the whole-day prefix
template, so an operator can confirm both assumptions against a real blob key without waiting for a
poll cycle.

#### Scenario: Unparsable head aborts startup

- **WHEN** the configured file-name head layout is not a valid date layout
- **THEN** startup fails with an error naming the configuration property and its environment
  variable, and the service does not begin ingesting

#### Scenario: Head naming a time field aborts startup

- **WHEN** the configured file-name head layout names a field a calendar date cannot supply (e.g. an
  hour field)
- **THEN** startup fails with an error naming the configuration property and its environment
  variable

#### Scenario: Blank head is accepted under a date directory

- **WHEN** the configured file-name head layout is blank and a date-directory layout is configured
- **THEN** startup succeeds and band prefixes place the embedded timestamp directly after the date
  directory

#### Scenario: Purely literal head is accepted under a date directory

- **WHEN** the configured file-name head layout names no date field at all and a date-directory
  layout is configured
- **THEN** startup succeeds and every band prefix carries that literal text

#### Scenario: A head that cannot separate days is refused under a blank date layout

- **WHEN** the date-directory layout is blank and the configured file-name head layout renders the
  same text for every date (blank, or purely literal)
- **THEN** startup fails with an error naming the configuration property, its environment variable
  and a colliding pair of dates, because nothing else in the prefix pins the day

#### Scenario: Resolved band prefix is reported at startup

- **WHEN** the service starts with a valid configuration
- **THEN** it logs a rendered band-prefix example reflecting the layouts actually in effect

### Requirement: A file name bounded listing cannot match is reported

The service SHALL report blob file names that the configured file-name head cannot match, rather than
leaving a misconfigured bounded (`band`) listing to report healthy cycles that discover nothing. On
every whole-day listing it already performs, the service SHALL check each listed key's file name
against the configured head followed by at least one digit, SHALL count each key that does not match,
and SHALL log a warning identifying a non-matching key, the shape expected, and the remedies
available.

The check SHALL NOT perform any additional listing: it is an assertion over keys already retrieved,
not a probe of the container. It SHALL NOT change which blobs are ingested — a non-matching key is
still ingested by the whole-day paths exactly as before — because a mismatch is a discovery-narrowing
defect, not a data defect.

The warning SHALL be logged at most once per process, while the count SHALL be recorded on every run,
so the condition is visible to monitoring for as long as it persists without the log repeating it.

Where the date-directory layout is **blank** the check SHALL remain in force but is necessarily weaker,
and the service SHALL NOT claim otherwise. The whole-day prefix there already carries the head, so a key
the listing returned cannot fail the head comparison and only the trailing-digit test still applies; a
*wrong* head makes the whole-day listing itself return nothing, which no assertion over its results can
distinguish from an idle day. The remedy the warning names SHALL therefore differ by mode: falling back to
whole-day listing SHALL be offered only where a date directory exists, since without one the whole-day
paths list under that same head and no fallback exists. The rendered prefix logged at startup — which an
operator can hold against a real key — SHALL be what covers the wrong-head case in that mode.

#### Scenario: Matching file names are not reported

- **WHEN** a whole-day run lists blobs whose file names all begin with the configured head followed by
  the embedded timestamp
- **THEN** nothing is counted and no warning is logged

#### Scenario: A non-matching file name is counted and warned

- **WHEN** a whole-day run lists a blob whose file name does not begin with the configured head
  followed by a digit
- **THEN** the run counts it, logs a warning naming the offending key and the expected shape, and
  still ingests the blob

#### Scenario: The warning does not repeat

- **WHEN** a second whole-day run in the same process again lists non-matching file names
- **THEN** the count increases again and no further warning is logged

#### Scenario: The remedy offered depends on whether a date directory exists

- **WHEN** the warning is logged and the date-directory layout is blank
- **THEN** it does not offer full-day listing as an unaffected fallback, and states instead that the
  file-name head is what every discovery path lists under
- **AND** with a date-directory layout configured it offers that fallback as before
