## Why

The project pins ClickHouse **24.8**, where the `JSON` type used by the `dial_usage_log`
`jwt_claims`/`request_tags` columns is still experimental and requires
`allow_experimental_json_type=1` to be mounted into both the Testcontainers integration tests and
`local_env`. ClickHouse **25.3 LTS** ships the `JSON` type as production-ready (GA) with the
experimental flag removed, letting us move to a newer supported LTS line and delete a workaround we
currently carry in three places.

## What Changes

- Bump the pinned ClickHouse server image from `clickhouse/clickhouse-server:24.8` to
  `clickhouse/clickhouse-server:25.3` in the Testcontainers integration base
  (`AbstractClickHouseIntegrationTest`) and in `local_env/docker-compose.yml`.
- Remove the experimental-JSON workaround now that the type is GA on 25.3: delete
  `src/test/resources/clickhouse/experimental.xml` and its `withCopyFileToContainer(...)` mount, and
  `local_env/clickhouse/users.d/experimental.xml` with its Docker Compose `:ro` volume entry.
- Update the migration (`V1__create_dial_usage_log.sql`) comment and `docs/configuration.md` to drop
  the `allow_experimental_json_type` operational note (the `AES_256_GCM_SIV` encryption-key
  requirement is unaffected and stays).
- Keep the ClickHouse client/driver libraries unchanged (`client-v2` / `clickhouse-jdbc` 0.9.8,
  `flyway-database-clickhouse` 10.24.0) — they are forward-compatible with a 25.3 server.

## Capabilities

### New Capabilities
<!-- None — this change introduces no new behavior. -->

### Modified Capabilities
- `data-sync`: the **Target table provisioning and schema** requirement currently states the `JSON`
  columns' use "SHALL be enabled operationally (e.g. `allow_experimental_json_type`)" on the pinned
  server. On 25.3 the `JSON` type is GA, so that operational-enablement clause for JSON is dropped
  (the `AES_256_GCM_SIV` server-side encryption-key requirement remains).

## Impact

- **Code/config**: `AbstractClickHouseIntegrationTest`; `local_env/docker-compose.yml`;
  `src/test/resources/clickhouse/experimental.xml` (deleted);
  `local_env/clickhouse/users.d/experimental.xml` (deleted);
  `src/main/resources/db/migration/V1__create_dial_usage_log.sql` (comment only);
  `docs/configuration.md`.
- **Dependencies**: ClickHouse server only — no Gradle dependency version changes expected.
- **Operations**: deployments targeting their own ClickHouse must run **25.3 LTS or newer**; they no
  longer need to set `allow_experimental_json_type`. Existing `dial_usage_log` data and the table DDL
  are unchanged.
- **Build**: the full Gradle build runs the integration tests against the new image via
  Testcontainers (requires Docker); `CREATE TABLE` with `JSON` must succeed on 25.3 with no flag.
