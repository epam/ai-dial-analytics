# Claude Code telemetry into ADAS

Claude Code speaks OTLP, and ADAS is an OTLP backend ([`otel-sink`](../openspec/specs/otel-sink/spec.md)),
so Claude Code usage — sessions, tokens, cost, prompts, tool results, API errors — can land straight in
ClickHouse and be queried through the same governed read path as every other ADAS table.

Three steps, in this order:

1. **Provision the sink** on ADAS (`POST /v1/otel/sinks`) — this creates the landing tables.
2. **Grant the exporter `write`** on those tables.
3. **Configure Claude Code** to export to the sink's endpoint (`env` block in `settings.json`).

Doing it the other way round just fills the log with `404 not_found`: the exporter's target does not
exist until the sink does.

> **Which signals?** Claude Code emits **metrics** and **logs** out of the box; traces only with
> `CLAUDE_CODE_ENHANCED_TELEMETRY_BETA=1`. Provision the signals you actually intend to export —
> exporting to a signal the sink did not enable is answered `404 not_found`.

## Prerequisites

- An ADAS instance with the OTLP surface enabled (`OTEL_SINK_ENABLED` defaults to `true`).
- An **operator credential** for the setup steps below: **`TABLE_CREATE`** (to create the sink),
  **`TABLE_GRANT`** (to grant write access), **`TABLE_READ`** (the verification queries at the end)
  and, if you follow the teardown, **`TABLE_DELETE`**. The `FULL_ADMIN` bundle covers all four. In `oidc` mode this is a Bearer token or a DIAL API key
  whose Core roles map to those privileges.
- If you want to use API keys (the `Api-Key` header, as every example below does), API-key
  authentication has to be switched on: `API_KEY_ENABLED=true` plus `API_KEY_CORE_URL`. It is **off by
  default**, and while it is off the header is ignored and the request is rejected `401`. Otherwise send
  `Authorization: Bearer <token>` instead — note that an `Authorization` header of any scheme always
  takes precedence, so never send both.
- An **exporter credential** for the Claude Code side. It needs *no* privilege mapping — only the
  `write` grant on the sink's landing tables (see step 2), which `TABLE_WRITE` authorizes without
  conferring any read. A key scoped that narrowly cannot read `/v1/otel/sinks` (it answers
  `403 forbidden`) and the landing tables stay absent from its catalog listings; that is expected, not
  a misconfiguration.

`https://<adas-host>` below stands for the ADAS base URL of the environment you are wiring up —
`http://localhost:5000` when running locally.

## Step 1 — provision the sink

```bash
curl -sS -X POST "https://<adas-host>/v1/otel/sinks" \
  -H "Api-Key: $ADAS_ADMIN_KEY" \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "claude_code",
        "description": "Claude Code CLI telemetry",
        "signals": ["logs", "metrics"],
        "retention_days": 90
      }'
```

The sink name is snake_case (`^[a-z][a-z0-9_]*$`, at most 51 chars) and becomes both the URL segment and
the table prefix. Response (HTTP 201):

```json
{
  "name": "claude_code",
  "description": "Claude Code CLI telemetry",
  "created_at": "2026-08-21T09:12:44Z",
  "endpoint": "/v1/otel/sink/claude_code",
  "signals": [
    {"signal": "logs",    "table": "otel_claude_code_logs",    "status": "active"},
    {"signal": "metrics", "table": "otel_claude_code_metrics", "status": "active"}
  ]
}
```

`endpoint` is a **path**: prefix it with the host and that is what the exporter is pointed at — it appends
`/v1/logs` and `/v1/metrics` itself.

Omit `retention_days` to take the configured default (`OTEL_DEFAULT_RETENTION_DAYS`, 30). In the body it
must be ≥ 1 — a `0` there is rejected `422 validation_error`; it is `OTEL_DEFAULT_RETENTION_DAYS=0` that
creates the tables with no TTL at all.

The signal set is fixed at creation. To add traces later, `DELETE /v1/otel/sinks/claude_code` (which
drops the landing tables and their data) and recreate it with `["logs", "metrics", "traces"]`.

## Step 2 — let the exporter write

Ingest is authorized exactly like `POST /v1/tables/{name}/rows`: authenticated at the URL layer, then the
caller must hold `TABLE_WRITE` on the signal's landing table. Grant it per table, as a `TABLE_GRANT`
holder
([table access control](../openspec/specs/table-access-control/spec.md)):

```bash
for t in otel_claude_code_logs otel_claude_code_metrics; do
  curl -sS -X PUT "https://<adas-host>/v1/tables/$t/access" \
    -H "Api-Key: $ADAS_ADMIN_KEY" \
    -H 'Content-Type: application/json' \
    -d '{"write": ["claude-code-telemetry"], "modify": []}'
done
```

