## MODIFIED Requirements

### Requirement: Grain-based enrichment join with enforced cardinality

The system SHALL resolve enrichment columns into a source's flat schema by a `LEFT JOIN` on the shared
`grain_key` (`source.grain_key = enrichment.grain_key`), extended to the tuple
`(grain_key, partition column)` for an **aligned** enrichment (see "An eligible row-grain enrichment is
laid out aligned with its source"). Enrichment tables
SHALL be stored as `ReplacingMergeTree(_updated_at)` ordered by the grain key so that at most one row
exists per grain key — per grain key and partition value for an aligned enrichment — making the declared
`ZERO_OR_ONE` cardinality physically true and preventing
the join from inflating source rows. The join of a non-aligned enrichment SHALL be a single-key join on
`grain_key` alone; the join of an aligned enrichment SHALL be
`source.grain_key = enrichment.grain_key AND source.<partition column> = enrichment.<partition column>`.
Either SHALL be emitted **keyed** — the enrichment side restricted to the grain keys the statement's own
source-only predicates select, see the structured-query capability — whenever the statement has such
predicates, so a lookup does not hash the whole enrichment table. A `grain_key` MAY NOT name a `map`
or an `object` column, nor a nullable one: ClickHouse refuses each in a sorting key, and the refusal
belongs at declaration time (422) rather than as a raw `CREATE TABLE` failure leaving a `FAILED`
definition. Joins SHALL be emitted only for enrichments a query actually
references. Enrichment columns SHALL be opt-in: a row query with an omitted projection (`select`
absent) SHALL return the source's own columns only — enrichment columns must be named explicitly to
be projected (and to trigger their join), so a bare projection never fans out into enrichment joins.

The tuple join is what makes the aligned layout safe without any catalog claim that the source's identity
fixes its partition: each source row can match only the enrichment row written from it, so two source rows
sharing an identity value across partitions never fan out, and a row written with a wrong partition value
is merely unmatched — it can never attach to another source row.

Because the join is by shared physical name, that name SHALL be immutable on **both** sides for as long
as the enrichment is `ACTIVE`: the enrichment's own grain-key column is its ordering key and already
rejected for drop and rename, and the source column it matches SHALL likewise be rejected for drop and
rename (see "Modify table schema via PATCH"). Neither side can be repaired by renaming the other —
ClickHouse refuses to rename a column that is part of a table's key expression — so a rename permitted on
the source side would break every query referencing that enrichment, and every Class-1 materialize over
it, with no way back except restoring the old name.

#### Scenario: Enrichment column resolves via grain join

- **WHEN** a query references an enrichment column of a source
- **THEN** the builder emits a `LEFT JOIN` of that enrichment on the shared `grain_key` and no join
  for enrichments the query does not reference

#### Scenario: An aligned enrichment joins on the grain key and the partition column

- **GIVEN** an aligned enrichment of `dial_usage_log`
- **WHEN** a query references one of its columns
- **THEN** the join condition is `event_id = event_id AND request_time = request_time`

#### Scenario: Two source rows sharing an identity across days do not fan out

- **GIVEN** an aligned enrichment and two source rows with the same `event_id` on different days, each
  with its own enrichment row
- **WHEN** a query projects an enrichment column over both
- **THEN** two rows are returned, each carrying the value written from its own source row

#### Scenario: Cardinality is enforced by storage

- **WHEN** two rows are written to an enrichment for the same `grain_key`
- **THEN** the `ReplacingMergeTree` keeps one row per key (latest `_updated_at` wins) and the source
  row count is not inflated by the join

#### Scenario: Omitted projection returns source columns only

- **WHEN** a row query against a source that has enrichments is executed with no `select`
- **THEN** only the source's own columns are returned, no enrichment is joined, and the query
  succeeds (enrichment columns appear only when explicitly selected/referenced)

#### Scenario: The joined name stays valid on both sides

- **WHEN** a source column that an `ACTIVE` enrichment names as its `grain_key` is drop- or
  rename-attempted through `PATCH /v1/tables/{name}/schema`
- **THEN** the operation is rejected with HTTP 422 and the join keeps resolving, so a query
  referencing that enrichment's columns continues to succeed

### Requirement: An enrichment's declared partitioning is readable but not settable

`GET /v1/tables/{name}` SHALL report `partition_by` for any table whose definition declares a partition
column, an enrichment included, and SHALL omit it for a table that declares none. Declaring one SHALL
remain a source-only operation for the **caller**: `POST /v1/tables/{name}/schema` and
`PATCH /v1/tables/{name}/schema` SHALL continue to reject a caller-supplied `partition_by` on an enrichment
with HTTP 422. The system itself SHALL set an enrichment's `partition_by` when it lays the enrichment out
aligned (see "An eligible row-grain enrichment is laid out aligned with its source"), and the DDL renderer
SHALL emit `PARTITION BY` for a source and for an aligned enrichment, and for no other enrichment.

The read matters because an enrichment's declared partition column changes how every query over its parent
entity is planned: it is what lets a keyed grain join bound the enrichment to the partitions that can hold
the row it is looking for. A value with that effect has to be visible on the definition that carries it, or
an operator asking why a body read is expensive has no way to see whether the alignment is declared. This
retires the state the split-payload migration created deliberately — a value it declined to set because it
"would be stored and never read".

The caller's write stays refused because the declaration asserts something about how a table is
**populated**: that for a given grain key both tables hold the same value in that column. The system can
guarantee that for a table whose rows it stamps from the source row itself, and a caller cannot. Nothing
at query time can catch a false claim — an over-restricted outer join yields the column's type default,
which is indistinguishable from a row that legitimately has no enrichment — so the claim is set only by
the system, for tables whose population it controls.

#### Scenario: A system enrichment reports its declared partitioning

- **GIVEN** an enrichment whose catalog definition declares a partition column
- **WHEN** it is fetched by name
- **THEN** the response carries `partition_by` with that column and its granularity

#### Scenario: An aligned API-created enrichment reports the partitioning ADAS set

- **GIVEN** an API-created enrichment of `dial_usage_log` laid out aligned at define-schema
- **WHEN** it is fetched by name
- **THEN** `partition_by` carries `request_time` with the source's granularity

#### Scenario: An enrichment without a declared partition column omits the member

- **GIVEN** an API-created enrichment that is not eligible for the aligned layout
- **WHEN** it is fetched by name
- **THEN** `partition_by` is absent

#### Scenario: Declaring partitioning on an enrichment is still refused

- **GIVEN** an enrichment whose schema is not yet defined
- **WHEN** its schema is posted with `partition_by`
- **THEN** the response is HTTP 422

## ADDED Requirements

### Requirement: An eligible row-grain enrichment is laid out aligned with its source

At `POST /v1/tables/{name}/schema`, the system SHALL lay out an enrichment **aligned** with its source when
all of these hold: its `grain_key` is the source's `identity_column`; the source declares a partition
column other than that identity column; and the source is partition-local. No request member SHALL request or
suppress the layout. An API-written source qualifies like a system one: an `append` source is partition-local by
construction, and an `upsert_by_key` source is when its partition column is in its `ordering_key`.

An aligned enrichment SHALL be created with `PARTITION BY` the source's partition expression, an
ADAS-owned column of the same name and type as the source's partition column (the **alignment column**,
`request_time` for `dial_usage_log`), and `ORDER BY` its grain key. The alignment column SHALL be a
**hidden** physical column, like the grain key and like `dial_usage_log_payload`'s `request_time`: it SHALL
NOT be a catalog column of the enrichment, SHALL NOT appear in its columns, in schema discovery or in an
export bundle, and SHALL NOT be droppable or renameable through `PATCH /v1/tables/{name}/schema`. The
definition's `partition_by` SHALL name it, and import SHALL re-add it at define-schema.

