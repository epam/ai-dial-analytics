## RENAMED Requirements

- FROM: `### Requirement: The payload table's retention is shorter than its parent's`
- TO: `### Requirement: The payload table's retention does not exceed its parent's`

## MODIFIED Requirements

### Requirement: The payload table's retention does not exceed its parent's

`dial_usage_log_payload` SHALL be provisioned with `TTL toDateTime(request_time) + INTERVAL 90 DAY` together
with `ttl_only_drop_parts = 1`, and `dial_usage_log` with its one-year
`TTL toDateTime(request_time) + INTERVAL 1 YEAR` together with `ttl_only_drop_parts = 1`. Each provisioned
retention SHALL be declared by the table's own migrations so that Flyway and an out-of-band operator
provision it identically.

Both retentions SHALL be configurable through environment properties:

- `SYNC_PAYLOAD_TTL_DAYS` (`sync.payload.ttl-days`) for `dial_usage_log_payload`, default `90`;
- `SYNC_USAGE_LOG_TTL_DAYS` (`sync.usage-log.ttl-days`) for `dial_usage_log`. Unset SHALL mean the
  provisioned one year, `TTL toDateTime(request_time) + INTERVAL 1 YEAR`.

For either property:

- a value of `1` or more SHALL mean `TTL toDateTime(request_time) + INTERVAL <value> DAY`;
- `-1` SHALL mean the table carries no `TTL`, so its rows are kept until removed by other means;
- `0`, any value below `-1`, and any value that is not a whole number SHALL fail startup, naming the
  property, before the service accepts requests.

The payload retention SHALL NOT exceed the usage-log retention, because a payload row is reached only
through its `dial_usage_log` row and one that outlives it is kept with no way to read it alongside its
event. For this comparison the default one year SHALL count as 365 days and `-1` as unlimited. A payload
retention longer than the usage-log retention (including `SYNC_PAYLOAD_TTL_DAYS=-1` with a finite
usage-log retention) SHALL fail startup, naming both properties, before the service accepts requests and
before any table is altered. Equal retentions SHALL be accepted.

At startup, after the ClickHouse migrations have run and before the service accepts requests, the service
SHALL compare each of the two tables' live `TTL` with its configured retention and, when they differ, alter
the table so its `TTL` equals the configured one — `MODIFY TTL` for a number of days or the default year,
`REMOVE TTL` for `-1`. A live `TTL` in any other shape (another column, another unit or interval, several
rules) SHALL count as differing and be replaced; in particular `INTERVAL 365 DAY` and `INTERVAL 1 YEAR` are
different retentions. A matching `TTL` SHALL be left untouched and no `ALTER` issued. Each alteration SHALL
be logged at `WARN`, stating the table, the previous retention and the new one. The configured value SHALL be
the single source of truth: a `TTL` changed by hand SHALL be reverted to it on the next start.

The alteration SHALL NOT rewrite the table's existing parts and SHALL need no free-disk headroom beyond the
table's metadata. A shortened retention SHALL apply to rows already stored: a day partition whose newest row
is past the new limit SHALL be dropped whole, exactly as an expiry under the old retention would have been.
`ttl_only_drop_parts = 1` SHALL be unaffected by the alteration.

The reconciliation SHALL run only when the service runs the ClickHouse migrations
(`CLICKHOUSE_MIGRATIONS_ENABLED=true`). When migrations are disabled the service SHALL NOT alter either
table and SHALL NOT apply either property, both of which are still validated, including against each other.
When a configured table does not exist, the service SHALL log that its reconciliation was skipped and start
normally. A failed alteration SHALL fail startup, as a failed migration does. The tables reconciled are the
ones the ingest targets write: `SYNC_TABLE` names the usage log, `SYNC_PAYLOAD_TABLE` the payload table.

Neither retention SHALL be a `/v1/tables` field, and the catalog SHALL NOT mirror either. They cover these
two tables only; `rate_analytics`'s `TTL` SHALL remain migration-defined.

Expiry SHALL be quantized to a daily part on both tables: a part is dropped once its newest row has
expired, so a row MAY outlive its `TTL` by at most the rest of its day.

A payload row SHALL expire independently of the `dial_usage_log` row it belongs to. A query naming a
body column for an event older than the payload retention SHALL return the parent's columns with the
body columns `NULL` — the outcome of a grain `LEFT JOIN` that finds no row — and SHALL NOT fail. The
two document columns (`jwt_claims`, `request_tags`) are non-nullable `JSON` and SHALL read as the empty
document `{}` for such an event — indistinguishable from a request that carried no claims or headers —
so a consumer distinguishing "expired" from "empty" SHALL test a body column's nullness or bound the
query to the payload window; the query surface documents this asymmetry rather than papering over it.

#### Scenario: Provisioned payload table declares the shorter retention

- **WHEN** `dial_usage_log_payload` is provisioned (by Flyway or by an operator applying the migrations
  in version order)
- **THEN** its declaration carries `TTL toDateTime(request_time) + INTERVAL 90 DAY` and
  `ttl_only_drop_parts = 1`, while `dial_usage_log` still carries its one-year `TTL`

#### Scenario: Default retention leaves a freshly provisioned table untouched

- **WHEN** the service starts with `SYNC_PAYLOAD_TTL_DAYS` unset and the payload table carries
  `TTL toDateTime(request_time) + INTERVAL 90 DAY`
- **THEN** no `ALTER` is issued and the table's `TTL` is unchanged

#### Scenario: Default usage-log retention leaves a provisioned table untouched

