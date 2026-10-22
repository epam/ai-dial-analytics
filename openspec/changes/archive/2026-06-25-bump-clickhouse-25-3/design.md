## Context

ADAS pins `clickhouse/clickhouse-server:24.8` in two spots that must stay in lockstep: the
Testcontainers base (`AbstractClickHouseIntegrationTest`) and `local_env/docker-compose.yml`. On
24.8 the `JSON` type used by `dial_usage_log.jwt_claims`/`request_tags` is experimental, so the repo
carries an `experimental.xml` profile (`allow_experimental_json_type=1`) mounted into
`/etc/clickhouse-server/users.d/` in both the tests and `local_env`; without it the migration's
`CREATE TABLE` fails. ClickHouse **25.3 LTS** ships the new `JSON` type as GA with the experimental
flag removed (confirmed: [ClickHouse 25.3 release](https://clickhouse.com/blog/clickhouse-release-25-03),
[JSON type docs](https://clickhouse.com/docs/sql-reference/data-types/newjson)), so moving to 25.3
lets us delete that workaround while staying on a supported LTS line.

The ClickHouse Java client (`client-v2`, `clickhouse-jdbc`) talks to the server over HTTP and is
forward-compatible across server minors; 0.9.8 already targets 24.x/25.x. Flyway's
`flyway-database-clickhouse` 10.24.0 runs the same DDL regardless of the 24.8→25.3 server bump. The
table DDL itself does not change — only the operational precondition (no experimental flag) does.

## Goals / Non-Goals

**Goals:**
- Move the pinned ClickHouse server to 25.3 LTS in the integration tests and `local_env`.
- Remove the experimental-JSON workaround (both `experimental.xml` files + their mounts) now that the
  `JSON` type is GA on 25.3.
- Keep the migration DDL and the table schema byte-for-byte unchanged; update only stale comments and
  the `docs/configuration.md` operational note.

**Non-Goals:**
- No Gradle client/driver version changes (`client-v2`/`clickhouse-jdbc` 0.9.8, Flyway 10.24.0 stay).
- No change to the `AES_256_GCM_SIV` encryption-key requirement or its `encryption.xml` mount — that
  is independent of the version bump and stays.
- Not adopting any new 25.x JSON features (typed paths, hints); the columns remain plain `JSON`.

## Decisions

- **Pin `25.3` (the LTS tag), not `latest` or a 25.x point tag.** 25.3 is the first LTS where the
  `JSON` type is GA, and an LTS tag matches the existing 24.8 (also LTS) pinning convention — it gives
  reproducible builds without chasing every minor. Alternatives: `latest` (non-reproducible, rejected)
  or a non-LTS minor like 25.5/25.6 (shorter support window, no benefit here).
- **Delete both `experimental.xml` files and their mounts rather than leaving them as harmless no-ops.**
  `allow_experimental_json_type` is removed in 25.3; keeping the mount risks an "unknown setting"
  startup error and leaves misleading dead config. The `encryption.xml` mount stays untouched.
- **Comment/doc-only edits to the migration and `configuration.md`.** The `CREATE TABLE` text is
  unchanged; only the header comment in `V1__create_dial_usage_log.sql` and the operational note in
  `docs/configuration.md` (which currently names `allow_experimental_json_type`) are updated. Per
  project convention, config descriptions live only in `docs/configuration.md`.
- **Verify at the integration-build checkpoint.** Per the project's testing note, the full build needs
  Docker for Testcontainers; the bump is proven when `./gradlew clean build` provisions
  `dial_usage_log` (JSON columns included) against the 25.3 image with no experimental flag.

## Risks / Trade-offs

- **JSON `CREATE TABLE` behaves differently on 25.3 than 24.8** → the type is GA and the existing DDL
  is standard `JSON` (no experimental object syntax), so it should apply unchanged; validated by the
  integration tests' provisioning path before the change is considered done.
- **Leftover `allow_experimental_json_type` in any deployment's config errors on 25.3** → call out in
  the proposal/configuration docs that operators on 25.3 must drop the setting; the service itself no
  longer mounts it.
- **A consumer is stuck on a ClickHouse older than 25.3** → documented as a hard requirement (25.3 LTS
  or newer); 24.x is no longer supported by this service after the bump.
- **The two pins drift** → both `AbstractClickHouseIntegrationTest` and `docker-compose.yml` are
  updated in the same change and cross-referenced in comments, as today.

## Migration Plan

1. Bump the image tag to `clickhouse/clickhouse-server:25.3` in `AbstractClickHouseIntegrationTest`
   and `local_env/docker-compose.yml`; refresh the lockstep comments.
2. Remove `src/test/resources/clickhouse/experimental.xml` and its `withCopyFileToContainer(...)`
   mount; remove `local_env/clickhouse/users.d/experimental.xml` and its compose `:ro` volume entry.
3. Update the comment in `V1__create_dial_usage_log.sql` and the operational note in
   `docs/configuration.md` to drop the experimental-JSON flag (keep the encryption-key note).
4. Run `./gradlew clean build` (Docker required) to confirm the integration tests provision and query
   `dial_usage_log` against 25.3 with no experimental flag.
5. **Rollback**: revert the commit — restoring the 24.8 tag and the two `experimental.xml` mounts
   brings back the prior working state; no data migration is involved.
