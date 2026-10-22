## ADDED Requirements

### Requirement: A non-deterministic declaration is refused at registration and at refresh

An aggregate declaration's `filter` and each measure's `where` SHALL be rejected when it calls a
function the query function catalog declares **non-deterministic** — the pipeline-wide rule in
[enrichment-registry](../enrichment-registry/spec.md) ("Every governed SQL fragment is
deterministic"), reported as this capability's HTTP 422 `pipeline_validation_failed`. Like every
other part of this gate, the check SHALL be applied in full **at registration and re-applied at
execution**, so a declaration cannot become non-deterministic under a stored pipeline by any route
that changes what the catalog declares.

In practice the registration half is total: such a declaration is never stored, so neither a refresh
nor a dry-run preview — which names a *registered* pipeline — can encounter one. The re-application at
execution is what makes that hold rather than being assumed.

The reason is this capability's own: a scheduled refresh recomputes every affected group key **in
full**, so a predicate that moves with the clock would recompute a historical bucket over a
*different* row set and silently rewrite a number somebody has already read and cited — the exact
failure the rest of this gate exists to prevent, arriving without an error to point at. A rollup's
time scoping belongs in its group key and its refresh window, both of which are recorded; a moving
`filter` is scoping nothing can reconstruct after the fact.

#### Scenario: An aggregate filter reading the clock is rejected at registration

- **WHEN** an aggregate pipeline is declared with a `filter` of `request_time >= now() - INTERVAL 7 DAY`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, and
  nothing is persisted

#### Scenario: A measure predicate reading the clock is rejected

- **WHEN** an aggregate pipeline declares a measure whose `where` calls `now()`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Nothing reaches preview or refresh to be re-checked

- **WHEN** a caller looks for the refused declaration after the rejection
- **THEN** no pipeline was created, so neither the dry-run preview nor a scheduled refresh can be
  reached with such a declaration at all

#### Scenario: Date arithmetic over a column still registers

- **WHEN** an aggregate pipeline is declared with a `filter` of
  `date_sub('day', 1, request_time) < _ingested_at`
- **THEN** the declaration is accepted — it is the clock that is refused, not date arithmetic
