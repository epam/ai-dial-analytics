## ADDED Requirements

### Requirement: Group member selection pre-selects partitions from an aligned group key

The selection phase of `POST /v1/pipelines/{name}/group-rows` SHALL pre-select partitions when the pipeline's
`trigger.group_by` names a `string` column of an aligned enrichment. It SHALL render the group-key predicate on the bare
column (`col = '<key>'`, never `toString(col) = '<key>'`) and SHALL apply the partition pre-selection of the
structured-query capability ("A lookup on an aligned enrichment pre-selects the source's partitions") with that predicate
as the qualifying conjunct, bounded by the pipeline `filter`'s own bounds on the source's partition column. The
enrichment supplying the key SHALL be restricted to the key's rows; every other aligned join SHALL be pruned to the
selected partitions through its tuple join; and every join without an aligned partition pair SHALL be keyed on the
pre-selection together with the `filter`'s source-only conjuncts and the safety lag, so it is keyed rather than unkeyed.
The selected members SHALL be the same as without the pre-selection.

A group key of any other type SHALL be compared through `toString(col) = '<key>'` and SHALL NOT be pre-selected,
because the caller's key arrives as text and parsing it would change which values match. A blank group key is refused
before any selection, so no default-valued key reaches the pre-selection.

#### Scenario: Selecting a session's members reads only its active days

- **GIVEN** `session-insights-live` grouping by `usage_client_identity.client_session_id`, aligned
- **WHEN** the group rows of one session are assembled
- **THEN** the selection restricts `dial_usage_log` and `usage_client_identity` to the partitions holding
  that session's enrichment rows, and returns the same members as before

#### Scenario: A non-string group key is not pre-selected

- **GIVEN** a group pipeline whose `trigger.group_by` is a `long` column of an aligned enrichment
- **WHEN** the group rows of a key are assembled
- **THEN** the key is compared through `toString` and no partition pre-selection is emitted

#### Scenario: A blank group key is refused

- **WHEN** the group rows of the key `''` are requested
- **THEN** the response is HTTP 400 `bad_request` and no selection is run

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

The runner contract version SHALL be raised to `4` by this change, so a runner that does not echo the value
is refused by the exact-match contract check rather than having its rows refused one by one.

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

### Requirement: Only a row-trigger pipeline of the target's own source can write an aligned enrichment

Registering or patching an `enrich` pipeline over an aligned target so that it is armed SHALL be refused with
HTTP 422 `pipeline_validation_failed` when the pipeline, of either transform type (`llm` or `sql`), declares a read
source other than the target's own source, or when its trigger is `group`. A row of another table holds no value
of the target's source rows to carry, and a row written from an assembled group has no single source row to take
it from; neither the scan page nor the group-rows assembly carries `__partition_v` for such a pipeline, so every
row it wrote would match nothing.

A target whose automatic relayout is in progress (`layout: migrating`) SHALL be judged as the aligned table it is
about to become: the two refusals above apply to it, and arming an `llm` pipeline onto it SHALL additionally be
refused with HTTP 503 `table_relayout_in_progress` until the relayout finishes, because its scans would carry no
`__partition_v` until the catalog records the alignment and every write-back after that would be refused. A `sql`
pipeline of the target's own source SHALL be accepted, since materialize writes the alignment column itself.

#### Scenario: A pipeline reading another table cannot target an aligned enrichment

- **GIVEN** an aligned enrichment of `dial_usage_log`
- **WHEN** a row-trigger pipeline targeting it, `llm` or `sql`, declares `rate_analytics` as its read source and is
  armed
- **THEN** the response is HTTP 422 `pipeline_validation_failed`

#### Scenario: A group-trigger pipeline cannot target an aligned enrichment

- **GIVEN** an aligned enrichment of `dial_usage_log` keyed on `event_id`
- **WHEN** a group-trigger pipeline with `group_by: event_id` targeting it is armed
- **THEN** the response is HTTP 422 `pipeline_validation_failed`

#### Scenario: An llm pipeline waits for its target's relayout

- **GIVEN** an enrichment whose relayout is in progress
- **WHEN** an `llm` pipeline targeting it is armed
- **THEN** the response is HTTP 503 `table_relayout_in_progress`, while a `sql` pipeline of its own source is accepted

### Requirement: The pipeline write-back refuses during a target's relayout hold

`POST /v1/pipelines/{name}/rows` for a pipeline whose target is held by a relayout SHALL be refused with
HTTP 503 `table_relayout_in_progress` before any write, as specified by the table-management capability
("The writers of a relaying-out enrichment are held briefly"). The refusal SHALL NOT change the runner
contract state: the runner treats a 503 as a transient gateway error and retries the write.

#### Scenario: A write-back during the hold is refused

- **WHEN** a write-back targets an enrichment whose relayout is holding writers
- **THEN** the response is HTTP 503 `table_relayout_in_progress` and nothing is written
