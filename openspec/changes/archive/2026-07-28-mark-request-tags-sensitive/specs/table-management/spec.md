# table-management Delta

## ADDED Requirements

### Requirement: Seeded column sensitivity on the built-in source tables

The catalog migrations SHALL seed the `sensitive` flag on the built-in source tables so that columns
carrying caller identity or raw user content are restricted to `FULL_ADMIN` on the query surface by
default (see the `authentication` capability's "Sensitive column authorization" for the enforcement
rule). The seeded sensitive set SHALL be `dial_usage_log.jwt_claims`, `dial_usage_log.request_tags`,
`dial_usage_log.request_body`, `dial_usage_log.response_body`, and `rate_analytics.comment`. Every
other seeded column SHALL default to `sensitive = false`.

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
- **THEN** `dial_usage_log` reports `jwt_claims`, `request_tags`, `request_body`, and `response_body`
  with `sensitive: true`, `user_hash` with `sensitive: false`, and `rate_analytics` reports `comment`
  with `sensitive: true`

#### Scenario: Management responses expose the seeded flag unfiltered

- **WHEN** a `FULL_ADMIN` caller reads `GET /v1/tables/dial_usage_log`
- **THEN** the response lists `request_tags` with `sensitive: true` — the `/v1/tables` surface is not
  role-filtered, so an operator can always audit which columns are restricted

#### Scenario: A seeded sensitive column can be unrestricted by an operator

- **WHEN** a `FULL_ADMIN` caller sends `PATCH /v1/tables/dial_usage_log/schema` with
  `{"update":[{"name":"request_tags","sensitive":false}]}`
- **THEN** the request succeeds without any ClickHouse `ALTER`, and `request_tags` is thereafter
  visible to non-`FULL_ADMIN` callers on the query surface
