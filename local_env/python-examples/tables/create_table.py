#!/usr/bin/env python3
"""Create the custom source table the write example needs, then materialize it in ClickHouse.

Two calls, on purpose:

    POST /v1/tables                  -> records the identity a table keeps for life (name, type, and
                                        the `write` discipline that picks its ClickHouse engine).
                                        No DDL yet: the table is PENDING.
    POST /v1/tables/{name}/schema    -> defines the physical schema and materializes it (CREATE TABLE
                                        with the writer credential), flipping the table to ACTIVE.

A table accepts rows only once it is ACTIVE -- writing to a PENDING or FAILED one is rejected with
409. Run `write_custom_table.py` next to fill it.

Prerequisites: the local stack (`docker compose -f local_env/docker-compose.yml up -d --wait`) and
ADAS (`./gradlew bootRun`). Python 3.11+, standard library only -- no pip install.

Usage:
    python local_env/python-examples/tables/create_table.py                     # create it
    python local_env/python-examples/tables/create_table.py --table my_metrics  # under a different name
    python local_env/python-examples/tables/create_table.py --drop              # drop it (DROP TABLE + catalog)
    ADAS_API_KEY=dial_xxx python local_env/python-examples/tables/create_table.py   # security.mode=oidc
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_BASE_URL = "http://localhost:5000"
DEFAULT_TABLE = "demo_widget_metrics"

# The physical schema, exactly as POST /v1/tables/{name}/schema takes it. `source_name` is the
# ClickHouse column -- what a row write is keyed by; `name` is what the query surface exposes.
SCHEMA = {
    "columns": [
        {"source_name": "event_id", "name": "event_id", "type": "uuid", "nullable": False,
         "description": "Unique id of one measurement"},
        {"source_name": "recorded_at", "name": "recorded_at", "type": "timestamp", "nullable": False,
         "display_name": "Recorded at"},
        {"source_name": "widget_id", "name": "widget_id", "type": "string", "nullable": False, "tag": "widget"},
        {"source_name": "region", "name": "region", "type": "string", "nullable": False, "tag": "widget"},
        {"source_name": "views", "name": "views", "type": "long", "nullable": False},
        {"source_name": "score", "name": "score", "type": "decimal", "nullable": False},
        {"source_name": "healthy", "name": "healthy", "type": "boolean", "nullable": False},
        # An array column is always non-nullable -- ClickHouse has no Nullable(Array(...)).
        {"source_name": "tags", "name": "tags", "type": "array", "element_type": "string"},
    ],
    # MergeTree ORDER BY. Physical column names, and mandatory for a source.
    "ordering_key": ["recorded_at", "event_id"],
    "partition_by": {"column": "recorded_at", "granularity": "month"},
    # Optional: the (unique row, monotonic version) pair the governed incremental scan pages by.
    "identity_column": "event_id",
    "version_column": "recorded_at",
}


def create(base_url: str, table: str, api_key: str | None) -> None:
    """POST /v1/tables then POST /v1/tables/{name}/schema -- identity first, then materialization."""
    identity = {
        "name": table,
        "type": "source",
        "description": "Demo custom table for the python-examples/tables example",
        # append (MergeTree) keeps every row; upsert_by_key (ReplacingMergeTree) collapses to the
        # latest row per ordering_key. Immutable: it picks the engine, frozen at materialization.
        "write": "append",
    }
    created = call("POST", f"{base_url}/v1/tables", api_key, identity)
    print(f"POST /v1/tables -> status={created['status']} (identity recorded, no DDL issued)")

    defined = call("POST", f"{base_url}/v1/tables/{table}/schema", api_key, SCHEMA)
    print(f"POST /v1/tables/{table}/schema -> status={defined['status']} "
          f"({len(defined['columns'])} columns, CREATE TABLE issued)")
    # Path-free on purpose: the caller's cwd is unknown (repo root, or local_env/python-examples/).
    print(f"\n'{table}' is ready. Write rows into it with write_custom_table.py"
          f"{'' if table == DEFAULT_TABLE else f' --table {table}'}")


def drop(base_url: str, table: str, api_key: str | None) -> None:
    """DELETE /v1/tables/{name} -- removes the catalog rows and issues DROP TABLE."""
    call("DELETE", f"{base_url}/v1/tables/{table}", api_key)
    print(f"DELETE /v1/tables/{table} -> catalog rows removed, DROP TABLE issued")


def call(method: str, url: str, api_key: str | None, payload: dict | None = None) -> dict:
    """One JSON call. A non-2xx response carries ADAS's ErrorView -- print its message, not a trace."""
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if api_key:
        # Only needed when ADAS runs with security.mode=oidc; harmless otherwise.
        request.add_header("Api-Key", api_key)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        try:
            detail = json.loads(detail).get("message", detail)
        except json.JSONDecodeError:
            pass
        if error.code == 409 and method == "POST":
            detail += " -- pass --drop first to replace it, or --table <other_name>"
        raise SystemExit(f"HTTP {error.code} from {url}: {detail}") from None
    except urllib.error.URLError as error:
        raise SystemExit(f"{url} is unreachable: {error.reason}") from None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.environ.get("ADAS_BASE_URL", DEFAULT_BASE_URL),
                        help=f"ADAS base URL (default {DEFAULT_BASE_URL}, env ADAS_BASE_URL)")
    parser.add_argument("--api-key", default=os.environ.get("ADAS_API_KEY"),
                        help="DIAL API key for the Api-Key header (env ADAS_API_KEY); only needed "
                             "when ADAS runs with security.mode=oidc")
    parser.add_argument("--table", default=DEFAULT_TABLE, help=f"table name (default {DEFAULT_TABLE})")
    parser.add_argument("--drop", action="store_true",
                        help="drop the table instead of creating it (DROP TABLE + catalog rows)")
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    if args.drop:
        drop(base_url, args.table, args.api_key)
    else:
        create(base_url, args.table, args.api_key)


if __name__ == "__main__":
    sys.exit(main())
