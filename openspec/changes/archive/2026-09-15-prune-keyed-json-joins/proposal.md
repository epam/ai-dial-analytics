## Why

`usage-request-baggage-live` stopped writing as soon as `bound-and-prune-pipeline-reads` shipped, and
fails every runner tick from then on. `POST
/v1/pipelines/usage-request-baggage-live/materialize` answers `422 pipeline_read_budget_exceeded`, "this
materialize window probe exceeded the 2147483648B memory ceiling", with ClickHouse `Code: 241 … (while
reading column request_tags)` naming parts across many daily partitions.

The read that fails is the *cheap* one. A Class-1 materialize probe selects two columns — `(version,
identity)` — and exists only to fix the window for the `INSERT … SELECT` that follows. It costs over 2 GiB
because three things compound:

1. **A budgeted probe gates an unbudgeted write.** The probe emits the grain join for the enrichments its
   own clauses reference, so a pipeline whose *filter* names an enrichment drags that join into the probe.
   The probe carries `pipeline.budget.*`; the write it gates is deliberately exempt. The ceiling therefore
   lands on the statement that reads two columns and misses the one that reads the payload.
   `usage-client-identity-live` reads the same table over the same window and is healthy purely because it
   declares no filter — its probe stays unjoined and its payload read happens inside the unbudgeted write.
   Whether a pipeline is bounded at all currently depends on whether it has a filter.

2. **A keyed grain join defeats JSON subcolumn pruning.** `EnrichmentJoins.keyed()` builds the restricted
   enrichment as a derived table and projects the whole object column for every referenced name. A
   single-path expression over that column — which ADAS otherwise renders as native subcolumn access, "~20x
   less data" by its own measurement — runs *after* the derived table has already materialized every path.
   The optimization survives only where the join is left unkeyed, which is the opposite of the intent:
   keying a join is supposed to make it cheaper.

3. **`json_extract_*_ci` over a native `JSON` column is unbounded in stream count, not merely wide.** It
   renders as `toJSONString(<column>)`, which opens one read stream per JSON path per part.
   `dial_usage_log_payload.request_tags` can hold **only on the order of 100 MiB on disk in total**, yet one
   part carries hundreds of `request_tags.*` files (one pair per path) and the table hundreds of active
   parts; at ~4 MiB per read buffer the
   ceiling is exhausted by buffers alone. The cost is independent of the window and of the data size — on
   the query surface a window matching **zero** source rows is still refused. The specs describe this as
   "reads the whole JSON document rather than short-circuiting"; what no spec says is that the memory cost
   scales with *paths × parts* rather than with rows, and registration accepts such a filter silently.

Defect 3 can be mitigated by registering a `usage-request-baggage` version with the case-sensitive
`json_extract_string` and patching the filter to match — equivalent where `request_tags.baggage` is only
ever present lowercase, both forms selecting the identical row set over the same window.
That makes the *preview* pass instantly and leaves the *probe* failing on the same column, which is how
defect 2 was isolated: the preview's sample query leaves its join unkeyed. Meanwhile such a pipeline has to
be held at `enabled: false`, because it fires a doomed ~2 GiB ClickHouse query every runner tick on a
shared ClickHouse node.

## What Changes

- **A keyed grain join projects only the paths the statement addresses.** When every expression referencing
  an `object` column addresses a literal path, the derived table projects those subcolumns instead of the
  whole column, so subcolumn pruning survives keying. A reference that addresses no statically known path —
  `json_extract_*_ci`, `json_extract_array`, or the bare column — falls back to the whole column, which is
  the behaviour today.
- **The pipeline read budget stops depending on whether a pipeline declares a filter.** A ceiling that
  refuses the gating read of a write it does not bound refuses the wrong statement. The asymmetry is
  resolved so that the bounded set of reads is a property of the pipeline's shape, not of whether its
  filter happens to name an enrichment. *(Which way to resolve it is a design decision, not settled here —
  see `design.md`.)*
- **A declaration whose cost scales with paths × parts is no longer accepted silently.** Registration
  **reports — never refuses —** `json_extract_*_ci` over an `object` column: the case where the cheap
  single-path form is available and the expensive one was chosen without the author being told.
  Affordability is a property of the table's layout, not of the declaration, so a static gate would reject
  affordable uses and still admit unaffordable ones.
- **The cost model is written down.** The existing "reads the whole document per row" wording understates
  it by the part count; the specs gain the actual scaling rule so the next author does not rediscover it
  through an outage.

No new configuration and no migration. The statements ADAS renders change, and the pipeline write
responses gain an additive, nullable `notes` member; the set of declarations ADAS **accepts** does not
change at all.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `structured-query`: the requirement "A keyed grain join is bounded by an aligned partition column" gains
  what the derived table **projects**, alongside what it restricts; and the `json_extract_string_ci`
  cost statement gains the paths × parts scaling rule.
- `enrichment-runtime`: the requirement "Every pipeline-internal read runs under a resource budget" changes
  where the boundary between budgeted and unbudgeted sits, so on the Class-1 path a budgeted read no longer
  gates an unbudgeted one. The aggregate refresh write stays unbudgeted and is named as such — it has the
  same shape and deserves the same treatment, but bounding it needs the narrowing contract worked out for a
  recompute that replaces whole group keys, which is its own change.
- `class1-enrichment`: the requirement "Probe-then-write window semantics" describes the probe as "a cheap
  two-column probe", which is false for a probe that joins an enrichment. It gains what such a probe
  projects and what that costs.

## Impact

**Code.** `service/query/EnrichmentJoins` (`keyed()` — the derived-table projection);
`service/query/translate/ExprTranslator` (exposing which literal paths a statement's expressions address
over a given `object` column); `service/enrichment/MaterializeService` (`probeWindow`) and
`service/enrichment/ScanService`;
`service/pipeline/PipelineReadBudget` and its call sites; the pipeline write responses' advisory notes (computed after the
governance gate, which still refuses nothing on this ground).

**Surfaces.** `POST /v1/pipelines/{name}/materialize` stops returning `422
pipeline_read_budget_exceeded` for a single-path filter over an `object` column, and may now return it for
a write that cannot be narrowed far enough. `POST /v1/pipelines` and `PATCH /v1/pipelines/{name}` gain an
additive, nullable `notes` member carrying the advisory above, so the OpenAPI snapshot moves. No catalog
schema change, no config keys.

**Acceptance.** Re-enabling `usage-request-baggage-live` against a running deployment and watching it drain
is the test. Its `materialized_through_version` is discarded on the first materialize after the generation
bump already applied, so it re-derives from the start of history; the unbounded read is small enough to be
safe (tens of thousands of rows, returned instantly through the cheap path).

**Out of scope.** The Enrichment Runner — it already retries this failure rather than dead-lettering
("cursor unchanged, retrying next fire"), so nothing there makes the failure worse. Re-keying or
re-partitioning `dial_usage_log_payload`. The heavy-String per-column cost addressed by
`split-heavy-group-projection`, which is a different mechanism (granule size, not stream count) and is
already mitigated.
