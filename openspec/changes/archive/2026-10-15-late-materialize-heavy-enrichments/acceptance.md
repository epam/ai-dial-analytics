# Acceptance against a running deployment (pre-deploy)

Corpus ids h2, h3, h4, h5, h7, h10, h13 and c5 of the enrichment-join-cost investigation, run **read-only**
on a ClickHouse 26.3.24.4 server under the read path's budget: `max_memory_usage = 2147483648`,
`max_execution_time = 20`, `do_not_merge_across_partitions_select_final = 1`, `join_use_nulls` at its default 0,
output discarded (`FORMAT Null`; bodies never left the server). Numbers are the `system.query_log` row of each
statement (`memory_usage`, `query_duration_ms`, `read_bytes`).

**How the SQL was produced.** This change is not deployed yet, so `execute-sql` on a running deployment still renders the old
statements. Each query was written as its DSL equivalent and rendered by this change's `StructuredQueryBuilder`
twice: shaped ("after") and with `withoutReadShaping()` ("before"). The model mirrors that deployment's catalog for the
columns involved: `dial_usage_log` with `dial_usage_log_payload` (heavy `Nullable(String)` bodies, light
`request_tags`; partition-aligned on `request_time`) and `usage_client_identity` (keyed on `event_id`, not
aligned). Windows were `request_time >= now − 1 d` / `now − 7 d` at run time.

| id | query | before | after |
|---|---|---|---|
| h2 | `event_id, request_body`, 1 d, `ORDER BY request_time DESC LIMIT 10` | OOM, 2043 MiB, 1.2 s | **24 ms, 8 MiB**, 0.011 GiB read |
| h3 | same, 7 d | OOM, 2048 MiB, 1.7 s | **23 ms, 25 MiB**, 0.029 GiB |
| h4 | `event_id, request_body`, 1 d, `LIMIT 10`, no order | OOM, 2047 MiB, 1.1 s | **88 ms, 29 MiB**, 0.049 GiB |
| h5 | `avg(length(request_body))` by deployment, 1 d | OOM, 2042 MiB, 1.0 s | **1.7 s, 705 MiB**, 3.4 GiB |
| h7 | `count(*)` where `request_body ILIKE '%error%'`, 1 d | OOM, 2047 MiB, 1.1 s | **1.9 s, 685 MiB**, 3.4 GiB |
| h10 | all three bodies, 1 d, `ORDER BY … LIMIT 5` | OOM, 2046 MiB, 1.3 s | **25 ms, 12 MiB**, 0.011 GiB |
| h13 | `max(length(response_body))` per day, 7 d | OOM, 2044 MiB, 1.7 s | **6.9 s, 549 MiB**, 14.1 GiB |
| c5 | by identity `user_email`: `count`, `avg(length(request_body))`, `max(json_extract_string(request_tags, 'user-agent'))`, 1 d | OOM, 2042 MiB, 1.9 s | **3.1 s, 1308 MiB**, 3.7 GiB |

Which rewrite carries each: A (late materialization) — h2, h3, h4, h10; C (call pushdown) — h5, h13, c5; B with
the conjunct computed as a column (D4) — h7. The first acceptance pass, before that refinement, had h7 still
failing at 2046 MiB: the copied `ILIKE` narrowed the build to matching rows, but those rows still carried their
bodies for the outer `WHERE`.

What the numbers say about the remaining cost. The late-materialized pages are bounded by the page and no longer
depend on the window (h3, 7 d, matches h2). The aggregate shapes (h5, h13, c5) and the body search (h7) still
**scan** every body in the window — read volume is unchanged by design — and now hold only the computed values in
the join; h13 at 7 d (14 GiB read, 6.9 s) is the closest to the 20 s ceiling. c5's 1.3 GiB is mostly the identity
join (RC4 of the report), not the payload.

**Post-deploy follow-up (outside this change):** rerun the same eight ids through `execute-sql` on a running deployment once the
change is deployed and confirm the `query_log` numbers match the table above.
