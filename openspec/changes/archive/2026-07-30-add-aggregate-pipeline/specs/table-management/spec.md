## MODIFIED Requirements

### Requirement: Delete a table definition

The system SHALL delete a table definition at `DELETE /v1/tables/{name}`, removing its catalog rows
and issuing `DROP TABLE` in ClickHouse (writer credential), returning HTTP 204. Deleting a table SHALL
also remove its access lists (see the `table-access-control` capability).
Deleting a `source` that still has enrichments referencing it SHALL be rejected with HTTP 409 until
those enrichments are deleted. Deleting a table that a **pipeline reads as its input** SHALL likewise be
rejected with HTTP 409, naming the pipeline, until that pipeline is deleted — otherwise the pipeline
would survive pointing at a table that no longer exists and fail on its next refresh instead of at the
moment the dependency was broken (see the `aggregate-pipeline` capability). Deleting a table that is a
pipeline's **target** SHALL be permitted and SHALL remove the pipeline with it: the pipeline exists only
to fill that table.
Deleting an unknown definition SHALL return 404. This endpoint SHALL
require the `FULL_ADMIN` authority in `oidc` mode; per-table access roles SHALL NOT authorize a delete.

#### Scenario: Delete drops the ClickHouse table

- **WHEN** `DELETE /v1/tables/{name}` is called for a table with no dependents
- **THEN** the catalog rows are removed, `DROP TABLE` is issued in ClickHouse, and the response is 204

#### Scenario: Delete of a source with enrichments is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` that has enrichments referencing it
- **THEN** the request is rejected with HTTP 409 and no table is dropped

#### Scenario: Delete of a pipeline's input table is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that a pipeline reads as its input
- **THEN** the request is rejected with HTTP 409 naming the pipeline, and no table is dropped

#### Scenario: Delete of a pipeline's target removes the pipeline

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that is a pipeline's target
- **THEN** the table is deleted and its pipeline is removed with it

#### Scenario: Delete removes the table's access

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that has per-table access roles
- **THEN** the table is deleted and its access lists are removed with it
