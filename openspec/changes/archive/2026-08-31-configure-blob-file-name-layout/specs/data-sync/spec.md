## MODIFIED Requirements

### Requirement: Uniform date-directory layout across discovery paths

Every ingestion path SHALL resolve its blob prefix through the configured date-directory layout, so
that the scheduled reconciliation run, both poller listing modes (`band` and the full-day fallback),
and the operator-triggered backfill agree on which keys belong to a given UTC date. A deployment
SHALL therefore become ingestible by configuration alone when its log blobs differ from the default
layout only in their date directory, with no code change and no path left on the default.

The configured date-directory layout SHALL apply only to the date directory. The portion of the blob
**file name** that precedes the embedded timestamp is governed separately, by its own configured
layout (see *Configurable file-name head preceding the embedded timestamp*), because it too differs
between producers. The embedded **timestamp** itself — its unit, its width and its position
immediately after that head — is produced by the log producer and SHALL NOT be governed by
configuration; an environment whose file names carry no such timestamp SHALL remain served by the
full-day (`day`) listing fallback.

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

## ADDED Requirements

### Requirement: Configurable file-name head preceding the embedded timestamp

The service SHALL resolve the text between the date directory and the blob file name's embedded
timestamp from a configured layout, so that a deployment whose producer names files differently
becomes ingestible on bounded (`band`) listing by configuration alone. The layout SHALL be
interpreted as a date layout rendered for the day the prefix covers, because a producer may embed the
date in that text (the default does). It SHALL default to the form the service resolved before it was
configurable, so no existing deployment's prefixes change.

A blank layout SHALL be accepted and SHALL mean the embedded timestamp begins the file name. This
differs from the date-directory layout, where blank is refused: an absent file-name head does not
widen any listing, because the date directory still bounds it.

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

#### Scenario: Head applies to bounded listing only

- **WHEN** a file-name head layout is configured
- **THEN** the whole-day prefixes used by the scheduled reconciliation run, the operator backfill and
  the poller's full-day fallback are unaffected by it

#### Scenario: A head rendering a leading separator does not double it

- **WHEN** the configured file-name head layout renders text beginning with a path separator
- **THEN** the resolved band prefix carries a single separator after the date directory

### Requirement: File-name head layout is validated at startup

The service SHALL validate the configured file-name head layout at startup and SHALL abort startup
when it is unusable, rather than starting and listing a prefix that matches no blob. A layout is
unusable when it cannot be interpreted as a date layout, or when it cannot render a date — for
example because it names a time-of-day field no date can supply, which is accepted as a layout and
fails only when a prefix is first resolved, i.e. on the first poll cycle.

The validation SHALL NOT require the layout to distinguish dates, and SHALL NOT refuse a blank or
purely literal layout: it is the date directory, not this text, that must separate days.

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

#### Scenario: Blank head is accepted

- **WHEN** the configured file-name head layout is blank
- **THEN** startup succeeds and band prefixes place the embedded timestamp directly after the date
  directory

#### Scenario: Purely literal head is accepted

- **WHEN** the configured file-name head layout names no date field at all
- **THEN** startup succeeds and every band prefix carries that literal text

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
