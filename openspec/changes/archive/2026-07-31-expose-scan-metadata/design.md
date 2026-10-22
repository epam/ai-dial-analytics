## Context

`table_definition` has carried `version_column`/`identity_column` since V13, populated by migration for
`dial_usage_log` (V13) and `rate_analytics` (V16). `ScanSourceMetadata.cursorColumns` reads them and
answers 400 when either is blank. `enrichment-runtime/spec.md` states the pair is operator-only
configuration and "NOT accepted as caller input on the table-management API", reasoning that the scan's
exactly-once guarantee rests on physical properties the table API cannot verify.

That reasoning does not survive contact with the alternative: an operator migration cannot verify those
properties either. The cost of the restriction is concrete — a caller-created source is unscannable and
the API does not say why — so this change opens the pair to callers and leaves everything else alone.

Deliberately small: the two fields become settable and readable, and no behaviour behind them moves.

## Goals / Non-Goals

**Goals:**

- A caller can set both columns where the rest of the physical schema is declared.
- A consumer can read both from the table read surface.
- Nothing else changes — same storage, same nullability, same scan, same grain contract.

**Non-Goals:**

- A `NOT NULL` migration on either catalog column.
- Type, nullability, uniqueness or monotonicity rules beyond "is a declared column" (see D5: this is what
  `harden-scan-metadata` had to revisit).
- Touching `grain_key`, `enrichment_grain`, `cardinality`, or the enrichment grain contract.
- A way to *set* or repoint the pair on a materialized table (`PATCH .../schema` freezes the columns it
  names but never assigns them).
- Skip indexes, ordering-key relationships, or any change to the scan, auto-join, or rule wire.

Note: "requiring either column" was a Non-Goal of this change's first commit and became a goal of its
third — see D5 and D6, which record what shipped.

## Decisions

### D1 — Declared at `POST /v1/tables/{name}/schema`, not at create

The names must be checkable against the table's columns, and that is the only request where those columns
exist. It also places them beside `ordering_key`, `partition_by` and `ttl` — the same kind of fact, on the
call that issues the `CREATE TABLE`.

*Alternative rejected:* accept them at `POST /v1/tables`. Create is identity-only by design and has no
columns to validate against, so it would accept two names that mean nothing until a later call.

### D2 — Optional, and an omitted field leaves the stored value alone

Optional keeps the change additive: every source that materializes today still materializes, and the two
seeded sources keep their migration-set values. "Omitted leaves it" rather than "omitted clears it" is
what makes re-posting a `FAILED` table's schema non-destructive.

### D3 — Validation is membership only

`identity_column` and `version_column`, when supplied, must be among the columns the request declares.
That is the one property this layer can actually check.

Uniqueness and monotonicity — the properties the scan's exactly-once guarantee rests on — are physical
facts about ingest that no layer here can verify, so they stay the declaring caller's promise, documented
where the fields are documented. A stricter gate (e.g. "the version column must be a non-nullable
timestamp") would be defensible but is behaviour this change deliberately does not add; it belongs with
whatever change decides to make the pair required.

### D4 — An enrichment rejects both

`ScanSourceMetadata` resolves the cursor only for a rule's **source**. An enrichment's declared columns
are its user columns — its key is the hidden grain column and its version is the system `_updated_at`, and
neither is in `column_mapping` — so no supplied name could resolve. A 422 naming the reason beats
Jackson's silent drop (the request would otherwise return 200 having ignored the field).

*Alternative rejected:* accept and ignore for an enrichment, matching how `ordering_key` is currently
ignored on that path. Consistent with an existing wart, but it hands back a 200 for a request that did
nothing.

### D5 — Both members are REQUIRED for a source (revised mid-change)

Optionality (D2) left the original failure in place for anyone who did not know to declare the pair: a
source materializes, takes writes, and only `POST /v1/rules/{id}/scan` reveals that no rule can page it.
Requiring both moves that to the one call that can explain it.

An `upsert_by_key` source MAY name `_updated_at`, the engine's own version column: such a table carries it
physically, and an upsert rewrites a key's row in place, so no *user* column of one tracks row version.
That allowance is what makes a rollup target scannable, and therefore what the conversations/turns spine
needs.

The cost is a breaking API change, and — since validation stayed membership-only here — a source could
still declare a pair the scan cannot use. `harden-scan-metadata` adds the type/nullability/sensitivity
rules that D3 had parked, and **withdraws this decision**: the identity half has no truthful value for a
multi-column-grain rollup (`aggregate-pipeline`'s canonical `(deployment, day)` target), so requiring it
only ever produced a declaration naming part of the key — which makes the keyset seek skip rows silently
rather than fail. Constraining the requirement instead (identity must equal the ordering key) is not
available either: it would forbid the five-column-key, `event_id`-identified shape both seeded sources use
correctly, and would make a multi-column rollup target undeclarable.

### D6 — The columns a declared pair names are frozen against drop and rename

Nothing repoints the pair after schema-define, so dropping or renaming a named column would leave the
definition advertising a scan column that does not exist — unpageable, and unrepairable through the API.
`requireMutable` therefore rejects both operations alongside the system, grain-key and ordering-key guards.

*Known inconsistency:* a rename of the *TTL* column is allowed in the same method and rewrites the stored
TTL column in the same transaction. The same treatment would work here; it was not done because nothing
else about the pair is mutable, so a repoint has no home yet.

## Risks / Trade-offs

- **A caller declares a version column that is backdated or non-monotonic** → exactly-once silently breaks
  for that source's rules. Mitigation: none added; this is the promise the change moves from operator to
  caller, stated in the spec and in the field's schema description. A change that makes the pair required
  is the place to add type-level checks.
- **A caller declares an identity column that is not unique per row** → keyset paging can skip or repeat
  rows. Same mitigation, same reasoning.
- **The pair cannot be set or repointed after materialization.** `PATCH .../schema` freezes the columns a
  declared pair names (D6) but never assigns the pair, and `POST .../schema` is a 409 on an `ACTIVE` table.
  A source created before this change therefore stays unscannable until an operator migration sets it —
  the original gap, still open for existing tables.

## Migration Plan

None. No schema change, no data change, no deploy ordering.

## Open Questions

- Both questions this change first excluded — should the pair be required, should it be frozen — were
  answered inside it: yes and yes (D5, D6). What that left open, and `harden-scan-metadata` closed, is the
  validation that makes a required pair usable. Still open: a repoint path for a materialized table.
