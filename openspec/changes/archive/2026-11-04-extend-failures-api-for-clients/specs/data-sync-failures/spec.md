## ADDED Requirements

### Requirement: A failure is attributed to the backfill run that hit it

A `failed` event written by a backfill run SHALL carry that run's id, the `run_id` that
`POST /v1/data-sync/backfill` returns. A `failed` event written by any other path, and every `resolved` and
`requeued` event, SHALL carry none. The id SHALL be stored in a `backfill_run_id` column of
`_dial_internal_failed_ingest`.

An outstanding subject's **backfill runs** SHALL be the distinct run ids of its `failed` events written after its
latest `resolved` event, sorted. A requeue does not end the attribution: a requeued subject is still the run's until
it resolves. A subject that resolved and then failed again on another path is no longer the run's.

#### Scenario: A backfill day failure names its run

- **WHEN** backfill run `00000000-0000-4000-8000-000000000001` fails day `2026-05-20` as a whole
- **THEN** the day's `failed` event carries that run id, and the outstanding day lists it in `backfill_run_ids`

#### Scenario: A resolution ends the attribution

- **WHEN** a blob failed in a backfill run, was then resolved, and later failed on the poller
- **THEN** the outstanding blob lists no `backfill_run_ids`, and filtering by that run does not select it

### Requirement: Failure responses report whether the retry sweep runs

Each failure response SHALL carry a `retry_sweep` object: those of `GET /v1/data-sync/failures` and
`POST /v1/data-sync/failures/retry`. It has:
- `enabled`: whether the sweep attempts due pending subjects. It is `true` only when `sync.retry.enabled` is on and
  `sync.retry.cron` schedules a sweep, so not when it is `-`;
- `next_sweep_at`: when the next sweep starts, computed from `sync.retry.cron` in the server's default time zone as
  the scheduler does. It is present only when `enabled` is `true`;
- `max_per_sweep`: `sync.retry.max-per-sweep`, the most subjects one sweep attempts.

With `enabled` `false` a requeue still succeeds, but its subjects stay `pending` until the sweep is enabled, so a
client can warn before or after posting one.

#### Scenario: A disabled sweep is reported

- **WHEN** `sync.retry.enabled` is `false` and the operator requeues a parked blob
- **THEN** the response reports `requeued` `1` and `retry_sweep.enabled` `false`, with no `next_sweep_at`

#### Scenario: An enabled sweep names its next run

- **WHEN** `sync.retry.enabled` is `true`, `sync.retry.cron` is `0 */15 * * * *`, and the caller lists failures at
  `10:07` server time
- **THEN** `retry_sweep.enabled` is `true` and `retry_sweep.next_sweep_at` is `10:15` server time

## MODIFIED Requirements

### Requirement: Outstanding failures are listable

The service SHALL expose `GET /v1/data-sync/failures`. It returns the outstanding subjects as an object with:
- a `failures` member: an array of entries;
- a `truncated` member: whether more subjects matched than were returned;
- a `total` member: how many subjects matched every filter, however many `limit` returned. A requeue selects with
  the same filters, so `total` is also how many subjects a retry with the same filters would requeue;
- a `by_state` member: an object with `pending` and `parked` counts of the subjects that matched every filter except
  `state`. One request therefore answers both how many subjects wait for an operator and how many are retried
  automatically, whichever state the list shows;
- a `retry_sweep` member, as defined in "Failure responses report whether the retry sweep runs".

Each entry SHALL carry:
- `kind`: `blob`, `day` or `records`;
- `subject`: the blob key or the ISO date;
- `day`;
- `blob_last_modified`: for blobs and `records` subjects only;
- `state`: `pending` or `parked`;
- `failure_class`, `reason` and `message`: from the latest failure;
- `path`: the path of the latest failure;
- `attempts`;
- `first_failed_at` and `last_failed_at`;
- `next_attempt_at`: for pending subjects only;
- `backfill_run_ids`: the subject's backfill runs, only when it has any.

Entries SHALL be ordered by `last_failed_at` descending, newest failure first. `resolved` subjects SHALL NOT be
listed.

The endpoint SHALL accept these optional query parameters:
- `state` (`pending` or `parked`, case-insensitive);
- `kind` (`blob`, `day` or `records`, case-insensitive);
- `reason` (a reason code);
- `from` and `to` (inclusive ISO dates on `day`);
- `backfill_run_id`: selects the subjects whose backfill runs include it;
- `limit` (1 to 1000, default 100).

A value outside its accepted set, an unparseable date, an inverted range, an empty parameter, a blank
`backfill_run_id`, or a `limit` outside its range SHALL be rejected with `400 bad_request` naming the parameter. No
match is a `200` with an empty `failures` array, `truncated` set to `false` and `total` `0`. A failure to read
ClickHouse SHALL answer `503 clickhouse_unavailable`.

#### Scenario: Outstanding failures are listed newest first

- **WHEN** one blob is pending, one blob is parked, one day is pending and one earlier blob failure is
  resolved, and the caller requests `GET /v1/data-sync/failures`
