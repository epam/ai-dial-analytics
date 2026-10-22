## 1. Seed data into ClickHouse

- [x] 1.1 Write `local_env/seed/seed_dial_usage_log.py`: stdlib-only Python that INSERTs synthetic
      `dial_usage_log` rows over the ClickHouse HTTP interface (`--url`, `--rows-per-hour`, `--hours`,
      `--table`, `--anchor`, `--seed`, `--purge`, `--dry-run` flags; defaults `localhost:8123`,
      `dial_usage_log`). Rows spread across the last N hours over a few deployments/projects/users
      with a daily traffic shape, `_source_file = 'local-seed'`, deterministic `event_id` derived from
      `(trace_id, core_span_id)`, `request_time` always present.
- [x] 1.2 Add `local_env/seed/README.md` covering how to run the seeder and how to delete only seeded
      rows (`ALTER TABLE dial_usage_log DELETE WHERE _source_file = 'local-seed'`).
- [x] 1.3 Run the seeder against the local stack and confirm the expected row count landed and that
      re-running does not multiply rows. **Found and fixed:** ADAS reads without `FINAL`, so the
      ReplacingMergeTree's pre-merge duplicates were visible to queries (raw 1200 vs FINAL 600 after a
      second run). The seeder now issues `OPTIMIZE TABLE … FINAL` after inserting (`--no-optimize` to
      skip); re-verified raw count back to 600.

## 2. Grafana in the local stack

- [x] 2.1 Add a profile-gated `grafana` service to `local_env/docker-compose.yml`:
      `grafana/grafana:11.6.3` (overridable via `GRAFANA_IMAGE_TAG`), `profiles: [grafana]`, port
      `${GRAFANA_BIND_ADDR:-127.0.0.1}:${GRAFANA_PORT:-3000}:3000` (loopback-qualified, because
      anonymous admin must not be network-reachable), plugin install via `GF_INSTALL_PLUGINS`,
      anonymous access at the `Editor` role (the least role that can view dashboards and use Explore —
      `Admin` could create a ClickHouse datasource and bypass ADAS), plugin versions pinned to the ones
      the findings were observed on, provisioning mounted read-only, named `grafana-data` volume,
      healthcheck on `/api/health`. Default (profile-less) startup behavior unchanged.
- [x] 2.2 Add `local_env/grafana/provisioning/datasources/adas.yml` declaring both datasources:
      Infinity pointed at `$ADAS_BASE_URL` and Flight SQL at `$ADAS_FLIGHT_ADDR` (insecure, no auth —
      matching `security.mode=none`), both expanded by Grafana from the container environment.
- [x] 2.3 Add `local_env/grafana/provisioning/dashboards/adas.yml` (file provider,
      `allowUiUpdates: false`) and the dashboard JSON `adas-dial-usage.json`: a time-series
      "Requests over time (hourly)" (`date_bin(1,'hour',request_time)` + `count(*)`), a table
      "Top deployments", and a text panel stating the ADAS query limits in-dashboard. Both data panels
      aggregate — no raw-row panels.

## 3. Bring the stack up and verify the connection end to end

- [x] 3.1 Start the data stores and ADAS with `FLIGHT_ENABLED=true`; `/actuator/health` returns
      `UP` and the log confirms `Flight SQL endpoint listening on port 32010`. **Note:** the shared
      `testdb` volume carries a dropped draft `V12__clear_system_table_access` whose checksum does not
      match this branch's real V12, so ADAS was run against a throwaway `adas_grafana` database
      (documented in `local_env/grafana/README.md`); ClickHouse was deliberately *not* swapped.
- [x] 3.2 Resolve design open question 1: `POST /v1/queries/execute-sql` returns timestamps as
      **ISO-8601 with a `Z` suffix** (`"2026-07-16T10:00:00Z"`), not epoch millis — the dashboard's
      Infinity column type `timestamp` is correct, and Grafana types the field as `time`.
