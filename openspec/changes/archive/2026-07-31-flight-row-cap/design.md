# Design — Flight SQL row cap with truncation detection

## Context

Before this change (as left by `flight-sql-catalog-metadata`) `AdasFlightSqlProducer.requireExplicitLimit` rejects
any statement whose translated page spec is absent or carries `limit == 0`. Limit governance itself lives
in `StructuredQueryBuilder.applyPage`/`validateLimit`, which is **shared with REST**: an absent page or a
limit of 0 becomes `DEFAULT_LIMIT` (100), and anything above `MAX_LIMIT` (1000) is rejected.

Two facts shape the design:

- **The defaulted/explicit distinction exists but only as a value convention.**
  `RelToStructuredQueryTranslator.page(ctx)` returns `null` when the statement had neither `OFFSET` nor
  `LIMIT`, and `OffsetPage(offset, 0, false)` for `OFFSET`-without-`LIMIT`. Nothing records the
  *intent*; `limit == 0` doubles as the sentinel. Reading intent out of the value is exactly what this
  change must not do, so the flag becomes explicit.
- **Flight cannot raise its ceiling without touching shared code.** A page of 10 001 rows would be
  rejected by the shared `validateLimit`, so the maximum has to become a parameter of the execution
  entry point rather than a constant.

`ArrowBatchWriter.drain` flushes every `batchRows` rows (or `batchMaxBytes` of allocated Arrow memory)
and calls `root.clear()` after each flush, so Arrow memory is bounded **per batch**, not per result.

## Goals / Non-Goals

**Goals:**

- Never return a truncated page as if it were complete — the original invariant, preserved.
- Let a client that cannot generate `LIMIT` (Power BI DirectQuery, any aggregate probe) succeed.
- Give the Flight transport a single configurable ceiling that governs both the implicit bound and the
  maximum explicit `LIMIT`.
- Leave REST behaviour and REST specs byte-for-byte unchanged.

**Non-Goals:**

- Changing the shared structured-query default (100) or maximum (1000) for any non-Flight caller.
- Paging or continuation for results beyond the cap. Over the cap is an error, not a page.
- Making DirectQuery work end to end — it remains gated on `INFORMATION_SCHEMA` and SQL-dialect gaps.
  This change removes one of three blockers.

## Decisions

### D1 — One property, governing both directions

```yaml
flight:
  max-rows: ${FLIGHT_MAX_ROWS:10000}
```

`flight.max-rows` is both the implicit bound on a defaulted-limit query and the maximum accepted
explicit `LIMIT`. Deliberately **not** split into two settings: a configuration in which a no-`LIMIT`
query may return 10 000 rows while `LIMIT 5000` is rejected would be incoherent — the client that asked
for less would be punished. One number, one meaning: *the most rows this transport will hand back*.

Per project convention the description lives only in `docs/configuration.md`; `application.yml` carries a
bare `key: ${ENV:default}` line and `README.md` names the variable in prose only.

### D2 — The defaulted/explicit distinction becomes an explicit flag

`SqlTranslation` gains `boolean explicitLimit`, set by `RelToStructuredQueryTranslator` from
`ctx.fetch != null` — the parser's own answer to "did the statement carry a `LIMIT`". It is not derived
from the limit value, so `LIMIT 100` and no-`LIMIT` are distinguishable even though both end up at 100
under the shared default, and a future change to the sentinel convention cannot silently flip Flight's
behaviour.

`OFFSET` without `LIMIT` yields `explicitLimit == false`, so it is bounded by the cap — the reading the
requirement asks for.

*Alternative rejected:* infer from `page() == null || limit == 0`. It works today and is what the
superseded rule did, but it couples Flight's contract to a sentinel documented as an internal
convenience in `validateLimit`.

### D3 — The maximum row limit becomes a parameter of the execution path

`StructuredQueryBuilder.build`, `StructuredQueryExecutor.execute` and `StructuredQueryService.execute`
each gain an overload taking `maxLimit`. The existing single-argument forms delegate with
`MAX_LIMIT` (1000), so every REST and DSL caller is unchanged — that delegation is what makes
"REST untouched" a structural property rather than a promise.

Flight passes `flight.max-rows + 1`: the probe row must be *authorised* by governance, since the probe
is expressed as a page limit. Flight validates an explicit `LIMIT` against the cap itself (see D5), so
the `+1` headroom is never reachable by a client-supplied bound.

**Flight's ceiling now exceeds REST's (10 000 vs 1 000). That is intended.** Flight is the streaming,
columnar, driver-facing transport: results leave as Arrow record batches over gRPC, per-request Arrow
memory is already bounded by `flight.request-memory-limit-bytes` (64 MiB) and concurrency by
`flight.max-concurrent-streams`. REST materialises a JSON document per request with no such bounds.

**Confirmed at 10 000 rows:** `ArrowBatchWriter.drain` flushes at `flight.batch-rows` (1024) or
`flight.batch-max-bytes` (4 MiB) and calls `root.clear()` after each flush, so Arrow allocation is
per-batch — 10 000 rows is ~10 sequential batches, each well inside the 64 MiB per-request allocator,
which is unchanged. The batching contract therefore still holds; nothing about the Arrow path scales
with total row count.