A declared column with the alignment column's name SHALL be **adopted** as the alignment column when it has
the source partition column's type, is non-nullable, and is neither `sensitive` nor `heavy`: no hidden column is added, the declared column
stays a visible catalog column, and its description, display name, tag and ordinal are kept. Its values are written by the system from
the source row from then on (see the class1-enrichment capability), whatever any writer binds to it. A
declared column with that name and a different type, or nullable, SHALL be refused with HTTP 422
`validation_error`, stating that it collides with the alignment column; so SHALL a declared column of that name
that is `sensitive` or `heavy`, and a `PATCH /v1/tables/{name}/schema` `update` marking the adopted column
either. Both flags would misdescribe a column the system writes from the source row: its visibility is decided
by the source column's own, and it is a sort-key-cheap scalar, not a body. An enrichment that is not
eligible — a group-grain enrichment, one whose grain key is not the source identity, one whose source's identity
column is its partition column (the grain key already carries the partition value), or one over an unpartitioned
source, or one that is not partition-local — SHALL be laid out exactly as before, and a column of that name on it
is an ordinary user column.

While an eligible enrichment is not yet laid out aligned — its relayout has not run, or is running (see "Existing
eligible enrichments are relaid out automatically") — `PATCH /v1/tables/{name}/schema` SHALL refuse with HTTP 422
`validation_error` an `add` of a column with the alignment column's name, or a `rename` onto it, unless that
column could be adopted. A column the relayout cannot adopt would leave the table `layout: unsupported` until that column is changed.

Adoption is what lets an existing table that already carries a copy of its source's partition column —
`usage_request_baggage`'s `request_time`, which drives its TTL — be relaid out and round-trip through
catalog export and import, where import replays define-schema with the column as stored.

#### Scenario: An eligible enrichment is created aligned

- **GIVEN** an enrichment of `dial_usage_log` with `grain_key` `event_id`
- **WHEN** its schema is defined
- **THEN** the ClickHouse table is `PARTITION BY` `dial_usage_log`'s partition expression, carries a
  hidden `request_time` column of the source's type, and is `ORDER BY event_id`
- **AND** `GET /v1/tables/{name}` lists no `request_time` column, and schema discovery exposes no
  `<table>.request_time` field

#### Scenario: A group-grain enrichment is not aligned

- **GIVEN** an enrichment of `dial_usage_log` whose `grain_key` is `client_session_id`
- **WHEN** its schema is defined
- **THEN** it is laid out without `PARTITION BY` and without an alignment column, as before

#### Scenario: A matching declared column is adopted

- **GIVEN** an eligible enrichment of `dial_usage_log`
- **WHEN** its schema declares a non-nullable `timestamp` column `request_time` with its own description
- **THEN** the table is created aligned with exactly one `request_time` column, `partition_by` names it,
  and `GET /v1/tables/{name}` returns the declared description

#### Scenario: An eligible enrichment of a partitioned append API source is created aligned

- **GIVEN** an `append` source declared through the API with `partition_by` `day` and `identity_column` `id`
- **WHEN** an enrichment of it with `grain_key` `id` has its schema defined
- **THEN** the table is `PARTITION BY` `toYYYYMMDD(day)` with a hidden `day` column, and `layout` reads `aligned`

#### Scenario: An upsert source partitioned outside its key yields an unaligned enrichment

- **GIVEN** an `upsert_by_key` source with `ordering_key` `[id]`, `partition_by` `day` and `identity_column` `id`
- **WHEN** an enrichment of it with `grain_key` `id` has its schema defined
- **THEN** it is laid out without `PARTITION BY` and without an alignment column

#### Scenario: A source whose identity is its partition column yields an unaligned enrichment

- **GIVEN** a source whose `identity_column` is its partition column
- **WHEN** an enrichment keyed on it has its schema defined
- **THEN** it is created without `PARTITION BY` and with a single column of that name, its grain key

#### Scenario: A sensitive or heavy declared column is not adopted

- **GIVEN** an eligible enrichment of `dial_usage_log`
- **WHEN** its schema declares a non-nullable `timestamp` column `request_time` that is `sensitive`, or `heavy`
- **THEN** the response is HTTP 422 `validation_error` naming the collision, and no DDL is issued

#### Scenario: An unadoptable alignment name cannot be added before the relayout

- **GIVEN** an eligible enrichment of `dial_usage_log` that is not laid out aligned yet
- **WHEN** a `PATCH /v1/tables/{name}/schema` adds a nullable `request_time`, or renames a `string` column to it
- **THEN** the response is HTTP 422 `validation_error` and no `ALTER` is issued

#### Scenario: A mismatched declared column is refused

- **GIVEN** an eligible enrichment of `dial_usage_log`
- **WHEN** its schema declares a column `request_time` of type `string`, or a nullable `timestamp` one
- **THEN** the response is HTTP 422 `validation_error` naming the collision, and no DDL is issued

#### Scenario: An aligned enrichment imports aligned onto a fresh environment

- **GIVEN** `usage_client_identity` and `usage_request_baggage`, aligned, exported with their pipelines
- **WHEN** the bundle is imported into an environment holding only the system tables
- **THEN** the import succeeds, both tables are created aligned with one physical `request_time` column
  each — hidden on `usage_client_identity`, the adopted visible one on `usage_request_baggage` — their
  column lists and descriptions match the exporting environment, and `layout` reads `aligned`

### Requirement: The rows API requires the alignment column for an aligned enrichment

`POST /v1/tables/{name}/rows` on an aligned enrichment SHALL require every row to carry the alignment
column; a row without it SHALL be refused with HTTP 422 `validation_error` naming the column, and no row of
the request SHALL be written. A row carrying a value that does not match its source row's SHALL be written
as given; under the tuple join it is unmatched and attaches to no source row.

#### Scenario: A row without the alignment column is refused

- **WHEN** a row lacking `request_time` is posted to an aligned enrichment of `dial_usage_log`
- **THEN** the response is HTTP 422 `validation_error` and nothing is written

#### Scenario: A wrong alignment value only orphans its own row

- **WHEN** a row is posted with an `event_id` that exists in the source and a `request_time` that differs
  from that source row's
- **THEN** a query projecting the enrichment over that source row reads the column's unmatched value, and
  no other source row reads the posted values

### Requirement: An aligned enrichment inherits its source's TTL

An aligned enrichment that declares no `ttl` SHALL be created with its source's physical TTL clause and its
`ttl_only_drop_parts` setting, both read from the source's ClickHouse table (for `dial_usage_log`, one year
with `ttl_only_drop_parts = 1`). The clause applies unchanged because the alignment column has the source
partition column's name and type. An explicitly declared `ttl` SHALL override the inherited one. An
inherited TTL SHALL NOT be mirrored into the catalog, so `GET /v1/tables/{name}` SHALL omit `ttl` for it,
as it does for the seeded tables' migration-managed TTLs. Every read of an enrichment starts from its
source, so a row whose source has expired is unreadable; inheriting the TTL removes only rows that can never
be read again.

