## ADDED Requirements

### Requirement: Sensitive columns can be excluded from ingest

The service SHALL accept an ingest setting `sync.sensitive-columns-enabled` (`SYNC_SENSITIVE_COLUMNS_ENABLED`), a
boolean with default `true`.

- **Under `true`,** ingest SHALL behave exactly as it would without the setting, and the catalog SHALL NOT be read
  for it.
- **Under `false`,** each ingest target SHALL leave out of every row it writes each column its mapping declares
  that the catalog flags `sensitive` on that target's table. The catalog is matched on the column's physical name,
  `column_mapping.source_name`. A left-out column SHALL take its ClickHouse default on the stored row: `NULL` for a
  `Nullable` column, `{}` for a `JSON` column, and the type's zero value otherwise. The service SHALL NOT read,
  transform, rewrite or count that column's source value for the row. Every other mapped column, the row itself and
  the target's emit predicate SHALL be unaffected. A record SHALL still produce a row in every target it produced
  one in before, so the all-or-none rule (see "A record is written for all of its targets or for none") and the
  grain join between `dial_usage_log` and `dial_usage_log_payload` hold.

The excluded set SHALL be resolved once per target at startup, from the catalog as it stands after the catalog
migrations have run. A column flagged `sensitive` by a later migration is therefore excluded without a code or
mapping change once the service restarts. With the seeded catalog this excludes `jwt_claims`, `request_tags`,
`request_body`, `response_body`, `assembled_response` and `request_cache_control` from `dial_usage_log_payload`,
and `comment` from `rate_analytics`. `dial_usage_log` has no sensitive column and is written as before.

Under `false`, startup SHALL fail, naming the target table and the column, when a column the catalog flags
`sensitive` is one the ingest cannot do without for that target:

