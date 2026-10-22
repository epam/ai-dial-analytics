## Context

ADAS exposes two ad-hoc SQL transports over the same governed pipeline:

| Surface | Transport | Spec |
|---|---|---|
| `POST /v1/queries/execute-sql` | HTTP/JSON, always on | `rest-sql-endpoint` |
| Flight SQL listener `:32010` | gRPC + Arrow, `flight.enabled=true` | `flight-sql-endpoint` |

Both accept the same SQL subset (`sql-query-translation`) and both are subject to the same
`structured-query` governance: single-entity `SELECT`, a closed function catalog, default limit 100
and **hard maximum 1000 rows** (a larger `LIMIT` is rejected, never clamped), no arithmetic, no
`CASE`, no `CAST`, no JOIN/CTE/window functions, and array/object columns are projection-only.

Grafana has no ADAS-native datasource, so a connection has to go through a generic plugin. Two
candidates matter:

- **Infinity** (`yesoreyeram-infinity-datasource`) — a generic REST/JSON datasource. It can POST a
  body and pick rows out of the response with a root selector, which is exactly the shape of
  `{"sql": "..."}` → `{"rows": [...]}`.
- **Flight SQL** (`influxdata-flightsql-datasource` — published by InfluxData; a Grafana-authored
  `grafana-flightsql-datasource` was assumed to exist when this was written and does not) — an Arrow
  Flight SQL datasource. This is the interesting one because it would make ADAS look like a real SQL
  database to Grafana.

The known risk on the Flight side is already documented in `local_env/pandas-client/README.md`:
ADAS is **statement-only v1**, so `CreatePreparedStatement` answers `UNIMPLEMENTED`. The Python ADBC
driver silently falls back to ad-hoc execution; whether Grafana's Go-based plugin does the same, and
whether it can survive `UNIMPLEMENTED` on the Flight SQL *metadata* RPCs (`GetSqlInfo`, `GetTables`,
`GetDbSchemas`) that its "Save & Test" and schema browser use, is unknown. Finding that out is a
deliverable of this change, not a prerequisite.

Local constraint: ADAS runs on the host from Gradle (the repo `Dockerfile` needs `GPR_USERNAME` /
`GPR_PASSWORD` for the GitHub Packages build stage, so containerising ADAS would add a credential
dependency for zero experimental value). Grafana therefore runs in Docker and reaches back to the
host.

## Goals / Non-Goals

**Goals:**

- Answer the question empirically: **can Grafana render a graph whose data comes from an ADAS
  endpoint?** For each of the two transports, produce a definite works / does-not-work verdict with
  the observed evidence.
- Ship the harness, not just the answer: one command brings up Grafana with the datasources and a
  working dashboard already provisioned.
- Give the graph real data on a fresh volume via a seeder, so the result does not depend on whether
  the developer happens to have run `data-sync`.
- Write down the dashboard-authoring limits that ADAS governance imposes, because they are the part
  that will actually surprise the next person.

**Non-Goals:**

- No ADAS code changes, no new endpoint, no contract change, no `security.mode=oidc` /
  auth-forwarding work (the local stack is `security.mode=none`; how a production Grafana would
  present an `Api-Key` is noted in the README but not built).
- Not a production Grafana deployment: no TLS, no real credentials, no dashboards-as-code pipeline,
  no alerting.
- Not a ClickHouse dashboard. Pointing Grafana's ClickHouse plugin straight at `:8123` would produce
  a much better dashboard and would answer the wrong question — it is explicitly avoided, and the
  README says why.
- No attempt to widen the SQL subset to suit Grafana (e.g. adding `$__timeFilter` macro support). If
  the subset is the blocker, that is a finding.

## Decisions

### D1. Grafana lives in the existing Compose file behind a `grafana` profile

`local_env/docker-compose.yml` gains one `grafana` service with `profiles: [grafana]`. Developers
who only want the data stores keep running `docker compose -f local_env/docker-compose.yml up -d
--wait` and get exactly what they get today; the experiment runs with
`--profile grafana` added.

*Alternative rejected:* a separate `docker-compose.grafana.yml`. Compose overlays need both `-f`
flags in every command and a second `name:` reconciliation; a profile is one word on the command
line and keeps a single source of truth for the stack. *Alternative rejected:* adding Grafana
unconditionally — it would slow the common `up --wait` path and pull plugins over the network for
developers who never open Grafana.

### D2. Fully provisioned, zero-click Grafana

Datasources and the dashboard are mounted read-only from `local_env/grafana/provisioning/` using
Grafana's file provisioning. Anonymous access is enabled with the login form disabled, so
`http://localhost:3000` opens straight onto the dashboard.

Rationale: the experiment must be reproducible and reviewable as a diff. Hand-built dashboards live
in the container's SQLite database, which is invisible to review and lost on `down -v`. Provisioned
dashboards are read-only in the UI — for a verification harness that is a feature.