The one thing that *does* scale with row count is **JVM heap**, not Arrow: `StructuredQueryExecutor`
materialises the whole result as `List<Map<String, Object>>` before any Arrow writing, so a 10 000-row
Flight result costs ~10× the heap of a 1 000-row REST result. At the default cap that is tens of MB for a
wide row — acceptable, and bounded by the stream gate to 4 concurrent executions. An operator raising
`FLIGHT_MAX_ROWS` far above the default is trading heap for reach, which is why the value is
configuration rather than a constant. Noted in Risks.

### D4 — limit+1, and the data-dependence it buys

For a defaulted-limit query the producer replaces the page with `OffsetPage(offset, maxRows + 1, false)`,
executes, and then:

- `rows.size() > maxRows` → `INVALID_ARGUMENT`, nothing streamed;
- otherwise stream all rows (necessarily `<= maxRows`).

The probe row cannot leak: the only path that would have carried it is the failure path, which streams
nothing. There is no "drop the last row and continue" branch, deliberately — that would be silent
truncation wearing a different hat, which is the thing this whole line of work exists to prevent.

**The accepted cost:** success now depends on the **data**, not only on the query. A report that works
against a 9 000-row result starts failing when the underlying data reaches 10 001, with no change to the
report. This is a genuine regression in predictability compared with the blunt rule, and it is why the
blunt rule was chosen first. It is accepted because the alternative — rejecting every unbounded query —
blocks the clients this endpoint exists to serve, and because the failure is *loud and specific*: the
message names the cap and both remedies. **Mitigation:** any client that supplies an explicit `LIMIT` is
immune, since explicit-limit queries never probe. The remedy for a report that starts failing is
therefore a one-clause edit, and the error says so.

### D5 — Where each check fires, and the `GetFlightInfo`/`DoGet` split

- **Explicit `LIMIT` above the cap** is checked in `getFlightInfoStatement`, before ClickHouse is
  contacted, and re-checked on the ticket path so a hand-forged ticket cannot bypass it. It is a property
  of the statement, so it can and should fail at the first call.
- **The truncation check is only possible at `DoGet`.** `getFlightInfoStatement` translates and validates
  without executing (the endpoint's two-call trust model), so how many rows exist is unknown there.
  `GetFlightInfo` therefore succeeds and the stream then fails.

That asymmetry is unavoidable for a data-dependent condition, but it is a real client-visible fact — a
driver sees a successful `GetFlightInfo` and a failing stream — so the spec states it explicitly rather
than leaving a reader to deduce it.

### D6 — Error shape

Both failures are `INVALID_ARGUMENT` via `FlightErrorMapper`, consistent with the endpoint's other
client errors. The truncation message names the configured cap and both remedies (add an explicit
`LIMIT`, or aggregate); the over-cap message names the requested limit and the configured cap, replacing
the hardcoded 1000.

## Risks / Trade-offs

- **Data-dependent failure (D4)** → mitigated by an explicit `LIMIT`, and by an error message that names
  that remedy. Documented as an accepted cost, not an oversight.
- **`GetFlightInfo` succeeds, `DoGet` fails (D5)** → unavoidable; specified so drivers' behaviour is a
  known quantity. Note the endpoint already has this shape for ClickHouse-down and saturation.
- **Heap scales with the cap (D3)** → 10 000 rows materialise as row maps before Arrow encoding. Bounded
  by the stream gate; the first thing to revisit if an operator raises `FLIGHT_MAX_ROWS` substantially,
  and the reason a streaming executor (rows straight from the ClickHouse response into Arrow batches,
  never a full `List`) is the natural follow-up if larger caps are wanted.
- **Flight and REST now disagree about the ceiling** → intended (D3) and documented, but it is one more
  thing a reader must know. The single-sentence rationale lives in both the spec and
  `docs/configuration.md`.
- **A shared entry point grew an overload (D3)** → the risk is a future caller passing a large maximum
  by accident. The overload is documented as "the caller's ceiling"; the single-argument form remains the
  governed default that every non-Flight caller uses.

## Migration Plan

No data migration, no Flyway version. One new optional property with a default; deployments that set
nothing get a 10 000-row Flight ceiling. Rollback is reverting the commit — or, without a deploy, setting
`FLIGHT_MAX_ROWS=1000` to restore the previous ceiling (though not the previous *rejection* behaviour,
which is code).

Verification: run via `./gradlew bootRun` with `FLIGHT_ENABLED=true`. An IntelliJ run configuration omits
the Arrow JVM flags that `build.gradle` attaches only to `test`/`bootRun`, which breaks every `DoGet`
with a bare `internal error`.

## Open Questions

- Should the cap also bound the REST surfaces' maximum (currently 1000) once a streaming executor exists?
  Out of scope here; REST's silent truncation at 100 is tracked separately.
