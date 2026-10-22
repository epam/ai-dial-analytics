## Context

The mechanism exists on both sides; only the wire format is wrong. ADAS parses and validates
`ready_when.signal` at registration, re-parses it on every scan (to know which columns to project), and
already owns `FilterTranslator`, which turns the parsed node into a jOOQ condition — that is how
`filter_sql` becomes a `WHERE` clause on the same query. The design question is therefore narrow: what
does a group rule's scan page say about readiness, and who evaluates it?

## Goals / Non-Goals

**Goals:** make `ready_when.signal` executable by a caller that holds no SQL grammar of its own; keep the
verdict's authority in the one place the predicate was validated; keep the sensitive posture exactly as
strict as it is today; and leave every non-signal scan's SQL untouched.

**Non-Goals:** aggregating the verdict per group (the caller owns per-group state; a page is rows);
returning both the verdict and the raw columns (the point is to stop shipping the raw columns);
re-validating the predicate more strictly than registration did; any readiness semantics — `idle`,
`max_staleness`, and how a verdict ages are the caller's.

## Decisions

### D-1: the page carries the verdict, not the evidence

`__signal` is a projected boolean; the raw columns the predicate reads are dropped from the projection.

*Rejected: keep shipping the raw columns and let the caller evaluate.* This is the status quo, and it is
what makes the feature unimplementable: the caller would need ADAS's bounded Calcite grammar — including
its function surface, null semantics, and type coercions — reimplemented in a second language. A
divergence there does not fail; it marks the wrong groups ready, which surfaces as labels computed too
early and is nearly undiagnosable.

*Rejected: ship both.* The raw columns then remain on the wire for a caller that has no use for them,
which keeps the egress (and the sensitive-data surface) of the rejected design while adding the verdict.

### D-2: one boolean per row, aggregated by nobody

The verdict is per row because the scan is per row and because a group's members arrive across pages: a
page cannot know whether a group's signalling row is in it. The caller already folds a page into
per-group state (it takes `max(version)` per group in exactly the same pass); folding one more field in
is free there and impossible here.

### D-3: `__signal`, double-underscored, and it survives into the response

The name matches the internal `__cursor_v`/`__cursor_id` aliases, so it cannot collide with a source
column (a user-declared column may not begin with `_`, and ClickHouse's own system columns take a single
`_`). Unlike the cursor aliases it is *not* stripped in `toPage` — those are internal machinery behind
the opaque cursor; this one is the payload.

### D-4: normalized to a JSON boolean in ADAS, not left as UInt8

ClickHouse answers a predicate with `UInt8`, and with a *nullable* `UInt8` when the predicate touches a
nullable column, so the raw cell would reach a caller as `0`/`1`/`null` and every caller would have to
know that. `toPage` maps it to a real boolean, with null → `false`.

*Why null → false:* a signal is a positive assertion that a group is complete. An unknown verdict must
not fire an evaluation, and the readiness fallbacks (`idle`, `max_staleness`) still get the group
evaluated eventually. Unknown therefore fails closed.

*Rejected: `CAST(... AS Bool)` in the query.* It pushes the same normalization into SQL where its
rendering depends on the dialect's boolean support, for no gain over three lines of Java that also handle
the nullable case.

### D-5: the sensitive guard covers the signal's columns even though they are not projected

`sensitiveGuard.enforce` is called with `projection ∪ signal columns`. The guard's rule is that a
non-entitled caller must not cause a sensitive column to be **read or derived** — and the verdict is a
derivation. Without this line, moving the evaluation server-side would have quietly turned a 403 into a
successful scan, which is a governance regression disguised as a refactor.

The registration-time entitlement check and the scan-time re-attribution of an unparseable predicate
(`reattributing`) are both unchanged, so a non-entitled caller is still refused at the earliest point it
can be refused.

## Risks / Trade-offs

- **A caller reading the raw signal columns breaks** → the only caller is the enrichment runner, which
  refuses signal rules today and so cannot be reading them. Called out as breaking in the proposal
  because the projection is part of the published contract.

- **The verdict is computed on every scanned row, including rows that will never matter** → it is one
  predicate over already-read columns in a query that is already reading them; ClickHouse evaluates it
  in the same pass. The alternative (evaluating only for rows in a signalling group) is not knowable
  before the group is folded.

- **A rule can change its signal, and pages scanned before the change carry the old verdict** → true, and
  unchanged from every other governed fragment: a rule update bumps `generation`, and the caller's own
  evaluator-version and rule-lifecycle guards decide what to do with in-flight work. The scan is
  stateless about it.
