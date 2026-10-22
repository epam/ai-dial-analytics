## MODIFIED Requirements

### Requirement: Date-derived prefix file discovery

The service SHALL determine which files to ingest by resolving a date-derived blob prefix of the
form `<path-prefix>/<date-path>/`, where `<path-prefix>` is the configurable base path within the
container and `<date-path>` is the target date rendered through a **configurable date-directory
layout** whose default is `{yyyy}/{MM}/{dd}`, and by listing all blobs under that prefix. The
layout SHALL be expressible as a single value covering both the three-segment form
(`{yyyy}/{MM}/{dd}`, e.g. `2026/06/18`) and a single-segment form (e.g. `2026-06-18`), since
different DIAL deployments bucket their log blobs by either. The target date SHALL be the current
date minus a configurable day offset (default 1, i.e. the previous day). The service MUST NOT
require an exact blob key, since blob names contain an unpredictable suffix.

#### Scenario: Prefix is resolved from the day offset

- **WHEN** a sync run starts with day offset 1 on 2026-06-19, with base path `logs` and the default
  date-directory layout
- **THEN** the service lists blobs under the prefix `logs/2026/06/18/`

#### Scenario: Prefix is resolved with a single-segment date layout

- **WHEN** a sync run starts with day offset 1 on 2026-06-19, with base path `your_dir` and a
  date-directory layout of `yyyy-MM-dd`
- **THEN** the service lists blobs under the prefix `your_dir/2026-06-18/`

#### Scenario: All files under the prefix are processed

- **WHEN** the resolved prefix contains multiple blobs
- **THEN** the service processes every blob found under that prefix

#### Scenario: Empty prefix completes cleanly

- **WHEN** the resolved prefix contains no blobs
- **THEN** the run completes successfully having ingested zero rows and reports zero files processed

## ADDED Requirements

### Requirement: Uniform date-directory layout across discovery paths

Every ingestion path SHALL resolve its blob prefix through the configured date-directory layout, so
that the scheduled reconciliation run, both poller listing modes (`band` and the full-day fallback),
and the operator-triggered backfill agree on which keys belong to a given UTC date. A deployment
SHALL therefore become ingestible by configuration alone when its log blobs differ from the default
layout only in their date directory, with no code change and no path left on the default.

The configured layout SHALL apply only to the date directory. The blob **file name** — including the
`date=<ISO date>` segment and the embedded start timestamp that bounded (`band`) listing relies on —
is produced by the log producer and is NOT governed by this configuration; an environment whose file
names differ SHALL remain served by the full-day (`day`) listing fallback.

#### Scenario: Poller band listing uses the configured layout

- **WHEN** bounded (`band`) listing is in effect and the date-directory layout is `yyyy-MM-dd`
- **THEN** each listed band prefix places the date directory in the configured form (e.g.
  `your_dir/2026-06-18/date=2026-06-18…`) and the embedded-timestamp portion of the key is
  unchanged

#### Scenario: Poller full-day fallback uses the configured layout

- **WHEN** the full-day (`day`) listing fallback is in effect and the date-directory layout is
  `yyyy-MM-dd`
- **THEN** the poller lists the active day (and, within the grace window, the previous day) under
  prefixes carrying the configured date directory

#### Scenario: Default layout preserves existing prefixes

- **WHEN** no date-directory layout is configured
- **THEN** every path resolves exactly the prefixes it resolved before the layout was configurable,
  on the scheduled, poller and backfill paths alike

### Requirement: Date-directory layout is validated at startup

The service SHALL validate the configured date-directory layout at startup and SHALL abort startup
when it is unusable, rather than starting and listing a prefix that matches no blob. A layout is
unusable when:

- it cannot be interpreted as a date layout;
- it cannot render a date — for example because it names a time-of-day field no date can supply;
- it fails to distinguish two dates that differ in **only** their year, in only their month, or in
  only their day. Such a layout collapses distinct dates onto one prefix, so each run lists far more
  than its target date while reporting success. Omitting the year is the trap worth naming: a layout
  of `MM/dd` renders distinctly within one year and collides across years, so validating against
  probe dates that vary several fields at once would wrongly admit it; or
- it is blank. An empty date segment SHALL NOT be read as "no date directory", because it would widen
  every whole-day listing to the entire base path.

The failure SHALL name the offending configuration property and its environment variable.

The service SHALL log the resolved discovery prefix template once at startup, and that log SHALL
reflect the layout actually in effect, so an operator can confirm what will be listed without
waiting for a run.

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

#### Scenario: Blank layout aborts startup

- **WHEN** the configured date-directory layout is blank
- **THEN** startup fails with an error naming the configuration property and its environment
  variable, rather than resolving a prefix with no date segment

#### Scenario: Valid layout is reported at startup

- **WHEN** the configured date-directory layout is valid
- **THEN** the service starts and logs the resolved discovery prefix template it will list under
