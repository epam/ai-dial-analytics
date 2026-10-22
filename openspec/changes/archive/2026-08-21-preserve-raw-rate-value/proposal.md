## Why

`rate_analytics.rate` is a `Nullable(Int8)` populated by a normalizer that maps boolean `true` to `1`
and boolean `false` to `0`, and passes numeric values through unchanged. A stored `0` is therefore
either **a thumbs-down submitted as `false`** or **a rating cleared to numeric `0`**, and nothing in
the row distinguishes them. The normalization is lossy at ingest, so no downstream query, rollup, or
enrichment can recover the difference.

In real feedback data `0` is far from rare — on the order of **one rating in four is uncountable**.
Asked "how much negative feedback is there?", ADAS can only answer with a range whose bounds differ by
that whole share, and neither bound is defensible. This blocks the one question a
feedback dataset exists to answer, and it gets worse — not better — as rating volume grows.

## What Changes

- **Add a `rate_raw` column to `rate_analytics`** carrying the submitted `rate` value's **verbatim
  scalar text** (`true`, `false`, `0`, `-1`), alongside the existing normalized `rate`. Boolean and
  numeric submissions become distinguishable, because `true`/`false` are not numeric text.
- **No new deriver.** The existing `json-extract-string` already produces exactly this: it reads
  `args.path` and returns `node.asString("")`, which Jackson renders as `true`/`false` for a boolean
  and as the literal digits for a number, and which falls back to the default (→ null, for a nullable
  column) for an object or array. Verified against the pinned Jackson 3.1.4 — see design D3. The
  column is therefore one mapping entry, not a registry change.
- **Expose `rate_raw` in the query catalog** as a nullable string, so an exact thumbs-down count is a
  single query — see the count expression under *Consumers* below.
- **`rate` is unchanged.** Its type, its normalization, and every existing query over it behave
  exactly as before. This is purely additive — **not** a breaking change.
- **Existing rows get `rate_raw = NULL`.** The information was never stored and cannot be recovered
  from ClickHouse; repopulating history requires a `data-sync-backfill` re-ingest of the blob range,
  which is out of scope here and viable only while those blobs are retained.

Deliberately **not** in scope: changing what `rate` stores (that would break existing consumers),
inferring intent for historical rows, and adding a derived `sentiment` column — with `rate_raw`
present, sentiment is a consumer-side expression over two columns and does not need materializing.

## Capabilities

### New Capabilities

None. This extends an existing capability rather than introducing one.

### Modified Capabilities

- `rate-analytics`: the *rate value is normalized* requirement gains a companion — the raw submitted
  value SHALL also be preserved — and the *rate_analytics table and exposed schema* requirement's
  closed column list ("SHALL expose exactly these columns") grows by `rate_raw`.
- `data-sync`: the *Closed named deriver registry* requirement's description of the JSON field
  extractors is **clarified, not extended** — it says they read a path "as a string", which leaves
  open what a boolean or numeric member reads as. `rate_raw` makes that load-bearing, so the shipped
  behavior (a scalar reads as its literal text; a non-scalar yields no value) is pinned with a
  scenario. No deriver is added and no code changes.

## Impact

**Surfaces**

- Query catalog: one new discoverable, filterable, groupable column on `rate_analytics`. Visible via
  `GET /v1/queries` schema discovery and usable in `/v1/queries/execute`,
  `/v1/queries/execute-sql`, translation, and Flight — no endpoint contract changes.
- No `/v1` request or response shape changes; no configuration keys added.

**Code and migrations**

- `src/main/resources/db/migration/clickhouse/V8__…` — additive `ALTER TABLE rate_analytics ADD COLUMN`.
  No data rewrite; the column reads NULL on existing parts.
- `src/main/resources/db/migration/postgres/V31__…` — register the column in the query catalog.
- `src/main/resources/mapping/rate-analytics.yaml` — one column entry, plus its header comment.
- **No Java change.** `DeriverRegistry` is untouched (see design D3).
- `docs/` — no configuration properties are added, so `docs/configuration.md` is untouched.

**Ordering invariant.** `SchemaValidator` runs at startup, after the migration runner, and aborts the
service if the mapping declares a column the physical table lacks. So the ClickHouse migration and the
mapping entry ship together, and an operator running with `clickhouse.migrations.enabled=false`
applies the DDL **before** the new build — the same invariant the V7 `assembled_response` migration
documents.

**Consumers**

- An exact negative count is
  `count(*) WHERE rate_raw = 'false' OR (rate_raw IS NOT NULL AND rate < 0)` — the boolean form named
  by its text, the numeric form by the already-normalized `rate`, so a producer that starts sending
  `-2` is still counted. Matching `rate_raw = '-1'` alone would silently miss it.
- `response_ratings` (the per-response rollup behind the `turn_feedback` enrichment) can later add
  exact measures over the pair — the shape that works is a `count` measure with a
  `where: "rate_raw = 'false' OR rate < 0"`, since a measure carries one column and its own row
  predicate. That is a follow-up declaration change, not part of this change, and nothing existing
  breaks without it.
- Any consumer reading `rate` keeps working unchanged; `rate_raw` is opt-in.

**Risk**

Low, and lower than first scoped: with no deriver added, the only executable change is a mapping line,
and the migration is additive. A null `rate_raw` is indistinguishable from today's behavior. The main
caveat to communicate is that `rate_raw` is **only populated for events ingested after deployment**, so
any thumbs-down count must either scope itself to `rate_raw IS NOT NULL` or accept that history is
unattributed.