- the deduplication-key columns `trace_id`, `core_span_id` and `request_time` (see "Records without a
  deduplication key are dropped");
- the table's grain key, identity column, ordering-key columns, partition column, version column or TTL column as
  the catalog's table definition records them.

Leaving one of these out would drop every row, collapse rows that do not belong together, or put rows in the epoch
partition where retention drops them.

Under `false`, startup SHALL also fail, naming the table, when a target's table has no catalog definition: its
sensitivity cannot be known, and writing it as mapped would store exactly what the setting is meant to keep out.
The operator defines the table in the catalog or points the target at a table the catalog defines.

Under `false`, startup SHALL log, at `INFO`, each target table with the columns excluded from it.

The setting SHALL apply only to rows written while it is in effect. It SHALL NOT change rows already stored, the
catalog's `sensitive` flags, read-side masking, or the physical table schema. Switching it back to `true` SHALL
make new rows carry the columns again. Rows written while it was `false` keep their defaults. A record
re-ingested later is written under the setting in effect at that time, and the row it writes replaces the earlier
one as any re-ingested row does (see "Primary-key deduplication"). Under `delta` this includes a parent: a
parent re-ingested under `false` is rewritten with no body and no path, so a delta child stored from another blob
and not re-ingested with it names a path no row carries and can no longer be rebuilt, as for a parent re-ingested
under `whole` (see "The request body storage mode is configurable").

#### Scenario: The default writes sensitive columns

- **WHEN** the service starts without `SYNC_SENSITIVE_COLUMNS_ENABLED` and ingests a chat completion record
  carrying claims, headers, a request body and a response body
- **THEN** the `dial_usage_log_payload` row carries `jwt_claims`, `request_tags`, `request_body`, `response_body`
  and `assembled_response` as captured today

#### Scenario: Disabled leaves the payload content columns at their defaults

- **WHEN** the service runs with `SYNC_SENSITIVE_COLUMNS_ENABLED=false` and ingests the same record
- **THEN** the `dial_usage_log_payload` row exists with the same `event_id`, `request_time`, `trace_id`,
  `core_span_id`, `project_id` and `user_hash` it would otherwise carry, `request_body`, `response_body`,
  `assembled_response` and `request_cache_control` are `NULL`, and `jwt_claims` and `request_tags` are `{}`

#### Scenario: Disabled keeps the usage-log row whole

- **WHEN** the service runs with `SYNC_SENSITIVE_COLUMNS_ENABLED=false` and ingests a record
- **THEN** its `dial_usage_log` row carries every column it would carry under `true`, and a query joining the
  payload table on `event_id` still finds the payload row

#### Scenario: Disabled leaves the rating comment at its default

- **WHEN** the service runs with `SYNC_SENSITIVE_COLUMNS_ENABLED=false` and ingests a `/rate` record with a
  comment
- **THEN** the `rate_analytics` row is written with its `rate`, `rate_raw` and `event_id`, and `comment` is `NULL`

#### Scenario: The excluded set follows the catalog flag

- **WHEN** a catalog migration flags a further mapped column of an ingest target `sensitive` and the service
  restarts with `SYNC_SENSITIVE_COLUMNS_ENABLED=false`
- **THEN** that column is left out of the target's new rows, and the startup log lists it among the target's
  excluded columns

#### Scenario: A sensitive key column fails startup

- **WHEN** the service starts with `SYNC_SENSITIVE_COLUMNS_ENABLED=false` and the catalog flags `trace_id`, or
  the target table's grain key, `sensitive` on an ingest target's table
- **THEN** startup fails, naming the table and the column

#### Scenario: A target without a catalog definition fails startup

- **WHEN** the service starts with `SYNC_SENSITIVE_COLUMNS_ENABLED=false` and a configured target names a table
  the catalog does not define
- **THEN** startup fails, naming the table

#### Scenario: Rows stored before the switch are unchanged

- **WHEN** rows were ingested under `true` and the service restarts under `false`
- **THEN** the rows already stored still carry their sensitive values, and only rows written after the restart
  carry the defaults

## MODIFIED Requirements

### Requirement: The request body storage mode is configurable

The service SHALL accept an ingest setting `sync.request-body-mode` (`SYNC_REQUEST_BODY_MODE`), set to
`whole`, `whole-with-path` or `delta`, with default `whole`. Any other value SHALL fail startup.

- **Under `whole`,** `request_body` SHALL be stored as it is today, and no request body SHALL be parsed for this
  setting: every request-delta column except the identity columns SHALL keep its default. A deployment that never
  opts in pays nothing for request-delta storage on its ingest path.
- **Under `whole-with-path`,** `request_body` SHALL be stored as under `whole`, and a chat request's row SHALL also
  carry its `request_hash`, so that a row stored whole can be a parent (see "Rebuild equivalence"); every other
  request-delta column except the identity columns SHALL keep its default.
- **Under `delta`,** a chat request body (see "A chat request body is stored as its new history and a parent
  link") SHALL be stored in `request_body` cut to what its parent does not supply, and every other body SHALL be
  stored whole (see "A request body that is not a chat request is stored whole").

The setting SHALL apply to rows written after it takes effect only; it SHALL NOT rewrite, convert or
re-read rows already stored. Switching from `delta` to either whole mode SHALL leave rows already written in
delta form as they are. Rolling back from `delta` SHALL go to `whole-with-path`: a parent re-ingested under it keeps
its path, so its delta children stay rebuildable. A parent re-ingested under `whole` loses its path, and a delta
child not re-ingested with it then names a path no row carries; avoiding that is the operator's, as for the other
re-ingest cases under "Rebuild equivalence". Switching from `whole-with-path` to `delta` SHALL let new requests link
to rows stored before the switch; rows stored under `whole` carry no path and are never parents.

The mode SHALL apply after the inline-file rewrite (see "Inline files are removed from payload bodies in
drop mode"), so a file removed by that rewrite is absent from the stored body.

The mode SHALL apply to an ingest target whose mapping maps `request_body`, `project_id` and `user_hash` and
whose table has the request-delta columns. The table SHALL be checked once, on the target's first flush, whether or
not startup schema validation is on: a body cut on a table that lacks the chain columns could never be rebuilt, and
validation off is an ordinary setting, not a sign that no table exists. A target whose table lacks the columns SHALL
have its bodies stored as mapped, with a warning. A check that fails SHALL fail that flush before any body is cut,
like any other ClickHouse error. Under `delta` with no target qualifying, startup SHALL log a warning that the
setting has no effect.

A target's mapping is taken here after sensitive-column exclusion (see "Sensitive columns can be excluded from
ingest"). A target whose `request_body` is excluded SHALL therefore not qualify, and SHALL get no request-delta
processing: no body is parsed, and its rows keep every request-delta column except the identity columns at its
default, `request_cache_control` included. Under `whole-with-path` or `delta` with no target qualifying while the
switch is `false`, startup SHALL log one warning that the mode has no effect, naming the switch, in place of the
`delta` warning above, whose advice to check the mappings would mislead.

#### Scenario: The default stores request bodies whole

- **WHEN** the service starts without `SYNC_REQUEST_BODY_MODE` and ingests a chat request
- **THEN** the payload row carries the body as received in `request_body`, an all-zero `request_hash` and
  `request_parent_hash`, an empty `request_ancestor_hashes`, and a `NULL` `request_cache_control`

#### Scenario: whole-with-path stamps a chat request with its path

- **WHEN** the service runs with `SYNC_REQUEST_BODY_MODE=whole-with-path` and ingests a chat request
- **THEN** the payload row carries the body as received in `request_body` and its `request_hash`,
  an all-zero `request_parent_hash`, an empty `request_ancestor_hashes`, and a `NULL` `request_cache_control`

#### Scenario: The table is checked with schema validation off

- **WHEN** the service runs under `delta` with `sync.validate-schema=false` and the payload table lacks the
  request-delta columns
- **THEN** its rows carry `request_body` as mapped and a warning names the table

#### Scenario: A table without the request-delta columns keeps its bodies

- **WHEN** a custom target maps `request_body`, `project_id` and `user_hash` onto a table without the
  request-delta columns, under `delta`
- **THEN** its rows carry `request_body` as mapped and a warning names the table

#### Scenario: An unknown mode fails startup

- **WHEN** the service starts with `SYNC_REQUEST_BODY_MODE=compact`
- **THEN** startup fails, naming the setting and the rejected value

#### Scenario: Switching back to a whole mode leaves delta rows unchanged

- **WHEN** rows were ingested under `delta` and the service is restarted under `whole` or `whole-with-path`
- **THEN** new rows carry their whole body, and the rows written under `delta` still carry their cut
  `request_body` and their chain columns

#### Scenario: A parent re-ingested under whole-with-path keeps its child rebuildable

- **WHEN** A and its child B were stored under `delta`, the service switches to `whole-with-path`, and A's event is
  re-ingested
- **THEN** the surviving A row holds its body whole and carries A's path, and B still rebuilds to its history
  through it

#### Scenario: A switch to delta links onto rows stored with their path

- **WHEN** request A was stored under `whole-with-path`, the service switches to `delta`, and A's follow-up B is
  ingested
- **THEN** B's parent is A's path, and B rebuilds through A's whole body

#### Scenario: An excluded request body gets no request-delta processing

- **WHEN** the service starts with `SYNC_SENSITIVE_COLUMNS_ENABLED=false` and `SYNC_REQUEST_BODY_MODE=delta`, and
  ingests a chat request
- **THEN** startup logs one warning that the mode has no effect, naming `sync.sensitive-columns-enabled`, and the
  payload row carries a `NULL` `request_body` and `request_cache_control`, an all-zero `request_hash` and
  `request_parent_hash`, and an empty `request_ancestor_hashes`