Anonymous access is safe here and only here, held down by two independent controls. The anonymous role
is **`Editor`**, the least role that can still view the dashboards and use Explore: `Admin` would be
able to *create datasources*, and a Grafana that can add its own ClickHouse datasource can read
`dial_usage_log` directly, bypassing the governance the experiment exists to exercise. And the
published port is loopback-qualified (`${GRAFANA_BIND_ADDR:-127.0.0.1}:${GRAFANA_PORT:-3000}:3000`) —
an unqualified `3000:3000` would bind `0.0.0.0` and hand an unauthenticated UI to the network. Neither
control needs the other to be sound, which is the point. Beyond that the container holds nothing but a
pointer to another local service: the same throwaway-local-credential posture as the AES key already
committed in `local_env/clickhouse/config.d/encryption.xml`.

### D3. Infinity is the primary transport; Flight SQL is the probe

The provisioned dashboard's panels use the **Infinity** datasource against
`POST /v1/queries/execute-sql`, because that path needs nothing from ADAS that isn't already
specified: a JSON body in, a `{"rows": [...]}` envelope out, no prepared statements, no metadata
RPCs. The Flight SQL datasource is provisioned alongside it and exercised during verification, but
the dashboard does not depend on it — so a Flight-side failure yields a finding instead of an empty
dashboard.

Infinity panel shape: `type: json`, `source: url`, `method: POST`, body `{"sql": "…"}`,
`root_selector: rows`, and an explicit column list (`selector` → `text` → `type`). Explicit columns
matter: Grafana's time-series panel needs a genuine time-typed field, and Infinity will otherwise
infer a bucket column as a string. The exact wire encoding ADAS produces for a `timestamp` result
column (ISO-8601 text vs epoch millis) is settled by observation during verification, and the column
type is set to match — this is the one detail deliberately left to the runtime step rather than
guessed here.

### D4. The graph is an aggregate query with `date_bin`, not a raw row dump

The time-series panel runs roughly:

```sql
SELECT date_bin(1, 'hour', request_time) AS ts, count(*) AS requests
FROM dial_usage_log
GROUP BY ts
ORDER BY ts
LIMIT 500
```

`date_bin(amount, unit, timestamp)` is in the DSL function catalog and renders to native
`toStartOfInterval`, so time bucketing is available without arithmetic — which matters because
arithmetic operators are rejected by the translator. Aggregating server-side also keeps the result
inside the 1000-row maximum: a raw-row panel would silently show at most 1000 points and mislead
whoever reads it. A second panel (top deployments by request count) demonstrates the aggregate +
`ORDER BY … DESC` shape.

*Revised after verification (this replaces the original assumption that the window would have to be
hard-coded).* Grafana's time picker **does** drive the query. ADAS understands no Grafana macro —
`$__timeFilter(col)` and friends are meaningless to the translator — but it does accept ordinary
`TIMESTAMP 'YYYY-MM-DD HH:MM:SS'` literals, and Infinity interpolates Grafana's `$__from`/`$__to`
global variables into the request body before sending it. So the panels carry:

```sql
WHERE request_time >= TIMESTAMP '${__from:date:YYYY-MM-DD HH:mm:ss}'
  AND request_time <  TIMESTAMP '${__to:date:YYYY-MM-DD HH:mm:ss}'
```

Measured: the same panel target returns 24 hourly buckets for a `now-24h` range and 7 for `now-6h`,
so the pushdown is real rather than client-side cropping. `${…:date:…}` formats in **UTC**, which is
why the dashboard pins `"timezone": "utc"` — on a browser-timezone dashboard the window would
silently shift by the viewer's offset.

### D5. Seed data goes into ClickHouse directly, not through ADAS

`local_env/seed/seed_dial_usage_log.py` INSERTs synthetic rows over the ClickHouse HTTP interface
(`:8123`). ADAS's read path is read-only by design and its only ingest path is `data-sync` from blob
storage, so there is no ADAS endpoint that could write these rows. Writing them directly is
therefore not a shortcut around governance — it is the only option.

The seeder writes rows spread over the last N hours across a handful of deployments/projects so the
time-series graph has a visible shape, uses a deterministic `event_id` (hash of
`trace_id`/`core_span_id`) matching the table's dedup contract, and always supplies `request_time`
(the table comment notes that ingest drops records without it, and a missing timestamp would land
rows in the 1970 partition where they could never collapse). Re-running it with the same parameters
therefore does not multiply rows. Python + stdlib only, matching the existing
`local_env/pandas-client` precedent; no new tooling requirement beyond a Python 3 on PATH.

*Alternative rejected:* a `.sql` file applied with `clickhouse-client`. Generating hundreds of rows
spread over a time range needs loops and randomness; a script is clearer than generated SQL, and the
seeder can be pointed at a different host/table by flag.

### D6. Container → host addressing via `host.docker.internal`, with **no** `extra_hosts`

Grafana's datasource URLs use `host.docker.internal:5000` and `host.docker.internal:32010`, supplied
as `ADAS_BASE_URL` / `ADAS_FLIGHT_ADDR` env vars so a developer running ADAS elsewhere can repoint
without editing a file.

