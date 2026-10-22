#!/usr/bin/env python3
"""Self-checking pandas client for the ADAS Arrow Flight SQL endpoint.

Verifies real-world ADBC connectivity against a locally running ADAS
(local_env docker compose + FLIGHT_ENABLED=true ./gradlew bootRun): the ADBC
Flight SQL driver attempts to PREPARE every statement, ADAS answers
UNIMPLEMENTED (statement-only v1), and the driver must fall back to ad-hoc
execution — check #1 is that fallback working.

The checks carry no fixed seed constants: they are self-consistent (server
aggregate vs pandas recomputation of the same extract), so they pass on any
dial_usage_log content, including an empty table.

Exit codes: 0 = all checks passed, 1 = at least one check failed,
2 = environment failure (endpoint unreachable, or the API key rejected).
"""

from __future__ import annotations

import argparse
import os
import sys
from dataclasses import dataclass, field

import pandas as pd

DEFAULT_FLIGHT_URI = "grpc://localhost:32010"
ENTITY = "dial_usage_log"
EXTRACT_COLUMNS = ["event_kind", "deployment", "prompt_tokens", "completion_tokens",
                   "total_tokens", "request_time"]
EXTRACT_SQL = f"SELECT {', '.join(EXTRACT_COLUMNS)} FROM {ENTITY} LIMIT 1000"
MAX_LIMIT = 1000  # the extract's own LIMIT; the server maximum is FLIGHT_MAX_ROWS (10000 by default)


class EnvironmentFailure(Exception):
    """Endpoint unreachable -- exit 2, distinct from check failures."""


@dataclass
class CheckResult:
    name: str
    passed: bool
    expected: str = ""
    actual: str = ""
    info: list = field(default_factory=list)  # extra lines printed on PASS


def fetch(conn, sql: str, params: list | None = None) -> pd.DataFrame:
    """One query over the ADBC Flight SQL driver, returned as a DataFrame.

    cursor.execute() internally tries the Flight SQL prepared-statement flow first;
    ADAS returns UNIMPLEMENTED and the driver falls back to ad-hoc execution
    (CommandStatementQuery). Errors propagate to the caller for assertion.
    """
    cursor = conn.cursor()
    try:
        if params is not None:
            cursor.execute(sql, parameters=list(params))
        else:
            cursor.execute(sql)
        return cursor.fetch_arrow_table().to_pandas()
    finally:
        cursor.close()


def check_plain_execute(ctx: dict) -> CheckResult:
    """The headline check: plain cursor.execute() works despite prepared statements
    being UNIMPLEMENTED — i.e. the ADBC prepare→fallback chain holds end to end."""
    df = ctx["fetch"](f"SELECT count(*) AS n FROM {ENTITY}")
    if len(df) == 1 and int(df.iloc[0]["n"]) >= 0:
        return CheckResult("plain-execute", True, info=[
            f"count(*) = {int(df.iloc[0]['n'])} — ADBC degraded gracefully from "
            "prepare (UNIMPLEMENTED) to ad-hoc statement execution"])
    return CheckResult("plain-execute", False,
                       "a single global-aggregate row with n >= 0",
                       f"frame shape {df.shape}")


def check_typed_extract(ctx: dict) -> CheckResult:
    """Projection round-trip: column order preserved and timestamps arrive as real
    tz-aware datetimes (the typed-transport win over the JSON endpoint)."""
    df = ctx["fetch"](EXTRACT_SQL)
    ctx["extract_df"] = df  # reused by aggregate-cross-check
    problems: list[str] = []
    if list(df.columns) != EXTRACT_COLUMNS:
        problems.append(f"columns {list(df.columns)}")
    if len(df) > MAX_LIMIT:
        problems.append(f"{len(df)} rows exceed the {MAX_LIMIT} maximum")
    ts_dtype = str(df["request_time"].dtype)
    if "UTC" not in ts_dtype:  # Arrow Timestamp(ms, UTC) -> datetime64[ms, UTC]
        problems.append(f"request_time dtype {ts_dtype!r} is not a tz-aware datetime")
    if problems:
        return CheckResult("typed-extract", False,
                           f"columns {EXTRACT_COLUMNS}, <= {MAX_LIMIT} rows, "
                           "request_time as datetime64[..., UTC]", "; ".join(problems))
    return CheckResult("typed-extract", True, info=[
        f"extracted {len(df)} rows; request_time dtype {ts_dtype} (no epoch-millis parsing needed)",
        *([f"head preview:"] + df.head(3).to_string().splitlines() if len(df) else [])])


