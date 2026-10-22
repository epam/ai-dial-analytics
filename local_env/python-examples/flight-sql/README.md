# Arrow Flight SQL clients (pandas / ADBC)

Standalone Python clients for the ADAS Arrow Flight SQL endpoint, using the stack an
external analytical consumer would — pandas over the ADBC Flight SQL driver:

- **`arrow_client.py`** — a small, linear working example: runs a handful of queries and prints
  the results; queries that must be rejected by server-side governance are expected to fail
  (exits non-zero if one is accepted).
- **`verify_client.py`** — the self-checking verification client: 7 named PASS/FAIL checks with
  exit codes (`0` all passed, `1` at least one failed, `2` endpoint unreachable). The checks are
  self-consistent (server aggregate vs pandas recomputation of the same extract), so they pass on
  any `dial_usage_log` content, including an empty table.
- **`describe_client.py`** — schema introspection without execution: a bare Flight
  `getFlightInfo` returns the typed Arrow result schema of a statement (incl.
  `list<item: …>` for array columns) while never running it against ClickHouse; validation
  happens there too, so it doubles as a pre-flight check. Speaks raw Flight via `pyarrow`
  because the ADBC driver's `adbc_execute_schema()` needs prepared statements, which
  statement-only ADAS answers with `UNIMPLEMENTED`.

What they demonstrate/verify:

1. Plain `cursor.execute()` works end to end — the ADBC driver attempts to PREPARE every
   statement, ADAS answers `UNIMPLEMENTED` (statement-only v1), and the driver silently
   falls back to ad-hoc execution
2. Typed results — timestamps arrive as tz-aware `datetime64` columns, no epoch-millis parsing
3. Server-side `GROUP BY` aggregation
4. Governance rejections: `LIMIT 50000` (above the Flight row maximum, `FLIGHT_MAX_ROWS`, 10000 by
   default), SQL the structured-query DSL cannot express (a comparison as an `if` condition), and
   write statements — all fail with `INVALID_ARGUMENT`

## Authentication

All three clients take `--api-key`, defaulting to `ADAS_API_KEY`, and forward it as the `api-key` gRPC
call header — through the ADBC option `adbc.flight.sql.rpc.call_header.api-key` in `arrow_client.py`
and `verify_client.py`, and as a raw Flight call header in `describe_client.py` — so the same examples
run against an endpoint secured with `security.mode=oidc`. It's only needed there — the local
`docker-compose` default is `security.mode=none`, so leave `ADAS_API_KEY` unset for open access.
Prefer the environment variable over the flag for a real key, which otherwise lands in shell history.

## Prerequisites

1. Local data stores: `docker compose -f local_env/docker-compose.yml up -d --wait` (repo root).
2. ADAS with the Flight endpoint enabled:
   - bash: `FLIGHT_ENABLED=true ./gradlew bootRun`
   - PowerShell: `$env:FLIGHT_ENABLED='true'; ./gradlew bootRun`
3. Python ≥ 3.11 on PATH.

## Setup (once)

```bash
cd local_env/python-examples/flight-sql
python -m venv .venv
source .venv/bin/activate        # PowerShell: .\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

## Run

All three take `--flight-uri` (env `ADAS_FLIGHT_URI`) and `--api-key` (env `ADAS_API_KEY`), so they
compose with each other and with `../tables/`. `arrow_client.py` and `describe_client.py` also take
`--entity`; `verify_client.py` does not — its checks are written against `dial_usage_log`'s own
columns, so it stays pinned to that table.

```bash
python arrow_client.py                                    # the example; grpc://localhost:32010
python arrow_client.py --flight-uri grpc://host:port      # other endpoint
ADAS_API_KEY=dial_xxx python arrow_client.py              # authenticate (security.mode=oidc)
python verify_client.py                                   # the PASS/FAIL verification checks
python describe_client.py                                 # schema introspection examples
python describe_client.py "SELECT event_kind FROM dial_usage_log"   # describe one statement
python arrow_client.py --help                             # every flag
```