*Corrected during verification.* The original plan added
`extra_hosts: ["host.docker.internal:host-gateway"]` to make the name resolve on plain Linux Docker
too. On Docker Desktop that is actively harmful: Desktop already maps the name to the real host
(`192.168.127.254` on this machine), and the `host-gateway` alias **overrides** it with the in-VM
bridge address (`172.17.0.1`), so every ADAS request fails `connect: connection refused`. The entry
is therefore omitted, and the Linux case is handled by overriding the *address* instead of the
hostname (`ADAS_BASE_URL=http://172.17.0.1:5000`), documented in the README.

## Risks / Trade-offs

- **The Flight SQL plugin may fail on metadata RPCs or prepared statements** → Accepted and
  isolated: the dashboard runs on Infinity, so this failure mode degrades the experiment's *second*
  answer, not its first. The exact failing RPC and the plugin's error text get recorded in the
  README, which is a directly actionable follow-up (implementing `GetSqlInfo`/`GetTables` is a
  bounded change to `AdasFlightSqlProducer`).
- **Plugin install needs the network at container start** (`GF_INSTALL_PLUGINS`) → Documented; the
  failure is loud (Grafana logs the download error and the datasource shows as an unknown type)
  rather than silent. Air-gapped developers can pre-seed the plugin volume.
- ~~Grafana's time picker will very likely not drive ADAS panels~~ → **Did not materialise**; the
  pushdown works (see revised D4). The residual risk is the timezone of `${…:date:…}`, handled by
  pinning the dashboard to UTC and documenting why.
- **1000-row maximum bites raw-data panels** → Every provisioned panel aggregates. Documented as
  the first thing a dashboard author must internalise.
- **Anonymous admin Grafana** → Localhost-only, dev-only, provisioned read-only dashboards, no
  secrets in the container. Called out in the README so nobody copies the file into a deployment.
- **Seeded rows look like real DIAL usage but are fabricated** → The seeder marks every row with a
  recognisable `_source_file` value (`local-seed`) so seeded data can always be told apart from, and
  deleted separately to, anything `data-sync` ingested.
- **A second Compose service on a fresh dev machine adds startup time and a port (3000)** →
  Profile-gated (D1), so only opted-in runs pay it; the port is the Grafana default and documented.

## Migration Plan

Not applicable — local development tooling only. Nothing is deployed, and "rollback" is
`docker compose --profile grafana down` plus deleting the seeded rows
(`ALTER TABLE dial_usage_log DELETE WHERE _source_file = 'local-seed'`).

## Open Questions

All three were deliberately left to the verification step rather than guessed, and all three are now
answered by observation:

1. **What JSON encoding does `POST /v1/queries/execute-sql` emit for a `timestamp` result column?**
   → **ISO-8601 with a `Z` suffix** (`"2026-07-16T10:00:00Z"`), not epoch millis. The Infinity
   column type is therefore `timestamp`, and Grafana turns it into a genuine `time` field.
2. **Does the Flight SQL plugin reach a successful query against statement-only ADAS?**
   → **Yes for execution, with two caveats.** First, the plugin ID guessed here does not exist:
   the datasource is `influxdata-flightsql-datasource` (InfluxData), and there is no
   `grafana-flightsql-datasource` — asking for one makes the container's entrypoint exit 1. Second,
   with the right plugin, ad-hoc statements execute fine (same 24/34 row counts as REST, correct
   values, correct column types), so `UNIMPLEMENTED` on prepared statements is *not* a blocker —
   but (a) the plugin's "Save & Test" probes with `SELECT 1`, which ADAS rejects because the DSL
   needs a single-entity `SELECT`, so the datasource always shows red in the UI, and (b) timestamp
   values come out wrong: ADAS emits `Timestamp(MILLISECOND, UTC)` per its spec, and the plugin
   reads the values as nanoseconds, landing every time-series point in January 1970. Both are
   plugin-side, recorded as follow-ups.

   **Superseded after archiving — the conclusion "Flight SQL is unusable for time series" was about
   the plugin, not the transport.** The defect is `pkg/flightsql/arrow.go` hardcoding
   `ToTime(arrow.Nanosecond)`; upstream issue #145 has named it and supplied the patch since
   2023-06-16, but no PR took it and the repo is archived at v1.1.1. Swapping in the maintained
   `gizmodata-gizmosql-datasource` (which derives the unit from the Arrow schema) makes Flight SQL
   work: verified against this ADAS with results identical to REST, and the dashboard now carries a
   Flight-backed panel alongside the REST ones. Caveat: that plugin's own `$__timeFrom`/`$__timeTo`
   macros emit RFC3339, which ADAS rejects — the time range has to go through Grafana's global
   variables, as the REST panels already do. Full verdicts in `local_env/grafana/README.md`.
3. **Can Grafana's dashboard time range be pushed into the ADAS SQL?** → **Yes**, via Infinity's
   interpolation of `$__from`/`$__to` into `TIMESTAMP` literals; verified by row-count change across
   ranges. See revised D4. This is why the REST path — not Flight — carries the dashboard.
