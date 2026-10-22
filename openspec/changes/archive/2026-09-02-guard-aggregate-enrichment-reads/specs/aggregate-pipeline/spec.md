## ADDED Requirements

### Requirement: An aggregate declaration's enrichment references are protected against drop and rename

An enrichment an aggregate declaration reads SHALL be a dependency of that pipeline, protected exactly as
its declared input already is. `DELETE /v1/tables/{name}` naming such an enrichment SHALL be refused with
HTTP 409 naming the pipelines that read it, and a schema patch that would **drop or rename** a column of
that enrichment which a declaration names SHALL be refused with HTTP 409 on the same basis.

The protection is the same one the enrich declaration has always had, stated here because the aggregate
path did not have it. Its absence was not a decision: the guards parse declarations, and they parsed only
the enrichment registry's — so an enrichment a rollup grouped on was protected only when an enrich
declaration happened to read it too. That is coincidence rather than a guard, and retiring the unrelated
declaration made an enrichment several rollups read droppable, with the failure arriving at the next
refresh instead of at the delete.

The references SHALL be resolved from the declaration's own positions: each group key's input column, each
measure's column, the membership `filter`, and each measure's own `where`. An enrichment column has exactly
one spelling in a declaration's namespace — the qualified flat name — so those four positions are where a
reference can appear, and nothing repoints that name on a rename.

The column check SHALL be **per column**, not per table: a schema change to a column of the same enrichment
that no declaration names SHALL be permitted. A guard that froze an enrichment's whole schema because one
of its columns entered a rollup would be as real a failure as the missing guard it replaced.

A declaration whose input no longer resolves SHALL contribute no references rather than failing the
operation. The alternative is a delete or a patch about one table failing because an unrelated declaration
has drifted, which is the same posture the enrich guard takes.

**This SHALL be reported, not repaired.** The refusal names the pipelines; retiring or re-declaring them
stays the operator's action, and no cascade is performed.

#### Scenario: Deleting an enrichment a rollup groups on is refused

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment whose column an aggregate declaration's group key
  reads, and no enrich declaration reads it
- **THEN** the request is refused with HTTP 409 naming that pipeline, and the enrichment is still readable

#### Scenario: A reference from a measure's predicate counts

- **WHEN** the only position naming the enrichment column is a measure's `where`, or the pipeline `filter`
- **THEN** the delete is refused just as it is for a group key, because a reference is a reference wherever
  the statement reads it

#### Scenario: Dropping or renaming the referenced column is refused

- **WHEN** a schema patch would drop or rename a column of that enrichment which the declaration names
- **THEN** the request is refused with HTTP 409 naming that pipeline

#### Scenario: An unreferenced column of the same enrichment stays changeable

- **WHEN** a schema patch drops a column of that enrichment which no declaration names
- **THEN** the patch succeeds

#### Scenario: The dependency releases when the reader goes

- **WHEN** the last pipeline reading that enrichment is deleted and the delete is retried
- **THEN** it succeeds