- **THEN** the response lists the three outstanding subjects ordered by latest failure, newest first, and does
  not list the resolved one

#### Scenario: Blobs with skipped records are listed

- **WHEN** two blobs have parked `records` subjects and the caller requests
  `GET /v1/data-sync/failures?kind=records`
- **THEN** both are listed with `kind` `records`, `state` `parked`, `reason` `skipped_records`, and the counts and
  line numbers in `message`

#### Scenario: Parked failures are filtered

- **WHEN** the caller requests `GET /v1/data-sync/failures?state=parked&reason=mapping_error`
- **THEN** only parked subjects whose latest failure reason is `mapping_error` are listed

#### Scenario: A limited list reports truncation and the total

- **WHEN** 150 subjects are outstanding and the caller requests `limit=100`
- **THEN** the 100 newest are listed, `truncated` is `true` and `total` is `150`

#### Scenario: The total is the count a retry would requeue

- **WHEN** twelve parked subjects have the reason `mapping_error` and the caller requests
  `GET /v1/data-sync/failures?state=parked&reason=mapping_error&limit=1`
- **THEN** `total` is `12`, and a retry posting `{"state": "parked", "reason": "mapping_error"}` reports `requeued` `12`

#### Scenario: Per-state counts disregard the state filter

- **WHEN** three subjects are pending and one is parked, and the caller requests `state=parked`
- **THEN** one entry is listed, `total` is `1`, and `by_state` reports `pending` `3` and `parked` `1`

#### Scenario: The failures of one backfill run are listed

- **WHEN** backfill run `00000000-0000-4000-8000-000000000001` failed one day, another run failed a blob in the
  same date range, and the caller requests `backfill_run_id=00000000-0000-4000-8000-000000000001`
- **THEN** only the day is listed

#### Scenario: An unknown state is a bad request

- **WHEN** the caller requests `GET /v1/data-sync/failures?state=resolved`
- **THEN** the service responds `400` with `error` `bad_request`, and the message names `pending` and `parked`

### Requirement: Outstanding failures can be requeued

The service SHALL expose `POST /v1/data-sync/failures/retry`. It writes a `requeued` event for every
outstanding subject that matches the request body, and answers `200` with an object whose `requeued` member is
the number of subjects requeued and whose `retry_sweep` member is as defined in "Failure responses report whether
the retry sweep runs". A requeued subject is `pending`, its attempt count is reset to zero, and the next sweep that
runs after the request is due to attempt it.

The body SHALL accept these optional members, combined with AND:
- `kind`, `state`, `reason`, `from`, `to` and `backfill_run_id`, with the same meaning and validation as the list
  parameters;
- `subjects`: an array of at most 1000 blob keys or ISO dates, matching a subject of any kind; a null or blank entry
  is rejected with `400 bad_request`;
- `targets`: an array of at most 1000 objects, each with exactly a `kind` and a `subject`, matching that subject of
  that kind only. A blob's `blob` and `records` subjects share its key, so `targets` is how a client names one of
  them, or a mixed selection of several kinds. A null entry, a missing or unknown `kind`, a missing or blank
  `subject`, or any other member is rejected with `400 bad_request`.

A body with no member present SHALL be rejected with `400 bad_request`, so that an empty body cannot requeue
everything by accident. An unknown member is also rejected with `400 bad_request`. Subjects that match nothing
outstanding, resolved subjects included, SHALL be ignored and not counted. Requeueing SHALL work whether or not
`sync.retry.enabled` is on. With retries disabled a requeued subject stays `pending` until retries are enabled.

#### Scenario: Parked mapping failures are requeued after a fix

- **WHEN** three blobs are parked with the reason `mapping_error`, the mapping is corrected and redeployed,
  and the operator posts `{"state": "parked", "reason": "mapping_error"}`
- **THEN** the response reports `requeued` `3`, the three blobs are `pending` with zero attempts, and the
  next sweep ingests them

#### Scenario: One kind of a shared key is requeued

- **WHEN** blob `logs/a.log.gz` has a parked `blob` subject and a parked `records` subject, and the operator posts
  `{"targets": [{"kind": "records", "subject": "logs/a.log.gz"}]}`
- **THEN** `requeued` is `1`, the `records` subject is requeued, and the `blob` subject stays parked

#### Scenario: The failures of one backfill run are requeued

- **WHEN** the operator posts `{"backfill_run_id": "00000000-0000-4000-8000-000000000001", "state": "parked"}`
- **THEN** only the parked subjects that run failed since they were last resolved are requeued

#### Scenario: An empty retry body is refused

- **WHEN** the operator posts `{}` to `/v1/data-sync/failures/retry`
- **THEN** the service responds `400` with `error` `bad_request` and requeues nothing

#### Scenario: A resolved subject is not requeued

- **WHEN** the operator posts a `subjects` list naming one parked blob and one resolved blob
- **THEN** `requeued` is `1` and only the parked blob is requeued
