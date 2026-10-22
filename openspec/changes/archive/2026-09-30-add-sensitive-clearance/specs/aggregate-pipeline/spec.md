## MODIFIED Requirements

### Requirement: The scheduler's system identity holds the narrowest sufficient privileges

The system principal the scheduler elevates to SHALL hold exactly `TABLE_READ` and the `TABLE_SENSITIVE`
clearance, closed under implication (`TABLE_READ` → `TABLE_GET` → `TABLE_LIST`; the clearance implies
nothing) — globally, and nothing else. It SHALL NOT hold
`TABLE_GRANT`, `TABLE_CREATE`, `TABLE_DELETE` or `REGISTRY_ADMIN`, so scheduled work cannot administer
anything.

Naming the set is what bounds the elevation. Before privileges existed the narrowest grantable thing was
the full administrative role, so the elevation had to be justified in prose rather than by construction;
the justification is now the set itself.

The narrowed principal SHALL be sufficient for every operation a scheduled refresh performs. If
service-layer writes are ever brought under `TABLE_WRITE`, the principal SHALL be extended to hold it,
because a refresh writes its target table.

#### Scenario: A scheduled refresh over a sensitive column succeeds

- **GIVEN** an enabled pipeline whose declaration reads a `sensitive` source column
- **WHEN** the scheduler runs its refresh with no caller present
- **THEN** the refresh executes and writes its target, exactly as an on-demand refresh does

#### Scenario: The system principal cannot administer

- **WHEN** the scheduled work attempts an operation requiring `TABLE_GRANT`, `TABLE_CREATE`,
  `TABLE_DELETE` or `REGISTRY_ADMIN`
- **THEN** the operation is denied, because the system principal holds no such privilege

#### Scenario: The elevated set is closed under implication

- **WHEN** the system principal's authorities are inspected
- **THEN** they are exactly `TABLE_READ`, `TABLE_SENSITIVE`, `TABLE_GET` and `TABLE_LIST`, because
  `TABLE_READ` confers the privileges it implies and a check tests exact containment

### Requirement: A rollup target may not grant a protected column more widely than its input

Where a rollup target **carries** a `sensitive` column, its `sensitive` grantee set SHALL be a
subset of the roles its input table lists under **both** `read` and `sensitive` (every role on the
target's `sensitive` list counts, whatever its `read` grant; the target's `modify` list is not
checked; and a role in the target's `read` list that `roles-mapping` maps to global `TABLE_SENSITIVE`
must be listed under `read` by the input, as `enrichment-runtime` states) — the same condition as the enrichment rule in `enrichment-runtime`, which
that capability states and defends: the target carrying such a column, **not** the narrower "a pipeline
derives one from a sensitive input column". The narrow reading is the more precise statement of the risk
and is not answerable when a grant is edited, and it is the input table the rollup reads that the subset
is taken against.

It SHALL be enforced at all four of the points `enrichment-runtime` names, reached here through the
pipeline's `input_table` rather than a catalog parent: registration and enable of an `aggregate` pipeline
(rejected `422 pipeline_validation_failed`), `PUT /v1/tables/{name}/access` on either table, and either
schema endpoint when the target first comes to carry a sensitive column (rejected
`422 sensitive_grant_widens`).

**This is a distinct rule from the sensitivity-propagation one above, and the gap between them was a
disclosure.** Requiring the derived column to carry the `sensitive` flag stops it being served to an
unentitled caller; it says nothing about *who* is entitled. A rollup target is a `SOURCE` — a
first-class queryable entity with its own grant lists and, unlike an enrichment, **no catalog
parent** — so a rule that walked source/enrichment parentage never examined it at all. The result was
more direct than the enrichment path: a rollup is addressable on its own, so a caller granted
sensitive-value reads on the target needed no grant whatsoever on the table being aggregated, and the
scheduler's elevated identity did the copying.

#### Scenario: A laxer rollup target is refused at registration

- **GIVEN** `dial_usage_log.jwt_claims` is `sensitive` and `dial_usage_log`'s `read` and `sensitive`
  lists both name `pii-team`, and no other role is on both
- **WHEN** an `aggregate` pipeline is registered whose target declares a `sensitive` column derived
  from `jwt_claims`, and whose target's `sensitive` list names `analysts`
- **THEN** the registration is rejected `422 pipeline_validation_failed`, naming the widening role
  and both tables

#### Scenario: Granting the rollup target more widely is refused

- **GIVEN** the same pipeline registered with matching lists
- **WHEN** an operator grants `sensitive` on the rollup target to a role the input does not list
  under both `read` and `sensitive`
- **THEN** the request is rejected `422 sensitive_grant_widens` and neither list changes

#### Scenario: Narrowing the input walks its rollup targets

- **WHEN** an operator revokes `sensitive` or `read` on the input table from a role while a rollup
  target of it still names that role under `sensitive` and carries a sensitive column
- **THEN** the request is rejected `422 sensitive_grant_widens`
