## REMOVED Requirements

### Requirement: The payload table's retention does not exceed its parent's

**Reason**: An unset retention property no longer means "the provisioned default". It means "leave the
live `TTL` alone", so the scenarios that revert a hand-set `TTL` with the property unset no longer hold, and
the bound is judged on effective retentions. Replaced by "The ingest tables' retention follows only
explicitly set properties".

**Migration**: Set `SYNC_USAGE_LOG_TTL_DAYS` or `SYNC_PAYLOAD_TTL_DAYS` to apply a retention. `90`
restores the payload table's provisioned `TTL`; no day count equals the usage log's provisioned
`INTERVAL 1 YEAR`, and `365` is the nearest. Removing a property keeps the `TTL` it last applied.

## ADDED Requirements

### Requirement: The ingest tables' retention follows only explicitly set properties

`dial_usage_log_payload` SHALL be provisioned with `TTL toDateTime(request_time) + INTERVAL 90 DAY` together
with `ttl_only_drop_parts = 1`, and `dial_usage_log` with `TTL toDateTime(request_time) + INTERVAL 1 YEAR`
together with `ttl_only_drop_parts = 1`. Each provisioned retention SHALL be declared by the table's own
migrations so that Flyway and an out-of-band operator provision it identically.

Each retention SHALL be changeable through an environment property, both unset by default:

- `SYNC_USAGE_LOG_TTL_DAYS` (`sync.usage-log.ttl-days`) for `dial_usage_log`;
- `SYNC_PAYLOAD_TTL_DAYS` (`sync.payload.ttl-days`) for `dial_usage_log_payload`.

For either property:

- unset SHALL mean the service leaves the table's live `TTL` as it is, whatever it is — provisioned, set by
  an earlier start, or changed by hand. Removing a property SHALL keep the `TTL` it last applied, and SHALL
  NOT restore the provisioned retention;
- a value of `1` or more SHALL mean `TTL toDateTime(request_time) + INTERVAL <value> DAY`;
- `-1` SHALL mean the table carries no `TTL`, so its rows are kept until removed by other means;
- `0`, any value below `-1`, and any value that is not a whole number SHALL fail startup, naming the
  property, before the service accepts requests.

At startup, after the ClickHouse migrations have run and before the service accepts requests, the service
SHALL compare the live `TTL` of each table whose property is set with that value and, when they differ,
alter the table so its `TTL` equals it — `MODIFY TTL` for a number of days, `REMOVE TTL` for `-1`. A live
`TTL` in any other shape (another column, another unit or interval, several rules) SHALL count as differing
and be replaced; in particular `INTERVAL 365 DAY` and `INTERVAL 1 YEAR` are different retentions. A
matching `TTL` SHALL be left untouched and no `ALTER` issued. Each alteration SHALL be logged at `WARN`,
stating the table, the previous retention and the new one. For a table whose property is unset, the service
SHALL log its live `TTL` at `INFO` and issue no statement.

The alteration SHALL NOT rewrite the table's existing parts and SHALL need no free-disk headroom beyond the
table's metadata. A shortened retention SHALL apply to rows already stored: a day partition whose newest row
is past the new limit SHALL be dropped whole, exactly as an expiry under the old retention would have been.
`ttl_only_drop_parts = 1` SHALL be unaffected by the alteration.

The payload retention SHALL NOT exceed the usage-log retention, because a payload row is reached only
through its `dial_usage_log` row and one that outlives it is kept with no way to read it alongside its
event. The bound SHALL be judged on each table's effective retention: its set value, or else its live
`TTL`. `-1`, or a live table without a `TTL`, SHALL count as unlimited. A live `TTL` SHALL be read in whole
days as the widest reading for the payload and the narrowest for the usage log: a `DAY` or `WEEK` interval
exactly, a `MONTH` as 28 to 31 days per month, a `YEAR` as 365 to 366 days per year, and an `HOUR` interval
rounded up to whole days for the payload and down for the usage log. Two `TTL`s in the same unit SHALL instead
compare their intervals directly. Equal retentions SHALL be accepted. A
payload retention longer than the usage-log retention SHALL fail startup, naming both properties, before any
table is altered:

- when both properties are set, from their values alone, before the service accepts requests and whatever
  the migrations setting;
- when exactly one is set, during the startup reconciliation, against the other table's live `TTL`;
- when neither is set, the service alters nothing and SHALL NOT fail startup; a live payload `TTL` longer
  than the live usage-log `TTL` SHALL be logged at `WARN`.

When at least one property is unset and either table does not exist, the bound against the live `TTL` SHALL be
skipped, since no payload row can then outlive an event row. When the unset side's live `TTL` has a shape the service cannot read as a single interval over
`request_time`, the bound SHALL be skipped with a `WARN`. In both cases the set side is still reconciled.

When both tables are altered, the payload table SHALL be altered first if the usage-log retention is being
shortened (or its live `TTL` cannot be read), and second otherwise, so that a failed second `ALTER` never
leaves payload rows outliving their events.

The reconciliation SHALL run only when the service runs the ClickHouse migrations
(`CLICKHOUSE_MIGRATIONS_ENABLED=true`). When migrations are disabled the service SHALL NOT alter either
table and SHALL NOT apply either property; each set value is still validated, and two set values are
still checked against each other. When a table does not exist, the service SHALL log that its
reconciliation was skipped and start normally. A failed alteration SHALL fail startup, as a failed migration
does. The tables reconciled are the ones the ingest targets write: `SYNC_TABLE` names the usage log,
`SYNC_PAYLOAD_TABLE` the payload table.

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