- **WHEN** the service starts with `SYNC_USAGE_LOG_TTL_DAYS` unset and `dial_usage_log` carries
  `TTL toDateTime(request_time) + INTERVAL 1 YEAR`
- **THEN** no `ALTER` is issued on `dial_usage_log` and its `TTL` is unchanged

#### Scenario: A configured retention replaces a differing one

- **WHEN** the service starts with `SYNC_PAYLOAD_TTL_DAYS=30` and the payload table carries a 90-day `TTL`
- **THEN** the table's `TTL` becomes `toDateTime(request_time) + INTERVAL 30 DAY`, `ttl_only_drop_parts = 1`
  is still set, and a `WARN` log names the table, the 90-day retention and the 30-day one

#### Scenario: A configured usage-log retention replaces the default year

- **WHEN** the service starts with `SYNC_USAGE_LOG_TTL_DAYS=180` and `dial_usage_log` carries its one-year
  `TTL`
- **THEN** its `TTL` becomes `toDateTime(request_time) + INTERVAL 180 DAY`, `ttl_only_drop_parts = 1` is
  still set, and a `WARN` log names `dial_usage_log`, the one-year retention and the 180-day one

#### Scenario: 365 days is not the default year

- **WHEN** the service starts with `SYNC_USAGE_LOG_TTL_DAYS=365` and `dial_usage_log` carries its one-year
  `TTL`
- **THEN** its `TTL` becomes `toDateTime(request_time) + INTERVAL 365 DAY` and the change is logged at
  `WARN`

#### Scenario: Shortening the retention drops stored partitions past the new limit

- **WHEN** the payload table holds a day partition older than 30 days but newer than 90, and the service
  starts with `SYNC_PAYLOAD_TTL_DAYS=30`
- **THEN** that partition is dropped whole once ClickHouse applies the new `TTL`, and the table's other
  parts are not rewritten

#### Scenario: Minus one removes the retention

- **WHEN** the service starts with `SYNC_PAYLOAD_TTL_DAYS=-1` and `SYNC_USAGE_LOG_TTL_DAYS=-1`, and both
  tables carry a `TTL`
- **THEN** neither table carries a `TTL` afterwards, and a `WARN` log names each removed retention

#### Scenario: A retention set by hand is reverted

- **WHEN** an operator has altered the payload table's `TTL` to 45 days by hand and the service restarts
  with `SYNC_PAYLOAD_TTL_DAYS` unset
- **THEN** the table's `TTL` is set back to 90 days and the change is logged at `WARN`

#### Scenario: A usage-log retention set by hand is reverted to the year

- **WHEN** an operator has altered `dial_usage_log`'s `TTL` to 200 days by hand and the service restarts
  with `SYNC_USAGE_LOG_TTL_DAYS` unset
- **THEN** its `TTL` is set back to `toDateTime(request_time) + INTERVAL 1 YEAR` and the change is logged
  at `WARN`

#### Scenario: An invalid retention fails startup

- **WHEN** the service starts with `SYNC_PAYLOAD_TTL_DAYS` or `SYNC_USAGE_LOG_TTL_DAYS` set to `0`, `-2`
  or `30d`
- **THEN** startup fails with an error naming that property (`sync.payload.ttl-days` or
  `sync.usage-log.ttl-days`), and no `ALTER` is issued

#### Scenario: A payload retention longer than the usage log's fails startup

- **WHEN** the service starts with `SYNC_USAGE_LOG_TTL_DAYS=60` and `SYNC_PAYLOAD_TTL_DAYS` unset (90), or
  with `SYNC_USAGE_LOG_TTL_DAYS` unset and `SYNC_PAYLOAD_TTL_DAYS=366` or `-1`
- **THEN** startup fails with an error naming both properties, and no `ALTER` is issued

#### Scenario: Equal retentions are accepted

- **WHEN** the service starts with `SYNC_USAGE_LOG_TTL_DAYS=60` and `SYNC_PAYLOAD_TTL_DAYS=60`, or with
  `SYNC_USAGE_LOG_TTL_DAYS` unset and `SYNC_PAYLOAD_TTL_DAYS=365`
- **THEN** startup proceeds

#### Scenario: Disabled migrations leave the retention alone

- **WHEN** the service starts with `CLICKHOUSE_MIGRATIONS_ENABLED=false`, `SYNC_PAYLOAD_TTL_DAYS=30` and
  `SYNC_USAGE_LOG_TTL_DAYS=180`
- **THEN** neither table's `TTL` is altered, while an invalid value such as `0`, or a payload retention
  longer than the usage-log one, still fails startup

#### Scenario: A missing payload table is skipped

- **WHEN** a configured table does not exist at startup
- **THEN** the service logs that its retention reconciliation was skipped, still reconciles the other
  table, and starts normally

#### Scenario: An expired payload leaves its parent row intact

- **WHEN** a day's payload partition expires and is dropped
- **THEN** the corresponding `dial_usage_log` rows remain, with every analytic column intact —
  including `request_body_bytes` and `response_body_bytes`, which outlive the bodies they measure

#### Scenario: Querying a body column past its retention yields null

- **WHEN** a query names a body column for an event whose payload partition has been dropped
- **THEN** the row is returned with that column `NULL`, and the query does not fail

#### Scenario: Querying a document column past its retention yields the empty document

- **WHEN** a query names `jwt_claims` or `request_tags` for an event whose payload partition has been
  dropped
- **THEN** the row is returned with that column `{}`, the same value a request with no claims or headers
  carries, and the query does not fail
