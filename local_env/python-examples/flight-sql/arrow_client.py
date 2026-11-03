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
- The row ceiling is the server's: a LIMIT above the Flight row maximum is
  clamped to it, and the call fails only when the result truly exceeds it.
- Governance is enforced server-side: SQL the structured-query DSL cannot
  express and write statements are rejected with INVALID_ARGUMENT.
- Columns come from the entity itself (SELECT * and its Arrow schema), so
  --entity works for any entity or user table.
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
import pyarrow as pa

DEFAULT_FLIGHT_URI = "grpc://localhost:32010"
DEFAULT_ENTITY = "dial_usage_log"
PREFERRED_GROUP_KEY = "event_kind"


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


def query_arrow(conn, sql: str) -> pa.Table:
    with conn.cursor() as cursor:
        cursor.execute(sql)
        return cursor.fetch_arrow_table()


def query(conn, sql: str) -> pd.DataFrame:
    return query_arrow(conn, sql).to_pandas()


def quote(name: str) -> str:
    """Double-quotes an identifier, so dotted enrichment columns and reserved words work too."""
    return '"' + name.replace('"', '""') + '"'


def pick_group_key(schema: pa.Schema, requested: str | None) -> str:
    """The column steps 3-5 group and filter on: the requested one, else `event_kind`, else the first text column.

    The extract's own Arrow schema is the entity's column list, so the example runs against any entity rather
    than assuming `dial_usage_log`'s columns.
    """
    names = schema.names
    if requested:
        if requested not in names:
            sys.exit(f"--group-by {requested!r} is not a column of the entity; it has: {', '.join(names)}")
        return requested
    if PREFERRED_GROUP_KEY in names:
        return PREFERRED_GROUP_KEY
    for field in schema:
        if pa.types.is_string(field.type) or pa.types.is_large_string(field.type):
            return field.name
    sys.exit(f"the entity has no text column to group on; pass --group-by (columns: {', '.join(names)})")


def expect_rejected(conn, sql: str) -> None:
    """Runs SQL that must fail server-side and prints the rejection."""
    try:
        query(conn, sql)
    except Exception as exc:
        print(f"   rejected as expected: {str(exc).splitlines()[0]}\n")
        return
    sys.exit(f"FAIL: expected a rejection but the query was accepted: {sql}")


def show_row_ceiling(conn, sql: str) -> None:
    """Runs a statement whose LIMIT exceeds the server's row maximum (FLIGHT_MAX_ROWS).

    The LIMIT literal is never rejected: the statement runs clamped to the maximum and fails only if the
    result really holds more rows than that, so a truncated page is never returned. Which of the two
    happens depends on the entity's size, and both are the expected behaviour.
    """
    try:
        rows = query_arrow(conn, sql).num_rows
    except Exception as exc:
        print(f"   result exceeds the row maximum, rejected: {str(exc).splitlines()[0]}\n")
        return
    print(f"   accepted: the whole result fits under the row maximum ({rows} rows)\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--flight-uri", default=os.environ.get("ADAS_FLIGHT_URI", DEFAULT_FLIGHT_URI),
                        help=f"Flight SQL endpoint (default {DEFAULT_FLIGHT_URI}, env ADAS_FLIGHT_URI)")
    parser.add_argument("--api-key", default=os.environ.get("ADAS_API_KEY"),
                        help="DIAL API key for the `api-key` call header (env ADAS_API_KEY); only "
                             "needed when ADAS runs with security.mode=oidc")
    parser.add_argument("--entity", default=DEFAULT_ENTITY,
                        help=f"entity to query (default {DEFAULT_ENTITY}); any queryable entity or table works")
    parser.add_argument("--group-by", default=None,
                        help=f"column for the GROUP BY and governance steps (default {PREFERRED_GROUP_KEY} when the "
                             "entity has it, else its first text column)")
    args = parser.parse_args()
    entity = quote(args.entity)

    print(f"connecting to {args.flight_uri} "
          f"({'with Api-Key' if args.api_key else 'no credentials -- security.mode=none'})\n")
    with connect(args.flight_uri, args.api_key) as conn:
        print(f"1. global aggregate over {args.entity}")
        total = query(conn, f"SELECT count(*) AS n FROM {entity}")
        print(f"   {int(total.iloc[0]['n'])} rows total\n")

        print("2. typed extract (SELECT * expands to the entity's columns server-side)")
        extract = query_arrow(conn, f"SELECT * FROM {entity} LIMIT 100")
        df = extract.to_pandas()
        print(df.head(3).to_string())
        print(f"   dtypes: {dict(df.dtypes.astype(str))}\n")

        key = pick_group_key(extract.schema, args.group_by)
        column = quote(key)

        print(f"3. server-side GROUP BY {key}")
        by_key = query(conn, f"SELECT {column}, count(*) AS n FROM {entity} "
                             f"GROUP BY {column} ORDER BY n DESC LIMIT 10")
        print(by_key.to_string(index=False) + "\n")

        print("4. row ceiling: a LIMIT above the Flight row maximum is clamped to it, not rejected")
        show_row_ceiling(conn, f"SELECT {column} FROM {entity} LIMIT 50000")

        print("5. governance: SQL outside the structured-query DSL is rejected")
        expect_rejected(conn, f"SELECT if(length({column}) > 0, 'a', 'b') FROM {entity}")

        print("6. governance: writes are rejected")
        expect_rejected(conn, f"DELETE FROM {entity}")

    print("all examples completed")


if __name__ == "__main__":
    sys.exit(main())
