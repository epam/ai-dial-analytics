## Why

ADAS already exposes two ad-hoc SQL surfaces — `POST /v1/queries/execute-sql` (REST/JSON) and the
Arrow Flight SQL listener on `:32010` — but nobody has verified that an off-the-shelf BI/dashboard
tool can actually drive them. Grafana is the most likely first consumer (it is already the house
tool for DIAL operational dashboards), and the answer matters for the roadmap: if Grafana can read
ADAS directly, analytics consumers get dashboards without a second query gateway; if it cannot, we
need to know exactly which part of the contract blocks it.

This change answers that question with a reproducible local experiment rather than a guess, and
leaves the harness in the repo so the next person can re-run it in one command.

## What Changes

- **Grafana added to the local dev stack** as a Compose-profile service in
  `local_env/docker-compose.yml` (`--profile grafana`), so the existing
  `docker compose up -d --wait` flow for ClickHouse + PostgreSQL is unchanged for developers who
  don't want it.
- **Provisioned, file-based Grafana config** under `local_env/grafana/provisioning/` — no clicking
  through the UI, no state to lose:
  - an **Infinity** datasource pointed at ADAS's REST SQL endpoint
    (`POST /v1/queries/execute-sql`), which is the transport most likely to work today;
  - a **Flight SQL** datasource pointed at `grpc://…:32010`, to test the native-SQL path;
  - a dashboard **"ADAS — DIAL usage"** with a time-series graph (requests over time, bucketed with
    the DSL's `date_bin`) plus a top-deployments table, both served through ADAS — never by
    connecting Grafana straight to ClickHouse.
- **A seed script** (`local_env/seed/seed_dial_usage_log.py`) that writes synthetic
  `dial_usage_log` rows straight into ClickHouse over HTTP, so the graph has something to plot on a
  fresh volume. Idempotent and clearly marked as throwaway local data.
- **Documentation** of the experiment and its outcome: a `local_env/grafana/README.md` covering
  how to start the stack, what each datasource does, and — importantly — the recorded findings
  about which ADAS surface Grafana can and cannot drive, including the governance limits a
  dashboard author will hit (1000-row result maximum, no arithmetic, no window functions,
  projection-only array/object columns).

Explicitly **not** in this change: no Java code, no new endpoint, no change to any request or
response contract, no auth work. If the experiment turns up a genuine gap in ADAS's shipped
behavior, it is recorded in the README as a follow-up, not fixed here.

## Capabilities

### New Capabilities

None. This change adds local development tooling and documentation only.

### Modified Capabilities

None. No requirement of any existing capability changes: the experiment drives
`rest-sql-endpoint` and `flight-sql-endpoint` exactly as they are already specified, and adding a
container to `local_env/` is not observable behavior of the service. Any gap the experiment
uncovers is captured as a follow-up finding rather than a silent spec edit.

## Impact

- **New files**: `local_env/grafana/**` (provisioning + dashboard JSON + README),
  `local_env/seed/**` (data seeder).
- **Modified**: `local_env/docker-compose.yml` (one profile-gated `grafana` service and its
  volume), `README.md` (one pointer to the new local_env doc).
- **No production surface touched**: no `src/` change, so no new configuration property and no
  `docs/configuration.md` entry. The Grafana container's own credentials are throwaway local-dev
  values, consistent with the ClickHouse dev encryption key already in `local_env/`.
- **Dependencies**: Grafana OSS image plus two community datasource plugins downloaded at container
  start (`GF_INSTALL_PLUGINS`) — an internet-dependent step, called out in the README.
- **Runtime shape of the experiment**: Grafana runs in Docker and reaches ADAS on the host via
  `host.docker.internal:5000` / `:32010`; ADAS itself keeps running the way developers already run
  it, from the Gradle build.