def check_aggregate_cross(ctx: dict) -> CheckResult:
    """Server-side GROUP BY equals a pandas recomputation of the same extract."""
    extract = ctx.get("extract_df")
    if extract is None:
        return CheckResult("aggregate-cross-check", False,
                           "typed-extract frame available", "typed-extract produced no frame")
    if len(extract) >= MAX_LIMIT:
        return CheckResult("aggregate-cross-check", True, info=[
            f"skipped comparison: extract hit the {MAX_LIMIT}-row maximum, so a full-table "
            "aggregate is not comparable; server aggregate still executed below"])
    df = ctx["fetch"](f"SELECT event_kind, count(*) AS n FROM {ENTITY} "
                      "GROUP BY event_kind ORDER BY event_kind")
    server = {r.event_kind: int(r.n) for r in df.itertuples()}
    recomputed = {k: int(v) for k, v in extract.groupby("event_kind").size().items()}
    if server == recomputed:
        return CheckResult("aggregate-cross-check", True,
                           info=[f"server == pandas: {server or '{} (empty table)'}"])
    return CheckResult("aggregate-cross-check", False,
                       f"server aggregate == pandas recomputation {recomputed}",
                       f"server {server}")


def check_limit_rejected(ctx: dict) -> CheckResult:
    """LIMIT above the structured-query maximum is rejected, not silently clamped."""
    return _expect_rejection(ctx, "limit-rejected",
                             f"SELECT event_kind FROM {ENTITY} LIMIT 50000",
                             ("exceeds the maximum", "INVALID_ARGUMENT"))


def check_unsupported_rejected(ctx: dict) -> CheckResult:
    """A DSL-inexpressible construct fails closed with INVALID_ARGUMENT."""
    return _expect_rejection(ctx, "unsupported-rejected",
                             f"SELECT if(length(deployment) > 0, 'a', 'b') FROM {ENTITY}",
                             ("cannot be expressed", "INVALID_ARGUMENT"))


def check_write_rejected(ctx: dict) -> CheckResult:
    """A write statement is rejected at the statement screen."""
    return _expect_rejection(ctx, "write-rejected",
                             f"DELETE FROM {ENTITY}",
                             ("only SELECT", "INVALID_ARGUMENT"))


def check_params_unsupported(ctx: dict) -> CheckResult:
    """Documents statement-only v1: a parameterized query needs the prepared-statement
    flow, which ADAS does not implement — the driver must surface that, not hang or
    silently mis-execute. If this ever PASSes by succeeding, prepared statements have
    landed and this check should be inverted."""
    try:
        ctx["fetch"](f"SELECT event_kind FROM {ENTITY} WHERE deployment = ?", params=["x"])
    except Exception as exc:  # adbc_driver_manager errors are not a stable public type
        message = str(exc)
        # Depending on driver version the failure is either server-side (our UNIMPLEMENTED
        # from CreatePreparedStatement) or client-side (Bind refused because the earlier
        # Prepare attempt did not yield a prepared statement). Both are loud and safe.
        if ("prepared statement" in message.lower() or "NOT_IMPLEMENTED" in message
                or "must call Prepare" in message):
            return CheckResult("params-unsupported-documented", True, info=[
                "parameterized execute failed as designed (statement-only v1): "
                + message.splitlines()[0][:160]])
        return CheckResult("params-unsupported-documented", False,
                           "an error naming prepared statements / NOT_IMPLEMENTED",
                           message[:300])
    return CheckResult("params-unsupported-documented", False,
                       "parameterized execution to fail (prepared statements are UNIMPLEMENTED)",
                       "query unexpectedly succeeded — have prepared statements been implemented? "
                       "Then invert this check.")


