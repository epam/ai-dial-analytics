## ADDED Requirements

### Requirement: Backfill runs are listable

The service SHALL expose `GET /v1/data-sync/backfill`, returning the backfill runs it retains as an
object whose `runs` member is an array of run statuses, so that a caller who did not trigger a run — or
no longer holds its `runId` — can learn whether a backfill is active, which run it is, and how recent
runs ended. Each entry SHALL carry exactly the representation `GET /v1/data-sync/backfill/{runId}`
returns for that run, including its live progress while running and its failed dates when any day
failed, so one entry and one by-id read are interchangeable.

The list SHALL be a runtime cache of recent runs, not a history: it is cleared on restart and redeploy
and scoped to the serving instance. Entries SHALL be ordered newest started first. The service SHALL
retain at most the 5 most recently started runs, evicted oldest-first; one retention governs both the
list and `GET /{runId}`, so an evicted run SHALL NOT be listed and its `GET /{runId}` SHALL return
`404`. A trigger the service refused to start SHALL NOT appear once the refusal has been returned.
Because the list is bounded by retention, it SHALL NOT be paginated.

An optional `state` query parameter SHALL filter the list to runs in that state, accepting the wire
values the status reports — `running`, `completed`, `failed` — matched case-insensitively. Because at
most one run is in flight, `state=running` SHALL yield at most one entry. A `state` value outside that
set SHALL be rejected with a `400` `ErrorView` whose `error` is `bad_request` and whose message names
the accepted values; an empty `state=` SHALL be rejected the same way rather than read as no filter. A
list with no matching run — including when the service retains no runs at all — SHALL be a `200` with an
empty `runs` array, never a `404`.

The list is a read: it SHALL be available to the same callers as `GET /{runId}`, including a read-only
admin, while triggering a backfill SHALL remain restricted to a full admin.

#### Scenario: The active run is discoverable without its run id

- **WHEN** a backfill is running and a caller that does not hold its `runId` requests
  `GET /v1/data-sync/backfill?state=running`
- **THEN** the service responds `200` with exactly one entry, carrying that run's `runId`, state
  `running`, current date and cumulative progress as `GET /{runId}` would report them

#### Scenario: No active run is an empty list, not an error

- **WHEN** no backfill is running and the caller requests `GET /v1/data-sync/backfill?state=running`
- **THEN** the service responds `200` with an empty `runs` array

#### Scenario: Runs are listed newest first

- **WHEN** three runs were started one after another and the caller requests `GET /v1/data-sync/backfill`
- **THEN** the entries are ordered from the most recently started run to the earliest

#### Scenario: A run evicted from retention is not listed

- **WHEN** five runs have been started and a sixth is started
- **THEN** the list holds the five most recently started runs, the first run is absent from it, and its
  `GET /{runId}` returns `404`

#### Scenario: A refused trigger never appears

- **WHEN** a trigger passed validation but the service could not start the run, and the caller then lists
  the runs
- **THEN** no entry for that trigger is listed

#### Scenario: A failed run lists its failed dates

- **WHEN** a retained run ended with one or more failed days and the caller lists runs with `state=failed`
  or with no filter
- **THEN** that run's entry carries its failed dates exactly as its `GET /{runId}` does

#### Scenario: An unknown state filter is a bad request

- **WHEN** the caller requests `GET /v1/data-sync/backfill?state=paused`
- **THEN** the service responds with a `400` `ErrorView` whose `error` is `bad_request` and whose message
  names `running`, `completed` and `failed`

#### Scenario: An empty state filter is a bad request

- **WHEN** the caller requests `GET /v1/data-sync/backfill?state=`
- **THEN** the service responds with a `400` `ErrorView` whose `error` is `bad_request` and whose message
  names the accepted values

#### Scenario: A read-only admin can list but not trigger

- **WHEN** a caller holding only the read-only admin role lists backfill runs and then triggers a backfill
- **THEN** the list is served with `200`, and the trigger is refused with a `403` `ErrorView` whose
  `error` is `forbidden`

### Requirement: Backfill run state is per service instance

The service SHALL scope backfill run state to the service instance that holds it: the retained runs, the
single-in-flight guard, and every read over them (`GET /{runId}` and the list). A read SHALL report only
the runs of the instance that serves it. When more than one instance is serving — as during a rolling
update, where the outgoing and incoming instances overlap — a list MAY omit a run another instance
holds, `GET /{runId}` MAY return `404` for it, and a second trigger routed to another instance MAY be
accepted while the first still runs. A caller SHALL NOT treat an empty `state=running` list as proof
that no backfill is running anywhere in the deployment.

#### Scenario: A read reports only its own instance's runs

- **WHEN** a run was started on one instance and a list request is served by another
- **THEN** the list served by the second instance does not include that run, and `GET /{runId}` on the
  second instance returns `404`

#### Scenario: A restart empties the list

- **WHEN** the instance holding retained runs restarts and the caller lists runs from the new process
- **THEN** the list is empty, consistent with run state not surviving a restart
