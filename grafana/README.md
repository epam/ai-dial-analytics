# Grafana dashboards for ADAS

Seven dashboards over DIAL usage data, every panel served by `POST /v1/queries/execute-sql`. They
are installable artifacts, not a demo: install them into your own Grafana, or bring up the local
preview stack here to look at a change before committing it.

**How to install, configure and operate them: [`docs/grafana-dashboards.md`](../docs/grafana-dashboards.md).**
That page owns the procedure — prerequisites, the datasource, both install routes, verification and
the operating envelope. This file is only the map of what is in this directory.

| Path | What it is |
|---|---|
| `dashboards/adas-dial-usage.json` | usage over time and top deployments; needs `dial_usage_log` alone, so install it first |
| `dashboards/adas-cost-and-model-mix.json` | spend by model deployment, cost decoupled from volume, prompt-cache coverage |
| `dashboards/adas-gateway-populations.json` | who uses the gateway, on one `user_ref` actor dimension |
| `dashboards/adas-ai-supply-chain.json` | the deployment call graph: chain depth, edges, the latency of indirection |
| `dashboards/adas-unit-economics.json` | cost per turn, per conversation, per outcome, per client |
| `dashboards/adas-agentic-depth.json` | how deep agentic work goes, what it costs, which tools it reaches |
| `dashboards/adas-session-insights.json` | the `sessions` + `session_insights` spine: what each session was for |
| `provisioning/datasources/adas.yml` | the one datasource — REST SQL over the Infinity plugin, env-parameterised |
| `provisioning/dashboards/adas.yml` | file-provider config, pointed at a mount of `dashboards/` |
| `verify_dashboards.py` | re-runs every panel query against a live ADAS; needs no Grafana |
| `docker-compose.yml` | the local preview stack — **development only**, never a deployment |

Each of the six analytics dashboards opens with its own "Read this before trusting a number" note
panel, covering what its figures mean and where they mislead; read that before reading the panels.
`adas-dial-usage`'s equivalent is titled "Read this before editing panels" and is about the SQL
surface rather than the data.

Nothing here connects to ClickHouse. That is the point: every query is subject to ADAS's governed
pipeline, and Grafana holds one API key and one URL rather than a database credential. Why this
runs over REST rather than Arrow Flight SQL is in
[`docs/grafana-dashboards.md`](../docs/grafana-dashboards.md).