Only the single-rule shape the server renders (`TTL <column> + toInterval<Unit>(<n>)`, optionally wrapped in
`toDateTime`) over the alignment column is inherited; a source TTL with several rules, a `WHERE`, or over another
column is not, and the enrichment is then created without one.

Because an inherited TTL is not in the catalog, `PATCH /v1/tables/{name}/schema` with `"ttl": null` SHALL NOT
remove it: with no catalog TTL the removal is the same no-op it is for any table without one, and no
`REMOVE TTL` is issued. Declaring a `ttl` replaces the inherited clause (`MODIFY TTL`); the inherited
`ttl_only_drop_parts` setting stays in either case.

#### Scenario: An aligned enrichment carries its source's TTL

- **GIVEN** an eligible enrichment of `dial_usage_log` defined without `ttl`
- **WHEN** its ClickHouse table is inspected
- **THEN** it carries `TTL toDateTime(request_time) + INTERVAL 1 YEAR` and `ttl_only_drop_parts = 1`, and
  `GET /v1/tables/{name}` omits `ttl`

#### Scenario: A declared TTL overrides the inherited one

- **WHEN** an eligible enrichment is defined with `ttl` `{column: request_time, interval: 90, unit: day}`
- **THEN** the table's TTL is 90 days

### Requirement: A grouped-by column of an aligned enrichment carries a lookup index

