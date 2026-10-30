## MODIFIED Requirements

### Requirement: The payload table's retention is shorter than its parent's

`dial_usage_log_payload` SHALL be provisioned with `TTL toDateTime(request_time) + INTERVAL 90 DAY` together
with `ttl_only_drop_parts = 1`, while `dial_usage_log` retains its one-year `TTL`. The provisioned retention
SHALL be declared in the table's own `CREATE TABLE` so that Flyway and an out-of-band operator provision it
identically.

The payload retention SHALL be configurable through the environment property `SYNC_PAYLOAD_TTL_DAYS`
(`sync.payload.ttl-days`), default `90`:

- a value of `1` or more SHALL mean `TTL toDateTime(request_time) + INTERVAL <value> DAY`;
- `-1` SHALL mean the table carries no `TTL`, so payload rows are kept until removed by other means;
- `0`, any value below `-1`, and any value that is not a whole number SHALL fail startup, naming the
  property, before the service accepts requests.

At startup, after the ClickHouse migrations have run and before the service accepts requests, the service
SHALL compare the payload table's live `TTL` with the configured retention and, when they differ, alter the
table so its `TTL` equals the configured one — `MODIFY TTL` for a number of days, `REMOVE TTL` for `-1`. A
live `TTL` in any other shape (another column, another unit, several rules) SHALL count as differing and be
replaced. A matching `TTL` SHALL be left untouched and no `ALTER` issued. Each alteration SHALL be logged at
`WARN`, stating the table, the previous retention and the new one. The configured value SHALL be the single
source of truth: a `TTL` changed by hand SHALL be reverted to it on the next start.

The alteration SHALL NOT rewrite the table's existing parts and SHALL need no free-disk headroom beyond the
table's metadata. A shortened retention SHALL apply to rows already stored: a day partition whose newest row
is past the new limit SHALL be dropped whole, exactly as an expiry under the old retention would have been.
`ttl_only_drop_parts = 1` SHALL be unaffected by the alteration.

The reconciliation SHALL run only when the service runs the ClickHouse migrations
(`CLICKHOUSE_MIGRATIONS_ENABLED=true`). When migrations are disabled the service SHALL NOT alter the payload
table and SHALL NOT apply `SYNC_PAYLOAD_TTL_DAYS`, which is still validated. When the configured payload table does not exist,
the service SHALL log that the reconciliation was skipped and start normally. A failed alteration SHALL fail
startup, as a failed migration does.

The payload retention SHALL NOT be a `/v1/tables` field, and the catalog SHALL NOT mirror it. It covers the
payload table only; `dial_usage_log`'s `TTL` SHALL remain migration-defined.

Expiry SHALL be quantized to a daily part exactly as the parent's is: a part is dropped once its newest
row has expired, so a payload row MAY outlive its `TTL` by at most the rest of its day.

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

#### Scenario: A configured retention replaces a differing one

- **WHEN** the service starts with `SYNC_PAYLOAD_TTL_DAYS=30` and the payload table carries a 90-day `TTL`
- **THEN** the table's `TTL` becomes `toDateTime(request_time) + INTERVAL 30 DAY`, `ttl_only_drop_parts = 1`
  is still set, and a `WARN` log names the table, the 90-day retention and the 30-day one

#### Scenario: Shortening the retention drops stored partitions past the new limit

- **WHEN** the payload table holds a day partition older than 30 days but newer than 90, and the service
  starts with `SYNC_PAYLOAD_TTL_DAYS=30`
- **THEN** that partition is dropped whole once ClickHouse applies the new `TTL`, and the table's other
  parts are not rewritten

#### Scenario: Minus one removes the retention

- **WHEN** the service starts with `SYNC_PAYLOAD_TTL_DAYS=-1` and the payload table carries a `TTL`
- **THEN** the table carries no `TTL` afterwards, and a `WARN` log names the removed retention

#### Scenario: A retention set by hand is reverted

- **WHEN** an operator has altered the payload table's `TTL` to 45 days by hand and the service restarts
  with `SYNC_PAYLOAD_TTL_DAYS` unset
- **THEN** the table's `TTL` is set back to 90 days and the change is logged at `WARN`

#### Scenario: An invalid retention fails startup

- **WHEN** the service starts with `SYNC_PAYLOAD_TTL_DAYS` set to `0`, `-2` or `30d`
- **THEN** startup fails with an error naming `sync.payload.ttl-days`, and no `ALTER` is issued

#### Scenario: Disabled migrations leave the retention alone

- **WHEN** the service starts with `CLICKHOUSE_MIGRATIONS_ENABLED=false` and `SYNC_PAYLOAD_TTL_DAYS=30`
- **THEN** the payload table's `TTL` is not altered, while an invalid value such as `0` still fails startup

#### Scenario: A missing payload table is skipped

- **WHEN** the configured payload table does not exist at startup
- **THEN** the service logs that the retention reconciliation was skipped and starts normally

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