- [x] 3.3 Start Grafana with `--profile grafana`; both plugins install
      (`yesoreyeram-infinity-datasource` 3.11.1, `influxdata-flightsql-datasource` 1.1.1) and both
      datasources provision with `$ADAS_BASE_URL` expanded. Infinity health check: `OK`.
      **Two defects found and fixed here:** (a) `grafana-flightsql-datasource` does not exist — the
      plugin is `influxdata-flightsql-datasource`, and the wrong ID made the entrypoint exit 1 in a
      restart loop; (b) `extra_hosts: host.docker.internal:host-gateway` overrode Docker Desktop's
      correct mapping with the in-VM bridge (172.17.0.1), so every ADAS request was refused — the
      entry was removed and the Linux fallback documented as an env override.
- [x] 3.4 Query both dashboard panels through Grafana's `/api/ds/query`: the time-series panel returns
      24 hourly buckets with `ts` typed `time (time.Time)`, the table panel returns 10 rows. ADAS's
      `http_server_requests_seconds_count{uri="/v1/queries/execute-sql"}` increments once per panel
      query, confirming Grafana's own query path reaches the endpoint.
- [x] 3.5 Resolve design open question 2: the Flight SQL datasource **executes ad-hoc statements
      correctly** (same row counts and values as REST), so `UNIMPLEMENTED` on prepared statements is
      not fatal. Two plugin-side defects recorded verbatim: its "Save & Test" probes `SELECT 1`, which
      ADAS rejects (`SQL construct 'LogicalValues' … cannot be expressed`), so the datasource shows
      red; and it reads Arrow timestamps as nanoseconds although ADAS advertises
      `Timestamp(MILLISECOND, UTC)`, putting every time-series point in January 1970.
- [x] 3.6 Resolve design open question 3: the dashboard time range **can** be pushed into ADAS SQL —
      Infinity interpolates `${__from:date:YYYY-MM-DD HH:mm:ss}` / `${__to:…}` into `TIMESTAMP`
      literals, which ADAS accepts. Verified by row count (24 buckets for `now-24h`, 7 for `now-6h`)
      and by boundary check (no timezone shift; formats UTC, so the dashboard pins `timezone: utc`).
      Both data panels now carry the filter, and `design.md` D4 was corrected — it had assumed this
      would not work.
- [x] 3.7 Record the evidence: Grafana's stored provisioned dashboard carries the interpolated SQL,
      the rendered frames contain a real `time` field with 24 points, and the ADAS-side request
      counter confirms the traffic. No headless browser render was performed (no image-renderer in the
      stack), so the visual check is left to opening `http://localhost:3000`.

## 4. Document the experiment and its findings

- [x] 4.1 Write `local_env/grafana/README.md`: quick start, the verdict-per-transport table, the two
      Flight SQL defects verbatim, the dashboard-authoring limits (1000-row maximum, aggregate-only,
      no arithmetic/`CASE`/`CAST`/JOIN, closed function catalog, projection-only array/object columns,
      `LIKE` restrictions), how the time picker is wired through and its UTC caveat, the
      loopback-bound anonymous-admin posture (and why the loopback bind is load-bearing: the `Admin`
      role can create datasources, so a network-reachable Grafana could read ClickHouse directly),
      the `GF_INSTALL_PLUGINS` network dependency, how a
      secured deployment would present an `Api-Key`, why Grafana is deliberately not pointed at
      ClickHouse, and the gotchas hit while setting it up.
- [x] 4.2 Add one pointer from the repo `README.md` (in the SQL-surfaces section) to
      `local_env/grafana/README.md` — prose only, no env-var tables.
- [x] 4.3 Record the ADAS-side follow-ups in `local_env/grafana/README.md`: the plugin's Arrow
      timestamp-unit bug (upstream), and the optional `GetSqlInfo`/table-less-`SELECT 1` tolerance that
      would turn the Flight datasource green. Neither is fixed here; no service behavior changed.

## 5. Close out

- [x] 5.1 Confirm no `src/` file changed and that `openspec/specs/` needs no update — the experiment
      drives already-specified `rest-sql-endpoint` / `flight-sql-endpoint` behavior, and both defects
      found are in the Grafana plugin, not in ADAS.
- [x] 5.2 `./gradlew clean build` passes.
- [x] 5.3 Leave the stack running as requested: ClickHouse + PostgreSQL + Grafana up, ADAS reachable,
      dashboard loading — with the stop commands documented.
