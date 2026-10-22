## ADDED Requirements

### Requirement: An enrichment's declared partitioning is readable but not settable

`GET /v1/tables/{name}` SHALL report `partition_by` for any table whose definition declares a partition
column, an enrichment included, and SHALL omit it for a table that declares none. Declaring one SHALL
remain a source-only operation: `POST /v1/tables/{name}/schema` and `PATCH /v1/tables/{name}/schema` SHALL
continue to reject `partition_by` on an enrichment with HTTP 422, and the DDL renderer SHALL continue to
emit `PARTITION BY` for a source alone.

The read matters because an enrichment's declared partition column changes how every query over its parent
entity is planned: it is what lets a keyed grain join bound the enrichment to the partitions that can hold
the row it is looking for. A value with that effect has to be visible on the definition that carries it, or
an operator asking why a body read is expensive has no way to see whether the alignment is declared. This
retires the state the split-payload migration created deliberately — a value it declined to set because it
"would be stored and never read".

The write stays refused because the declaration asserts something about how a table is **populated**: that
for a given grain key both tables hold the same value in that column. That holds for a table written from
one source record on both sides, and not for one written by arbitrary row writes through the API. Nothing
at query time can catch a false claim — an over-restricted outer join yields the column's type default,
which is indistinguishable from a row that legitimately has no enrichment — so the claim is carried by the
catalog for tables whose population guarantees it. No API-created enrichment is partitioned at all, so
refusing the write forecloses nothing that exists.

#### Scenario: A system enrichment reports its declared partitioning

- **Given** an enrichment whose catalog definition declares a partition column
- **When** it is fetched by name
- **Then** the response carries `partition_by` with that column and its granularity

#### Scenario: An enrichment without a declared partition column omits the member

- **Given** an API-created enrichment
- **When** it is fetched by name
- **Then** `partition_by` is absent

#### Scenario: Declaring partitioning on an enrichment is still refused

- **Given** an enrichment whose schema is not yet defined
- **When** its schema is posted with `partition_by`
- **Then** the response is HTTP 422
