## MODIFIED Requirements

### Requirement: An aligned enrichment inherits its source's TTL

An aligned enrichment that declares no `ttl` SHALL be created with its source's physical TTL clause and its
`ttl_only_drop_parts` setting, both read from the source's ClickHouse table (for `dial_usage_log`, its
configured retention — one year by default, see the data-sync capability — with `ttl_only_drop_parts = 1`).
The clause applies unchanged because the alignment column has the source partition column's name and type.
It is read when the enrichment is created: a later change of the source's retention SHALL NOT by itself
re-align an existing enrichment's TTL. An explicitly declared `ttl` SHALL override the inherited one. An
inherited TTL SHALL NOT be mirrored into the catalog, so `GET /v1/tables/{name}` SHALL omit `ttl` for it,
as it does for the seeded tables' migration-managed TTLs. Every read of an enrichment starts from its
source, so a row whose source has expired is unreadable; inheriting the TTL removes only rows that can never
be read again.

Only the single-rule shape the server renders (`TTL <column> + toInterval<Unit>(<n>)`, optionally wrapped in
`toDateTime`) over the alignment column is inherited; a source TTL with several rules, a `WHERE`, or over another
column is not, and the enrichment is then created without one.

Because an inherited TTL is not in the catalog, `PATCH /v1/tables/{name}/schema` with `"ttl": null` SHALL NOT
remove it: with no catalog TTL the removal is the same no-op it is for any table without one, and no
`REMOVE TTL` is issued. Declaring a `ttl` replaces the inherited clause (`MODIFY TTL`); the inherited
`ttl_only_drop_parts` setting stays in either case.

#### Scenario: An aligned enrichment carries its source's TTL

- **GIVEN** an eligible enrichment of `dial_usage_log` defined without `ttl`, while `dial_usage_log` keeps
  its default one-year retention
- **WHEN** its ClickHouse table is inspected
- **THEN** it carries `TTL toDateTime(request_time) + INTERVAL 1 YEAR` and `ttl_only_drop_parts = 1`, and
  `GET /v1/tables/{name}` omits `ttl`

#### Scenario: A declared TTL overrides the inherited one

- **WHEN** an eligible enrichment is defined with `ttl` `{column: request_time, interval: 90, unit: day}`
- **THEN** the table's TTL is 90 days

#### Scenario: An existing enrichment keeps the TTL it inherited

- **GIVEN** an aligned enrichment of `dial_usage_log` created while the source carried its one-year `TTL`
- **WHEN** the service restarts with `SYNC_USAGE_LOG_TTL_DAYS=180`
- **THEN** `dial_usage_log`'s `TTL` becomes 180 days while the enrichment still carries one year, and an
  aligned enrichment created afterwards inherits the 180 days

### Requirement: Table TTL

The system SHALL support an optional per-table **TTL** (row expiry) on materialized tables,
declared as a structured object `{column, interval, unit}` — never a free-form ClickHouse
expression. `column` SHALL name a declared **non-nullable** column of the table of type `date` or
`timestamp`; `interval` SHALL be a positive integer; `unit` SHALL be one of `hour`, `day`, `week`,
`month`, `year`. The TTL SHALL render in ClickHouse DDL as
`TTL <source_name> + INTERVAL <interval> <UNIT>` for a `date` column and
`TTL toDateTime(<source_name>) + INTERVAL <interval> <UNIT>` for a `timestamp` column (ClickHouse
requires the TTL expression to be a non-nullable `Date`/`DateTime`; a bare `DateTime64` or a
nullable column is rejected server-side, so the system SHALL reject both at validation time). A
violation of any of these semantic constraints — an unknown, nullable, or non-time `column`, or a
zero/negative `interval` — SHALL be rejected with HTTP 422 and the `ErrorView` contract, before
any DDL is issued or anything is persisted. A `unit` value outside the enumerated set fails
request binding and SHALL be rejected with HTTP 400 `bad_request`, the codebase-wide
malformed-body convention (the same as any other unbindable enum member, e.g. a `partition_by`
granularity).

The TTL SHALL be declared and modified only through the schema endpoints: the optional `ttl`
member of `POST /v1/tables/{name}/schema` (declared at materialization, rendered into the
`CREATE TABLE`) and the optional `ttl` member of `PATCH /v1/tables/{name}/schema` (set, replace,
or remove on a live table — see "Modify table schema via PATCH"). `POST /v1/tables` (create)
SHALL reject a supplied `ttl` with HTTP 422 like every other physical-schema field. No dedicated
TTL endpoint SHALL exist.

The TTL SHALL be catalog metadata as well as physical DDL: it SHALL be persisted on the table's
`table_definition` row (nullable — unset means no TTL; stored either fully or not at all) and
returned as a `ttl` object by `GET /v1/tables/{name}`; consistent with `NON_NULL` serialization,
an unset TTL SHALL be omitted from responses. The seeded `system` tables' TTLs
SHALL NOT be mirrored into the catalog, and the API SHALL NOT modify them: they are provisioned by
the migrations, and `dial_usage_log`'s and `dial_usage_log_payload`'s are set by the
`SYNC_USAGE_LOG_TTL_DAYS` and `SYNC_PAYLOAD_TTL_DAYS` properties (see the data-sync capability).

TTL-based row expiry is applied by ClickHouse lazily (on merges), so rows older than the TTL MAY
remain visible for some time after a TTL change — the system SHALL NOT force immediate
re-materialization.

#### Scenario: GET returns the TTL

- **WHEN** `GET /v1/tables/{name}` is called for a table with a TTL set
- **THEN** the response carries the `ttl` object `{column, interval, unit}`; a table without a
  TTL omits the member

#### Scenario: TTL column must be a non-nullable date or timestamp column

- **WHEN** a TTL declaration (via `POST .../schema` or `PATCH .../schema`) names a column that is
  not a declared column of the table, is nullable, or is not of type `date` or `timestamp`
- **THEN** the request is rejected with HTTP 422 and nothing is persisted or issued

#### Scenario: Non-positive interval is rejected

- **WHEN** a TTL declaration carries a zero or negative `interval`
- **THEN** the request is rejected with HTTP 422 and nothing is persisted or issued

#### Scenario: Unknown unit fails request binding

- **WHEN** a TTL declaration carries a `unit` outside {`hour`, `day`, `week`, `month`, `year`}
- **THEN** the request is rejected with HTTP 400 `bad_request` (the value does not bind to the
  unit enum, the malformed-body convention)

#### Scenario: TTL at create is rejected

- **WHEN** `POST /v1/tables` is called with a body carrying a `ttl` member
- **THEN** the request is rejected with HTTP 422 (the TTL is declared via the schema endpoints)

#### Scenario: Timestamp TTL column is wrapped in the rendered DDL

- **WHEN** a TTL over a `timestamp` column is declared (at materialization or via PATCH)
- **THEN** the rendered DDL uses `toDateTime(<column>) + INTERVAL <n> <UNIT>`, while a `date`
  column is used bare
