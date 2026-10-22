## Context

`now()` renders as `now64(3, 'UTC')` (`ExprTranslator.now`). ClickHouse 26.3 evaluates each such call
site once per statement — constant across rows — but evaluates separate call sites separately, so two
in one row differed in 7/300 measured runs (same with and without `FINAL`). The spec, the catalog
description, the Javadoc, `docs/sql-reference.md` and one integration test all claim the stronger
"one instant per execution". See proposal.md — Why.

The two requirements to modify, plus two neighbours, sit after `## Implementation notes` in their main
specs, where openspec does not parse them (`openspec validate --specs` already fails on exactly these
four).

## Goals / Non-Goals

**Goals:**
- Every statement of the `now()` contract describes ClickHouse's observed behaviour.
- The integration test asserts what ClickHouse guarantees and stops flaking.
- The deltas archive cleanly.

**Non-Goals:**
- Any change to rendered SQL or query results.
- Making two call sites agree (rejected below).

## Decisions

**D1 — Move the contract, not the engine.** Alternatives rejected: (a) render one `now64` into a
`WITH now64(3,'UTC') AS __now` alias and reference it — changes the statement ClickHouse sees and
diverges from what the same SQL does in ClickHouse; (b) pin the instant in ADAS as a literal / injected
`Clock` / query parameter — reads the service's clock rather than the store's, which the spec forbids,
and is the "ADAS-side workaround" the directive excludes. Both would make ADAS behave unlike
ClickHouse, which is the opposite of the project's fidelity goal.

**D2 — Test asserts per-call-site constancy, plus a documented tolerance.** The rewritten test projects
`now()` as `a` and `b` over several rows and asserts: all `a` equal, all `b` equal, both non-null.
It additionally asserts |a − b| ≤ 1 s, commented as a *tolerance* that only catches a gross defect
(e.g. one call rendered with second precision or a different zone), not a contract — ClickHouse
promises no bound, and a loaded CI box can plausibly stretch the gap past a few ms. One second is wide
enough never to flake and narrow enough to catch a zone or precision bug (a zone bug moves by hours;
second precision truncates by < 1 s but the precision is pinned elsewhere by the rendering tests).
Renamed `eachNowCallSiteIsOneValueAcrossRows`. More rows than two (five) make "constant across rows"
a stronger claim at no cost.

**D3 — Relocate the four misplaced requirements verbatim first.** Move the four blocks, text
unchanged, to the end of each spec's `## Requirements` section (just before `## Implementation
notes`), in a separate commit before any wording change, so the move is reviewable as a pure
relocation. Alternative — leave them and hand-edit the main specs at archive — rejected: archive would
not find the MODIFIED headers, and the requirements would stay invisible to `validate`.

**D4 — Catalog description is corrected, not rewritten.** Only the two sentences carrying the
single-instant claim change; the string stays a single description. It is API-visible text
(`GET /v1/queries/functions`), not behaviour; no test pins it verbatim (checked by grep during apply).

**D5 — Other tests audited.** The only test asserting two call sites equal is the one above. The
relative-window tests seed rows minutes or days from the bound, so a millisecond skew cannot flip them;
`SqlQueryFrontEndTest` compares translated *documents* (call nodes), not evaluated instants. Add a
translation test for the new "two spellings stay two calls" scenario and a rendering assertion for the
"two independent `now64` calls" scenario.

## Risks / Trade-offs

- [A caller relied on the false invariant for a two-bound window] → nothing changes at runtime; the
  spec and `docs/sql-reference.md` now say how to get one instant (supply it as a literal).
- [The 1 s tolerance in the test is mistaken for a guarantee] → the test comment says it is a
  tolerance, and the spec states no bound.

## Migration Plan

None: documentation, test and description-string change only. Rollback is a revert.
