## 1. Bump the pinned server image

- [x] 1.1 In `src/test/java/com/epam/aidial/analytics/integration/AbstractClickHouseIntegrationTest.java`, change the `DockerImageName.parse("clickhouse/clickhouse-server:24.8")` to `:25.3`.
- [x] 1.2 In `local_env/docker-compose.yml`, change `image: clickhouse/clickhouse-server:24.8` to `:25.3` and update the lockstep comment (header note + the line referencing the tests' version).

## 2. Remove the experimental-JSON workaround

- [x] 2.1 In `AbstractClickHouseIntegrationTest`, remove the `withCopyFileToContainer(...experimental.xml... -> users.d/experimental.xml)` call and its explanatory comment (keep the `encryption.xml` mount).
- [x] 2.2 Delete `src/test/resources/clickhouse/experimental.xml`.
- [x] 2.3 In `local_env/docker-compose.yml`, remove the `./clickhouse/users.d/experimental.xml:...:ro` volume entry and its comment (keep the `encryption.xml` volume).
- [x] 2.4 Delete `local_env/clickhouse/users.d/experimental.xml`.

## 3. Update comments and docs (no DDL change)

- [x] 3.1 In `src/main/resources/db/migration/V1__create_dial_usage_log.sql`, update the header comment to drop the `allow_experimental_json_type` note (state the `JSON` type is GA on 25.3+); keep the `AES_256_GCM_SIV` encryption-key note. Do not change the `CREATE TABLE` statement.
- [x] 3.2 In `docs/configuration.md`, update the operational note so it no longer requires `allow_experimental_json_type` for the JSON columns (keep the encryption-key requirement); note ClickHouse 25.3 LTS or newer.

## 4. Verify

- [x] 4.1 Run `./gradlew checkstyleMain checkstyleTest` and confirm it passes.
- [x] 4.2 Build checkpoint (Docker required): run `./gradlew clean build` and confirm the Testcontainers integration tests provision `dial_usage_log` (including the `jwt_claims`/`request_tags` `JSON` columns) and query it against the 25.3 image with no experimental flag. Verified: 25.3.14.14 pulled; `CREATE TABLE` from the migration succeeds with only the encryption key (no experimental flag), `jwt_claims`/`request_tags` materialize as `JSON`; full `clean build` is green. NOTE: the Testcontainers Ryuk reaper (`testcontainers/ryuk:0.14.0`) times out in this environment, so the build must run with `TESTCONTAINERS_RYUK_DISABLED=true` — unrelated to this change.
- [x] 4.3 Grep the repo for any remaining `24\.8`, `experimental.xml`, or `allow_experimental_json_type` references (outside `openspec/changes/archive/`) and confirm none remain in active code/config/docs.

## 5. Spec sync

- [x] 5.1 Confirm the shipped behavior matches the `data-sync` delta spec (JSON columns provision without an experimental flag on 25.3+); the delta merges into `openspec/specs/data-sync/spec.md` at archive time.
