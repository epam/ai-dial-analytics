## Context

`AdasFlightSqlProducer.getStreamSqlInfo` builds its response with Arrow's `SqlInfoBuilder`, which
holds a map from `SqlInfo` code → value provider and, on `send(infoList, listener)`, walks the
**requested** codes writing one row each.

The failure mode is worse than "the value is missing". `SqlInfoBuilder.send` does no null check:

```java
providers.get(infos.get(i)).accept(root, i);   // NPE when the code has no provider
```

The NPE is caught by the surrounding `catch (Throwable)` and routed to `listener.error(throwable)`.
So a single unadvertised code does not yield a missing row — it **fails the entire `GetSqlInfo`
call**, taking every other requested value down with it. That is the mechanism behind the measured
`<Error>` results, and it means the blast radius of an unanswered code depends on how the client
batches its requests: a driver that asks for its capability set in one call loses the whole set.

This also fixes the shape of the "keep the function sets unadvertised" decision. Withholding them is
not free — a client that asks for `SQL_DATETIME_FUNCTIONS` in the same call as values ADAS does
supply gets nothing at all. The trade stands (a wrong function set breaks *queries*, a failed
`GetSqlInfo` breaks *discovery*, and discovery already fails today), but it is a trade, not a
no-op, and the follow-up is worth more because of it.

Current state: 17 providers, covering server identity, read-only/transaction posture, the three
`withSqlDdl*` flags, the catalog/schema namespace declaration (shipped with
`flight-sql-catalog-metadata`; see proposal), identifier case, and the identifier quote character.
`SqlInfoBuilder` (Arrow 18.3.0) exposes 88 `with*` methods.

The constraint that shapes this change is asymmetric risk. Most of the unadvertised codes are
questions ADAS has a settled answer to. But a subset are *capability claims that clients act on*: a
driver reads `SQL_STRING_FUNCTIONS`, tells Power Query that `UPPER` is foldable, Power Query pushes
`upper(x)` into the generated statement, and ADAS's closed function catalog either accepts it or
turns a computation that worked locally into a hard error. Answering "everything we can" and
answering "everything that is safe" are different changes; this is the second.

## Goals / Non-Goals

**Goals:**

- An ODBC/JDBC client over Flight SQL gets an *answer* — not an error — for every capability question
  whose answer is already determined by shipped ADAS behaviour.
- The rule deciding what may be advertised is written down in the spec, so the next person adding a
  value does not have to re-derive the risk analysis.
- Every value ADAS advertises as *supported* is backed by a test that exercises the behaviour, not by
  a reading of the spec.

**Non-Goals:**

- Function-support sets (`SQL_STRING_FUNCTIONS`, `SQL_NUMERIC_FUNCTIONS`, `SQL_SYSTEM_FUNCTIONS`,
  `SQL_DATETIME_FUNCTIONS`). `SQL_TIMEDATE_FUNCTIONS` keeps erroring. See "Follow-up" below.
- SQL-grammar level claims (`SQL_SUPPORTED_GRAMMAR`, `SQL_ANSI92_SUPPORTED_LEVEL`) and
  `SQL_SUPPORTED_GROUP_BY` — same class of risk, and their ODBC bit semantics need measurement
  against a real driver rather than derivation.
- Any change to the SQL front end, the catalog RPCs, the zero-row `GetCatalogs`/`GetDbSchemas`
  behaviour, or REST.
- Making `GetSqlInfo` answer *all* codes. Completeness is not the goal; the absence of avoidable
  errors is.
- `SQL_SUPPORTED_CONCURRENCIES_FOR_RESULT_SET_*` — the companion to the result-set types this change
  does answer, and the backing for JDBC's `supportsResultSetConcurrency`. An empty bitmask would pass
  the admissibility rule cleanly (read-only server, forward-only stream), but `SqlInfoBuilder` exposes
  no `with*` setter for it, so supplying it means hand-rolling a provider against Arrow internals.
  Not worth that coupling for a code no measured client asked for; recorded here so the omission is
  deliberate rather than incidental, and cheap to revisit if a driver turns out to consult it.