`write` holds **raw provider role names** (the same names used as `roles-mapping` keys), matched against
the roles the exporter credential actually carries — for a DIAL API key, the roles Core returns for it.
A credential holding global `TABLE_WRITE` may export without any grant, but don't hand one to every
developer's CLI: a
narrow key that can write these two tables and nothing else is the point of the per-table grant.

## Step 3 — configure Claude Code

Put this in `~/.claude/settings.json` (per user) or, for a team, in the managed/enterprise settings file
so it applies to every developer:

```json
{
  "env": {
    "CLAUDE_CODE_ENABLE_TELEMETRY": "1",
    "OTEL_LOGS_EXPORTER": "otlp",
    "OTEL_METRICS_EXPORTER": "otlp",
    "OTEL_EXPORTER_OTLP_PROTOCOL": "http/json",
    "OTEL_EXPORTER_OTLP_ENDPOINT": "https://<adas-host>/v1/otel/sink/claude_code",
    "OTEL_EXPORTER_OTLP_HEADERS": "Api-Key=<exporter-key>",
    "OTEL_RESOURCE_ATTRIBUTES": "service.name=claude-code",
    "OTEL_LOG_USER_PROMPTS": "1",
    "OTEL_LOG_ASSISTANT_RESPONSES": "1"
  }
}
```

The same names work as plain environment variables if you prefer to export them from a shell profile or a
CI job. Restart Claude Code after changing them — telemetry config is read at startup.

What matters and why:

| Variable | Value | Why |
| --- | --- | --- |
| `CLAUDE_CODE_ENABLE_TELEMETRY` | `1` | Master switch; nothing is exported without it. |
| `OTEL_LOGS_EXPORTER` / `OTEL_METRICS_EXPORTER` | `otlp` | One per signal the sink enabled. Leave a signal's exporter unset (or `none`) if the sink does not land it. |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/json` | **Required, and easy to forget** — leaving it unset falls back to the OTLP default `grpc`, which never reaches the endpoint. ADAS accepts OTLP/HTTP **JSON** only; `http/protobuf` is answered `415 unsupported_media_type`. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `https://<adas-host>/v1/otel/sink/<sink>` | The sink **base** path — no `/v1/logs` suffix. The exporter appends the signal path itself. |
| `OTEL_EXPORTER_OTLP_HEADERS` | `Api-Key=<key>` | `key=value`, comma-separated for several. Use `Authorization=Bearer <token>` instead if the exporter identity is an OIDC token. |
| `OTEL_RESOURCE_ATTRIBUTES` | `service.name=claude-code` | Claude Code does not set `service.name` itself, and `service_name` is the **leading ordering key** of both landing tables — leave it unset and every row lands with an empty one. Add your own dimensions here too (`,team.id=platform`). |
| `OTEL_LOG_USER_PROMPTS` | `0` / `1` | `1` stores prompt **text** in the logs table. Off by default — see [Privacy and retention](#privacy-and-retention). |
| `OTEL_LOG_ASSISTANT_RESPONSES` | `0` / `1` | Same for response text; falls back to `OTEL_LOG_USER_PROMPTS` when unset. |

Optional knobs worth knowing: `OTEL_METRIC_EXPORT_INTERVAL` (default `60000` ms) and
`OTEL_LOGS_EXPORT_INTERVAL` (default `5000` ms) set how long you wait for the first rows;
`OTEL_LOG_TOOL_DETAILS=1` un-redacts tool names, parameters and executed commands on the tool events
(distinct from `OTEL_LOG_TOOL_CONTENT`, which puts tool input/output into spans and so needs tracing);
`OTEL_METRICS_INCLUDE_SESSION_ID=false` and `OTEL_METRICS_INCLUDE_ACCOUNT_UUID=false` drop attributes that
are on by default, if you want less per-session/per-user detail. Metrics default to **delta** temporality
(`OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE`), which is what makes `sum(point_value)` over a time
window the right way to total a counter.

Do **not** set `OTEL_TRACES_EXPORTER=otlp` unless the sink enabled `traces` — and traces additionally
require `CLAUDE_CODE_ENHANCED_TELEMETRY_BETA=1`.

## Step 4 — verify

Run a prompt or two in Claude Code, wait one metrics interval (~60 s), then read the tables back:

```bash
curl -sS -X POST "https://<adas-host>/v1/queries/execute-sql" \
  -H "Api-Key: $ADAS_ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"sql": "SELECT event_time, metric_name, point_value, point_attributes FROM otel_claude_code_metrics ORDER BY event_time DESC LIMIT 20"}'
```

If nothing arrives, check in this order. The exporter's own HTTP failures are not shown in the normal
Claude Code UI — start the CLI with `claude --debug` to see them (the ADAS access log is the other end of
the same story):

| Symptom | Cause |
| --- | --- |
| Nothing at all, no errors | `CLAUDE_CODE_ENABLE_TELEMETRY` unset, or Claude Code not restarted after the edit. |
| `401 unauthorized` | No usable credential: header missing, or `Api-Key` sent while API-key auth is disabled (`API_KEY_ENABLED`). |
| `415 unsupported_media_type` | `OTEL_EXPORTER_OTLP_PROTOCOL` is not `http/json`. |
| `404 not_found` | Wrong sink name, a signal the sink did not enable, or `/v1/logs` left on the endpoint URL (it becomes `.../v1/logs/v1/logs`). |
| `403 forbidden` | The exporter credential holds no `write` grant on that landing table (step 2). |
| `409 conflict` | The landing table was deleted through `/v1/tables`, or is not `active`. |
| `422 validation_error` | One export flattened to more rows than `OTEL_MAX_ROWS_PER_REQUEST` (50 000), or a row failed the landing table's value checks. The batch is rejected whole, never partially. |
| `503 otel_disabled` | The OTLP surface is switched off (`OTEL_SINK_ENABLED=false`). |
| `503 clickhouse_unavailable` | ADAS cannot reach ClickHouse; the export is lost, not queued. |

## What lands where

Both tables are ordinary catalog source tables — listed by `GET /v1/tables`, queryable through
`/v1/queries`, `POST /v1/queries/execute-sql` and Flight SQL, and subject to per-table grants. Full
column reference: [`openspec/specs/otel-sink/spec.md`](../openspec/specs/otel-sink/spec.md).

**`otel_claude_code_metrics`** — one row per data point, identified by `metric_name`:
`claude_code.session.count`, `claude_code.token.usage`, `claude_code.cost.usage`,
`claude_code.lines_of_code.count`, `claude_code.commit.count`, `claude_code.pull_request.count`,
`claude_code.code_edit_tool.decision`, `claude_code.active_time.total`. The number itself is in
`point_value` (counters and gauges); everything that qualifies it — `model`, `type`, `decision` — is in
the `point_attributes` map.

**`otel_claude_code_logs`** — one row per event. The event's own fields land in the `log_attributes` map
(`body` is a scalar column of its own): `claude_code.user_prompt` (`prompt`, `prompt_length`),
`claude_code.assistant_response` (`response`, `response_length`, `model`), `claude_code.api_request`
(`model`, `cost_usd`, `duration_ms`, `input_tokens`, `output_tokens`, `cache_read_tokens`),
`claude_code.tool_result` (`tool_name`, `success`, `duration_ms`), `claude_code.api_error`,
`claude_code.tool_decision`, `claude_code.mcp_server_connection`, and the rest.

Identity rides on **both** ends: Claude Code attaches `session.id`, `user.id`, `user.email`,
`user.account_uuid`, `organization.id`, `terminal.type` (and your `OTEL_RESOURCE_ATTRIBUTES` keys) to
every record *and* sends them in the OTLP resource block, so they appear in `resource_attributes` and
again in `log_attributes` / `point_attributes`.

### Reading map columns

`resource_attributes`, `scope_attributes`, `log_attributes` and `point_attributes` are ClickHouse
`Map(String, String)` columns. A `map` field is still **projection-only** on its own — it cannot be
filtered, grouped or sorted as a whole — but three accessors read into one, and their results are
ordinary scalars you can group and filter by:

| Function | Returns | Use |
|---|---|---|
| `map_value(map, key)` | text | the value under one key; empty string when the key is absent |
| `map_contains(map, key)` | boolean | whether the key is present at all |
| `map_keys(map)` | array of text | which keys this row actually carries |

**The key is matched literally, not as a path.** Claude Code's attribute keys are dotted —
`user.email`, `session.id`, `user.account_id` — and `map_value(point_attributes, 'user.email')` looks up
that one flat key. It is not a `json_extract_*`-style path: nothing is traversed, and there is no `user`
sub-document to descend into.

So the per-model, per-person and per-agent breakdowns are now expressible directly:

```sql
SELECT map_value(point_attributes, 'model')        AS model,
       map_value(point_attributes, 'query_source') AS agent,
       sum(point_value)                            AS cost
FROM otel_claude_code_metrics
WHERE metric_name = 'claude_code.cost.usage'
  AND event_time >= '2026-08-01'
GROUP BY model, agent
ORDER BY cost DESC
```

Note the **bare string time bound** — an ISO-8601 string compared against a timestamp column now
coerces, so `'2026-08-01'`, `'2026-08-01 00:00:00'` and `'2026-08-01T00:00:00Z'` all work and the typed
`TIMESTAMP '…'` prefix is optional. This is what lets Grafana, Power BI and most generated SQL query
these tables unchanged.

Ratios and deltas work too, with `/` returning NULL rather than failing when a denominator is empty:

```sql
SELECT map_value(point_attributes, 'model') AS model,
       sum(point_value) / count(DISTINCT map_value(point_attributes, 'session.id')) AS cost_per_session
FROM otel_claude_code_metrics
WHERE metric_name = 'claude_code.cost.usage'
GROUP BY model
```

`map_contains` answers the question `map_value` cannot: an absent key and a key stored with an empty
value both read as the empty string, so use the presence test to tell them apart. `map_keys` is how you
discover what a producer actually sends — Claude Code's attribute set varies by `metric_name`, so
`array_length(map_keys(point_attributes))` and a projected `map_keys(...)` are the way to find the keys
worth querying. An array-returning call like `map_keys` is itself projection-only, except as the first
argument of an array-operand function: `array_has`/`array_has_any`/`array_has_all`/`array_length`,
`array_join`, which expands the array into one row per element, `array_to_string`, which renders it as
one text cell, or `array_slice`, which caps it. So `array_join(map_keys(point_attributes))`, aliased and
grouped by that alias, counts how often each attribute key is sent, and
`array_to_string(map_keys(point_attributes), ', ')` lists the keys of each point in one readable
column — text, so it can be grouped by and sorted on where the array itself cannot.

**`point_attributes` is a sensitive column.** It carries `user.email`, `user.id` and `user.account_id`
alongside `model` and `effort`, and the accessors make that identity aggregatable rather than merely
projectable — so the column resolves only for a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the table that
owns it, and sensitivity is whole-column: a principal
that cannot read it cannot read the non-identifying keys either. A reader without the role sees the
column as unknown rather than forbidden, which is deliberate (a `403` would confirm it exists). To expose
one key more widely, lift it into an enrichment column with `map_value`, where it carries its own
`sensitive` flag. `resource_attributes` and `scope_attributes` are not flagged — they describe the host,
OS and service, not the user.

Scalar columns you can slice on without an accessor: `event_time`, `service_name`, `metric_name`,
`metric_type`, `aggregation_temporality`, `point_value`/`point_count`/`point_sum` (metrics);
`event_time`, `service_name`, `severity_number`, `severity_text`, `body`, `trace_id` (logs).

One consequence still worth knowing: Claude Code discriminates its log events with an `event.name`
attribute (`user_prompt`, `api_request`, …) that lands in `log_attributes`, so separating the logs table
by event type means `WHERE map_contains(log_attributes, 'event.name')` and
`GROUP BY map_value(log_attributes, 'event.name')` rather than a column of its own.

## Privacy and retention

`OTEL_LOG_USER_PROMPTS=1` and `OTEL_LOG_ASSISTANT_RESPONSES=1` put **verbatim prompt and response text**
into `log_attributes`, and `user.email` is in `resource_attributes` whenever the CLI is authenticated.
Treat the sink tables accordingly: keep them out of broadly granted read paths, set `retention_days` to
the shortest window that answers your questions, and leave both flags at `0` if you only need volume,
cost and latency. `OTEL_LOG_TOOL_DETAILS=1` adds executed Bash commands to the same map — the same class
of data. The exporter key sits in plain text in `settings.json`, so keep that file out of version control
(or distribute it through managed settings).

Retention is a row-expiry TTL on `event_time`, fixed when the sink is created. To change it later, send
the landing table a `PATCH /v1/tables/{name}/schema` carrying a `ttl` member — a full
`{column, interval, unit}` object replaces the TTL, `{}` removes it, an omitted member leaves it alone
(a partial object is `422`). That needs `TABLE_MODIFY`, held globally or granted on the table. Recreating
the sink is the other
route, at the cost of the data.

## Turning it off

- **Per developer**: remove the `env` block (or set `CLAUDE_CODE_ENABLE_TELEMETRY` to `0`) and restart.
- **Whole surface**: `OTEL_SINK_ENABLED=false` on ADAS — every `/v1/otel/**` endpoint then answers
  `503 otel_disabled` and nothing is created or written.
- **Sink and data**: `DELETE /v1/otel/sinks/claude_code` (`TABLE_DELETE`) drops both landing tables.

## Reference

- [`openspec/specs/otel-sink/spec.md`](../openspec/specs/otel-sink/spec.md) — endpoints, error contract,
  landing schemas, OTLP payload mapping.
- [`docs/configuration.md`](configuration.md#otlp-sinks) — `OTEL_SINK_ENABLED`,
  `OTEL_MAX_ROWS_PER_REQUEST`, `OTEL_DEFAULT_RETENTION_DAYS`.
- Claude Code monitoring reference — <https://code.claude.com/docs/en/monitoring-usage>.
