## Context

`DeriverRegistry.jsonExtractRate` is the single point where the submitted rating's type is discarded:

```java
if (node.isBoolean()) { return node.asBoolean(false) ? 1L : 0L; }
if (node.isNumber())  { return node.asLong(0); }
```

Both branches produce a `Long` that lands in `rate_analytics.rate` (`Nullable(Int8)`). After that
insert, nothing in the row records which branch ran, so boolean `false` and numeric `0` are
indistinguishable for the lifetime of the data.

In observed feedback data the zeros are the ambiguous population — on the order of a quarter of all
ratings. DIAL's own `/rate` contract permits both
forms, and both appear in real logs (the polymorphism is why the normalizer exists at all), so this is
not a producer bug to push upstream; it is an ADAS ingest decision to revisit.

Constraints:

- `rate_analytics` is a **system table** (`system: true`) provisioned by ClickHouse migration, not by
  the table-management API. Schema changes go through `db/migration/clickhouse/`, and the query
  catalog registration through `db/migration/postgres/`.
- `rate` has existing consumers (the `response_ratings` rollup's six rate measures, and the
  `rate-analytics` spec's normalization requirement). Its behavior must not change.
- The table carries a 1-year TTL partitioned by day, so recent history is still retained.
- `SchemaValidator` reconciles the mapping against the live table at startup and refuses to boot on
  drift, which fixes the deployment ordering (see Migration Plan).

## Goals / Non-Goals

**Goals**

- Make an exact thumbs-down count expressible in one query.
- Preserve the boolean-vs-numeric distinction for every event ingested after deployment.
- Change nothing about `rate`, so no existing query, rollup, or enrichment is affected.
- Spend as little new surface as the problem needs — the deriver registry is closed and deliberately
  small, so an entry that duplicates an existing one is a cost, not a clarification.

**Non-Goals**

- Recovering the distinction for existing rows. The information is not in ClickHouse.
- Replacing `rate`. A second representation is cheaper and safer than migrating consumers.
- Materializing a `sentiment` column. With `rate_raw` present, sentiment is a two-column expression
  the consumer can write; baking in one interpretation would repeat this change's mistake.
- Changing `/rate` ingest matching, `comment` handling, or anything else in the mapping.

## Decisions

### D1: Store the verbatim scalar text (`rate_raw`), not a type enum (`rate_kind`)

**Decision.** One new column holding the submitted scalar's text: `true`, `false`, `0`, `-1`.

**Alternative rejected — `rate_kind` enum** (`boolean` | `number` | null). It answers "was it a
boolean?" but not "what did it say", so a consumer must still join it against `rate` to reason, and it
is strictly less informative at identical storage cost. `rate_raw` subsumes it: kind is derivable
(`rate_raw IN ('true','false')`), and the raw text additionally survives a producer that starts
sending a value outside {-1, 0, 1} — a 5-star rating, say — which an enum would silently flatten.

**Alternative rejected — widen `rate` to a string.** Breaks every existing consumer and every
numeric measure over it, to solve a problem an additive column solves.

**Alternative rejected — two boolean columns** (`rate_was_boolean`, `rate_was_cleared`). Encodes a
current interpretation of the data into the schema. The interpretation is exactly what is uncertain
(see Open Questions), so it must not be frozen at ingest.

### D2: `LowCardinality(Nullable(String))`, nullable, non-sensitive, non-heavy

**Decision.** The domain is a handful of short tokens, so `LowCardinality` makes the column near-free
and keeps `GROUP BY rate_raw` fast. Nullable, matching `rate`, so absent means absent rather than a
sentinel.

Not `sensitive`: it holds a rating scalar, never user-authored text — unlike `comment`, which is
`sensitive` and AES-coded. Marking it sensitive would gate the thumbs-down count behind `FULL_ADMIN`
for no privacy gain. Not `heavy`: a token of a few bytes, so it must not be excluded from `SELECT *`.

**The nested wrapper is safe with `SchemaValidator`, verified rather than assumed.** That class
compares a mapping's declared kind and nullability against `DESCRIBE TABLE`, and its `isNullable`
walks *through* wrappers in either nesting order while `normalize` strips both before classifying —
so `LowCardinality(Nullable(String))` reconciles as a nullable `STRING`. This mattered enough to check:
a naive `startsWith("Nullable(")` would have refused startup, and the failure would have surfaced only
on a real boot against a migrated ClickHouse.

### D3: Reuse `json-extract-string` — add no deriver

**Decision.** `rate_raw` is declared in `mapping/rate-analytics.yaml` as
`derive: json-extract-string, sources: [request.body], args: { path: rate }, kind: string,
nullable: true`. `DeriverRegistry` is not touched.

That deriver returns `node.asString("")` for the node at `args.path`. On the pinned Jackson 3.1.4
(`tools.jackson.core:jackson-databind:3.1.4`) that yields, measured directly:

| `request.body` member | `asString("<default>")` | column value |
|---|---|---|
| `true` / `false` | `true` / `false` | `true` / `false` |
| `0` / `-1` / `1.5` | `0` / `-1` / `1.5` | same |
| `"x"` | `x` | `x` |
| `""` | `` (blank) | null, via the nullable fallback |
| `{}` / `[]` | the default | null, via the nullable fallback |
| absent | — (`extract` returns null) | null |

Which is the specified behavior exactly, including the non-scalar case: `MappingEngine.applyFallback`
treats a blank string as absent, and the column is nullable, so an object or array yields null rather
than a serialized document.

**Alternative rejected — a new "raw scalar reader" deriver.** It would be a second registry entry whose
body is the same one-line `asString` call, differing from `json-extract-string` in nothing an actual
mapping can reach. The registry is closed and small on purpose; a near-duplicate in it is the same
category of near-duplicate this change removes from the schema, and it would give two names for one
behavior that a later edit could silently make disagree.

**What reuse costs, and how it is paid.** The behavior `rate_raw` depends on — a boolean member reading
as `true`/`false` rather than as `1`/`0` — is currently incidental: the deriver's javadoc discusses only
the string and non-scalar cases, because every path it serves today (`id`, `method`, `params.name`,
`comment`, `responseId`) resolves to a string. So it is pinned by a scenario in the `data-sync` delta
and by a test, and the deriver's javadoc gains the sentence. That is the whole cost, and it is smaller
than a registry entry.

### D4: Both rate columns read the same node independently

**Decision.** `rate` and `rate_raw` each read `request.body` at `args.path: rate` through their own
deriver invocation. Neither reads the other's output.

The alternative — having `json-extract-rate` return a pair, or having the raw column read the
normalized one — would couple two columns inside the registry and break the one-deriver-one-column
shape the `data-sync` spec defines. The cost is one extra JSON walk per rate record, on a table taking
very few rows.

### D5: Leave history null; do not infer

**Decision.** Existing rows read null. `rate_raw` is not populated by any inference from `rate`.

Inferring would mean guessing — the guess would be wrong for a knowable fraction of rows and, worse,
would be indistinguishable from a real observation afterwards. A null that says "not captured" is more
useful than a value that says "probably". Consumers scope to `rate_raw IS NOT NULL` when they need
certainty.

### D6: The negative count reads both columns, not `rate_raw` alone

**Decision.** The published expression is
`rate_raw = 'false' OR (rate_raw IS NOT NULL AND rate < 0)`.

`rate_raw` is a string, so it has no ordering: `rate_raw < '0'` is nonsense and `rate_raw = '-1'`
enumerates one value of an open domain. The numeric half of the question is already answered correctly
by `rate`, which is normalized and typed; only the boolean half needs the raw text. Reading both is
therefore not a workaround but the intended division of labour, and it keeps the count correct if the
producer ever sends `-2`. The `rate_raw IS NOT NULL` guard scopes the count to the post-deployment
population, which is the only population where "unambiguous" means anything.

## Risks / Trade-offs

- **A queried thumbs-down count silently under-reports over any window spanning the deployment.**
  → The spec requires null (not a substituted value) for pre-deployment rows, so the boundary is
  detectable: `count(*) WHERE rate_raw IS NULL` names exactly the unattributed population. Say so
  wherever the number is published.
- **A JSON string `"true"` is indistinguishable from boolean `true`.** Both store the text `true`.
  → Accepted. It requires a producer to send a quoted boolean, which DIAL does not do; and the
  consequence is limited to that one pathological case rather than the large ambiguous share the change fixes.
- **Reuse means an unrelated edit to `json-extract-string` could break `rate_raw`.** The deriver now
  has a caller that depends on its boolean and numeric branches, which no previous caller did.
  → Pinned by the `data-sync` delta's scenario and by a `DeriverRegistryTest` case asserting the
  boolean and numeric forms directly, so the dependency is stated where an editor will see it.
- **Two columns can drift if a future edit changes one branch of the normalizer.**
  → The *Normalized rate stays ambiguous on its own* scenario asserts the pair's relationship directly,
  so a change to either deriver that breaks the pairing fails a test rather than a dashboard.
- **`LowCardinality` degrades if the producer starts sending high-cardinality garbage.**
  → The domain is a rating scalar; a producer sending unbounded text here would be a separate defect,
  and the column stays functional (just larger) if it happens.

## Migration Plan

1. ClickHouse migration: `ALTER TABLE rate_analytics ADD COLUMN rate_raw LowCardinality(Nullable(String)) CODEC(ZSTD(1)) AFTER rate`.
   Additive metadata-only operation — no part rewrite, existing parts read null.
2. Postgres migration: register the catalog column (nullable string, tag `request`, not sensitive, not
   heavy) with its description, following the existing `rate_analytics` seed/backfill migrations.
3. Mapping entry, shipped in the same build. **Ordering between (1) and (3) is enforced, not merely
   advisable**: `SchemaValidator` runs at `@Order(1)`, after `ClickHouseMigrationRunner`, and throws at
   startup when a mapped column is missing from `DESCRIBE TABLE`. Under the normal
   migrations-on-startup path that ordering is automatic; an operator running with
   `clickhouse.migrations.enabled=false` must apply the DDL **before** rolling the new build, or the
   service will not start.
4. Verify on the first ingested rate event after deploy: `rate_raw` non-null and consistent with
   `rate`.

**Rollback.** Revert the mapping entry (ingest stops writing the column; `rate` is unaffected). The
column itself can stay — an unwritten nullable column is inert — so a rollback needs no `DROP COLUMN`
and no data loss. Dropping it later is a separate additive-inverse migration. Note the asymmetry: the
mapping can be rolled back alone, but the migration cannot, because rolling back only the migration
would leave the mapping declaring a column the table lacks and `SchemaValidator` would refuse to start.

**No new credential and no new config key.** The column does not use the AES codec, so it does not
inherit `comment`'s server-side-encryption-key prerequisite.

## Open Questions

- **Does DIAL actually use numeric `0` for "cleared" and boolean `false` for thumbs-down?** This is the
  question the change exists to answer and it **cannot be answered before shipping** — the evidence was
  discarded at ingest. If both forms turn out to mean thumbs-down, the exact negative count is
  `rate_raw = 'false' OR rate <= 0` and the "cleared rating" category simply does not exist; if they
  differ, the counts separate cleanly. Either way `rate_raw` is what makes the answer observable, so
  this question does not block the change — but a consumer should not publish a "cleared ratings" figure
  until it is settled.
- **Are the blob-storage records for the existing events still retained?** If so, a
  `data-sync-backfill` re-ingest over that range would populate `rate_raw` for history. Worth checking
  before telling anyone history is permanently unattributed. Out of scope here either way, but cheap to
  answer: the question is whether the blob container's retention reaches back to the table's earliest
  event.
