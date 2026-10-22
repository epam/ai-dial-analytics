#!/usr/bin/env python3
"""Example pandas client for the ADAS Arrow Flight SQL endpoint.

Shows how an analytical consumer queries ADAS with plain SQL over the ADBC
Flight SQL driver and lands results directly in typed pandas DataFrames.
Noteworthy behaviors demonstrated along the way:

- The ADBC driver internally tries to PREPARE every statement; ADAS is
  statement-only and answers UNIMPLEMENTED, and the driver transparently
  falls back to ad-hoc execution -- so plain cursor.execute() just works.
- Results arrive typed: timestamps come back as tz-aware datetime64 columns,
  no epoch-millis parsing needed.
- Governance is enforced server-side: a LIMIT above the Flight row maximum,
  SQL the structured-query DSL cannot express, and write statements are all
  rejected with INVALID_ARGUMENT.
- An optional ADAS_API_KEY is forwarded as the `api-key` gRPC call header, so
  the same example runs unchanged against an oidc-secured endpoint; without it
  the connection is open, matching the default security.mode=none.

Prerequisites: see README.md. Exits non-zero if a query that must be rejected
is accepted.

Usage:
    python arrow_client.py
    python arrow_client.py --flight-uri grpc://host:port
    ADAS_API_KEY=dial_xxx python arrow_client.py       # security.mode=oidc
"""

from __future__ import annotations

import argparse
import os
import sys

import adbc_driver_flightsql.dbapi as flight_sql
import pandas as pd

DEFAULT_FLIGHT_URI = "grpc://localhost:32010"
DEFAULT_ENTITY = "dial_usage_log"


def connect(uri: str, api_key: str | None):
    """Opens the Flight SQL connection, forwarding the API key when one is set.

    The ADBC Flight SQL driver attaches arbitrary per-RPC gRPC headers via
    `adbc.flight.sql.rpc.call_header.<name>` database options; setting the
    `api-key` header lets ADAS introspect the DIAL key on every call (both the
    getFlightInfo translate and the doGet execute). Absent a key the connection
    is open, matching the default security.mode=none.

    autocommit=True: ADAS is read-only and statement-only (no transactions), so
    opting out up front avoids a DB-API autocommit compliance warning.
    """
    db_kwargs = {"adbc.flight.sql.rpc.call_header.api-key": api_key} if api_key else {}
    return flight_sql.connect(uri, db_kwargs=db_kwargs, autocommit=True)


def query(conn, sql: str) -> pd.DataFrame:
    with conn.cursor() as cursor:
        cursor.execute(sql)
        return cursor.fetch_arrow_table().to_pandas()


def expect_rejected(conn, sql: str) -> None:
    """Runs SQL that must fail server-side and prints the rejection."""
    try:
        query(conn, sql)
    except Exception as exc:
        print(f"   rejected as expected: {str(exc).splitlines()[0]}\n")
        return
    sys.exit(f"FAIL: expected a rejection but the query was accepted: {sql}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--flight-uri", default=os.environ.get("ADAS_FLIGHT_URI", DEFAULT_FLIGHT_URI),
                        help=f"Flight SQL endpoint (default {DEFAULT_FLIGHT_URI}, env ADAS_FLIGHT_URI)")
    parser.add_argument("--api-key", default=os.environ.get("ADAS_API_KEY"),
                        help="DIAL API key for the `api-key` call header (env ADAS_API_KEY); only "
                             "needed when ADAS runs with security.mode=oidc")
    parser.add_argument("--entity", default=DEFAULT_ENTITY,
                        help=f"entity to query (default {DEFAULT_ENTITY})")
    args = parser.parse_args()
    entity = args.entity

    print(f"connecting to {args.flight_uri} "
          f"({'with Api-Key' if args.api_key else 'no credentials -- security.mode=none'})\n")
    with connect(args.flight_uri, args.api_key) as conn:
        print(f"1. global aggregate over {entity}")
        total = query(conn, f"SELECT count(*) AS n FROM {entity}")
        print(f"   {int(total.iloc[0]['n'])} rows total\n")

        print("2. typed extract")
        df = query(conn, "SELECT event_kind, deployment, execution_path, total_tokens, request_time "
                         f"FROM {entity} LIMIT 100")
        print(df.head(3).to_string())
        print(f"   dtypes: {dict(df.dtypes.astype(str))}\n")

        print("3. server-side GROUP BY")
        by_kind = query(conn, f"SELECT event_kind, count(*) AS n FROM {entity} "
                              "GROUP BY event_kind ORDER BY n DESC")
        print(by_kind.to_string(index=False) + "\n")

        print("4. governance: LIMIT above the Flight row maximum is rejected")
        expect_rejected(conn, f"SELECT event_kind FROM {entity} LIMIT 50000")

        print("5. governance: SQL outside the structured-query DSL is rejected")
        expect_rejected(conn, f"SELECT if(length(deployment) > 0, 'a', 'b') FROM {entity}")

        print("6. governance: writes are rejected")
        expect_rejected(conn, f"DELETE FROM {entity}")

    print("all examples completed")


if __name__ == "__main__":
    sys.exit(main())