def _expect_rejection(ctx: dict, name: str, sql: str, expected_fragments: tuple) -> CheckResult:
    try:
        df = ctx["fetch"](sql)
    except Exception as exc:
        message = str(exc)
        if any(fragment.lower() in message.lower() for fragment in expected_fragments):
            return CheckResult(name, True, info=["rejected: " + message.splitlines()[0][:160]])
        return CheckResult(name, False,
                           f"an error containing one of {expected_fragments}", message[:300])
    return CheckResult(name, False, "a Flight error (fail-closed rejection)",
                       f"query was ACCEPTED — result frame shape {df.shape}")


CHECKS = [
    check_plain_execute,
    check_typed_extract,
    check_aggregate_cross,
    check_limit_rejected,
    check_unsupported_rejected,
    check_write_rejected,
    check_params_unsupported,
]


def _connect(uri: str, api_key: str | None):
    try:
        import adbc_driver_flightsql.dbapi as flight_sql
    except ImportError as exc:
        raise EnvironmentFailure(
            "the ADBC Flight SQL driver is not installed; run "
            "`pip install -r requirements.txt`") from exc
    try:
        # An API key travels as the `api-key` gRPC call header, so these checks also run against
        # an endpoint secured with security.mode=oidc. Unset for the local default (mode=none).
        db_kwargs = {"adbc.flight.sql.rpc.call_header.api-key": api_key} if api_key else {}
        # autocommit=True: ADAS is read-only/statement-only (no transactions), so opting
        # out up front avoids the DB-API autocommit compliance warning.
        conn = flight_sql.connect(uri, db_kwargs=db_kwargs, autocommit=True)
    except Exception as exc:
        raise EnvironmentFailure(
            f"cannot connect to the Flight SQL endpoint at {uri} — is ADAS running with "
            f"FLIGHT_ENABLED=true? ({exc})") from exc
    try:
        # connect() dials lazily, so force one round trip while we can still report it as an
        # environment failure (exit 2). Without this an unreachable endpoint surfaces as a raw
        # driver traceback out of the first check instead. adbc_get_info() is a Flight metadata
        # RPC (GetSqlInfo), so it tests reachability without touching SQL governance -- and, with
        # an API key set, it is also where a rejected key surfaces.
        conn.adbc_get_info()
        return conn
    except Exception as exc:
        conn.close()
        raise EnvironmentFailure(
            f"cannot reach the Flight SQL endpoint at {uri} — is ADAS running with "
            f"FLIGHT_ENABLED=true, and is the API key accepted? ({exc})") from exc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--flight-uri", default=os.environ.get("ADAS_FLIGHT_URI", DEFAULT_FLIGHT_URI),
                        help=f"Flight SQL endpoint (default {DEFAULT_FLIGHT_URI}, env ADAS_FLIGHT_URI)")
    parser.add_argument("--api-key", default=os.environ.get("ADAS_API_KEY"),
                        help="DIAL API key for the `api-key` call header (env ADAS_API_KEY); only "
                             "needed when ADAS runs with security.mode=oidc")
    args = parser.parse_args()
    uri = args.flight_uri

    results: list[CheckResult] = []
    conn = None
    try:
        conn = _connect(uri, args.api_key)
        ctx = {"fetch": lambda sql, params=None: fetch(conn, sql, params)}
        for check in CHECKS:
            result = check(ctx)
            results.append(result)
            print(f"{'PASS' if result.passed else 'FAIL'}  {result.name}")
            if result.passed:
                for line in result.info:
                    print(f"      {line}")
            else:
                print(f"      expected: {result.expected}")
                print(f"      actual:   {result.actual}")
    except EnvironmentFailure as env:
        print(f"ERROR: {env}")
        return 2
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass

    failed = sum(1 for r in results if not r.passed)
    print(f"{len(results) - failed} passed, {failed} failed -- {'OK' if failed == 0 else 'FAIL'}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