The system SHALL index an aligned enrichment column that a group trigger's `group_by` or a form-mode aggregate's `group_by` names.
When a pipeline naming it is registered or patched, the column SHALL carry
`INDEX idx_<column> <column> TYPE bloom_filter(0.001) GRANULARITY 1`. The index SHALL be added with
`ALTER TABLE … ADD INDEX` only and SHALL NOT be materialized over existing parts: no `MATERIALIZE INDEX`
mutation SHALL be issued. Deleting the pipeline SHALL leave the index in place. A column of a non-aligned
enrichment SHALL receive no index.

Parts without the index SHALL still be read correctly; only the partition pre-selection over them is slower.

A column SHALL count as indexed when any `bloom_filter` skip index is over exactly that column, whatever its name:
a `RENAME COLUMN` keeps an index's name and moves its expression to the new name. When `idx_<column>` is taken by an
index over another column, the new index SHALL take the first free `idx_<column>_<n>`. An SQL-mode aggregate's
grouping lives in its statement, which is not parsed for this, so its grouped-by columns get the partition
pre-selection but no index.

Because the index outlives its pipeline, a `drop` of an indexed column through `PATCH /v1/tables/{name}/schema`
SHALL drop every skip index over exactly that column in the same `ALTER`, ahead of the `DROP COLUMN`;
ClickHouse otherwise refuses the drop.

