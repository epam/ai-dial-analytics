## MODIFIED Requirements

### Requirement: The scan carries an aligned target's partition value and the write-back returns it

The scan (`POST /v1/pipelines/{name}/scan`) of a row-trigger pipeline whose target is aligned SHALL return, in
every row, a member `__partition_v` holding the source row's alignment-column value (`request_time` for
`dial_usage_log`), whatever the declaration's inputs. The pipeline write-back (`POST /v1/pipelines/{name}/rows`)
for such a pipeline SHALL require `__partition_v`, non-null, on each row and SHALL write it into the target's
alignment column. A row without it, or with it `null`, SHALL be refused with HTTP 422 `validation_error` naming
`__partition_v`, before any row of the request is written. A scan or write-back of a pipeline whose target is not
aligned SHALL neither return nor accept the member: the write-back refuses it as a system column with HTTP 422
`write_column_not_allowed`. The scan and the write-back SHALL decide whether the member applies by one shared rule,
so the write-back requires it exactly when the scan returns it.

On the write-back the member SHALL be the only source of the alignment column's value. A row naming the alignment
column itself SHALL be refused with HTTP 422 `write_column_not_allowed` unless the declaration binds an output to
it; a row naming both a bound alignment column and `__partition_v` SHALL be refused with HTTP 422
`validation_error` when the two values differ, rather than one silently overwriting the other.

The write-back's inserts SHALL NOT be limited by ClickHouse's `max_partitions_per_insert_block`: a batch spanning
more than 100 day partitions of an aligned target is written, not refused.

The value travels with the row because the write-back is the only writer of an LLM pipeline's rows and ADAS
holds no per-caller state between the two calls: looking it up at write-back would scan the source's identity
column across every partition per batch, which is the cost the aligned layout removes. The Enrichment Runner
carries it from the scanned row to the write row unchanged; a wrong value only leaves that row unmatched under
the tuple join, and attaches it to no other source row.

The runner contract version this build serves SHALL be `5`. A runner that does not echo the value, or
cannot decode the `dial_app_route` transform preset (see
[enrichment-registry](../enrichment-registry/spec.md), "Both projections carry the transform's route"), is
refused by the exact-match contract check, rather than having its rows or its pipelines refused one by
one.

#### Scenario: The scan returns the partition value of an aligned target

- **GIVEN** a row-trigger `llm` pipeline targeting an aligned enrichment of `dial_usage_log`
- **WHEN** it is scanned
- **THEN** every returned row carries `__partition_v` equal to its source row's `request_time`

#### Scenario: The echoed value lands in the alignment column

- **WHEN** the runner writes back a row carrying the `__partition_v` it was scanned with
- **THEN** the row is written with that `request_time` and a query projecting the enrichment over its source
  row reads the written labels

#### Scenario: A write-back row without the value is refused

- **WHEN** a write-back row for an aligned target omits `__partition_v`, or carries it as `null`
- **THEN** the response is HTTP 422 `validation_error` naming `__partition_v`, and nothing is written

#### Scenario: A write-back row carrying the alignment column instead of the value is refused

- **GIVEN** a row-trigger `llm` pipeline targeting an aligned enrichment, with no output bound to `request_time`
- **WHEN** a write-back row carries `request_time` but no `__partition_v`
- **THEN** the response is HTTP 422 `write_column_not_allowed` and nothing is written

#### Scenario: An unaligned target is unaffected

- **GIVEN** a row-trigger `llm` pipeline whose target is not aligned
- **WHEN** it is scanned and written back
- **THEN** the scan rows carry no `__partition_v`, the write-back does not require it, and a row carrying it is
  refused with HTTP 422 `write_column_not_allowed`
