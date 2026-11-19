## MODIFIED Requirements

### Requirement: Sensitive columns can be excluded from ingest

The service SHALL accept an ingest setting `sync.sensitive-columns` (`SYNC_SENSITIVE_COLUMNS`): a comma-separated
list naming the columns the catalog flags `sensitive` that ingest writes. Its default is `*`.
Whitespace around an entry SHALL be ignored, as SHALL a blank entry and a repeated one. The setting takes one of
three forms:

- **`*`:** every sensitive column is written. Ingest SHALL behave exactly as it would without the
  setting, and the catalog SHALL NOT be read for it.
- **`none`, or an empty value:** no sensitive column is written.
- **A list of column entries:** only the sensitive columns the entries name are written. A bare entry `column`
  SHALL name that column on every ingest target table. An entry `table.column` SHALL name it on that table only.
  Table and column names SHALL be matched exactly, case-sensitive, against the target's configured table and the
  column's physical name, `column_mapping.source_name`.

Under any setting other than `*`, each ingest target SHALL leave out of every row it writes each column that its
mapping declares, or that ingest writes beyond the mapping, when the catalog flags that column `sensitive` on the
target's table and no entry names it there. A left-out column SHALL take its ClickHouse default on the stored row:
`NULL` for a `Nullable` column, `{}` for a `JSON` column, and the type's zero value otherwise. The service SHALL NOT
read, transform, rewrite or count that column's source value for the row. A sensitive column that an entry names,
every column the catalog does not flag `sensitive`, the row itself and the target's emit predicate SHALL be
unaffected. A record SHALL still produce a row in every target it produced one in before, so the all-or-none rule
(see "A record is written for all of its targets or for none") and the grain join between `dial_usage_log` and
`dial_usage_log_payload` hold.

The excluded set SHALL be resolved once per target at startup, from the catalog as it stands after the catalog
migrations have run. A column flagged `sensitive` by a later migration is therefore excluded, unless an entry names
it, without a code or mapping change once the service restarts. With the seeded catalog, `none` excludes
`jwt_claims`, `request_tags`, `request_body`, `response_body`, `assembled_response` and `request_cache_control` from
`dial_usage_log_payload`, and `comment` from `rate_analytics`. `dial_usage_log` has no sensitive column and is
written as before.

Startup SHALL fail, naming the setting and the offending entry, when:

- `*` or `none` appears together with any other entry;
- an entry has more than one `.`, or an empty table or column part (`.comment`, `rate_analytics.`);
- a bare entry names no column the catalog flags `sensitive` on any ingest target table;
- a qualified entry names a table that is not an ingest target, or a column the catalog does not flag
  `sensitive` on that table.

An entry that matches nothing is a typo or a column that is not sensitive. Starting anyway would silently leave
out a column the operator meant to keep.

The boolean setting this one replaces, `sync.sensitive-columns-enabled` (`SYNC_SENSITIVE_COLUMNS_ENABLED`), SHALL
NOT be honoured. When it is set to any value, startup SHALL fail, naming it and `sync.sensitive-columns`. Ignoring
it instead would make a deployment that set it to `false` write every sensitive column again after an upgrade.

Under any setting other than `*`, startup SHALL fail, naming the target table and the column, when a column the
setting leaves out is one the ingest cannot do without for that target:

