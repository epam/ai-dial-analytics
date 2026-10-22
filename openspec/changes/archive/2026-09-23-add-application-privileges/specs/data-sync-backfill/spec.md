## ADDED Requirements

### Requirement: Backfill is authorized by DATA_SYNC_OPERATE

Triggering a backfill (`POST /v1/data-sync/backfill`) SHALL require `DATA_SYNC_OPERATE`, and reading
a run's status (`GET /v1/data-sync/backfill/{runId}`) SHALL require `REGISTRY_READ`.

`DATA_SYNC_OPERATE` SHALL cover operations that advance ingestion without reference to a stored
declaration, and SHALL NOT authorize anything that executes one — a pipeline refresh or preview
requires `REGISTRY_ADMIN` (see `aggregate-pipeline`). This is what lets an operator re-run a stalled
ingest without acquiring the authority to change what any pipeline computes.

#### Scenario: An operator can trigger a backfill without authoring rights

- **WHEN** a caller holding `DATA_SYNC_OPERATE` and `REGISTRY_READ`, and no other privilege, triggers
  a backfill
- **THEN** the run starts and its status is readable by that caller

#### Scenario: Backfill requires its own privilege

- **WHEN** a caller holding `REGISTRY_ADMIN` but not `DATA_SYNC_OPERATE` triggers a backfill
- **THEN** the response is `403 forbidden`
