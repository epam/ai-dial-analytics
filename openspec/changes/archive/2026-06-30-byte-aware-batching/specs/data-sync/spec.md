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
inserts rather than one insert per blob. The byte-size bound SHALL ensure that no single batch
accumulates an in-memory serialized payload large enough to exceed the JVM's maximum array length,
independent of how large individual rows are. When the writer serializes a batch for insertion it MUST
NOT first materialize the entire batch as one in-memory `String`; the per-batch byte bound together with
direct byte serialization keep the peak insert memory bounded. The isolation of a single blob's ingest
failure (counted and skipped, the run continuing and the watermark still advancing) SHALL be preserved,
and such a failure MUST NOT discard rows already accumulated from other blobs in the same run.

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

#### Scenario: A failed blob does not discard the run's buffered rows

- **WHEN** one blob fails to ingest after earlier blobs in the same run have already contributed rows
  to the shared batch
- **THEN** the service counts and skips the failing blob and retains the rows already accumulated from
  the other blobs

#### Scenario: Writer credential is isolated from the read path

- **WHEN** the sync job writes to ClickHouse
- **THEN** it uses the dedicated writer credential, and the read/discovery path continues to use its
  own read-only credential
