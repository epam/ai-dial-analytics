## ADDED Requirements

### Requirement: A batch ClickHouse refuses for its data is split by blob

When ClickHouse refuses a batch insert with a **data-class** error, the service SHALL split the batch by blob rather
than fail the flush. A data-class error is one whose numeric server error code is in this closed set:

| Code | Name |
|------|------|
| 6    | `CANNOT_PARSE_TEXT` |
| 25   | `CANNOT_PARSE_ESCAPE_SEQUENCE` |
| 26   | `CANNOT_PARSE_QUOTED_STRING` |
| 27   | `CANNOT_PARSE_INPUT_ASSERTION_FAILED` |
| 38   | `CANNOT_PARSE_DATE` |
| 41   | `CANNOT_PARSE_DATETIME` |
| 53   | `TYPE_MISMATCH` |
| 69   | `ARGUMENT_OUT_OF_BOUND` |
| 70   | `CANNOT_CONVERT_TYPE` |
| 72   | `CANNOT_PARSE_NUMBER` |
| 117  | `INCORRECT_DATA` |
| 131  | `TOO_LARGE_STRING_SIZE` |
| 321  | `VALUE_IS_OUT_OF_RANGE_OF_DATA_TYPE` |
| 349  | `CANNOT_INSERT_NULL_IN_ORDINARY_COLUMN` |
| 469  | `VIOLATED_CONSTRAINT` |
| 691  | `UNKNOWN_ELEMENT_OF_ENUM` |

Recognition SHALL be by the code, never by message text. Every other failure SHALL fail the flush as before,
including:
- a transport fault or an unavailable server;
- a memory limit;
- too many parts;
- disk exhaustion.

The split works as follows:
- The service groups the refused batch's rows by the blob they came from, and splits the set of blobs in halves.
- It inserts the rows of each half. A half that is accepted is written. A half refused with a data-class error is
  split again.
- A single blob whose rows are still refused with a data-class error is **refused**:
  - its rows are not written;
  - it is not recorded in the ingest ledger;
  - its failure is recorded in the failure ledger with the reason `rejected_by_server` (`data-sync-failures`). The
    message names the target table and the error code and name, and holds no row content.
- A sub-insert that fails with any error outside the set fails the flush.

A refused blob's rows that the run still holds unwritten, in this table or any other, SHALL be dropped and not
written. Any further rows read from it SHALL be dropped too. A source row, such as a usage-log row, is therefore
not written in this run for a blob whose enrichment rows, such as its payload rows, were refused. Rows of the blob
that earlier batches already wrote stay written, as for any blob that fails part-way. They collapse on the
deduplication key when the blob is ingested again.

The rows a flush stage rewrote, such as a request-delta rewrite, SHALL be split and inserted as rewritten. They
SHALL NOT be rewritten a second time. The flush stage SHALL learn which rows were not stored, so the request path
of a refused row never becomes a parent candidate for later flushes. A stored row of the same batch that took a
refused row as its parent keeps that link, and can be rebuilt once the refused blob is ingested again.

Rows that a refused insert partly committed, and that a later sub-insert or retry writes again, collapse on the
table's deduplication key like any other re-ingest.

#### Scenario: A refused row does not take its batch with it

- **WHEN** a poll cycle flushes a batch holding the rows of 40 blobs, and ClickHouse refuses it with
  `CANNOT_PARSE_DATETIME` because of one row of one blob
- **THEN** the rows of the other 39 blobs are written and those blobs are recorded in the ingest ledger, the one
  blob is parked with the reason `rejected_by_server`, the cycle succeeds, and the watermark advances

#### Scenario: A refused payload row withholds its blob's usage-log rows

- **WHEN** the payload batch is refused because of one blob's payload row, and that blob's usage-log rows are still
  buffered
- **THEN** none of that blob's buffered rows is written in either table, and the blob is parked with the reason
  `rejected_by_server`

#### Scenario: A non-data failure is not split

- **WHEN** a batch insert fails because the connection to ClickHouse is refused
- **THEN** the batch is not split, and the flush fails as a run-level fault

#### Scenario: A refused row's request path is not cached

- **WHEN** `sync.request-body-mode` is `delta` and a blob's payload rows are refused
- **THEN** the request paths of those rows are not offered as parents to the requests of later flushes

## MODIFIED Requirements

### Requirement: Malformed lines are skipped

The service SHALL skip any NDJSON line that cannot be parsed, log a warning for it, and count it,
without failing the rest of the run. A malformed line MUST NOT abort ingestion of the remaining
lines in the file or the remaining files in the run.

A line whose decompressed length exceeds `sync.max-line-bytes` SHALL be skipped and counted the same
way, and the service MUST NOT hold more than that many bytes of it in memory while reading past it, so
a blob consisting of one enormous line cannot exhaust the heap or make every poll cycle fail on the
same blob. The warning SHALL state the line's length and the configured cap. Each such line SHALL also
increment the `sync.lines.oversized` counter (scheduled sync and backfill alike), so oversized skips
can be told apart from malformed ones; both remain part of the skipped-rows count.

A blob with any skipped line SHALL also be recorded in the failure ledger as a `records` subject with the reason
`skipped_records`, naming the count and the first line numbers of each cause (`data-sync-failures`). Line numbers
count every line of the decompressed stream from 1, blank lines included.

