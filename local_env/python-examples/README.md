# Python examples

Standalone Python clients for ADAS, one directory per surface they exercise. Each has its own README
with the details; this page is the index.

| Directory | Surface | Needs |
| --- | --- | --- |
| [`flight-sql/`](flight-sql/) | Arrow Flight SQL — reading, over the ADBC driver + pandas, the way an external analytical consumer would. Includes a self-checking verification client and a schema-introspection example. | `pip install -r requirements.txt` |
| [`tables/`](tables/) | The REST table-management surface — create a custom table (`/v1/tables`) and write rows into it (`/v1/tables/{name}/rows`). | standard library only |

Row **writes** go through `tables/`; everything else here reads. For fabricated `dial_usage_log`
rows to read, see [`../seed/`](../seed/) — that one writes straight to ClickHouse, because
`dial_usage_log` is a `system` table whose row writes the API refuses.

## Shared prerequisites

1. Local data stores: `docker compose -f local_env/docker-compose.yml up -d --wait` (repo root).
2. ADAS: `./gradlew bootRun` — plus `FLIGHT_ENABLED=true` for the `flight-sql/` examples.
3. Python ≥ 3.11 on PATH.

## Every example takes the same flags

Each one is a `main()` with an `argparse` parser whose defaults come from the environment, so the
same invocation works whether you pass flags, export variables, or neither:

| Flag | Env | Applies to |
| --- | --- | --- |
| `--base-url` | `ADAS_BASE_URL` | REST examples (`tables/`) |
| `--flight-uri` | `ADAS_FLIGHT_URI` | Flight examples (`flight-sql/`) |
| `--api-key` | `ADAS_API_KEY` | all |
| `--table` | — | the table written to (`tables/`) |
| `--entity` | — | the entity queried — `arrow_client.py` and `describe_client.py` only (`verify_client.py` is pinned to `dial_usage_log`, whose columns its checks assert on) |

`--help` on any of them lists its own flags.

## Running them one after another

Point every example at the same endpoint once, then walk through them:

```bash
# from local_env/python-examples/, with flight-sql/.venv activated (see flight-sql/README.md) —
# the tables/ examples are standard-library only and run under any Python 3.11+
export ADAS_BASE_URL=http://localhost:5000        # defaults, shown for clarity
export ADAS_FLIGHT_URI=grpc://localhost:32010
export ADAS_API_KEY=dial_xxx                      # only for security.mode=oidc

python tables/create_table.py                     # create + materialize the custom table
python tables/write_custom_table.py               # write a row into it
python flight-sql/verify_client.py                # read path: 7 PASS/FAIL checks
python flight-sql/arrow_client.py                 # read path: worked examples
python tables/create_table.py --drop              # clean up
```

Or pass the same thing per-invocation instead — `python tables/create_table.py --base-url
http://host:5000 --api-key dial_xxx`.

The API key is only needed against `security.mode=oidc`; the local `docker-compose` default is
`security.mode=none`, so leave `ADAS_API_KEY` unset for open access. The REST examples send it as the
`Api-Key` header, the Flight examples as an `api-key` gRPC call header.