- the deduplication-key columns `trace_id`, `core_span_id` and `request_time` (see "Records without a
  deduplication key are dropped");
- the table's grain key, identity column, ordering-key columns, partition column, version column or TTL column as
  the catalog's table definition records them.

Leaving one of these out would drop every row, collapse rows that do not belong together, or put rows in the epoch
partition where retention drops them. A sensitive column of this kind that an entry names is written, so the
setting does not refuse it.

Under any setting other than `*`, startup SHALL also fail, naming the table, when a target's table has no catalog
definition: its sensitivity cannot be known, and writing it as mapped could store exactly what the setting is
meant to keep out. The operator defines the table in the catalog or points the target at a table the catalog
defines.

Under any setting other than `*`, startup SHALL log, at `INFO`, each target table with the sensitive columns
excluded from it and the sensitive columns written to it.

The setting SHALL apply only to rows written while it is in effect. It SHALL NOT change rows already stored, the
catalog's `sensitive` flags, read-side masking, or the physical table schema. When a column is written again after a
change of the setting, new rows SHALL carry it. Rows written while it was left out keep their defaults. A record
re-ingested later is written under the setting in effect at that time, and the row it writes replaces the earlier
one as any re-ingested row does (see "Primary-key deduplication"). Under `delta` this includes a parent: a
parent re-ingested with `request_body` left out is rewritten with no body and no path, so a delta child stored from
another blob and not re-ingested with it names a path no row carries and can no longer be rebuilt, as for a parent
re-ingested under `whole` (see "The request body storage mode is configurable").

#### Scenario: The default writes sensitive columns

- **WHEN** the service starts without `SYNC_SENSITIVE_COLUMNS` and ingests a chat completion record
  carrying claims, headers, a request body and a response body
- **THEN** the `dial_usage_log_payload` row carries `jwt_claims`, `request_tags`, `request_body`, `response_body`
  and `assembled_response` as captured today

#### Scenario: The removed boolean switch fails startup

- **WHEN** the service starts with `SYNC_SENSITIVE_COLUMNS_ENABLED=false`, or with
  `SYNC_SENSITIVE_COLUMNS_ENABLED=true`, whatever `SYNC_SENSITIVE_COLUMNS` holds
- **THEN** startup fails, naming `sync.sensitive-columns-enabled` and `sync.sensitive-columns`

#### Scenario: none leaves the payload content columns at their defaults

- **WHEN** the service runs with `SYNC_SENSITIVE_COLUMNS=none` and ingests the same record
- **THEN** the `dial_usage_log_payload` row exists with the same `event_id`, `request_time`, `trace_id`,
  `core_span_id`, `project_id` and `user_hash` it would otherwise carry, `request_body`, `response_body`,
  `assembled_response` and `request_cache_control` are `NULL`, and `jwt_claims` and `request_tags` are `{}`

#### Scenario: An empty value writes no sensitive column

- **WHEN** the service runs with `SYNC_SENSITIVE_COLUMNS` set to the empty string and ingests the same
  record
- **THEN** the rows are the ones `none` writes

#### Scenario: A list writes only the named sensitive columns

- **WHEN** the service runs with `SYNC_SENSITIVE_COLUMNS=request_tags,jwt_claims` and ingests the same
  record
- **THEN** the `dial_usage_log_payload` row carries `jwt_claims` and `request_tags` as captured today,
  `request_body`, `response_body`, `assembled_response` and `request_cache_control` are `NULL`, and the startup log
  lists `jwt_claims` and `request_tags` as written and the other four as excluded for that table

#### Scenario: A qualified entry applies to its table only

- **WHEN** the service runs with `SYNC_SENSITIVE_COLUMNS=rate_analytics.comment` and ingests a chat
  completion record and a `/rate` record with a comment
- **THEN** the `rate_analytics` row carries its `comment`, and the `dial_usage_log_payload` row has every
  sensitive column at its default, as under `none`

#### Scenario: none keeps the usage-log row whole

- **WHEN** the service runs with `SYNC_SENSITIVE_COLUMNS=none` and ingests a record
- **THEN** its `dial_usage_log` row carries every column it would carry under `*`, and a query joining the
  payload table on `event_id` still finds the payload row

#### Scenario: none leaves the rating comment at its default

- **WHEN** the service runs with `SYNC_SENSITIVE_COLUMNS=none` and ingests a `/rate` record with a
  comment
- **THEN** the `rate_analytics` row is written with its `rate`, `rate_raw` and `event_id`, and `comment` is `NULL`

#### Scenario: The excluded set follows the catalog flag

- **WHEN** a catalog migration flags a further mapped column of an ingest target `sensitive` and the service
  restarts with `SYNC_SENSITIVE_COLUMNS=request_tags`
- **THEN** that column is left out of the target's new rows, and the startup log lists it among the target's
  excluded columns

#### Scenario: An entry that matches no sensitive column fails startup

- **WHEN** the service starts with `SYNC_SENSITIVE_COLUMNS=request_tag`, or with
  `SYNC_SENSITIVE_COLUMNS=model`, a column the catalog does not flag `sensitive`
- **THEN** startup fails, naming `sync.sensitive-columns` and the entry

#### Scenario: A qualified entry on a table that is not a target fails startup

- **WHEN** the service starts with `SYNC_SENSITIVE_COLUMNS=sessions.comment` and no ingest target writes
  `sessions`
- **THEN** startup fails, naming `sync.sensitive-columns` and the entry

#### Scenario: A keyword mixed with column entries fails startup

- **WHEN** the service starts with `SYNC_SENSITIVE_COLUMNS=*,request_body` or
  `SYNC_SENSITIVE_COLUMNS=none,request_tags`
- **THEN** startup fails, naming `sync.sensitive-columns` and the keyword

#### Scenario: A malformed entry fails startup

- **WHEN** the service starts with `SYNC_SENSITIVE_COLUMNS=.comment`, `SYNC_SENSITIVE_COLUMNS=rate_analytics.` or
  `SYNC_SENSITIVE_COLUMNS=a.b.c`
- **THEN** startup fails, naming `sync.sensitive-columns` and the entry

#### Scenario: A sensitive key column fails startup

- **WHEN** the service starts with `SYNC_SENSITIVE_COLUMNS=none` and the catalog flags `trace_id`, or
  the target table's grain key, `sensitive` on an ingest target's table
- **THEN** startup fails, naming the table and the column

#### Scenario: A sensitive key column that an entry names is written

- **WHEN** the catalog flags `trace_id` `sensitive` on an ingest target's table and the service starts with
  `SYNC_SENSITIVE_COLUMNS=trace_id`
- **THEN** startup succeeds and the target's rows carry `trace_id`

#### Scenario: A target without a catalog definition fails startup

- **WHEN** the service starts with `SYNC_SENSITIVE_COLUMNS=none`, or with a list of column entries, and a
  configured target names a table the catalog does not define
- **THEN** startup fails, naming the table

#### Scenario: Rows stored before a change of the setting are unchanged

- **WHEN** rows were ingested under `*` and the service restarts under `none` or under a list
- **THEN** the rows already stored still carry their sensitive values, and only rows written after the restart
  carry the defaults in the columns now left out

### Requirement: The request body storage mode is configurable

The service SHALL accept an ingest setting `sync.request-body-mode` (`SYNC_REQUEST_BODY_MODE`), set to
`whole`, `whole-with-path` or `delta`, with default `whole`. Any other value SHALL fail startup.

- **Under `whole`,** `request_body` SHALL be stored as it is today, and no request body SHALL be parsed for this
  setting: every request-delta column except the identity columns SHALL keep its default. A deployment that never
  opts in pays nothing for request-delta storage on its ingest path.
- **Under `whole-with-path`,** `request_body` SHALL be stored as under `whole`, and a chat request's row SHALL also
  carry its `request_hash`, so that a row stored whole can be a parent (see "Rebuild equivalence"), and the row of a
  body eligible for a body hash SHALL carry that hash for the same reason (see "A large request body equal to an
  earlier one is stored once"); every other request-delta column except the identity columns SHALL keep its default.
- **Under `delta`,** a chat request body (see "A chat request body is stored as its new history and a parent
  link") SHALL be stored in `request_body` cut to what its parent does not supply, and every other body SHALL be
  stored whole (see "A request body that is not a chat request is stored whole"), except one that "A large
  request body equal to an earlier one is stored once" makes a copy.

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
default, `request_cache_control` included. Under `whole-with-path` or `delta` with no target qualifying while
`sync.sensitive-columns` leaves `request_body` out of at least one target, startup SHALL log one warning that
the mode has no effect, naming the setting, in place of the `delta` warning above, whose advice to check the mappings
would mislead.

Under `delta`, startup SHALL fail, naming the target table, `sync.sensitive-columns` and
`request_cache_control`, when a target qualifies while the setting leaves `request_cache_control` out of it. Under
`delta` a qualifying target moves a chat request's cache markers out of `request_body` into `request_cache_control`
(see "Cache markers are kept per event"). Leaving that column out would store a body whose rebuild has lost its
markers, and keeping the markers in the body would break rebuild equivalence. The operator either names
`request_cache_control` alongside `request_body`, leaves both out, or runs under `whole` or `whole-with-path`, which
keep `request_cache_control` at its default whatever the setting says.

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

- **WHEN** the service starts with `SYNC_SENSITIVE_COLUMNS=none` and `SYNC_REQUEST_BODY_MODE=delta`, and
  ingests a chat request
- **THEN** startup logs one warning that the mode has no effect, naming `sync.sensitive-columns`, and the
  payload row carries a `NULL` `request_body` and `request_cache_control`, an all-zero `request_hash` and
  `request_parent_hash`, and an empty `request_ancestor_hashes`

#### Scenario: A delta body without its cache markers fails startup

- **WHEN** the service starts with `SYNC_REQUEST_BODY_MODE=delta` and
  `SYNC_SENSITIVE_COLUMNS=request_body`
- **THEN** startup fails, naming `dial_usage_log_payload`, `sync.sensitive-columns` and
  `request_cache_control`

#### Scenario: A delta body with its cache markers is cut

- **WHEN** the service runs with `SYNC_REQUEST_BODY_MODE=delta` and
  `SYNC_SENSITIVE_COLUMNS=request_body,request_cache_control`, and ingests a follow-up chat request
- **THEN** its payload row carries the cut `request_body`, its chain columns and its `request_cache_control`, and
  `jwt_claims`, `request_tags`, `response_body` and `assembled_response` are at their defaults

#### Scenario: whole-with-path keeps a body without its cache-marker column

- **WHEN** the service runs with `SYNC_REQUEST_BODY_MODE=whole-with-path` and
  `SYNC_SENSITIVE_COLUMNS=request_body`, and ingests a chat request
- **THEN** startup succeeds, and the payload row carries the body as received, its `request_hash` and a `NULL`
  `request_cache_control`

### Requirement: A large request body equal to an earlier one is stored once

A request body SHALL be **eligible for a body hash** when it is not JSON (a body truncated at 4 MiB and a
multipart upload are such bodies), holds at least 65,536 UTF-16 code units as ingest is about to store it, that
is, after the inline-file rewrite, its row has a project or a user, and its `request_time` is a date and time
written without a UTC offset or with `Z`; a time written with a numeric offset, `+00:00` included, is not eligible. A body is JSON when it is one syntactically valid JSON value with nothing
but whitespace around it, however
deeply nested, however long its numbers and strings, and whether or not an object names a member twice. A body
nested too deeply for ingest to read to its end SHALL be treated as JSON. A client whose conversation has outgrown the 4 MiB body
limit sends the same cut text on every later turn, so such bodies repeat exactly. A body that is JSON SHALL NOT be
eligible, whatever its size and shape, a JSON object naming one member twice included: its top-level members stay
readable from `request_body` on every row. Its **body hash** SHALL be a 128-bit hash of the project, the user, the UTC day of
`request_time` and the body text exactly as ingest is about to store it, that is, after the inline-file rewrite.
The body hash SHALL be computed under a seed of its own, apart from the chat prefix hashes. A chat request's parent
is always a prefix hash of its own history, which no body hash equals, so a chat request never takes a carrier as
its parent.

Under `whole-with-path`, an eligible row SHALL carry its body hash in `request_hash` and its body whole.

Under `delta`, an eligible row SHALL be stored as one of:

- a **carrier**: `request_body` whole, `request_hash` = its body hash, every other request-delta column except
  the identity columns at its default;
- a **copy**: `request_body` the empty string, `request_parent_hash` = its body hash, `request_ancestor_hashes`
  holding that hash alone, an all-zero `request_hash` and a `NULL` `request_cache_control`.

A row SHALL be a copy only when a carrier of its body hash with a strictly earlier `request_time`, compared as
instants at millisecond precision, is in the same insert or stored in the payload table; otherwise it SHALL be a
carrier. Only a row holding the body therefore carries the hash, and the earliest request with a given body is a
carrier however often its event is re-ingested, so a re-ingested carrier never becomes a copy of itself. Two
requests with equal bodies and equal `request_time` SHALL both be carriers. A stored carrier SHALL count only when
the current version of its row carries the hash: a row version that a re-ingest has since replaced SHALL NOT make
a copy, and neither SHALL the stored row of an event that is in the same insert, which that insert replaces. A
row with no carrier in its insert SHALL be a carrier whenever the lookup returns no stored one, for example
because only another instance's concurrent insert holds it, the lookup's bound on hashes per flush is reached, or
the lookup fails, or is not issued because the flush's lookup for chat parents failed. A failed lookup SHALL be logged and counted and SHALL NOT fail the insert. Each of these costs storage only. When a
batch is inserted in parts, a copy whose carrier lies in another part SHALL be stored as a carrier instead, so no
stored copy names a carrier that a refused part kept out of the table.

`dial_usage_log_payload.request_body_full` SHALL return, for a copy, the `request_body` of a row carrying its
parent hash byte for byte, with `request_body_rebuild` = `rebuilt` (`resolvable` when read without the body),
read through the same two phases, bounds and partition as any other rebuild. When no row of the partition carries
the hash, it SHALL return the stored empty body marked `partial` with reason `missing`. `request_body` SHALL keep
returning the stored empty string.

A copy holds none of its body, so it depends on its carrier more than a chat child does on its parent. What
"Rebuild equivalence" promises of a parent's path, that a row carrying it exists while the child does, holds for a
body hash only outside the cases below, which are an operator's to avoid. Re-ingesting a carrier gives its row another hash after a change
of `sync.inline-files-drop-truncated` or `sync.inline-files-max-size`, and no hash at all under `whole`, with
`request_body` left out of ingest (see "Sensitive columns can be excluded from ingest"), or on a release that predates body hashes or that changes which bodies
are eligible or how the hash is computed; a copy not re-ingested with it then names a hash no row carries. A carrier whose hash is removed between the lookup that found it and the insert
of its copy, and a carrier seen on a replica that has not yet received its replacement, orphan a copy the same
way. Re-ingesting whole UTC days under one configuration and release restores every copy of those days. As for a chat parent,
part-granular expiry MAY drop a carrier before its copy in the last day of a day's retention.

#### Scenario: A repeated truncated body is stored once

- **WHEN** a body of 70,000 characters that is not valid JSON is ingested under `delta` at `10:00:00.000`, and the
  same body arrives from the same project and user at `10:00:05.000` on the same UTC day
- **THEN** the first row carries the body and its body hash in `request_hash`, and the second carries an empty
  `request_body`, that hash in `request_parent_hash` and as the only element of `request_ancestor_hashes`, and an
  all-zero `request_hash`

#### Scenario: Equal bodies in one insert are stored once

- **WHEN** two requests of one project and user with equal eligible bodies, `request_time` `10:00:00.000` and
  `10:00:05.000`, are ingested in one batch that is inserted whole
- **THEN** the earlier row is a carrier and the later one a copy

#### Scenario: A body exactly at the size bound is hashed

- **WHEN** a body of exactly 65,536 UTF-16 code units that is not JSON is ingested under `delta`, and a body one
  unit shorter that is not JSON is ingested too
- **THEN** the first row carries its body hash in `request_hash`, and the second an all-zero `request_hash`

#### Scenario: The size is measured on the body as stored

- **WHEN** a truncated body holds 70,000 characters as received and 40,000 once the inline-file rewrite has
  removed a file from it
- **THEN** its row carries the rewritten body and an all-zero `request_hash`

#### Scenario: A body with neither project nor user is not hashed

- **WHEN** two equal eligible-sized bodies that are not JSON arrive with an empty `project_id` and an empty
  `user_hash`
- **THEN** both rows carry the body and an all-zero `request_hash`

#### Scenario: A request time with a UTC offset is not hashed

- **WHEN** an eligible-sized body that is not JSON arrives with a `request_time` carrying the offset `+02:00`
- **THEN** its row carries the body and an all-zero `request_hash`

#### Scenario: A carrier replaced by the same insert does not make a copy

- **WHEN** a carrier is stored, an inline-file setting changes, and one batch then carries that carrier's event,
  whose body now differs, together with a new request whose body equals the carrier's stored one
- **THEN** the new request's row holds its body and is a carrier

#### Scenario: A copy reads back whole

- **WHEN** a query projects `dial_usage_log_payload.request_body_full` and `request_body_rebuild` for a copy whose
  carrier is stored
- **THEN** the row carries the carrier's body byte for byte and `rebuilt`

#### Scenario: A copy whose carrier is gone is partial

- **WHEN** a copy is read and no row of its partition carries its parent hash
- **THEN** `request_body_full` is the empty string, `request_body_rebuild` is `partial`, and the value is counted
  with reason `missing`

#### Scenario: A copy read without its body is resolvable

- **WHEN** a query projects `request_body_rebuild` without `request_body_full` for a copy whose carrier is stored
- **THEN** the row carries `resolvable`, and no body is read

#### Scenario: A failed lookup stores a carrier

- **WHEN** an eligible body is ingested, no row of its insert carries its hash, and the payload-table lookup fails
- **THEN** its row holds the body and its hash, the batch is written, and the failure is counted

#### Scenario: A replaced row version is not a carrier

- **WHEN** a carrier was stored, its event was re-ingested under `whole` so that its current row carries no hash,
  the replaced version is still on disk, and an equal body is then ingested under `delta`
- **THEN** the new row holds the body and is a carrier

#### Scenario: Valid JSON beyond a parser's usual limits is not hashed

- **WHEN** two equal bodies of 70,000 characters, each one valid JSON array nested 2,000 levels deep, are
  ingested under `delta`
- **THEN** both rows carry the body and an all-zero `request_hash`

#### Scenario: A body nested too deeply to read is treated as JSON

- **WHEN** two equal bodies, each 100,001 `[` characters, are ingested under `delta`
- **THEN** both rows carry the body and an all-zero `request_hash`

#### Scenario: An earlier request arriving later becomes the carrier's peer

- **WHEN** a carrier with `request_time` `10:00:05.000` is stored, and a request with an equal body and
  `request_time` `10:00:00.000` is ingested afterwards
- **THEN** the new row is a carrier, and both rows hold the body

#### Scenario: A JSON object naming a member twice is not hashed

- **WHEN** two equal bodies of 70,000 characters, each a JSON object naming one member twice, are ingested under
  `delta`
- **THEN** both rows carry the body and an all-zero `request_hash`

#### Scenario: A body below the size bound is not hashed

- **WHEN** two equal bodies of 1,000 characters that are not JSON are ingested under `delta`
- **THEN** both rows carry the body and an all-zero `request_hash`

#### Scenario: A large JSON body is not hashed

- **WHEN** two equal MCP `tools/call` bodies of 70,000 characters, each valid JSON, are ingested under `delta`
- **THEN** both rows carry the body and an all-zero `request_hash`

#### Scenario: An equal body of another user, project or day is not a copy

- **WHEN** a body equal to a stored carrier's arrives from another user, another project, or on another UTC day
- **THEN** its row is a carrier with a different body hash

#### Scenario: A re-ingested carrier stays a carrier

- **WHEN** a carrier and its copy are stored and the carrier's event is ingested again
- **THEN** the surviving carrier row still holds the body and the hash, and the copy still reads back whole

#### Scenario: Equal bodies at the same instant are both carriers

- **WHEN** two requests with equal eligible bodies and equal `request_time` are ingested
- **THEN** both rows hold the body

#### Scenario: whole-with-path stamps an eligible body with its hash

- **WHEN** an eligible body is ingested under `whole-with-path`, and under `delta` the same body arrives later
- **THEN** the first row holds the body and its body hash, and the second is a copy of it

#### Scenario: A copy split from its carrier is stored whole

- **WHEN** a carrier and its copy are prepared in one batch that is then inserted in two parts, one row in each
- **THEN** both rows hold the body, each with the body hash in `request_hash` and an all-zero
  `request_parent_hash`

#### Scenario: A failed chat lookup stores the flush's bodies whole

- **WHEN** a flush carries a chat request and an eligible body, and the payload-table lookup for the chat request's
  parent fails
- **THEN** the eligible body's row holds the body and its hash, and the table is not asked about its hash