## Decisions

### The admissibility rule

A `GetSqlInfo` value is advertised only when **both** hold:

1. **Determined** — the answer follows from behaviour already specified in `flight-sql-endpoint` or
   `sql-query-translation`, or from an absence (a feature ADAS has no implementation of at all).
2. **Non-steering** — the value cannot cause a client to generate SQL that ADAS then rejects. In
   practice this admits three shapes:
   - a **negative** declaration (`false`, or an empty enum bitmask) — it can only narrow what a
     client attempts;
   - a **limit** of `0`, which in ODBC/JDBC means "no limit, or unknown";
   - a **positive** declaration that this change covers with a test.

Everything a positive claim asserts is therefore either untestable-and-excluded or tested. That is
the whole guard: the reason function sets are out is not that they are hard, it is that getting them
right means measuring which ids each client consults and mapping each bit against the closed
catalog — work with its own verification plan.

Alternative considered: advertise every value `SqlInfoBuilder` exposes, defaulting the risky ones to
the most conservative setting. Rejected — "conservative" is not well-defined for the function
bitmasks (a driver may treat an empty `SQL_STRING_FUNCTIONS` as "unknown, assume all" rather than
"none"), so the conservative reading is itself a measurement question.

### The values, and what determines each

Identifier and search metadata:

| Value | Setting | Determined by |
|---|---|---|
| `SQL_EXTRA_NAME_CHARACTERS` | `""` | Unquoted identifiers carry no character beyond `[A-Za-z0-9_]`; the one dotted form (`"deployment_ref.display_name"`) is addressable **only** double-quoted (`sql-query-translation`, "SQL is validated against the query-schema catalog"), and ODBC scopes this value to unquoted names. This is the measured `SQL_SPECIAL_CHARACTERS` error. |
| `SQL_QUOTED_IDENTIFIER_CASE` | `CASE_INSENSITIVE` | "Identifier matching SHALL be case-insensitive", same clause. Tested. |
| `SQL_SEARCH_STRING_ESCAPE` | `"\"` | The catalog RPCs' `LIKE` patterns escape `%` and `_` with a backslash (`flight-sql-endpoint`, "Catalog metadata RPCs stream from the query catalog"; `FlightCatalogStreams:171`). |
| `SQL_KEYWORDS` | `[]` | ADAS reserves no keyword beyond standard SQL. `date_bin` and `width_bucket` are function names, not keywords — a client quoting them as identifiers is unaffected. |

Absences, each a construct `sql-query-translation`'s "Unsupported SQL fails closed before execution"
rejects, or a feature with no implementation: `SQL_SUPPORTED_UNIONS`, `SQL_SUPPORTED_SUBQUERIES`,
`SQL_OUTER_JOIN_SUPPORT_LEVEL`, `SQL_SUPPORTS_CONVERT`, `SQL_SUPPORTED_POSITIONED_COMMANDS`,
`SQL_SUPPORTED_TRANSACTIONS_ISOLATION_LEVELS` (all empty), and the booleans
`SQL_STORED_PROCEDURES_SUPPORTED`, `SQL_STORED_FUNCTIONS_USING_CALL_SYNTAX_SUPPORTED`,
`SQL_CORRELATED_SUBQUERIES_SUPPORTED`, `SQL_SELECT_FOR_UPDATE_SUPPORTED`,
`SQL_BATCH_UPDATES_SUPPORTED`, `SQL_SAVEPOINTS_SUPPORTED`, `SQL_NAMED_PARAMETERS_SUPPORTED`
(`?` markers are rejected), `SQL_SUPPORTS_LIKE_ESCAPE_CLAUSE` (an `ESCAPE` clause is rejected —
"LIKE maps only to the contains operators"), `SQL_SUPPORTS_INTEGRITY_ENHANCEMENT_FACILITY`,
`SQL_SUPPORTS_NON_NULLABLE_COLUMNS` (every Arrow field is nullable),
`SQL_DATA_DEFINITION_CAUSES_TRANSACTION_COMMIT`, `SQL_DATA_DEFINITIONS_IN_TRANSACTIONS_IGNORED`,
`SQL_LOCATORS_UPDATE_COPY`, `SQL_CATALOG_AT_START`, `SQL_MAX_ROW_SIZE_INCLUDES_BLOBS` — all `false`.
`SQL_PROCEDURE_TERM` is `""` for the same reason `SQL_SCHEMA_TERM` is: no such namespace.
`SQL_DEFAULT_TRANSACTION_ISOLATION` is `0` (`TRANSACTION_NONE`).

