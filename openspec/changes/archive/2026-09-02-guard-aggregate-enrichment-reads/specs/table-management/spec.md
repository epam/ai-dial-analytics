## MODIFIED Requirements

### Requirement: Delete a table definition

The system SHALL delete a table definition at `DELETE /v1/tables/{name}`, removing its catalog rows
and issuing `DROP TABLE` in ClickHouse (writer credential), returning HTTP 204. Deleting a table SHALL
also remove its access lists (see the `table-access-control` capability).
Deleting a `source` that still has enrichments referencing it SHALL be rejected with HTTP 409 until
those enrichments are deleted. Deleting a table that a **pipeline reads as its input** SHALL likewise
be rejected with HTTP 409, naming the pipelines, until each is deleted or repointed — otherwise a pipeline
would survive pointing at a table that no longer exists and fail on its next run instead of at the moment
the dependency was broken.

This SHALL be **one check across both kinds**, where the two registries needed two. The enrichment registry
asked it as "which rules declare this as their read source" and the aggregate registry as "which pipelines
read this as their input", and after the merge those are the same question of the same column — a declared
read source *is* a pipeline's input. The reason it was stated twice remains true of neither: it is not the
same protection the enrichment check gives a source read through a target enrichment, because an input need
have no enrichment of its own.

A second, distinct check SHALL protect an **enrichment** whose columns a declaration reads through the
grain join without targeting it. That one cannot be answered by the input lookup — it asks which
*declarations* name a column of this enrichment, and the reader's own input is a different table entirely —
so it survives the merge as its own query (see the
[enrichment-registry](../enrichment-registry/spec.md) capability).

That second check SHALL consult **every stored declaration, whatever its kind**. An `aggregate`
declaration reads an enrichment through the same grain join and is broken by the drop in the same way, so
scoping the guard to one kind protected an enrichment only when a declaration of that kind happened to
read it too — which is coincidence, not a guard.

Because the two kinds name their columns in different places, the check SHALL resolve each declaration
through the gate its own kind selects: an `enrich` declaration names them in its bindings, filter,
readiness signal and its evaluator's output expressions, an `aggregate` one in its group keys, its
measures, its filter and each measure's own `where`. Deleting a table that is a pipeline's **target** SHALL be permitted and SHALL remove the
pipeline with it: the pipeline exists only to fill that table.
Deleting an unknown definition SHALL return 404. This endpoint SHALL
require the `FULL_ADMIN` authority in `oidc` mode; per-table access roles SHALL NOT authorize a delete.

#### Scenario: Delete drops the ClickHouse table

- **WHEN** `DELETE /v1/tables/{name}` is called for a table with no dependents
- **THEN** the catalog rows are removed, `DROP TABLE` is issued in ClickHouse, and the response is 204

#### Scenario: Delete of a source with enrichments is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` that has enrichments referencing it
- **THEN** the request is rejected with HTTP 409 and no table is dropped

#### Scenario: Delete of a pipeline's declared read source is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` that a pipeline declares as its input, and
  no enrichment references that source
- **THEN** the request is rejected with HTTP 409 naming the pipeline, and no table is dropped

#### Scenario: Delete of an enrichment only an aggregate pipeline reads is blocked

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment whose column an `aggregate` pipeline's group key,
  measure, filter or measure `where` reads, and no `enrich` declaration reads it
- **THEN** the request is rejected with HTTP 409 naming that pipeline, and no table is dropped

#### Scenario: The enrichment becomes deletable once nothing reads it

- **WHEN** the last pipeline reading that enrichment is deleted and the delete is retried
- **THEN** it succeeds — the guard is a dependency, not a lock

#### Scenario: One check answers for both kinds

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` read by an `enrich` pipeline and by an
  `aggregate` pipeline
- **THEN** the request is rejected with HTTP 409 naming both, from one lookup rather than one per kind

#### Scenario: Delete of a pipeline's input table is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that a pipeline reads as its input
- **THEN** the request is rejected with HTTP 409 naming the pipeline, and no table is dropped

#### Scenario: Delete of a pipeline's target removes the pipeline

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that is a pipeline's target
- **THEN** the table is deleted and its pipeline is removed with it

#### Scenario: Delete removes the table's access

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that has per-table access roles
- **THEN** the table is deleted and its access lists are removed with it
