# Grafana dashboards for ADAS

Seven ready-made Grafana dashboards over DIAL usage data, in [`grafana/dashboards/`](../grafana/dashboards/).
Every panel queries ADAS over `POST /v1/queries/execute-sql` — never ClickHouse directly — so each
one runs inside the governed pipeline: entity and field validation, the closed function catalog,
per-column visibility, and the row maximum. There is no query gateway, no driver and no database
credential anywhere in the path; Grafana holds one API key and one URL.

This page is how to install, configure and operate them. For the SQL surface the panels are written
against, see [sql-reference.md](sql-reference.md). The dashboards run over REST rather than Arrow
Flight SQL because both Grafana Flight SQL plugins are third-party, and one of them silently misreads
the Arrow time unit, plotting every timestamp in 1970.

## The dashboards

| Dashboard | UID | Panels | Queries | Reads |
|---|---|---|---|---|
| DIAL usage | `adas-dial-usage` | 3 | 2 | `dial_usage_log` |
| Cost & model mix | `adas-cost-model-mix` | 8 | 7 | `dial_usage_log`, `usage_client_identity` |
| AI gateway populations | `adas-gateway-populations` | 8 | 7 | `dial_usage_log`, `conversations`, `usage_client_identity`, `conversation_insights` |
| AI supply chain | `adas-ai-supply-chain` | 7 | 6 | `dial_usage_log` |
| Unit economics | `adas-unit-economics` | 7 | 6 | `dial_usage_log`, `conversations`, `usage_client_identity`, `conversation_insights` |
| Agentic depth & tool frontier | `adas-agentic-depth` | 6 | 5 | `dial_usage_log` |
| What the AI is actually used for | `adas-session-insights` | 12 | 11 | `sessions`, `session_insights` |

`adas-dial-usage` needs nothing but ingested usage logs and is the one to install first — if it
draws, the datasource, the credential and the time-range interpolation are all correct.

`adas-ai-supply-chain` and `adas-agentic-depth` read `dial_usage_log` alone too, so those three are
the set a seeded local ADAS can draw. The remaining four need more of the catalog.
`usage_client_identity`, `session_insights` and `conversation_insights` are **enrichment tables
read as dotted columns** on their spine
(`"usage_client_identity.client_type"`), not as joins; `sessions` and `conversations` are aggregate
rollups. A dashboard whose tables are missing from the catalog fails per panel with a
`400 bad_request` naming the unresolved table, which is a deployment state rather than a dashboard
defect — check `GET /v1/queries/entities` for what the target ADAS actually exposes before
concluding otherwise.

Where an enrichment table exists but has not yet covered the window, the spine row is still
returned: in these single-entity statements the dotted columns read their **type default** (`''`
for a non-nullable string), not `NULL`. The dashboards that depend on labels carry a coverage panel
for exactly that reason — read it before reading anything else.

## Prerequisites

- **Grafana 11.6 or newer.** Developed and pinned on 11.6.3; also verified importing and rendering
  on a vanilla 13.2.2.
- **The Infinity datasource plugin**, `yesoreyeram-infinity-datasource`. It is a generic JSON/REST
  datasource: it posts `{"sql": "..."}` and reads the `{"rows": [...]}` envelope back. Verified on
  3.11.1 (pinned in the preview stack) and on 4.0.0.
- **A reachable ADAS** with the `rest-sql-endpoint` capability — that is, `POST /v1/queries/execute-sql`
  answering.
- **An API key** when the target runs `security.mode=oidc`. Each endpoint requires a named privilege, so
  the key's principal must hold `TABLE_READ` on the tables the panels query (globally, or granted per
  table) plus `SAVED_QUERY_READ` if a panel reads a saved query; the `READ_ONLY_ANALYTIC` bundle is the
  right fit for a dashboard. Note that `sensitive` columns resolve only with the `TABLE_SENSITIVE` clearance — no
  panel here
  selects one, so a read-only key loses nothing.