#### Scenario: Grouping by an aligned column adds the index without a mutation

- **GIVEN** an aligned enrichment with a `client_session_id` column and no index on it
- **WHEN** a group pipeline with `group_by` `client_session_id` is registered over it
- **THEN** the table carries a `bloom_filter(0.001)` index on `client_session_id`, and
  `system.mutations` holds no `MATERIALIZE INDEX` for it

#### Scenario: An index already present is not re-added

- **WHEN** a second pipeline groups by the same column
- **THEN** no further DDL is issued

#### Scenario: A non-aligned enrichment column receives no index

- **GIVEN** an enrichment of `dial_usage_log` keyed on `chat_id`
- **WHEN** an aggregate grouping by one of its columns is registered
- **THEN** the table carries no skip index

#### Scenario: A renamed column stays covered by its index

- **GIVEN** an aligned enrichment whose `session` column carries `idx_session`, renamed to `sid` with a new
  `session` column added
- **WHEN** one pipeline groups by `sid` and another by `session`
- **THEN** `idx_session` still covers `sid`, no index is added for it, and `session` gets `idx_session_2`

#### Scenario: Dropping a column whose index outlived its pipeline succeeds

- **GIVEN** an aligned enrichment column carrying a lookup index whose pipelines have been deleted
- **WHEN** the column is dropped through `PATCH /v1/tables/{name}/schema`
- **THEN** the drop succeeds and the table carries neither the column nor the index

### Requirement: Existing eligible enrichments are relaid out automatically

