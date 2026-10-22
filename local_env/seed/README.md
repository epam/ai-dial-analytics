# Synthetic `dial_usage_log` data

`seed_dial_usage_log.py` writes fabricated DIAL usage rows into a local ClickHouse so there is
something to query on a fresh volume — a dashboard, a `curl` against `/v1/queries/execute-sql`, or the
Flight SQL clients in `../python-examples/flight-sql`. Standard library only; needs Python 3.11+ and the local
stack up.

It writes **directly to ClickHouse**, not through ADAS, because `dial_usage_log` is a `system` table:
the row-write endpoint refuses it, and its only ingest path is `data-sync` reading blob storage.
Everything that *reads* the rows still goes through ADAS.

```bash
docker compose -f local_env/docker-compose.yml up -d --wait     # from the repo root

python local_env/seed/seed_dial_usage_log.py                    # ~600 rows over the last 24h
python local_env/seed/seed_dial_usage_log.py --hours 72         # wider window
python local_env/seed/seed_dial_usage_log.py --rows-per-hour 5  # thinner
python local_env/seed/seed_dial_usage_log.py --dry-run          # print one row, insert nothing
python local_env/seed/seed_dial_usage_log.py --purge            # delete seeded rows and exit
python local_env/seed/seed_dial_usage_log.py --help             # --url/--user/--password/--table/--seed/--anchor
```

Rows land across a handful of deployments, projects and users with a daily traffic shape, ~6% of them
failures, so `count`/`sum`/`avg`, `GROUP BY deployment` and the `success`/`response_status` columns all
have something non-degenerate to show.

## Telling seeded data apart

Every row carries `_source_file = 'local-seed'`, which is the handle for both inspecting and deleting
it — nothing else in the table uses that value:

```sql
SELECT count() FROM dial_usage_log WHERE _source_file = 'local-seed';
ALTER TABLE dial_usage_log DELETE WHERE _source_file = 'local-seed';   -- or --purge
```

The `DELETE` is a ClickHouse mutation, so it is asynchronous — the rows go away once it completes.

## Idempotency, and why the script runs `OPTIMIZE`

Values come from a **per-hour** RNG stream keyed by that hour's own timestamp, and timestamps sit on a
fixed grid anchored to `--anchor` (default: the start of the current UTC hour). So a given hour
regenerates identically regardless of the window it was reached with: running `--hours 24` and then
`--hours 72` **tops up** the three days, it does not duplicate the most recent one. Identical rows
share the table's full `ORDER BY` tuple, so the `ReplacingMergeTree` collapses them — but only on
background merge.

(That per-hour keying is deliberate. With one RNG stream for the whole run, each hour's values would
depend on how many hours were generated before it, so the same wall-clock hour would come out
differently under a different `--hours` — different `project_id`/`deployment`, hence a different sort
key, hence no collapse, hence roughly doubled traffic in the recent window.)

That gap matters whenever a read skips `FINAL` — ADAS adds it by default (`FORCE_FINAL_QUERIES=true`),
but with that off, or for a direct ClickHouse query, a re-seed shows as doubled counts until the merge.
The script therefore issues
`OPTIMIZE TABLE … FINAL` after inserting, which forces the collapse immediately. Pass `--no-optimize`
to skip it (e.g. on a table too large to rewrite), and expect transient duplicates if you do.

Changing `--anchor` (including just letting the clock roll into the next hour), `--seed`, or
`--rows-per-hour` shifts the grid and so produces a *new* set of rows rather than replacing the old
ones. Pass an explicit `--anchor` when you want a run to be exactly reproducible later, and keep
`--seed`/`--rows-per-hour` fixed when topping up a window.
