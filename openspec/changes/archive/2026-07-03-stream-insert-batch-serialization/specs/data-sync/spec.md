## MODIFIED Requirements

### Requirement: Batched inserts via a dedicated writer credential

The service SHALL insert rows into ClickHouse in batches, using a ClickHouse credential that is
separate from the read-only credential used by the query/discovery path. The read path's ClickHouse
access MUST remain read-only.

Within a single ingestion run (a poll cycle or a scheduled reconciliation run), the service SHALL
accumulate mapped rows across the run's blobs into shared batches and flush a batch when it reaches
**either** the configured row count (`sync.batch-size`) **or** a configured accumulated serialized-byte
size (`sync.max-batch-bytes`), whichever is reached first, and once more at the end of the run — rather
than flushing a separate partial batch per blob — so that a run of many small blobs produces few large
inserts rather than one insert per blob. The byte-size bound SHALL ensure that accumulating rows across
a run's blobs cannot, on its own, grow a batch's in-memory serialized payload past the JVM's maximum
array length — the bound caps the multi-row buffer, not a single row, so a lone row whose own serialized
form exceeds the array length remains an inherent limit it cannot guard against. The accumulated-byte
estimate SHALL approximate each row's **escaped UTF-8** serialized size — accounting for JSON string
escaping and multi-byte characters — so that the bound reflects the payload actually produced at flush
rather than an undercount, while remaining a cheap estimate rather than a real serialization.

When the writer serializes a batch for insertion it MUST NOT first materialize the entire batch as a
single in-memory buffer (neither one `String` nor one `byte[]`); it SHALL stream the batch to ClickHouse
row by row, so the peak insert memory beyond the retained row buffer is bounded by roughly a single
row's serialized form regardless of batch size. The shipped default byte bound (`sync.max-batch-bytes`)
SHALL be heap-relative — small enough that a full batch flushes safely under a typical container heap —
while remaining operator-overridable within the validated range.

The isolation of a single blob's ingest failure (counted and skipped, the run continuing and the
watermark still advancing) SHALL be preserved, and such a failure MUST NOT discard rows already
accumulated from other blobs in the same run.

#### Scenario: Rows are inserted in batches

- **WHEN** a file yields more rows than the configured batch size
- **THEN** the service inserts the rows in multiple batches of at most the configured size

#### Scenario: A run's small blobs share batches

- **WHEN** an ingestion run processes many blobs that each yield fewer rows than the configured batch
  size
- **THEN** their rows accumulate into shared batches flushed when the configured size is reached and
  once at the end of the run, rather than one insert per blob

#### Scenario: A large-row batch flushes on the byte bound before the row count

- **WHEN** the rows accumulated in the shared buffer reach the configured serialized-byte size before
  the buffer reaches the configured row count
- **THEN** the service flushes the buffered rows as a batch at that point, so the in-memory payload
  stays bounded regardless of individual row size

#### Scenario: A batch is streamed to ClickHouse without a whole-batch buffer

- **WHEN** the writer flushes a batch to ClickHouse
- **THEN** it serializes and sends the rows incrementally rather than first assembling the entire batch
  as one in-memory `byte[]` or `String`, so peak insert memory beyond the retained row buffer does not
  scale with the batch's total serialized size

#### Scenario: A failed blob does not discard the run's buffered rows

- **WHEN** one blob fails to ingest after earlier blobs in the same run have already contributed rows
  to the shared batch
- **THEN** the service counts and skips the failing blob and retains the rows already accumulated from
  the other blobs
