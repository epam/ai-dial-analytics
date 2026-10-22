## ADDED Requirements

### Requirement: Materialize writes the alignment column of an aligned target

A Class-1 materialize into an aligned enrichment SHALL write the alignment column from the source row's
partition column (`source.request_time` for `dial_usage_log`) in the same `INSERT … SELECT`, with no change
to the request the runner sends. A materialize into a non-aligned target SHALL be unchanged.

A pipeline output bound to the alignment column SHALL remain valid, at registration, patch, import and
arming, and SHALL be **superseded**: the statement SHALL write the column once, from the source row, and
SHALL NOT render the bound expression. Preview SHALL show the value that is written for a bound alignment
column, and SHALL NOT list a hidden alignment column the declaration does not bind, which is not one of its
outputs. A binding whose
expression is the source partition column itself (`request_time: request_time`, as `usage-request-baggage-live`
declares) writes exactly what it wrote before, so such a pipeline's rows, filter, cursor and cadence are
unchanged by the alignment. Every other output binding SHALL be rendered as before.

The alignment SHALL be judged against the target's own source definition, never against the pipeline's read
source. A materialize of a pipeline whose read source is not the target's own source, into an aligned target, SHALL
be refused with HTTP 422 `pipeline_validation_failed` before any statement runs, and SHALL write nothing: that
table's rows hold no value of the target's source rows, so a write would stamp the column's default or another
table's same-named column and leave every row unmatched. Registration refuses the same declaration (enrichment-runtime,
"Only a row-trigger pipeline of the target's own source can write an aligned enrichment"); this guards one that drifted.

The `INSERT … SELECT` SHALL NOT be limited by ClickHouse's `max_partitions_per_insert_block`: a window spanning more
than 100 day partitions of an aligned target is written, not refused on every tick.

#### Scenario: Materialized rows carry the source's partition value

- **GIVEN** a Class-1 pipeline targeting an aligned enrichment of `dial_usage_log`
- **WHEN** a window is materialized
- **THEN** every written row's `request_time` equals its source row's `request_time`

#### Scenario: A pipeline binding the alignment column keeps working unchanged

- **GIVEN** `usage-request-baggage-live`, with outputs `baggage` and `request_time: request_time`, targeting
  `usage_request_baggage` after it is aligned
- **WHEN** a window is materialized
- **THEN** the `INSERT` names `request_time` once, the call succeeds, and the written rows — `baggage`,
  `request_time` and which events get a row — are identical to those the same window wrote before the
  alignment

#### Scenario: A window spanning many days is written

- **GIVEN** a Class-1 pipeline targeting an aligned enrichment of `dial_usage_log`
- **WHEN** a materialized window spans more than 100 days of source rows
- **THEN** the `INSERT` succeeds

#### Scenario: A binding to the alignment column with another expression is superseded

- **GIVEN** a Class-1 pipeline binding the alignment column to an expression other than the source column
- **WHEN** it is registered, previewed and materialized
- **THEN** registration succeeds, and both the preview and the written rows carry the source row's
  `request_time`

### Requirement: Materialize refuses during the target's relayout hold

`POST /v1/pipelines/{name}/materialize` for a pipeline whose target is held by a relayout SHALL be refused
with HTTP 503 `table_relayout_in_progress` before the probe, and SHALL record no materialized position. The hold
SHALL be checked again immediately before each `INSERT … SELECT`, so a hold taken while the probe runs also refuses
the call with the same 503 before anything is written. The next materialize after the hold is released SHALL resume
from the runner's committed cursor.

#### Scenario: A held materialize writes nothing and records no position

- **WHEN** a materialize targets an enrichment whose relayout is holding writers
- **THEN** the response is HTTP 503 `table_relayout_in_progress`, no row is written and the pipeline's
  materialized position is unchanged