Limits — every `SQL_MAX_*` is `0`, "no limit imposed by ADAS", **except**:

- `SQL_MAX_TABLES_IN_SELECT = 1`. The translator accepts a single-entity `FROM` only; every JOIN and
  every multi-table shape is rejected. This is a real, useful fact — a driver that respects it will
  not attempt to generate a join.

`0` is deliberately not "unlimited" as a claim about the backend: ClickHouse and the HTTP transport
impose their own bounds. It is the ODBC/JDBC encoding of "the source does not specify", which is
exactly ADAS's position — it never validates an identifier's length or a statement's size.

Transport facts: `SQL_SUPPORTED_RESULT_SET_TYPES` = `FORWARD_ONLY` (a Flight stream is consumed once,
forward), `FLIGHT_SQL_SERVER_CANCEL` = `false`, `FLIGHT_SQL_SERVER_BULK_INGESTION` and
`..._BULK_INGESTION_TRANSACTION` = `false`, `FLIGHT_SQL_SERVER_STATEMENT_TIMEOUT` and
`..._TRANSACTION_TIMEOUT` = `0` (no timeout enforced by the server).

Positive claims, each tested in this change:

| Value | Setting | Test |
|---|---|---|
| `SQL_SUPPORTS_COLUMN_ALIASING` | `true` | `AS` aliasing already round-trips in the suite; a `GetSqlInfo` assertion pins the declaration alongside it. |
| `SQL_SUPPORTS_TABLE_CORRELATION_NAMES` | `true` | New: `SELECT t.deployment FROM dial_usage_log t` streams. (`SELECT t.*` is already specified; a qualified *column* reference was not, so it is measured rather than assumed.) |
| `SQL_SUPPORTS_DIFFERENT_TABLE_CORRELATION_NAMES` | `false` | ODBC's "correlation names must differ from the table name" — ADAS imposes no such rule. Negative. |
| `SQL_ALL_TABLES_ARE_SELECTABLE` | `true` | ADAS gates no entity's `SELECT`: per-table authorization (`TableAccessEvaluator`) covers WRITE and MODIFY only, so every name `GetTables` streams is selectable by the caller it was streamed to. New test in `FlightMetadataAuthIntegrationTest`: list the entity names as a non-admin caller, then `SELECT * FROM <each> LIMIT 1` with the same credentials. |
| `SQL_NULL_ORDERING` | measured | See below. |
| `SQL_NULL_PLUS_NULL_IS_NULL` | `true` | Standard SQL semantics; unobservable in practice since arithmetic is rejected outright. |

### `SQL_NULL_ORDERING` is measured, not derived

Default null placement is a ClickHouse behaviour, not an ADAS one, and the `sort` DSL exposes explicit
`NULLS FIRST`/`LAST` per key without documenting the default. Rather than guess a `SqlNullOrdering`
constant, the implementation task pins it with an integration test that sorts a nullable column with
no explicit null clause against the Testcontainers ClickHouse and asserts the declared constant
matches the observed placement. If the two disagree the declaration follows the measurement.

