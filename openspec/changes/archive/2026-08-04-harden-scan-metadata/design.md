## Context

`expose-scan-metadata` shipped in three steps: the pair became caller-declarable, then required for a
source, then frozen against a drop or rename of the column it names. Only the first step was covered by that
change's own design; the second contradicts its Non-Goals ("Requiring either column") and shipped without the
type gate its D3 said belonged with it.

This change keeps what was sound (the pair is declarable, readable, and frozen once named), pays for the part
that was under-validated, and withdraws the part that could not be made honest.

## Goals / Non-Goals

**Goals:**

- A declared pair is one the scan can actually page by: right type, non-nullable, not sensitive.
- Declaring it stays a deliberate claim, so a table with no truthful identity column declares none.
- Every rejection happens at define time, with a message naming the property and why it matters.
- One home for the pair's rules, so the kind rule and the physical rules cannot drift apart.
- An enrichment declares neither member.

**Non-Goals:**

- Verifying uniqueness or monotonicity — physical facts about ingest no layer here can see.
- Tuple identity (`identity_column` as a list), which is what would make a multi-column-grain rollup
  scannable. That is the deferred tuple-grain work; see D2 and Open Questions.
- A repoint path for a materialized table, or relaxing the freeze (a rename could rewrite the stored name, as
  the TTL column's rename does; deliberately left alone — see Open Questions).
- Any migration: both catalog columns stay nullable, so a definition that declares nothing stays
  representable and the scan's 400 covers it.

## Decisions

### D1 — The pair is optional, not required

Requiring both members does not make a source scannable; it makes every source *claim* to be. The claim is
unverifiable here, and for one common shape it is necessarily false: a rollup collapses by its whole
`ordering_key`, so with a multi-column grain no single column identifies a row.

The consequence is concrete rather than theoretical. `AggregateCompiler` selects a literal `now64(3)` into
`_updated_at` — one scalar per statement, so every row a refresh writes shares a version to the millisecond.
With `identity_column: "deployment"` on a `(deployment, day)` rollup, all of a deployment's bucket rows carry
the same cursor tuple; `CursorPredicates.seekPast` is a strict tuple `>`, so as soon as a page ends inside
that group the remaining rows are excluded from every later page. Silent row loss, in the shape
`aggregate-pipeline` uses as its worked example.

Optional avoids manufacturing that claim: a table that cannot name a unique identity names none, and
`ScanSourceMetadata` refuses to scan it with the 400 it already had — a clear failure at the moment someone
actually tries, instead of a wrong answer later.

### D2 — Why not "require it, but check identity against the ordering key"

The reviewed alternative was to keep the requirement and constrain it for `upsert_by_key`: require
`identity_column` to equal the ordering key, and reject a multi-column ordering key rather than accepting one
member of it. It does not survive contact with two facts:

- **It contradicts `aggregate-pipeline`.** That capability requires the target's `ordering_key` to equal the
  pipeline's `group_by` output names. Rejecting a multi-column ordering key while the pair is required means
  a `(deployment, day)` rollup could not be created at all — the canonical target of the capability becomes
  undeclarable.
- **It is not what correctness needs, and the platform's own tables show it.** `dial_usage_log` and
  `rate_analytics` are both `upsert_by_key` with a five-column ordering key
  (`project_id, deployment, request_time, trace_id, core_span_id`) and `identity_column = event_id`, which is
  in no part of that key — and they are right: `event_id` is unique per row, which is the property that
  matters. A rule of "identity equals the ordering key" would forbid the one shape the platform relies on.

The property to check is "unique per row", which this layer cannot see. So the honest options were tuple
identity (out of scope) or optional (D1).

### D3 — The version column must be declared `timestamp` (or the engine version)

`CursorPredicates.versionExpr` is `toUnixTimestamp64Milli(<version>)`, and `safetyLag` compares the column to
`now64(3) - toIntervalMillisecond(...)`. `ClickHouseTypeMapper` renders `timestamp` as
`DateTime64(3, 'UTC')`, the only declarable type those expressions accept — `date` becomes `Date` and fails,
as does every numeric type.

The exception is `_updated_at` on an `upsert_by_key` source: `DateTime64(3)`, stamped by the platform, and the
only monotonic column such a table has, since an upsert rewrites a key's row in place. It is a system column
and so absent from `column_mapping`, which is why it is allowed by name rather than by lookup.

*Alternative rejected:* accept any temporal type and widen `CursorPredicates`. The cursor's wire form is
epoch milliseconds; making the SQL type-dependent would fork the one place every rule-scoped read agrees on.

### D4 — Both members must be non-nullable

`toUnixTimestamp64Milli(NULL)` and `toString(NULL)` are `NULL`, so the safety-lag predicate and the keyset
tuple compare are both `NULL` for such a row: it is filtered out of every page, forever, with no error. A
silent gap in an exactly-once scan is the worst available outcome, and the request already declares
nullability, so this is free to check. `_updated_at` needs no check — the platform's DDL declares it
non-nullable with a default.

### D5 — Neither member may be `sensitive`

`ScanService` projects both cursor columns and passes them to `SensitiveProjectionGuard.enforce`, so a
sensitive member turns every scan by a caller without the sensitive entitlement into a 403 — a rule operable
only by a `FULL_ADMIN`, discovered at scan time. The identity value also travels inside the page cursor.
Rejecting at declaration is the honest place to say so.

### D6 — An enrichment declares neither member

It is keyed on its `grain_key`, and its row version is the `ReplacingMergeTree` column the platform stamps —
neither is a caller-declared column, and nothing in the runtime reads an enrichment's stored `version_column`.
Accepting one would persist, return and freeze a value with no consumer.

*Alternative rejected:* keep it, documented as reserved. A field the API accepts is a field callers set; the
cheaper time to remove it is before anything depends on it.

### D7 — One rules class, two entry points

`ScanMetadataRules.requireDeclarable` runs in `TableSchemaCreationService` before anything is persisted (it
needs only the kind and the two names); `ScanMetadataRules.validateSource` runs inside
`CatalogSchemaWriter.persistSourceSchema`, which is where the resolved `ColumnMappingEntity` rows — with
nullability defaulted per kind, and the declared type and sensitivity — exist. The split is by what is
available, not by rule.

### D8 — No test helper defaults the pair

`IntegrationTables` previously defaulted the identity to the first ordering-key column, on the reasoning that
"any declared column is legal, since the endpoint only checks membership". That is how the suite came to
assert a total order that does not hold. The helper now declares nothing; a fixture that wants the pair
passes it and means it. Treat a helper that can invent a correctness claim as the tell that the claim is not
checkable.

## Risks / Trade-offs

- **A caller can still declare a non-unique identity.** Nothing here can see uniqueness. What changed is that
  nothing *pushes* a caller into declaring one, the shapes where it is unavoidable are documented, and the
  three properties that are checkable are checked.
- **An `append` source's version column is caller-populated.** Nothing stamps it, so a caller that declares
  one and forgets to write it gets ClickHouse's zero default and one shared version across rows; the scan
  still pages (identity breaks the tie) but the safety lag stops being meaningful. The alternative — the
  platform stamping an `_ingested_at` on every source, as `dial_usage_log` carries — is a DDL change for all
  new tables and belongs in its own change.
- **Multi-column-grain rollups remain unscannable.** That is the honest state until tuple identity exists; it
  is now stated in `aggregate-pipeline` rather than papered over with a declaration.

## Migration Plan

None. No schema change, no data change, no deploy ordering. Strictly fewer rejections than the required-pair
contract this replaces (which never reached the default branch), plus four rejections for declarations that could
not have worked.

## Open Questions

- **Tuple identity.** `identity_column` as an ordered list would let a multi-column-grain rollup be scanned
  and would remove the "single-column grain" caveat from `aggregate-pipeline`. It touches `CursorPredicates`,
  `CursorCodec`, the cursor wire format and every rule-scoped read path — the deferred tuple-grain work.
- **A rename could repoint instead of being rejected.** `TableSchemaService.requireMutable` rejects a rename
  of a scan-metadata column, while a rename of the TTL-referenced column is allowed and rewrites the stored
  TTL column in the same transaction. The same treatment is available here; left out because nothing else
  about the pair is mutable, so a repoint has no home yet.
- **Whether the platform should stamp `_ingested_at` on every source**, which would make a declared version
  column always platform-assigned and remove the monotonicity promise entirely.
