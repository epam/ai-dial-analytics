## MODIFIED Requirements

### Requirement: Table write discipline

The system SHALL carry on every table definition a **write discipline** facet
`write` ∈ {`append`, `upsert_by_key`}, declared at `POST /v1/tables` alongside `type` and defaulting
to `append` when the member is absent. The facet SHALL be **immutable**: it selects the physical
ClickHouse engine, which is frozen at materialization and cannot be `ALTER`ed, so an attempt to
change it through `PUT /v1/tables/{name}` or `PATCH /v1/tables/{name}/schema` SHALL be rejected with
HTTP 422. The facet is a **table** facet, decoupled from `type`: it says how a write to an existing
key behaves, not what the table is.

The facet SHALL be declarable only on a `source`. An `enrichment`'s write discipline is implied by
its kind (it is always keyed and collapsed on its grain), so a `write` member on an `enrichment`
create SHALL be rejected with HTTP 422.

Being undeclarable is not being unreported: the read surface SHALL report an `enrichment` as
`upsert_by_key`, the discipline its kind implies, rather than the `append` a stored default holds. What is
reported is the discipline the table *has*, never the one a caller was permitted to state — otherwise the
one kind whose discipline is not caller-settable is the one the catalog describes wrongly, which is
precisely the mis-declaration the next paragraph rules out.

**The facet SHALL describe the table's actual engine, not merely have selected it.** For a table ADAS
materializes the two cannot diverge — the facet is the input that produced the engine, and it is
immutable thereafter. For a table whose DDL is written by hand and registered in the catalog afterwards,
nothing derives one from the other, so the agreement SHALL be established deliberately: a source backed by
a `ReplacingMergeTree` SHALL be declared `upsert_by_key`, and one backed by a plain `MergeTree` SHALL be
declared `append`.

A disagreement is not always self-revealing, and where it does reveal itself it does so as a failure. The
pipeline's own reads deduplicate through a mechanism that asks ClickHouse what engine a table has, so a
mis-declared table keeps behaving correctly there while the catalog explains that behaviour wrongly — and
the wrong explanation is what an operator reasons from. A user-facing read decides from the facet instead
(see [structured-query](../structured-query/spec.md) "Read result deduplication"): a `ReplacingMergeTree`
declared `append` never takes `FINAL`, returning the unmerged versions the engine means to collapse, and a
plain `MergeTree` declared `upsert_by_key` is rendered `FINAL` and fails in ClickHouse. Either way the difference between a raw `SELECT count(*)` and an ADAS read
has no correct interpretation available from the catalog.

The facet SHALL select the engine at materialization:

- **`append`** — a plain `MergeTree ORDER BY (ordering_key)`. Repeated writes of the same key
  accumulate rows.
- **`upsert_by_key`** — a `ReplacingMergeTree(_updated_at) ORDER BY (ordering_key)` carrying the
  version column rendered as `_updated_at DateTime64(3) DEFAULT now64(3)`, so a re-write of an
  existing `ordering_key` collapses to the latest version. The `ordering_key` is therefore the
  table's unique key.

