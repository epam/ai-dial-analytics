## MODIFIED Requirements

### Requirement: Seeded column sensitivity on the built-in source tables

The catalog migrations SHALL seed the `sensitive` flag on the built-in source tables so that columns
carrying caller identity or raw user content are restricted to `FULL_ADMIN` on the query surface by
default (see the `authentication` capability's "Sensitive column authorization" for the enforcement
rule). The seeded sensitive set SHALL be `dial_usage_log.jwt_claims`, `dial_usage_log.request_tags`,
`dial_usage_log.request_body`, `dial_usage_log.response_body`, `dial_usage_log.assembled_response`,
and `rate_analytics.comment`. Every other seeded column SHALL default to `sensitive = false`.

`dial_usage_log.assembled_response` SHALL be seeded sensitive on the same grounds as the two body
columns: it stores the model's answer verbatim, which is user-visible content, and for a streamed call
it is the *only* form in which that answer can be read without reassembling a chunk transcript. That
it is a merged rather than a raw payload changes its shape, not its sensitivity.

`dial_usage_log.request_tags` SHALL be seeded sensitive because it stores the producer's request
`headers` object verbatim — the ingest mapping applies no allowlist or denylist — so it may carry
credentials, cookies, forwarded client addresses, and per-user correlation identifiers. It is
classified by what it stores rather than by its intended "routing metadata" role.

`dial_usage_log.user_hash` SHALL NOT be seeded sensitive: it is a de-identified per-user surrogate
whose purpose is to let non-`FULL_ADMIN` roles run per-user analytics (distinct-user counts, per-user
rollups) without exposing identity.

Sensitivity is catalog metadata, not an immutable property: a `FULL_ADMIN` operator MAY change any
column's flag after the seed via `PATCH /v1/tables/{name}/schema`, and the catalog row is thereafter
authoritative. Seeding is applied by a `column_mapping` update only — the flag is never part of the
ClickHouse DDL, so no seeding change materializes as an `ALTER`.

#### Scenario: Seeded sensitivity is present after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log` reports `jwt_claims`, `request_tags`, `request_body`, `response_body`, and
  `assembled_response` with `sensitive: true`, `user_hash` with `sensitive: false`, and
  `rate_analytics` reports `comment` with `sensitive: true`

#### Scenario: Management responses expose the seeded flag unfiltered

- **WHEN** a `FULL_ADMIN` caller reads `GET /v1/tables/dial_usage_log`
- **THEN** the response lists `request_tags` with `sensitive: true` — the `/v1/tables` surface is not
  role-filtered, so an operator can always audit which columns are restricted

#### Scenario: A seeded sensitive column can be unrestricted by an operator

- **WHEN** a `FULL_ADMIN` caller sends `PATCH /v1/tables/dial_usage_log/schema` with
  `{"update":[{"name":"request_tags","sensitive":false}]}`
- **THEN** the request succeeds without any ClickHouse `ALTER`, and `request_tags` is thereafter
  visible to non-`FULL_ADMIN` callers on the query surface

### Requirement: Seeded heavy columns on the built-in source tables

The catalog migrations SHALL seed the `heavy` flag on the built-in source tables' raw-payload columns
so that a wildcard projection over the seeded schema is cheap by default. The seeded heavy set SHALL
be `dial_usage_log.request_body`, `dial_usage_log.response_body`, and
`dial_usage_log.assembled_response` — the verbatim LLM request, the verbatim response, and the
producer's merged response, the only seeded columns whose per-row size is unbounded. Every other
seeded column SHALL default to `heavy = false`, including the `object`-typed `jwt_claims` and
`request_tags` (small claim/header documents) and `rate_analytics.comment` (a short free-text note).

All three seeded heavy columns are also seeded `sensitive` (see "Seeded column sensitivity on the
built-in source tables"), so for a caller who is not `FULL_ADMIN` they are already absent from the
model and the heavy flag changes nothing; the wildcard behavior change is observable only to
`FULL_ADMIN` callers and when `security.mode=none`.

#### Scenario: Seeded heavy flags after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log` reports `request_body`, `response_body`, and `assembled_response` with
  `heavy: true`, and every other column of `dial_usage_log` and `rate_analytics` with `heavy: false`

#### Scenario: Management responses expose the seeded heavy flag

- **WHEN** a `FULL_ADMIN` caller reads `GET /v1/tables/dial_usage_log`
- **THEN** the response lists `response_body` with `heavy: true` alongside its `sensitive: true`, so
  an operator can audit which columns a wildcard omits

#### Scenario: A wildcard omits the merged response

- **WHEN** a `FULL_ADMIN` caller runs a wildcard projection over `dial_usage_log`
- **THEN** no row carries an `assembled_response` key, and naming the column explicitly returns its
  value in full