After the application is ready, and after a fixed grace delay of ten minutes, the system SHALL migrate every
existing enrichment that is eligible for the aligned layout but not laid out aligned, and whose only writer
is a Class-1 materialize pipeline or no pipeline. An eligible enrichment with a row-grain LLM pipeline
writing it SHALL be left as it is, with a WARN log naming it: the runner may hold queued and dead-lettered
write rows for it built before the runner echoed the partition value (enrichment-runtime, "The scan carries an
aligned target's partition value and the write-back returns it"), and those would be refused once it is aligned. A table already declaring a column with the
alignment column's name SHALL be migrated with that column adopted when it matches (see "An eligible
row-grain enrichment is laid out aligned with its source"), its values re-derived from the source by the
copy; when it does not match, the table SHALL be left as it is with `layout: unsupported` and a WARN naming
the column. The migration SHALL need no call, property or operator step.

The migration SHALL run as one background job per table, off the probe path, under a Postgres advisory lock
so that exactly one instance runs it, with progress persisted in Postgres so that a restart resumes it from
its last completed step. It SHALL:

1. read the current database's engine and proceed only for `Atomic` or `Replicated`; for any other engine
   it SHALL record `layout: unsupported`, log a WARN and leave the table unchanged;
2. copy the table's columns, with `_updated_at` unchanged and the alignment column derived from the source,
   into a new aligned table — one slice of grain keys per statement (see below) — whose DDL already carries every lookup index
   the table's pipelines need, and no TTL yet. The copy is named with a reserved leading `_`, which no user
   table can have. A row whose source row no longer exists is not copied, and their count is logged;
3. copy the rows written during that copy;
4. check the table's writer again, and abandon the relayout — dropping the copy and recording
   `layout: unsupported` with a WARN — when a row-grain LLM pipeline now writes it; otherwise hold the table's
   writers (see "The writers of a relaying-out enrichment are held briefly");
5. copy the rows written since the previous copy, after the hold has waited out a materialize already
   running, and check the writer again before the swap as in step 4;
6. swap the two tables with `EXCHANGE TABLES`, then re-stamp and move any row of the new table's epoch
   partition from the source;
7. update the catalog and release the hold;
8. copy any row of the old table newer than the step-5 mark, repeat the epoch-partition repair, and verify
   that every row of the old table whose source row exists is in the aligned table — logging the rows with
   no source row at WARN, and keeping the old table, with an ERROR log, when any row with a source row is
   missing; otherwise copy what the old table received since this step began and drop it. Then apply the
   table's TTL, its own or the inherited one, recomputing each part's TTL information without rewriting its
   data (`materialize_ttl_recalculate_only`, reset once the recalculation is done).

Each catch-up copy SHALL include the rows stamped in its mark's own millisecond, and SHALL write any number of
partitions in one statement.

Every statement that reads the table's rows — the copies of steps 2, 3, 5 and 8, the epoch-partition repair and
the step-8 verification — SHALL read one **slice** at a time: a range of the table's grain keys, in the table's
sort order, holding about 256 MiB of the table's uncompressed data, each range's upper key read just before it
runs and the last range open above, so that together the ranges cover every key whatever is written meanwhile.
The source SHALL be joined restricted to the keys the slice holds, and every such statement SHALL run capped at
`pipeline.budget.max-memory`. The history copy SHALL persist the last key a completed slice copied through, and a
restart SHALL resume its copy after that key. A source month or day SHALL NOT bound a statement: its size follows
the traffic, and the join that stamps the alignment column holds the source rows of its whole range — 2 GiB for
one month of a moderately busy deployment's `dial_usage_log`, more than the whole server budget for a month at ten times that volume.

The TTL comes last because a row an earlier build writes with no alignment value lands in the epoch partition,
which a TTL in force has already expired: ClickHouse would drop its part before the repair could re-stamp it.

A failure before the swap SHALL leave the old table in service, release the hold before any further
ClickHouse statement, drop the copy and record the error; the next start SHALL migrate the table again from
the beginning. A failure after the swap SHALL keep the progress for the next start to resume. Each start SHALL
also judge a skipped table again, and migrate it once nothing refuses it. A failure of one table SHALL NOT
stop the migration of the others, and a table another instance holds the lock of SHALL be tried again after
the grace delay. A stop — shutdown or interruption — SHALL release the hold, drop nothing and keep the
progress for the next start to resume. A migration failure SHALL NOT fail application startup. Finding rows
in step 6 or step 8 SHALL log a WARN with their count; in step 6 the rows re-stamped from the source and the
rows with no source row are counted separately.

While a table's migration has started and not finished, `PATCH /v1/tables/{name}/schema` SHALL be refused with
HTTP 409 `conflict` before any DDL: the migration copies the columns and TTL it planned from at its start
into the table that replaces this one, so an ALTER would reach only the table being replaced.

`GET /v1/tables/{name}` SHALL report `layout` for an enrichment: `migrating` while the job has started and not
finished, `aligned` once laid out aligned (a system enrichment such as `dial_usage_log_payload` included),
`unsupported` when the job skipped it — an LLM writer, a same-named column it cannot adopt, or an engine that
cannot exchange tables — and omit it otherwise, a relayout that failed before the exchange included. Catalog export SHALL carry no layout; import SHALL derive it at
define-schema.

#### Scenario: An existing eligible enrichment is migrated without an operator step

- **GIVEN** an unaligned enrichment of `dial_usage_log` with `grain_key` `event_id`, written only by a
  Class-1 pipeline
- **WHEN** the application has been ready for the grace delay
- **THEN** the table is replaced by an aligned table holding the same rows with the same `_updated_at`,
  every row carrying its source row's `request_time`, and `layout` reads `aligned`

#### Scenario: A table with its own copy of the partition column is migrated with it adopted

- **GIVEN** `usage_request_baggage`, unaligned, declaring a non-nullable `timestamp` column `request_time`
  and a catalog `ttl` over it
- **WHEN** the migration runs
- **THEN** the aligned table has one `request_time` column carrying its source rows' values, keeps the
  declared column's description and its declared `ttl`, and `layout` reads `aligned`

#### Scenario: A migration interrupted mid-way resumes

- **WHEN** the instance stops after any step of the migration and starts again
- **THEN** the migration resumes from the step after the last completed one and finishes with the same row
  count as the original table

#### Scenario: A history copy interrupted between slices resumes after the last completed slice

- **GIVEN** a migration whose history copy completed the slices up to some grain key
- **WHEN** the instance stops and starts again
- **THEN** the copy continues with the keys above that key, and the aligned table ends with every row of the
  original table

#### Scenario: Statement size does not follow traffic

- **GIVEN** an enrichment whose busiest source day holds more rows than one slice
- **WHEN** it is migrated
- **THEN** no statement of the migration reads more than one slice of the table, and each runs under
  `pipeline.budget.max-memory`

#### Scenario: Only one instance migrates

- **WHEN** two instances become ready together
- **THEN** exactly one runs the migration of a given table

#### Scenario: An LLM-written enrichment is left alone

- **GIVEN** an eligible enrichment written by a row-grain LLM pipeline
- **WHEN** the migration runs
- **THEN** the table is unchanged, `layout` reads `unsupported`, and a WARN names it

#### Scenario: An unsupported database engine is skipped

- **WHEN** the current database's engine is neither `Atomic` nor `Replicated`
- **THEN** the table is unchanged and `layout` reads `unsupported`

#### Scenario: A failure before the exchange leaves the table in service

- **WHEN** the exchange cannot run
- **THEN** the old table is unchanged and in service, the hold is released and the error is recorded
- **AND** the next start migrates the table from the beginning

#### Scenario: A stop inside the hold keeps the progress

- **WHEN** the application shuts down while the migration holds a table's writers
- **THEN** the hold is released, the copy is kept and no error is recorded, and the next start resumes

#### Scenario: Rows whose source rows have expired are not a loss

- **GIVEN** an enrichment row whose source row no longer exists
- **WHEN** the migration completes
- **THEN** the row is not carried over, a WARN gives the count, and the old table is dropped

#### Scenario: A schema change during the migration is refused

- **WHEN** `PATCH /v1/tables/{name}/schema` reaches a table whose migration is running
- **THEN** it is refused with HTTP 409 `conflict` and no ALTER runs

#### Scenario: A write from an old instance during deploy skew survives

- **GIVEN** rows written into the old table after the catch-up copy, and rows with an epoch `request_time`
  written into the new table after the swap
- **WHEN** the migration completes
- **THEN** both sets of rows are present in the aligned table with their source's `request_time`, and each
  repair logged one WARN with its count

### Requirement: The writers of a relaying-out enrichment are held briefly

The system SHALL refuse every write to an enrichment with HTTP 503 `table_relayout_in_progress` while its
relayout is between its hold and its catalog update — `POST /v1/pipelines/{name}/materialize` for a pipeline
targeting it, `POST /v1/pipelines/{name}/rows`, and `POST /v1/tables/{name}/rows` — before any statement is
issued. The hold SHALL be read from Postgres on each write, so that it holds on every instance.
Reads SHALL NOT be held. Before its last copy the hold SHALL wait `pipeline.budget.timeout` plus a small
margin, so that a materialize already past its check has finished. The relayout SHALL refresh the hold while
it stands, and a hold not refreshed for ten minutes — its instance gone — SHALL no longer refuse writes.

#### Scenario: A materialize during the hold is refused and retried later

- **WHEN** a materialize targets an enrichment whose relayout is holding writers
- **THEN** the response is HTTP 503 `table_relayout_in_progress`, nothing is written, and a materialize
  after the hold is released succeeds from the same cursor

#### Scenario: A hold whose instance died lapses

- **WHEN** the instance holding an enrichment's writers dies and ten minutes pass
- **THEN** writes to the enrichment are accepted again

#### Scenario: Reads continue during the hold

- **WHEN** a query projects a held enrichment's columns
- **THEN** it succeeds
