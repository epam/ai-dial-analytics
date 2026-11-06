# Acceptance

## Subcolumn reads on a page-keyed join (tasks 3.2, 1.4)

Measured on a throwaway local ClickHouse 26.8, over a synthetic fixture: a `ReplacingMergeTree` source of a few hundred
thousand rows across about ten daily partitions, and a payload table aligned with it on `request_time`, whose `request_tags`
`JSON` document holds a short `conversation_id` and a few KB of padding per row. Each statement returns a 25-row page
over the whole window, read `FINAL` with `do_not_merge_across_partitions_select_final = 1`, in the shapes the read path
renders. Figures are orders of magnitude.

| Statement | Payload columns read (`system.query_log` `columns`) | Read | Peak memory |
| --- | --- | --- | --- |
| Page-keyed join, literal path | `request_tags.conversation_id`, `event_id`, `request_time` | ~10 MiB | ~2 MiB |
| Page-keyed join, whole column | `request_tags`, `event_id`, `request_time` | ~60 MiB | ~50 MiB |
| Window-bounded derived table, whole column (today's shape) | `request_tags`, `event_id`, `request_time` | ~2 GiB | ~200 MiB |
| Window-bounded derived table, pruned path (today's shape) | `request_tags.conversation_id`, `event_id`, `request_time` | ~30 MiB | ~1 MiB |

Findings:

- A page-keyed join reads a literal path as its subcolumn only: the hash build never reads the whole document. The
  fallback task 1.4 describes (revisiting design D5) is not needed.
- `IN (K)` in the page-keyed join's `ON` prunes the payload scan to the page's granules (payload rows read: a few
  granules, not the window), so even a whole-column read costs the page.
- At this fixture's scale a window-bounded join over a pruned path is cheap, and the whole column (a bare projection
  of `request_tags`, `json_extract_string_ci`) is what costs. At a running deployment's scale both cost the window
  (see below): the hash build holds one key tuple per in-window row whatever it projects.

## Running deployment (tasks group 7)

Measured on a running deployment holding millions of rows in `dial_usage_log`, its payload and `usage_client_identity`
(all day-partitioned on `request_time`, a few months of history, still ingesting). Each shape is a 25-row page sorted
by `request_time` descending, rendered twice from the same structured query: by the code before this change
("before", today's statement) and by this change ("after"). Both ran read-only under the read path's own settings
(`FINAL` on the payload, `do_not_merge_across_partitions_select_final = 1`) and its default budget (2 GB memory, 20 s),
twice each with the order alternated. Figures are orders of magnitude from `system.query_log`.

| Shape (task) | Window | Before: peak memory, duration | After: peak memory, duration |
| --- | --- | --- | --- |
| Whole `request_tags` (7.1, 7.3) | hours | ~100 MiB, ~0.2 s | under 1 MiB, ~10 ms |
| Whole `request_tags` (7.1) | a week | ~1 GiB, seconds | under 1 MiB, tens of ms |
| Whole `request_tags` (7.2) | a month | ~1 GiB, seconds | under 1 MiB, tens of ms |
| Whole `request_tags` (7.2) | full history | refused: memory budget | ~10 MiB at most, under 0.1 s |
| One `request_tags` path (7.8, 7.3) | hours | ~10 MiB, tens of ms | under 1 MiB, ~10 ms |
| One `request_tags` path (7.8) | a week | ~100 MiB, ~0.1 s | under 1 MiB, tens of ms |
| One `request_tags` path (7.8) | a month | hundreds of MiB, ~0.5 s | under 1 MiB, tens of ms |
| One `request_tags` path (7.8) | full history | ~1 GiB, ~1 s | under 1 MiB, tens of ms |
| Identity scalar `user_email` (7.3) | hours to a week | ~10–100 MiB, tens to ~100 ms | under 1 MiB, tens of ms |
| Identity scalar `user_email` | a month to full history | hundreds of MiB to ~1 GiB, ~1 s | under 1 MiB, tens of ms |
| Whole `request_tags`, filter a substring of an identity column (7.4) | a week | ~1 GiB, seconds | ~100 MiB, a few hundred ms |
| Whole `request_tags` or one path, `trace_id = X`, no time bound (7.5) | all | tens of MiB, tens of ms | the same |

Findings:

- No shape regresses under design D6. The shapes where the trade could invert — a short window (7.3), a path-only
  read (7.8), a point lookup (7.5), a filter that the page block and the outer statement both join (7.4) — cost the
  same as today or less.
- The page block's second pass over the source window is cheap: it reads the key and sort columns only, on the
  order of tens of MiB for a month. What goes away is the window-sized hash build, which is large even for a single
  path or a scalar column over a wide window, because it holds one key tuple per in-window row.
- The whole-document page over the full history, which today's statement cannot complete within the memory budget,
  completes in well under a second.
- Results match: the same rows on every shape, in the same order except among rows tying on `request_time`, which
  neither statement orders. A page whose window ends at the present also differs by the rows ingested between the
  two runs.
- 7.7: the session lookup renders byte-identical SQL before and after, so it costs what it costs today.

### Sparse aligned enrichment (7.6)

`usage_request_baggage` holds a row for a few percent of source events. A 25-row page projecting its `baggage`
column, sorted by `request_time` descending:

| Window | Before: peak memory, duration | After: peak memory, duration |
| --- | --- | --- |
| a week | ~10 MiB, tens of ms | under 1 MiB, tens of ms |
| a month | ~100 MiB, ~0.1 s | under 1 MiB, tens of ms |
| full history | ~100 MiB, a few hundred ms | under 1 MiB, under 0.1 s |

A sparse enrichment's window join is already small, so there is less to save, but nothing regresses: the page block
reads only source columns, and most page keys find no row in the late join. Results match on every window.

### Replayed reads (7.9)

Row-mode reads issued against the deployment over a month, rebuilt as `execute-sql` statements and rendered by the code
before and after this change, then run as above. Of a few dozen distinct read shapes that join an aligned enrichment,
these project an aligned enrichment's columns only in their `select`:

| Read | Before: peak memory, duration | After: peak memory, duration |
| --- | --- | --- |
| Trace view: a few traces over a few days, one identity column, `LIMIT 600` | a few MiB, tens of ms | ~10 MiB, tens of ms |
| Event detail: one trace, every identity, payload document, baggage and second-enrichment column, `LIMIT 300` | ~200 MiB, ~0.2 s | ~170 MiB, ~0.2 s |
| Identity and payload documents over a month, `LIMIT 100`, unsorted | refused: memory budget | ~200 MiB, a few hundred ms |
| Header page: `request_tags` and source columns over a few days, `LIMIT 25` | ~1 GiB, ~1 s | tens of MiB, tens of ms |
| Client-identity columns over three hours, sorted by `_ingested_at`, `LIMIT 25` | ~10 MiB, tens of ms | a few MiB, tens of ms |
| Identity lookup (`client_type = 'dialcli'`) over two days, `LIMIT 25` | tens of MiB, tens of ms | the same: not late-materialized |

Results match on every read, up to ties on the sort key and rows ingested between the two runs.

The trace view is the one read whose peak memory more than doubles: its filter (a trace id list plus a time range)
already keys today's identity join down to the trace's few rows, so there is no window-sized build to save, and the
page-keyed join costs a few MiB more. Its duration is unchanged.
