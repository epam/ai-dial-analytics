## ADDED Requirements

### Requirement: The pipeline data plane refuses a row-confined caller

The scan, the group assembly and the Class-1 materialize — and the enrich dry-run preview, which reads
through them — read the pipeline's sources with pipeline settings, which carry no row-policy filter (see
[table-access-control](../table-access-control/spec.md)). A caller confined by a row policy on any table
those endpoints may read for the pipeline — its read source, its target's `source_table`, and every
enrichment of either — SHALL therefore be rejected with HTTP 403 and error code `row_policy_confined`,
naming the table, after the read and write authorization those endpoints already require has passed. The
data plane SHALL NOT serve a confined caller a filtered read instead: the exactly-once scan cursor and the
group assembly are defined over the whole source, and a filtered view would silently drop rows from a
derivation.

The write endpoint (`POST /v1/pipelines/{name}/rows`) reads nothing and SHALL NOT be subject to this
check; row policies narrow reads only.

In `none` mode the check SHALL NOT apply.

#### Scenario: A confined runner cannot scan

- **GIVEN** a caller holding `read` on a pipeline's read source and `write` on its target, whose role is
  confined by a row policy on that read source
- **WHEN** it calls `POST /v1/pipelines/{name}/scan`, the group assembly or the materialize endpoint
- **THEN** the request is rejected `403 row_policy_confined`

#### Scenario: A confined caller may still write back

- **WHEN** the same caller posts rows to `POST /v1/pipelines/{name}/rows`
- **THEN** the write is authorized by the target's `TABLE_WRITE` as before