The cap bounds the raw line buffer, not the memory a line costs. A line just under the cap is held at
once as its bytes, its decoded string (up to twice the byte count for non-Latin-1 text), its JSON tree,
its mapped row and its share of the open batch, so one parsing thread needs a small multiple of the
cap, and the scheduled sync and a backfill can each be parsing at the same time. The cap SHALL be
between 1 KiB and 1 GiB. A line buffer grown well past the read chunk by one long line SHALL be
released once that line is consumed rather than kept for the rest of the blob.

Lines SHALL be split on LF only. A CR SHALL be stripped only when it directly precedes the LF (CRLF);
a lone CR does not end a line and stays part of it.

#### Scenario: A bad line does not abort the run

- **WHEN** a blob contains a line that cannot be parsed as a valid record
- **THEN** the service logs a warning, increments the skipped-rows count, and continues processing
  the remaining lines and files

#### Scenario: A blob with a bad line is recorded in the failure ledger

- **WHEN** the third line of a blob, after a blank second line, cannot be parsed, and the blob's other records are
  written
- **THEN** the blob is recorded in the ingest ledger, and the failure ledger holds a `records` failure for it with
  the reason `skipped_records` and a message naming one `malformed_line` at line 3

#### Scenario: An oversized line is skipped without being buffered

- **WHEN** a blob contains a line longer than `sync.max-line-bytes`, with or without a trailing newline
- **THEN** the service logs a warning naming the line's length and the cap, increments the skipped-rows
  count, and parses the lines after it

#### Scenario: Oversized skips are counted apart from malformed ones

- **WHEN** a blob contains two lines longer than `sync.max-line-bytes` and one line that is not valid JSON
- **THEN** the skipped-rows count grows by three and the `sync.lines.oversized` counter by two

#### Scenario: A CRLF line ending is accepted

- **WHEN** a blob's lines end with CRLF
- **THEN** each line is parsed without its trailing CR, and a line exactly at the cap is not skipped

### Requirement: Records without a deduplication key are dropped

The service SHALL exclude from ingestion any record whose `trace_id` or `core_span_id` is absent or
blank, because such records would otherwise collapse together under the deduplication key and lose
data. Each excluded record SHALL be logged and counted in a dropped-rows metric, and its exclusion
MUST NOT abort ingestion of the remaining records or files. A blob with any excluded record SHALL also be recorded
in the failure ledger as a `records` subject with the reason `skipped_records`. Its message counts such records under
`missing_dedup_key`, once per record and not once per target, and names their first line numbers
(`data-sync-failures`).

#### Scenario: Record missing the dedup key is dropped

- **WHEN** a record has an absent or blank `trace_id` or `core_span_id`
- **THEN** the service does not insert that record, logs it, increments the dropped-rows metric, and
  continues processing the remaining records and files

#### Scenario: A blob with a dropped record is recorded in the failure ledger

- **WHEN** the record on line 12 of a blob has a blank `core_span_id` and two targets match it
- **THEN** the failure ledger holds a `records` failure for the blob whose message names one `missing_dedup_key` at
  line 12

### Requirement: An insert refused for lack of disk space is classified distinctly

The service SHALL recognise a ClickHouse write refused for lack of disk space (server error code 243,
`NOT_ENOUGH_SPACE`) as its own failure mode, distinct from an otherwise-failed insert, and SHALL report
it as such in logs and metrics, naming the target table. Recognition SHALL be by the numeric server
error code rather than by matching message text.

A failed batch insert is a **run-level** fault, not the blob-scoped failure the poller isolates (a
corrupt blob is counted, skipped, and does not hold the watermark). The one exception is a data-class refusal, which
is split by blob ("A batch ClickHouse refuses for its data is split by blob"). For any other failure, disk exhaustion
included, the run stops writing. A poll cycle then leaves its watermark unchanged and records in the ingest ledger
only the blobs whose rows were all written before the failure. Every other blob it read stays eligible for
re-ingest, and the next cycle retries. Daily ingest therefore resumes on
its own once space is available, with no operator action and no rows lost. Rows an earlier attempt
already flushed and a retry writes again SHALL collapse on the table's deduplication key rather than
double-count, exactly as any other re-ingest does.

A failure the service cannot attribute to disk exhaustion SHALL keep its existing generic treatment, so
a driver or server change that stops surfacing code 243 costs the specific classification and nothing
more.

#### Scenario: A disk-full insert is named in logs and metrics

- **WHEN** ClickHouse refuses a batch insert with error code 243 (`NOT_ENOUGH_SPACE`)
- **THEN** the service logs the failure as disk exhaustion, naming the target table, and increments the
  insert-failure metric with a reason distinguishing it from other insert failures

#### Scenario: The poller holds its watermark and recovers unattended

- **WHEN** a poll cycle's batch insert fails after one or more blobs have contributed rows, whether ClickHouse
  refused it for lack of disk space or for any other reason that is not a data-class refusal
- **THEN** the cycle fails without advancing the watermark, the blobs whose rows the failed insert carried
  are not recorded in the ledger, and a later cycle re-ingests them successfully once the write path is
  healthy again

#### Scenario: Another insert failure keeps its generic treatment

- **WHEN** a batch insert fails for a reason other than error code 243
- **THEN** the failure is surfaced as an ordinary run-level insert failure, not as disk exhaustion, unless it is a
  data-class refusal, which is split by blob
