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
- A window-bounded join over a pruned path is already cheap. The case this change exists for is the whole column
  (a bare projection of `request_tags`, `json_extract_string_ci`), which today hashes every in-window document.

## Running deployment (tasks group 7)

Not yet run.
