#!/usr/bin/env python3
"""Write one row into a custom ADAS table: POST /v1/tables/{name}/rows.

Prerequisite: the table exists and is ACTIVE -- create it with `create_table.py`. Read the row back
with the queries in README.md.

Usage:
    python local_env/python-examples/tables/write_custom_table.py                    # -> inserted=1
    python local_env/python-examples/tables/write_custom_table.py --table my_metrics # a different table
    ADAS_API_KEY=dial_xxx python local_env/python-examples/tables/write_custom_table.py  # security.mode=oidc
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

# One row, keyed by PHYSICAL column name -- each column's `source_name`, not the `name` the query
# surface exposes. Two values need care:
#   recorded_at: ISO-8601 with an explicit zone -- 'Z' or an offset like '+03:00'. This is the same
#                form the read path returns, so a row you read back is writable as-is. A zone-less
#                or space-separated value is rejected with 422 (datetime.now(timezone.utc)
#                .isoformat() works; datetime.now().isoformat() does not -- it carries no zone).
#   score:       a string, so the digits reach ClickHouse intact rather than through a JSON float
#                (a number works too, at float precision).
ROW = {
    "event_id": "8f14e45f-ceea-4d1b-a1c0-9c1e6d3b7a52",
    "recorded_at": "2026-08-11T10:00:00Z",
    "widget_id": "gauge",
    "region": "emea",
    "views": 1280,
    "score": "94.25",
    "healthy": True,
    "tags": ["beta", "pinned"],
}


def write(base_url: str, table: str, api_key: str | None) -> None:
    """POST /v1/tables/{name}/rows -- `rows` is a list, so a batch is the same call with more rows."""
    response = call(f"{base_url}/v1/tables/{table}/rows", api_key, {"rows": [ROW]})
    print(f"POST /v1/tables/{table}/rows -> inserted={response['inserted']}")


def call(url: str, api_key: str | None, payload: dict) -> dict:
    """One JSON POST. A non-2xx response carries ADAS's ErrorView -- print its message, not a trace."""
    request = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), method="POST")
    request.add_header("Content-Type", "application/json")
    if api_key:
        # Only needed when ADAS runs with security.mode=oidc; harmless otherwise.
        request.add_header("Api-Key", api_key)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        try:
            detail = json.loads(detail).get("message", detail)
        except json.JSONDecodeError:
            pass
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
    args = parser.parse_args()

    write(args.base_url.rstrip("/"), args.table, args.api_key)


if __name__ == "__main__":
    sys.exit(main())