#### Scenario: Provisioned tables declare their retention

- **WHEN** the ingest tables are provisioned (by Flyway or by an operator applying the migrations in
  version order)
- **THEN** `dial_usage_log_payload` carries `TTL toDateTime(request_time) + INTERVAL 90 DAY` and
  `dial_usage_log` carries `TTL toDateTime(request_time) + INTERVAL 1 YEAR`, each with
  `ttl_only_drop_parts = 1`

#### Scenario: Unset properties leave both tables untouched

- **WHEN** the service starts with `SYNC_USAGE_LOG_TTL_DAYS` and `SYNC_PAYLOAD_TTL_DAYS` unset
- **THEN** no `ALTER` is issued, each table keeps its `TTL`, and each live `TTL` is logged at `INFO`

#### Scenario: A hand-set retention survives with the property unset

- **WHEN** an operator has altered `dial_usage_log`'s `TTL` to 500 days by hand and the service restarts
  with `SYNC_USAGE_LOG_TTL_DAYS` unset
- **THEN** the table still carries the 500-day `TTL` and no `ALTER` is issued

#### Scenario: Removing a property keeps the retention it applied

- **WHEN** the service has applied `SYNC_PAYLOAD_TTL_DAYS=30`, and restarts with the property removed
- **THEN** the payload table still carries `TTL toDateTime(request_time) + INTERVAL 30 DAY`

#### Scenario: A set retention replaces a differing one

- **WHEN** the service starts with `SYNC_USAGE_LOG_TTL_DAYS=180` and `SYNC_PAYLOAD_TTL_DAYS=30` while the
  tables carry one year and 90 days
- **THEN** their `TTL`s become `INTERVAL 180 DAY` and `INTERVAL 30 DAY`, `ttl_only_drop_parts = 1` is still
  set on both, and a `WARN` log names each table with its previous and new retention

#### Scenario: A set retention reverts a hand-set one

- **WHEN** an operator has altered the payload table's `TTL` to 45 days by hand and the service restarts
  with `SYNC_PAYLOAD_TTL_DAYS=90`
- **THEN** the table's `TTL` is set back to 90 days and the change is logged at `WARN`

#### Scenario: 365 days is not the provisioned year

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

#### Scenario: An invalid retention fails startup

- **WHEN** the service starts with `SYNC_PAYLOAD_TTL_DAYS` or `SYNC_USAGE_LOG_TTL_DAYS` set to `0`, `-2`
  or `30d`
- **THEN** startup fails with an error naming that property, and no `ALTER` is issued

#### Scenario: Two set retentions with the payload longer fail startup

- **WHEN** the service starts with `SYNC_USAGE_LOG_TTL_DAYS=60` and `SYNC_PAYLOAD_TTL_DAYS=90`, or with
  `SYNC_USAGE_LOG_TTL_DAYS=500` and `SYNC_PAYLOAD_TTL_DAYS=-1`, with migrations enabled or disabled
- **THEN** startup fails with an error naming both properties, and no `ALTER` is issued

#### Scenario: A set retention is bounded by the other table's live TTL

- **WHEN** the service starts with `SYNC_USAGE_LOG_TTL_DAYS=60`, `SYNC_PAYLOAD_TTL_DAYS` unset, and the
  payload table carries 90 days; or with `SYNC_PAYLOAD_TTL_DAYS=366`, `SYNC_USAGE_LOG_TTL_DAYS` unset, and
  `dial_usage_log` carries `INTERVAL 1 YEAR`
- **THEN** startup fails with an error naming both properties, and no `ALTER` is issued on either table

#### Scenario: Equal retentions are accepted

- **WHEN** the service starts with `SYNC_USAGE_LOG_TTL_DAYS=60` and `SYNC_PAYLOAD_TTL_DAYS=60`, or with
  `SYNC_PAYLOAD_TTL_DAYS=365`, `SYNC_USAGE_LOG_TTL_DAYS` unset and `dial_usage_log` carrying
  `INTERVAL 1 YEAR`
- **THEN** startup proceeds

#### Scenario: A shrinking usage log is altered after the payload

- **WHEN** the service starts with `SYNC_USAGE_LOG_TTL_DAYS=30` and `SYNC_PAYLOAD_TTL_DAYS=30` while the
  tables carry one year and 90 days, and the payload `ALTER` is refused
- **THEN** startup fails and `dial_usage_log` still carries its one-year `TTL`

#### Scenario: Live TTLs out of bound with both properties unset are only reported

- **WHEN** both properties are unset and the payload table's live `TTL` is longer than `dial_usage_log`'s
- **THEN** the service logs a `WARN` naming both tables, alters nothing and starts normally

#### Scenario: Disabled migrations leave the retention alone

- **WHEN** the service starts with `CLICKHOUSE_MIGRATIONS_ENABLED=false`, `SYNC_PAYLOAD_TTL_DAYS=30` and
  `SYNC_USAGE_LOG_TTL_DAYS=180`
- **THEN** neither table's `TTL` is altered, while an invalid value such as `0`, or two set values with the
  payload longer, still fail startup

#### Scenario: A missing table is skipped

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