### `SQL_SUPPORTS_EXPRESSIONS_IN_ORDER_BY` and `SQL_SUPPORTS_ORDER_BY_UNRELATED` are declared `false`

Both are genuinely mixed: `ORDER BY count(*)` resolves through a select output and works, while
`ORDER BY price * 2` is rejected as arithmetic; ordering by an unselected column works in row mode
but not in aggregate mode. `false` is the honest conservative answer — it steers a client to name the
output column, which always works, and cannot produce a rejected statement. `true` could. This is the
one place the change declares something narrower than reality, and it does so on purpose.

Alternative considered: omit both, leaving them to error. Rejected — a false-but-safe answer beats an
error for values whose only effect is to make the client do more of the work itself.

## Risks / Trade-offs

- **A driver treats an empty bitmask as "unknown" rather than "none", and behaves as it did before.**
  → No worse than today, where the value is absent entirely. The change cannot regress a client that
  currently works, because every added value either states an absence or matches the driver's
  existing assumption.
- **`SQL_MAX_TABLES_IN_SELECT = 1` makes a client refuse to offer a join it would previously have
  attempted.** → That is the intent: the attempt fails today with `INVALID_ARGUMENT`, and failing in
  the client's UI before a round trip is strictly better than failing after one.
- **The conservative `false` on the two ORDER BY values costs a client a foldable ordering.** → It
  costs a client an ordering it would have folded and had rejected roughly as often as one it would
  have folded successfully. Revisit alongside the function sets, where the same measurement applies.
- **`SQL_NULL_ORDERING` is pinned to observed ClickHouse behaviour, which a ClickHouse upgrade could
  change.** → The integration test fails if it does, which is the point of measuring it there.
- **`SQL_ALL_TABLES_ARE_SELECTABLE = true` holds only while ADAS has no per-table read grant.** The
  catalog is *column*-filtered, not table-filtered — `CatalogQueryService.queryableEntityNames()`
  lists every ACTIVE source with no caller in scope, and `SensitiveColumnAccess` narrows columns
  within a listed entity. So the claim is not "the listing is already restricted to what you may
  read"; it is "nothing is restricted", which is a weaker and more fragile fact.
  `rework-uac-role-based-access` is the in-flight change that could falsify it. → The test executes a
  statement against every listed name as a non-admin caller, so the day a read grant lands the
  declaration fails here rather than in a client that trusted it. Whoever adds that grant flips this
  value to `false` in the same change.
- **Growth of `getStreamSqlInfo` from 17 to ~55 providers makes the method long.** → It is a flat
  declaration list with no branching; grouped by concern with a comment per group. The
  admissibility rule in the spec is what keeps it from growing carelessly.

## Follow-up (not this change)

**Advertise the function-support sets.** `SQL_STRING_FUNCTIONS`, `SQL_NUMERIC_FUNCTIONS`,
`SQL_SYSTEM_FUNCTIONS`, and `SQL_DATETIME_FUNCTIONS` (ODBC `SQL_TIMEDATE_FUNCTIONS`, one of the two
measured errors) stay unadvertised here. Doing them right requires mapping each ODBC bit against
ADAS's closed catalog — scalar `lower upper length trim abs width_bucket date_bin`; aggregate
`count sum avg min max percentile_cont percentile_disc` — and measuring which ids clients actually
consult and how they act on a partial set.

Why it matters: query folding is what makes Power BI usable against a row-capped source. Without
folding, Power Query pulls rows and computes locally, which the Flight row cap
(`flight.max-rows`, default 10000) bounds hard; with correct folding, filters and aggregates execute
in ClickHouse and the cap stops binding for the shapes that matter. It is a prerequisite for any
DirectQuery work. Over-claiming is the failure mode to avoid — a folded expression ADAS rejects turns
a working local computation into a hard error — which is why it needs its own measurement pass rather
than a best guess appended to this one.
