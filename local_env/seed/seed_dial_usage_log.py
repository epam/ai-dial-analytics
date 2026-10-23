#!/usr/bin/env python3
"""Seed synthetic dial_usage_log rows straight into a local ClickHouse.

Why this writes to ClickHouse and not through ADAS: dial_usage_log is a `system` table, so ADAS's
row-write endpoint refuses it, and its only ingest path is `data-sync`, which reads DIAL usage logs out
of blob storage. A direct INSERT over the ClickHouse HTTP interface is the only way to give a local
dashboard something to plot. This is not a way around query governance —
everything the dashboard reads still goes through ADAS.

The rows are FABRICATED. Every one is stamped `_source_file = 'local-seed'` so seeded data can
always be told apart from anything a real ingest produced, and deleted on its own:

    ALTER TABLE dial_usage_log DELETE WHERE _source_file = 'local-seed'

...or just run this script with --purge.

Determinism / idempotency: row values come from a per-hour RNG stream keyed by the bucket's own
timestamp, and timestamps sit on a fixed grid anchored to --anchor (default: the start of the current
UTC hour). A given hour therefore regenerates identically no matter what --hours it was reached with,
so widening the window (--hours 24 then --hours 72) tops up the older hours instead of duplicating the
recent ones. --anchor, --seed and --rows-per-hour do change the grid. Identical rows share the table's full
ORDER BY tuple, so the ReplacingMergeTree collapses them — but only on background merge. This
matters whenever a read skips FINAL (ADAS adds it unless FORCE_FINAL_QUERIES=false; a direct
ClickHouse query does not): until that merge happens a re-seed is visible as doubled counts. The script therefore runs
`OPTIMIZE TABLE <table> FINAL` after inserting, which forces the collapse immediately; pass
--no-optimize to skip it (e.g. on a table too large to rewrite).

What is NOT seeded: the verbatim-content columns (request_body, response_body, assembled_response,
jwt_claims, request_tags). They live on dial_usage_log_payload and were dropped from dial_usage_log by
ClickHouse V11, so a row written here carries none of them and a local query naming one of their
qualified names ("dial_usage_log_payload.request_body" and so on) reads NULL or {}. Seeding them would
mean writing a second table on the payload's own grain, which this script does not do.

Prerequisites: the local stack (`docker compose -f local_env/docker-compose.yml up -d --wait`) and
Python 3.11+. Standard library only — no pip install.

Usage:
    python local_env/seed/seed_dial_usage_log.py                     # ~24h of data, ~25 rows/hour
    python local_env/seed/seed_dial_usage_log.py --hours 72          # a wider window
    python local_env/seed/seed_dial_usage_log.py --purge             # delete seeded rows and stop
    python local_env/seed/seed_dial_usage_log.py --dry-run           # print the first row, insert nothing
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

SOURCE_FILE_MARKER = "local-seed"

# Deployment name -> (event_kind, rough token scale, price per 1k tokens). Names mirror the shape of
# real DIAL deployment ids without being any actual deployment.
DEPLOYMENTS = [
    ("gpt-4o", "llm_call", 1400, "0.0100"),
    ("gpt-4o-mini", "llm_call", 900, "0.0006"),
    ("claude-sonnet-4", "llm_call", 1800, "0.0030"),
    ("mistral-large", "llm_call", 700, "0.0020"),
    ("text-embedding-3-small", "embedding", 120, "0.0000200"),
]

PROJECTS = ["project-alpha", "project-beta", "project-gamma"]


def _hex_id(*parts: object) -> str:
    """Deterministic 32-char hex id from the given parts."""
    joined = "|".join(str(p) for p in parts)
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()[:32]


def _event_id(trace_id: str, core_span_id: str) -> str:
    """Mirror the ingest contract: event_id is a deterministic hash of (trace_id, core_span_id)."""
    digest = hashlib.md5(f"{trace_id}|{core_span_id}".encode("utf-8")).digest()
    return str(uuid.UUID(bytes=digest))


def _rows_in_bucket(hour_index: int, rows_per_hour: int) -> int:
    """A daily-ish traffic shape so the time-series graph has something to look at."""
    wave = 1.0 + 0.55 * math.sin(2.0 * math.pi * (hour_index % 24) / 24.0)
    return max(1, round(rows_per_hour * wave))


def build_rows(anchor: datetime, hours: int, rows_per_hour: int, seed: int) -> list[dict]:
    rows: list[dict] = []

    for offset in range(hours - 1, -1, -1):
        bucket_start = anchor - timedelta(hours=offset)
        # One RNG stream PER BUCKET, keyed by the bucket's own timestamp — not one stream for the
        # whole run. With a single stream the values an hour gets would depend on how many buckets
        # were generated before it, so `--hours 72` would produce different rows for the hours
        # `--hours 24` already wrote; they would not share the table's ORDER BY tuple, the
        # ReplacingMergeTree could not collapse them, and the recent window would show doubled
        # traffic. Keyed per bucket, a given hour always regenerates identically.
        rng = random.Random(f"{seed}|{bucket_start.isoformat()}")
        count = _rows_in_bucket(bucket_start.hour, rows_per_hour)

        for j in range(count):
            # Spread evenly inside the hour; never on the exact boundary, so date_bin bucketing is
            # visibly doing something rather than trivially matching the raw value.
            request_time = bucket_start + timedelta(seconds=int(3600 * (j + 0.5) / count))

            deployment, event_kind, token_scale, price_per_1k = DEPLOYMENTS[rng.randrange(len(DEPLOYMENTS))]
            project_id = PROJECTS[rng.randrange(len(PROJECTS))]

            trace_id = _hex_id("trace", bucket_start.isoformat(), j)
            core_span_id = _hex_id("span", bucket_start.isoformat(), j)[:16]
            user_index = rng.randrange(12)

            prompt_tokens = rng.randrange(token_scale // 3, token_scale)
            completion_tokens = 0 if event_kind == "embedding" else rng.randrange(20, token_scale)
            total_tokens = prompt_tokens + completion_tokens
            price = (total_tokens / 1000.0) * float(price_per_1k)

            # ~6% failures, so the success/response_status columns are not degenerate.
            failed = rng.random() < 0.06
            response_status = rng.choice([429, 500, 503]) if failed else 200

            rows.append(
                {
                    "event_id": _event_id(trace_id, core_span_id),
                    "event_kind": event_kind,
                    "request_time": request_time.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
                    "_source_file": SOURCE_FILE_MARKER,
                    "project_id": project_id,
                    "chat_id": _hex_id("chat", project_id, user_index, offset // 6),
                    "trace_id": trace_id,
                    "core_span_id": core_span_id,
                    "core_parent_span_id": None,
                    "execution_path": ["core", deployment],
                    "parent_deployment": None,
                    "deployment": deployment,
                    "response_upstream_uri": f"https://upstream.local/{deployment}",
                    "user_hash": _hex_id("user", user_index),
                    "response_id": _hex_id("response", trace_id),
                    "prompt_tokens": prompt_tokens,
                    "completion_tokens": completion_tokens,
                    "total_tokens": total_tokens,
                    "cached_prompt_tokens": 0,
                    "cache_creation_tokens": 0,
                    "reasoning_tokens": 0,
                    "number_request_messages": rng.randrange(1, 12),
                    "total_price": f"{price:.12f}",
                    "deployment_price": f"{price:.12f}",
                    "operation_duration_ms": rng.randrange(120, 9000),
                    "response_status": response_status,
                    "success": not failed,
                    "request_method": "POST",
                    "request_uri": f"/openai/deployments/{deployment}/chat/completions",
                    "mcp_method": "",
                    "mcp_tool_call_name": "",
                    "request_body_bytes": prompt_tokens * 4,
                    "response_body_bytes": completion_tokens * 4,
                    # Model and embedding calls are never served by a route, so both read NULL.
                    "route_name": None,
                    "route_type": None,
                }
            )

    return rows


def _clickhouse(base_url: str, user: str, password: str, query: str, body: bytes | None = None) -> str:
    url = f"{base_url.rstrip('/')}/?{urllib.parse.urlencode({'query': query})}"
    request = urllib.request.Request(url, data=body, method="POST")
    request.add_header("X-ClickHouse-User", user)
    if password:
        request.add_header("X-ClickHouse-Key", password)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace").strip()
        raise SystemExit(f"ClickHouse rejected the request ({error.code}): {detail}") from error
    except urllib.error.URLError as error:
        raise SystemExit(
            f"Cannot reach ClickHouse at {base_url}: {error.reason}\n"
            "Is the local stack up? docker compose -f local_env/docker-compose.yml up -d --wait"
        ) from error


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Seed synthetic dial_usage_log rows into a local ClickHouse.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--url", default="http://localhost:8123", help="ClickHouse HTTP endpoint")
    parser.add_argument("--user", default="default", help="ClickHouse user")
    parser.add_argument("--password", default="", help="ClickHouse password")
    parser.add_argument("--table", default="dial_usage_log", help="target table")
    parser.add_argument("--hours", type=int, default=24, help="how many hours back to fill")
    parser.add_argument("--rows-per-hour", type=int, default=25, help="average rows per hour")
    parser.add_argument("--seed", type=int, default=20260728, help="RNG seed (determinism)")
    parser.add_argument(
        "--anchor",
        default=None,
        help="newest bucket, ISO-8601 UTC hour (default: the start of the current UTC hour)",
    )
    parser.add_argument("--purge", action="store_true", help="delete rows with _source_file='local-seed' and exit")
    parser.add_argument(
        "--no-optimize",
        dest="optimize",
        action="store_false",
        help="skip the post-insert OPTIMIZE ... FINAL that collapses re-seeded duplicates",
    )
    parser.add_argument("--dry-run", action="store_true", help="print the first row and the row count, insert nothing")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)

    if args.purge:
        _clickhouse(
            args.url,
            args.user,
            args.password,
            f"ALTER TABLE {args.table} DELETE WHERE _source_file = '{SOURCE_FILE_MARKER}'",
        )
        print(f"Purge submitted for {args.table} (_source_file = '{SOURCE_FILE_MARKER}').")
        print("ClickHouse mutations are asynchronous; the rows disappear once the mutation completes.")
        return 0

    if args.hours < 1 or args.rows_per_hour < 1:
        raise SystemExit("--hours and --rows-per-hour must both be >= 1")

    if args.anchor:
        anchor = datetime.fromisoformat(args.anchor)
        anchor = anchor.replace(tzinfo=timezone.utc) if anchor.tzinfo is None else anchor.astimezone(timezone.utc)
    else:
        anchor = datetime.now(timezone.utc)
    anchor = anchor.replace(minute=0, second=0, microsecond=0)

    rows = build_rows(anchor, args.hours, args.rows_per_hour, args.seed)
    payload = "\n".join(json.dumps(row) for row in rows).encode("utf-8")

    if args.dry_run:
        print(json.dumps(rows[0], indent=2))
        print(f"\n{len(rows)} rows would be inserted into {args.table} "
              f"({args.hours}h window ending {anchor.isoformat()}).")
        return 0

    _clickhouse(
        args.url,
        args.user,
        args.password,
        f"INSERT INTO {args.table} FORMAT JSONEachRow",
        body=payload,
    )

    if args.optimize:
        # Collapse duplicates from a previous identical run now, rather than whenever ClickHouse
        # decides to merge — any read without FINAL would otherwise see doubled counts.
        _clickhouse(args.url, args.user, args.password, f"OPTIMIZE TABLE {args.table} FINAL")

    seeded = _clickhouse(
        args.url,
        args.user,
        args.password,
        f"SELECT count() FROM {args.table} FINAL WHERE _source_file = '{SOURCE_FILE_MARKER}'",
    ).strip()

    print(f"Inserted {len(rows)} rows into {args.table} ({args.hours}h window ending {anchor.isoformat()}).")
    print(f"Total seeded rows now in the table (FINAL, deduplicated): {seeded}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
