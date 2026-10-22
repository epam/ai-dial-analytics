#!/usr/bin/env python3
"""Schema introspection client for the ADAS Arrow Flight SQL endpoint.

Answers "what result shape would this SQL produce?" WITHOUT moving any data:
a bare Flight getFlightInfo makes ADAS translate and validate the statement
and reply with the typed Arrow result schema, while never executing against
ClickHouse -- execution happens only on doGet, which this client never calls.

Because validation runs at getFlightInfo time, this doubles as a cheap
pre-flight check: unknown columns, unsupported SQL constructs, and misuse the
translator rejects (e.g. filtering on an array column) all fail here exactly
as they would on execution.

Implementation note: the ADBC driver's cursor.adbc_execute_schema() cannot be
used against ADAS -- the driver implements it via prepared statements, and
statement-only ADAS answers UNIMPLEMENTED. So this client speaks raw Flight
via pyarrow, hand-encoding the Flight SQL CommandStatementQuery descriptor
(two tiny protobuf messages -- no codegen dependency).

Usage:
    python describe_client.py                          # describe the built-in examples
    python describe_client.py "SELECT ..."             # describe one statement
    python describe_client.py --flight-uri grpc://host:port
    ADAS_API_KEY=dial_xxx python describe_client.py    # security.mode=oidc
"""

from __future__ import annotations

import argparse
import os
import sys

import pyarrow
import pyarrow.flight

DEFAULT_FLIGHT_URI = "grpc://localhost:32010"
DEFAULT_ENTITY = "dial_usage_log"

# A Flight SQL command descriptor is a google.protobuf.Any wrapping a
# CommandStatementQuery; both are tiny messages, encoded by hand below.
_COMMAND_STATEMENT_QUERY = b"type.googleapis.com/arrow.flight.protocol.sql.CommandStatementQuery"


def _proto_field(tag: int, payload: bytes) -> bytes:
    """One length-delimited protobuf field: tag byte + varint length + payload."""
    length = len(payload)
    varint = b""
    while True:
        byte = length & 0x7F
        length >>= 7
        varint += bytes([byte | (0x80 if length else 0)])
        if not length:
            return bytes([tag]) + varint + payload


def _statement_query_command(sql: str) -> bytes:
    """Any{type_url=CommandStatementQuery, value=CommandStatementQuery{query=sql}}."""
    inner = _proto_field(0x0A, sql.encode("utf-8"))  # CommandStatementQuery.query (field 1)
    return _proto_field(0x0A, _COMMAND_STATEMENT_QUERY) + _proto_field(0x12, inner)  # Any (fields 1, 2)


def describe(sql: str, uri: str, api_key: str | None) -> pyarrow.Schema:
    """Returns the typed Arrow result schema of `sql` without executing it."""
    descriptor = pyarrow.flight.FlightDescriptor.for_command(_statement_query_command(sql))
    options = (pyarrow.flight.FlightCallOptions(headers=[(b"api-key", api_key.encode("utf-8"))])
               if api_key else None)
    with pyarrow.flight.connect(uri) as client:
        return client.get_flight_info(descriptor, options).schema


def print_schema(sql: str, uri: str, api_key: str | None) -> None:
    print(f"   {sql}")
    for field in describe(sql, uri, api_key):
        print(f"      {field.name}: {field.type}{'' if field.nullable else ' NOT NULL'}")
    print()


def expect_rejected(sql: str, uri: str, api_key: str | None) -> None:
    """Describes SQL that must fail validation and prints the rejection."""
    print(f"   {sql}")
    try:
        describe(sql, uri, api_key)
    except Exception as exc:  # pyarrow maps INVALID_ARGUMENT to ArrowInvalid
        print(f"      rejected as expected: {str(exc).splitlines()[0]}\n")
        return
    sys.exit(f"FAIL: expected a rejection but the statement was accepted: {sql}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--flight-uri", default=os.environ.get("ADAS_FLIGHT_URI", DEFAULT_FLIGHT_URI),
                        help=f"Flight SQL endpoint (default {DEFAULT_FLIGHT_URI}, env ADAS_FLIGHT_URI)")
    parser.add_argument("--api-key", default=os.environ.get("ADAS_API_KEY"),
                        help="DIAL API key for the `api-key` call header (env ADAS_API_KEY); only "
                             "needed when ADAS runs with security.mode=oidc")
    parser.add_argument("--entity", default=DEFAULT_ENTITY,
                        help=f"entity used by the built-in examples (default {DEFAULT_ENTITY})")
    parser.add_argument("sql", nargs="?",
                        help="describe this one statement instead of running the built-in examples")
    args = parser.parse_args()
    uri, api_key, entity = args.flight_uri, args.api_key, args.entity

    print(f"connecting to {uri} "
          f"({'with Api-Key' if api_key else 'no credentials -- security.mode=none'})\n")

    if args.sql:
        print_schema(args.sql, uri, api_key)
        return

    print("1. typed extract, incl. an array column (list<item: ...>)")
    print_schema(f"SELECT event_kind, deployment, execution_path, total_tokens, request_time "
                 f"FROM {entity} LIMIT 100", uri, api_key)

    print("2. aggregate result shapes come from ClickHouse runtime semantics")
    print_schema(f"SELECT event_kind, count(*) AS n, avg(total_tokens) AS avg_tokens "
                 f"FROM {entity} GROUP BY event_kind", uri, api_key)

    print("3. validation runs here too: unknown column rejected without execution")
    expect_rejected(f"SELECT no_such_column FROM {entity}", uri, api_key)

    print("4. array columns are projection-only: filtering on one is rejected")
    expect_rejected(f"SELECT event_kind FROM {entity} WHERE execution_path IS NULL", uri, api_key)

    print("all examples completed")


if __name__ == "__main__":
    sys.exit(main())
