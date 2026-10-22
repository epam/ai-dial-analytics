## Context

Three things describe how a table collapses rows, and until now nothing required them to agree:

1. the **physical engine**, fixed at `CREATE TABLE`;
2. the catalog's **`write` facet**, which `TableDdlRenderer` uses to *choose* that engine for tables ADAS
   materializes;
3. the **read path's deduplication**, applied as the statement-wide `final = 1` setting.

For a table ADAS creates, (1) and (2) cannot diverge — the facet is the input that produced the engine,
and it is immutable afterwards. The two seeded sources are the exception: their DDL is hand-written
(`clickhouse/V1`, `V2`), so the facet was never an input to anything, and `V19` gave them the `APPEND`
default without checking. And (3) never consults (2) at all — it asks the server, which answers from (1).

So the defect is confined to what the catalog *says*, and it has been unobservable because the only
consumer of that statement was documentation.

## What the measurements actually showed

This change was first written as `final-semantics-append-sources`, claiming `final = 1` silently
deduplicates plain `MergeTree` tables on ClickHouse 25.3 and was therefore eating 6.5% of an append-only
log. Both halves were tested directly against ClickHouse **25.3.14.14** and both are false. Recording the
results, because the wrong version of this change was persuasive and someone will reach for it again:

- **The `final = 1` setting is a genuine no-op on a plain `MergeTree`.** A `MergeTree` holding two rows
  that share its `ORDER BY` key returns **both** under the setting. The original `structured-query`
  requirement was right.
- **Rendered `FINAL` on a plain `MergeTree` is rejected outright** — `Code: 181 DB::Exception: Storage
  MergeTree doesn't support FINAL (ILLEGAL_FINAL)` — not silently applied. The setting and the rendered
  modifier are therefore *not* interchangeable, which is the opposite of what the superseded design
  assumed.
- **`dial_usage_log` is not a plain `MergeTree`.** It is `ReplacingMergeTree(_ingested_at)`, by design and
  with the reasoning written into its migration header. The 70,281-versus-65,665 gap is that engine
  collapsing re-ingested duplicates on a sort tuple that is meant to be a dedup key — the behaviour the
  table was built for, not a loss.

The gap was real and the investigation was worth doing; the diagnosis was wrong because the catalog
described the table as something it is not. That the mis-declaration produced a plausible, wrong,
expensive-to-chase conclusion *is* the argument for fixing it.

## Goals / Non-Goals

**Goals:**

- The catalog's `write` facet tells the truth about the two seeded sources.
- The specs state that it is *required* to tell the truth, so a future seeded table cannot repeat this.
- Zero behavioural change: the same rows in, the same rows out, on every surface.

**Non-Goals:**

- Changing the deduplication mechanism. The statement-wide setting is correct, and — as measured above —
  strictly more robust than a rendered per-relation modifier, which fails hard when the catalog is wrong
  about an engine. Given that this change exists *because* the catalog was wrong about an engine, moving
  the mechanism onto the catalog would be the wrong lesson to draw.
- Re-materializing either table. Their engine is what it should be.
- Auditing user-created tables. For those the facet is the engine's cause, so they cannot diverge.

## Decisions

### Correct the catalog, not the tables

The engine is right and the declaration is wrong, so the declaration moves. The alternative — rebuilding
both tables as plain `MergeTree` to match the `append` declaration — would discard the re-ingest
idempotency the ingest path depends on, turning a redelivered log line into a double-counted one. The
declaration is the cheap, correct side to change, and the DDL headers make the intent unambiguous.

### Ship it as a migration, because the facet is immutable

`write` cannot be changed through `PUT /v1/tables/{name}` or `PATCH /v1/tables/{name}/schema` — deliberately,
since for an ADAS-created table it would imply an engine change that ClickHouse cannot `ALTER`. That rule
is right and stays. A migration is therefore the only route, which is also the honest framing: this is a
seed correction, not an operator action.

### Leave the deduplication mechanism alone, and say why in the spec

The statement-wide setting derives deduplication from the engine itself, so it is correct even when the
catalog is not — as it has been for the entire life of these two tables. A per-relation rendered `FINAL`
driven by the catalog was implemented and reverted during this change: it is expressive enough to
deduplicate one relation of a join and not another, but nothing needs that (enrichments are always
`ReplacingMergeTree`; a source is one or is not, and the setting handles both), and it converts a catalog
error from invisible into a hard query failure. Robustness to exactly the error this change is fixing is
worth more than expressiveness nothing is asking for.

The spec keeps the mechanism and gains the tie-back: the facet must describe the engine, so that reading
the facet and reading the engine give the same answer.

## Risks / Trade-offs

- **A consumer reading `write` from `/v1/tables` sees the value change.** → It is a two-value field on a
  read surface, changing from a wrong value to a right one, on tables whose behaviour is unchanged. Worth
  a line in the release notes, not a migration plan.
- **The specs now assert an agreement nothing automatically enforces.** A future hand-written seeded table
  can still be declared wrongly. → The requirement makes it reviewable, and the check is one line of DDL
  against one column. An automated reconciliation against `system.tables` was considered and rejected as
  disproportionate: it costs a startup lookup and a new failure mode to guard two rows that change when a
  migration is written.

## Migration Plan

One Flyway migration, no data movement, no `ALTER`, no code. Rollback is the inverse `UPDATE`, and neither
direction changes a query result.

## Open Questions

None.