`_updated_at` on an `upsert_by_key` source SHALL be a hidden system column with exactly the standing
system-column semantics: it SHALL NOT appear in the exposed `columns` and SHALL NOT be counted by
`column_count`; it SHALL NOT be declarable in a schema (the reserved `_`-prefix rule in "Schema
validation"); and it SHALL NOT be caller-settable in a row write (the reserved-prefix rule in "Write
rows into a materialized table"), so ClickHouse stamps an increasing version on every write.

Reads of an `upsert_by_key` source SHALL return the latest row per key unless the query opts out: a
structured query that omits `final` reads it final, a SQL reference reads it final when it carries `FINAL`,
and while `query.force-final` is on every user-facing read of it is final whatever the query says (see
[structured-query](../structured-query/spec.md) "Read result deduplication"). A structured query sending
`"final": false`, or a SQL reference without `FINAL`, with the flag off reads its raw parts, unmerged
versions included.

**The seeded sources.** `dial_usage_log` and `rate_analytics` are materialized by hand-written ClickHouse
DDL as `ReplacingMergeTree(_ingested_at)` ordered by `(project_id, deployment, request_time, trace_id,
core_span_id)`, so both SHALL be declared `upsert_by_key`. The collapse is the point of that engine
choice: the sort tuple is the ingest path's re-ingest dedup key, so a log line delivered twice resolves to
one row on the greatest `_ingested_at` instead of being counted twice.

The facet defaults to `append`, so a source seeded without an explicit value claims a discipline it may
not have — the mis-declaration this requirement rules out. Because the facet is immutable through the
API, a seeded value SHALL be set or corrected by catalog migration; doing so changes no engine, issues no
`ALTER`, and moves no row.

#### Scenario: Create a source with an explicit write discipline

- **WHEN** `POST /v1/tables` is called with `{name, type:"source", write:"upsert_by_key"}`
- **THEN** the response is 201 with `status=PENDING` and the definition carries
  `write: "upsert_by_key"`

#### Scenario: Write discipline defaults to append

- **WHEN** `POST /v1/tables` is called with `{name, type:"source"}` and no `write` member
- **THEN** the created definition carries `write: "append"`

#### Scenario: An upsert source materializes as a keyed, version-collapsing table

- **WHEN** `POST /v1/tables/{name}/schema` materializes an `upsert_by_key` source whose
  `ordering_key` is `(deployment, day)`
- **THEN** a single `CREATE TABLE … ENGINE = ReplacingMergeTree(_updated_at) ORDER BY (deployment,
  day)` is issued, declaring `_updated_at DateTime64(3) DEFAULT now64(3)` in addition to the declared
  columns

#### Scenario: An append source is unaffected

- **WHEN** `POST /v1/tables/{name}/schema` materializes a source whose `write` is `append` (declared
  or defaulted)
- **THEN** the rendered `CREATE TABLE` uses `ENGINE = MergeTree ORDER BY (ordering_key)` with no
  version column

#### Scenario: Write discipline on an enrichment create is rejected

- **WHEN** `POST /v1/tables` is called with `type:"enrichment"` and a `write` member
- **THEN** the request is rejected with HTTP 422 (an enrichment's write discipline is implied by its
  kind)

#### Scenario: An enrichment reports the discipline its kind implies

- **WHEN** `GET /v1/tables/{name}` or `GET /v1/tables` is called for an `enrichment`
- **THEN** it reports `write: "upsert_by_key"`, matching the `ReplacingMergeTree(_updated_at)` its kind
  materializes as — not the `append` default of a facet that is not settable for it

#### Scenario: Write discipline is immutable

- **WHEN** `PUT /v1/tables/{name}` or `PATCH /v1/tables/{name}/schema` attempts to set or change
  `write` on an existing definition
- **THEN** the request is rejected with HTTP 422 and the stored facet is unchanged

#### Scenario: The version column stays hidden and platform-owned

- **WHEN** an `upsert_by_key` source is materialized and `GET /v1/tables/{name}`,
  `GET /v1/tables`, and a row write naming `_updated_at` are exercised
- **THEN** `_updated_at` appears in neither the exposed `columns` nor `column_count`, and the row
  write is rejected with HTTP 422

#### Scenario: An upsert source reads as the latest row per key

- **WHEN** the same `ordering_key` is written twice into an `upsert_by_key` source with different
  measure values and the parts have not merged, then the table is queried by a structured query that
  omits `final`
- **THEN** the query returns one row for that key carrying the values from the later write

#### Scenario: The seeded sources declare the engine they were built with

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log` and `rate_analytics` both report `write: "upsert_by_key"`, matching the
  `ReplacingMergeTree` their ClickHouse DDL creates

#### Scenario: Correcting a seeded facet changes no behaviour

- **WHEN** a seeded source's `write` facet is corrected to match its engine
- **THEN** no `ALTER` is issued and no row moves; every pipeline read returns the same rows it would have
  returned under the uncorrected facet, because its deduplication is the engine's; and a user-facing read
  now resolves an absent `final` and accepts `FINAL` from the corrected facet, as the engine allows

#### Scenario: An upsert-by-key source read raw returns unmerged versions

- **WHEN** `query.force-final` is off, an `upsert_by_key` source holds two not-yet-merged writes of one
  `ordering_key`, and a SQL statement without `FINAL` selects that key
- **THEN** both writes are returned; the same statement with `FINAL` returns only the later one