## Install

### 1. The plugin

```bash
grafana-cli plugins install yesoreyeram-infinity-datasource
```

Or, for a containerised Grafana, `GF_INSTALL_PLUGINS=yesoreyeram-infinity-datasource 3.11.1`.
Pin the version: the panels depend on two Infinity behaviours (backend interpolation of Grafana's
`__from`/`__to` globals, and the backend parser's field ordering) that an unannounced plugin
upgrade could change with nothing in the dashboards to show for it.

### 2. The datasource

[`grafana/provisioning/datasources/adas.yml`](../grafana/provisioning/datasources/adas.yml) is the
reference. Copy it into your Grafana's provisioning directory and supply two environment variables:

```bash
ADAS_BASE_URL=https://adas.example.com
ADAS_API_KEY=<a DIAL API key mapped to READ_ONLY_ANALYTIC>
```

Four properties of that file are load-bearing:

- **`uid: adas-rest-sql`.** Every panel references the datasource by this uid. The Grafana UI cannot
  set a uid, so either provision the datasource (which can) or create it with
  `POST /api/datasources` and `"uid": "adas-rest-sql"` in the body.
- **`auth_method: apiKey` with `apiKeyKey: Api-Key`.** ADAS's own header. It is sent
  unconditionally: a `security.mode=none` ADAS ignores it, so one file serves both a local and a
  secured target.
- **`allowedHosts`.** Infinity refuses to send credentials to a host that is not allow-listed, and
  the failure reads *"URL is not in the allowed list"*, which points nowhere near the cause. It must
  contain the base URL. This stops being optional the moment `auth_method` is anything but `none`.
- **`secureJsonData.apiKeyValue`.** Grafana stores it encrypted and never serves it back. Inject the
  key from your secret manager; do not write one into the file.

To use a datasource you already have instead, rewrite the uid in the dashboards rather than the
datasource: `sed -i 's/adas-rest-sql/<your-uid>/g' grafana/dashboards/adas-*.json`.

### 3. The dashboards

Two routes, and the choice has a consequence worth understanding.

**File provisioning** — the dashboards stay read-only and cannot drift from what is in git:

```yaml
# provisioning/dashboards/adas.yml — see grafana/provisioning/dashboards/adas.yml
apiVersion: 1
providers:
  - name: adas
    folder: ADAS
    type: file
    allowUiUpdates: false
    options:
      path: /etc/adas-dashboards      # wherever you mounted grafana/dashboards/
```

**HTTP import** — the dashboards land in Grafana's database and are editable, which also means a
save from the UI rewrites one in the newer schema version and it no longer matches the file here:

```bash
for f in grafana/dashboards/adas-*.json; do
  curl -sS -X POST "$GRAFANA_URL/api/dashboards/db" \
    -H "Authorization: Bearer $GRAFANA_TOKEN" -H 'Content-Type: application/json' \
    -d "$(jq -n --slurpfile d "$f" '{dashboard: $d[0], overwrite: true}')"
done
```

The files are deliberately **not** in Grafana's "export for sharing" format. That format's `${DS_*}`
placeholders are substituted by the import endpoint only — the file provisioner stores a dashboard
verbatim, so an exported copy would provision with dead datasource references and both variants
would have to be maintained.

The seven UIDs are stable, so re-installing a newer copy updates each dashboard in place instead of
creating a duplicate.

## Verify

[`grafana/verify_dashboards.py`](../grafana/verify_dashboards.py) re-runs every panel query against a
live ADAS without Grafana in the path. It reads the SQL back out of the dashboard files, resolves
the same `__from`/`__to` and template-variable placeholders Grafana would, and posts each statement
to `execute-sql`:

```bash
ADAS_BASE_URL=https://adas.example.com ADAS_API_KEY=$MY_KEY python grafana/verify_dashboards.py
```

Exit status is 0 only when every query returns 200. Two report lines matter as much as the failures:

- **`AT CAP`** — the query returned exactly 1000 rows, so the panel is silently showing part of its
  data (see below).
- **`no rows`** — the query is valid but the window holds nothing, which is usually missing
  enrichment coverage rather than a broken panel.

A green run proves the SQL contract only. It does not exercise rendering, and the frontend-only
mechanics the panels depend on — `partitionByValues` splitting a long-format result into series,
`organize` restoring the `SELECT` column order — are not covered. Open a dashboard in a browser
after installing.

## The operating envelope

Read this before writing or widening a panel.

- **1000 rows is a hard maximum**, a `LIMIT` above it is rejected rather than clamped (`400`), the
  default when a statement omits `LIMIT` is 100, and a result of exactly 1000 rows carries no marker
  saying it was truncated — the graph simply stops partway. Aggregate server-side; a raw-row panel
  is not slow but wrong. The cap applies to the rows the panel receives, so for a time series it is the **bucket**
  count that matters: hourly buckets fit ~41 days, and a long-format series (one row per bucket
  *per* series) divides that by the series count. That is why several panels bucket daily or
  3-hourly. To widen a range, widen the `date_bin` unit first.
- **Grafana macros do not exist here.** No `$__timeFilter`, no `$__timeInterval`, no `$__interval`.
  The range reaches ADAS as ordinary SQL literals —
  `TIMESTAMP '${__from:date:YYYY-MM-DD HH:mm:ss}'` — which Infinity interpolates in its backend
  before the request. Bucket widths are therefore fixed by the SQL and do **not** follow panel width.
- **Every dashboard pins `timezone: utc`, and it must stay that way.** The `${…:date:…}` formatter
  renders in the browser's clock; on a browser-timezone dashboard the picker and the SQL window
  would disagree by the viewer's offset, with nothing to warn you.
- **`ORDER BY … DESC` puts NULLs first**, where ClickHouse puts them last. Every `DESC` on a
  nullable sort key in these dashboards spells `DESC NULLS LAST` for that reason, and so should any
  panel you add — otherwise a "top N by cost" panel leads with the rows that have no cost. (The two
  `DESC`s here without it sort on a `count` or a filtered sum that cannot be null.)
- **Enrichment is read as dotted columns**, e.g. `"usage_client_identity.client_type"`. A missing
  enrichment row never drops the spine row; what it reads depends on the statement shape. Every
  panel here is a plain single-entity statement, and there the column reads its **type default** —
  `''` for a non-nullable string — *not* `NULL`. `NULL` is what an unmatched side yields only
  inside an explicit join ([sql-reference.md](sql-reference.md#joins)); joins *are* supported by
  ADAS, but these dashboards need none. That is why the shipped coverage panels test `<> ''`:
  a panel written as `WHERE "usage_client_identity.client_type" IS NULL` returns zero rows and
  reads as complete coverage when coverage is in fact absent.
- **Array and object columns are near-projection-only.** An `array` column (`execution_path`,
  `"usage_client_identity.user_roles"`) may be projected and passed as argument 0 of the array
  functions — `array_length`, `array_join`, `array_has`, `array_has_any`, `array_has_all`,
  `array_to_string`, `array_slice`. An `object` column (`jwt_claims`, `request_tags`) may be
  projected, passed as argument 0 of `json_extract_*`, and used as the **left** operand of
  `=`, `<>`, `IN` or `LIKE` against a literal in `WHERE`. Nothing else — no bare `GROUP BY`,
  `ORDER BY`, `DISTINCT` or arithmetic on the column itself. The scalar *result* of one of those
  functions is an ordinary value carrying none of the restrictions, which is how panels group by
  and filter on `array_length(execution_path)`.
- **A non-Latin-1 string literal is currently answered with HTTP 500**, which is why panel labels use
  ASCII `->` and `-` rather than `→` and `—`.

The full dialect, including what is and is not supported, is [sql-reference.md](sql-reference.md).
Panel-level caveats about the *data* live in the note panel each of the six analytics dashboards
opens with, "Read this before trusting a number" — start there when a number surprises you.
(`adas-dial-usage`'s note is titled "Read this before editing panels" and covers the SQL surface
instead.)

### The `Client` variable on `adas-session-insights`

It is a **fixed list**, not a query. A provisioned Infinity query-variable does not resolve: its
option list comes back empty, `${client_type:singlequote}` expands to nothing, every panel reads
`client_type IN ()` → "No data", and Grafana renders nothing at all until every variable resolves.
A new client type appears only once the list is regenerated. Get the values:

```sql
SELECT client_type, count(*) AS sessions
FROM sessions
WHERE first_request_time >= TIMESTAMP '2026-08-01 00:00:00'
GROUP BY client_type
ORDER BY sessions DESC NULLS LAST
LIMIT 50
```

then update `templating.list[0].query` (comma-separated) and the matching `options` array in
`grafana/dashboards/adas-session-insights.json`.

## The local preview stack

[`grafana/docker-compose.yml`](../grafana/docker-compose.yml) brings up a throwaway Grafana with the
plugin, the datasource and the dashboards already wired, for looking at a dashboard change before
committing it:

```bash
docker compose -f local_env/docker-compose.yml up -d --wait   # ClickHouse + PostgreSQL
./gradlew bootRun                                             # ADAS on :5000
python local_env/seed/seed_dial_usage_log.py                  # something to plot
docker compose -f grafana/docker-compose.yml up -d --wait     # Grafana on :3000
```

A seeded local ADAS has `dial_usage_log` only, so four of the seven dashboards need a deployment
that carries the rollups and enrichment; `adas-dial-usage`, `adas-ai-supply-chain` and
`adas-agentic-depth` draw locally. Point the same stack at a deployment — both values are
environment variables, so no file changes:

```bash
ADAS_BASE_URL=https://adas.example.com ADAS_API_KEY=$MY_KEY \
  docker compose -f grafana/docker-compose.yml up -d --wait
```

**This stack is a development convenience and must not be deployed.** It runs anonymous access with
the login form disabled, which is safe only because it publishes on loopback
(`${GRAFANA_BIND_ADDR:-127.0.0.1}`) and holds no secret of its own. Its anonymous role is `Editor`
rather than `Admin` deliberately: an `Admin` can create datasources, and a fresh ClickHouse
datasource pointed at the local container would read `dial_usage_log` directly — body columns and
all — bypassing every control the dashboards exist to respect. If you override `GRAFANA_BIND_ADDR`,
turn anonymous access off in the same breath.

Three failure modes that cost an hour each if you meet them cold:

- **A wrong plugin id is an endless restart loop, not an error message.** `GF_INSTALL_PLUGINS` makes
  the entrypoint exit 1 (`404: Plugin not found`), and `restart: unless-stopped` retries forever.
- **`GF_PLUGINS_PREINSTALL_DISABLED=true` is mandatory on Grafana 11.x**, and the failure it prevents
  looks like broken dashboards rather than a broken plugin: 11.6.3 auto-preinstalls
  `grafana-lokiexplore-app`, whose `module.js` imports a SystemJS external this build does not
  serve, and the resulting 404 breaks the module resolver for *every* plugin — all panels hang on
  "Loading plugin panel…" with the only clue in the browser console. It takes effect on a fresh
  volume only, so flipping it needs `down -v`.
- **`./gradlew bootRun` can die with `Migration checksum mismatch` before Grafana is involved.** The
  shared `adas-postgres` volume of `local_env` can hold migrations from branches that never merged,
  whose checksums no longer match this branch's. Run against a throwaway database rather than
  repairing the volume — and leave ClickHouse alone, so the seeded rows and the dashboards keep
  looking at the same data:

  ```bash
  docker exec adas-postgres psql -U postgres -d postgres -c "CREATE DATABASE adas_grafana"
  POSTGRES_DATABASE=adas_grafana ./gradlew bootRun
  ```
