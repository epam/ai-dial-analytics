## MODIFIED Requirements

### Requirement: Execution reuses structured-query governance

Every Flight query SHALL execute through the same execution path as `POST /v1/queries/execute`: a
single-block statement by constructing a `StructuredQuery`, a composite statement (one that joins, reads a
derived table or CTE, carries a subquery, or combines branches with a set operation — see the
sql-query-translation contract) by executing the
composite plan the shared front end produces. All structured-query governance SHALL apply unchanged with
one exception, the row ceiling: entity and field validation in every block, the closed function catalog,
sensitive-column masking, `final` deduplication, the query budget and enrichment join rules apply as-is,
while the maximum number of rows a Flight query may return is the Flight transport's own configured cap
`flight.max-rows` (default 10000) rather than the shared 1000-row maximum. That the Flight ceiling exceeds
the REST ceiling is deliberate: Flight streams Arrow record batches with per-request Arrow memory bounded
by `flight.request-memory-limit-bytes` and concurrency bounded by `flight.max-concurrent-streams`, whereas
the REST surfaces materialize a JSON document. For a composite statement the ceiling, the clamp and the
truncation probe below apply to the **outermost** block; an inner block's `LIMIT`/`OFFSET` is executed as
written and an inner block without one is not bounded. For a set-operation statement the outermost block
is the combined result, so the cap, the clamp and the probe count the rows the branches produce together.

`flight.max-rows` is a **ceiling on the number of rows the endpoint returns**, not a constraint on the
`LIMIT` literal a client may write. No statement SHALL be rejected for the size of its `LIMIT`
(**BREAKING**: superseding the rule that a `LIMIT` above `flight.max-rows` is rejected with
`INVALID_ARGUMENT` naming the requested limit and the cap). A `LIMIT` far above the cap is how BI tools
express a self-protection bound — Power BI's DirectQuery generator emits `LIMIT 1000001` on results of
a few dozen rows — and refusing it blocks queries the endpoint can answer while protecting nothing.

The governing distinction is therefore whether the client stated a bound **that ADAS can honour**:

- A statement with an explicit `LIMIT n` where `n <= flight.max-rows` SHALL return up to `n` rows with
  no truncation error and no probe: the caller asked for a bound, so stopping at it is not a surprise.
- Every other statement — one whose `LIMIT` was not stated (no `LIMIT` at all, or an `OFFSET` with no
  `LIMIT`), **or one whose explicit `LIMIT` exceeds `flight.max-rows`** — SHALL be executed under an
  implicit bound of `flight.max-rows`, with its effective limit clamped to the cap. Such a statement
  SHALL NOT be rejected for lacking a `LIMIT` or for stating an oversized one (**BREAKING**:
  superseding `flight-sql-catalog-metadata`, which rejected every statement without an explicit
  `LIMIT`; that rule blocked results that cannot truncate, such as `SELECT count(*)`, and every query a
  BI tool's DirectQuery generator emits).

Whether the limit was stated SHALL be carried explicitly by the translation and SHALL NOT be inferred
from the limit's value; whether a stated limit can be honoured SHALL be decided by comparing it to the
configured cap, so a non-default `flight.max-rows` governs both the clamp and the ceiling.

A statement executed under the implicit bound SHALL be executed requesting one row more than the cap,
and:

- if more than `flight.max-rows` rows are returned, the call SHALL fail with `INVALID_ARGUMENT` naming
  the configured cap and both remedies (add an explicit `LIMIT`, or aggregate), and SHALL NOT stream a
  partial page;
- otherwise the rows SHALL be streamed, and the extra probe row SHALL NEVER be visible to the client.

Any `OFFSET` the statement carried SHALL be preserved when the limit is clamped.

Because `getFlightInfo` validates without executing, a truncation failure can only surface at `doGet`:
`getFlightInfo` SHALL succeed for such a statement and the stream SHALL then fail. Consequently a
query's success depends on the data as well as the statement — a result that fits the cap today may
exceed it later — and a client that supplies an explicit `LIMIT` within the cap is not subject to it.
The clamp SHALL be applied on the `doGet` ticket path as well as at `getFlightInfo`, because the ticket
carries client-supplied SQL and a hand-forged ticket must not reach execution unbounded.

The REST surfaces are unaffected: `POST /v1/queries/execute-sql`, `POST /v1/queries/execute`, and the
translation endpoints SHALL keep the shared default limit of 100 and maximum of 1000, including their
existing silent truncation of an unbounded query to the default and their rejection of an explicit
`limit` above the shared maximum. The clamp specified here SHALL NOT be extended to them: it is a
property of a streaming transport whose clients write guard rails, not of a caller hand-writing a page
size.

#### Scenario: Limit above the configured cap is clamped, not rejected

- **WHEN** a client runs the Power BI DirectQuery statement
  `select "deployment", "C1" from (select "deployment", SUM("total_price") as "C1" from "adas"."dial_usage_log" group by "deployment") as "ITBL" where not "C1" is null LIMIT 1000001 OFFSET 0`
  via Flight with `flight.max-rows` at its default, and the aggregate produces fewer rows than the cap
- **THEN** the call succeeds and every result row is streamed — the `LIMIT` literal alone causes no
  error

#### Scenario: Clamped limit still fails on an oversized result

- **WHEN** a client runs a statement whose explicit `LIMIT` exceeds `flight.max-rows` and whose result
  exceeds `flight.max-rows`
- **THEN** `getFlightInfo` succeeds, the `doGet` stream fails with `INVALID_ARGUMENT` naming the cap and
  the remedies, and no rows are delivered to the client

#### Scenario: Unbounded query under the cap succeeds

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log` with no `LIMIT` and the entity holds
  fewer rows than `flight.max-rows`
- **THEN** every matching row is streamed and no error is raised

#### Scenario: Unbounded query over the cap fails without a partial page

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log` with no `LIMIT` and the entity holds
  more than `flight.max-rows` rows
- **THEN** `getFlightInfo` succeeds, the `doGet` stream fails with `INVALID_ARGUMENT` naming the cap and
  the remedies, and no rows are delivered to the client

#### Scenario: Unbounded aggregate succeeds

- **WHEN** a client runs `SELECT count(*) AS n FROM dial_usage_log` with no `LIMIT` over an entity of any
  size
- **THEN** the single aggregate row is returned and no error is raised, because one row cannot exceed the
  cap

#### Scenario: Probe row never reaches the client

- **WHEN** a query executed under the implicit bound — whether it stated no `LIMIT` or one above the cap
  — returns exactly `flight.max-rows` rows
- **THEN** exactly `flight.max-rows` rows are streamed; the extra row requested to detect truncation is
  never part of the result

#### Scenario: Explicit limit stops at the bound

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log LIMIT 7` where far more rows match
- **THEN** exactly 7 rows are streamed and no truncation error is raised

#### Scenario: Offset survives the clamp

- **WHEN** a client runs a statement with an explicit `LIMIT` above the cap and a non-zero `OFFSET`
- **THEN** the offset is applied and the result is bounded by the cap, exactly as it would be for the
  same statement with no `LIMIT`

#### Scenario: Offset without limit is bounded, not rejected

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log ORDER BY total_tokens OFFSET 5` with no
  `LIMIT`
- **THEN** the query executes under the implicit cap instead of being rejected

#### Scenario: Configured cap governs the clamp

- **WHEN** `flight.max-rows` is configured to 5 and a client runs, over an entity holding 6 rows, an
  unbounded query and separately `SELECT … LIMIT 6`
- **THEN** both are executed under the bound of 5 and both fail naming 5, because the result exceeds the
  cap — the explicit `LIMIT 6` is not rejected for its literal but for the size of its result

#### Scenario: REST keeps the shared governance

- **WHEN** the same unbounded statement is sent to `POST /v1/queries/execute-sql` over an entity holding
  more than 100 rows
- **THEN** it succeeds and returns the shared default of 100 rows, unchanged by the Flight cap

#### Scenario: REST still rejects an oversized explicit limit

- **WHEN** a client sends `SELECT project_id FROM dial_usage_log LIMIT 50000` to
  `POST /v1/queries/execute-sql`
- **THEN** it fails with the shared page-limit error naming 50000 and 1000 — the Flight clamp does not
  apply to REST

#### Scenario: Composite statement streams under the same cap

- **WHEN** a client runs `SELECT a.project_id, b.rate FROM dial_usage_log a JOIN rate_analytics b ON a.response_id = b.response_id` via Flight with no `LIMIT`
- **THEN** `getFlightInfo` reports the two result columns with their Arrow types, the stream is bounded
  by `flight.max-rows` with the same truncation probe as a single-entity statement, and the rows are
  streamed

#### Scenario: Composite statement failures map like single-entity ones

- **WHEN** a client runs a join whose `ON` carries no equality, or a correlated subquery, via Flight
- **THEN** `getFlightInfo` fails with `INVALID_ARGUMENT` carrying the translator's message, and nothing
  is executed

#### Scenario: Set-operation statement streams under the same cap

- **WHEN** a client runs `SELECT model FROM dial_usage_log UNION ALL SELECT model FROM rate_analytics` via
  Flight with no `LIMIT`
- **THEN** `getFlightInfo` reports the single result column with its Arrow type, the stream is bounded by
  `flight.max-rows` with the same truncation probe applied to the combined result, and the rows are
  streamed

### Requirement: Capability advertisement declares no catalog and no schema namespace

`CommandGetSqlInfo` SHALL advertise an **empty** `SQL_CATALOG_TERM` and an **empty** `SQL_SCHEMA_TERM`,
together with `SQL_CATALOGS_SUPPORTED_ACTIONS` and `SQL_SCHEMAS_SUPPORTED_ACTIONS` carrying **no
supported actions**, so that a client can discover that neither namespace level exists and generates
unqualified table names. Both keys SHALL be present in the response when requested: an omitted key
leaves a client on its own default, which for schemas is "supported".

Declaring DDL support (`SQL_DDL_CATALOG`, `SQL_DDL_SCHEMA`) SHALL NOT be treated as satisfying this
requirement — it answers whether DDL can create such objects, not whether the namespace level exists.

An unadvertised `GetSqlInfo` value is **not** a default for a client — it fails the call. A requested
code the server holds no value for aborts the whole `CommandGetSqlInfo` response, so every other
value requested alongside it is lost too; the blast radius of one unanswered code is however many the
client asked for in that call. Beyond the namespace declaration above, `CommandGetSqlInfo` SHALL
therefore also advertise:

- **identifier and search metadata** — an empty `SQL_EXTRA_NAME_CHARACTERS` (no character beyond
  `[A-Za-z0-9_]` may appear in an *unquoted* identifier; the dotted enrichment form is addressable
  only double-quoted), a case-insensitive `SQL_QUOTED_IDENTIFIER_CASE` matching the front end's
  case-insensitive identifier handling, a `SQL_SEARCH_STRING_ESCAPE` of backslash matching the
  catalog RPCs' `LIKE` pattern escaping, and an empty `SQL_KEYWORDS` (ADAS reserves no keyword beyond
  standard SQL);
- **the absence of every construct the SQL front end rejects and every feature the endpoint does not
  implement** — no `CONVERT`, positioned commands, or transaction
  isolation levels (each an empty set), and `false` for stored procedures and call syntax, correlated
  subqueries, `SELECT FOR UPDATE`, batch updates, savepoints, named parameters, a `LIKE ESCAPE`
  clause, the integrity-enhancement facility, and non-nullable columns; an empty `SQL_PROCEDURE_TERM`
  and a `SQL_DEFAULT_TRANSACTION_ISOLATION` of none;
- **limits** — every `SQL_MAX_*` name-length and size value as `0`, the ODBC/JDBC encoding of "the
  source imposes no limit", which is ADAS's actual position: it validates neither identifier length
  nor statement size. `SQL_MAX_TABLES_IN_SELECT` SHALL be `1`, which is no longer a real bound — the
  join and set-operation requirements widened the single-entity `FROM` it was derived from — and is
  recorded below as an inherited under-claim rather than restated as a limit here;
- **set operations** — `SQL_SUPPORTED_UNIONS` SHALL declare `SQL_UNION` and `SQL_UNION_ALL`, both
  determined by the `sql-query-translation` set-operation requirement and non-steering because every
  spelling a client generates from them is accepted. `INTERSECT` and `EXCEPT` have no `SqlInfo` code, so
  their support is not advertisable and their absence from this list is not a claim about them;
- **transport facts** — forward-only result sets, no server-side query cancellation, no bulk
  ingestion, and no server-enforced statement or transaction timeout;
- **the capabilities ADAS does have** — column aliasing, table correlation names (with no rule that a
  correlation name differ from the table it names, so `SQL_SUPPORTS_DIFFERENT_TABLE_CORRELATION_NAMES`
  is `false`), standard `NULL + NULL IS NULL` semantics, the default null ordering
  (`SQL_NULL_ORDERING`), and that every table the catalog lists is selectable. `SQL_NULL_ORDERING`
  describes ClickHouse's placement rather than any behaviour ADAS specifies — the DSL exposes explicit
  `NULLS FIRST`/`LAST` without fixing a default — so it SHALL be determined by measurement against the
  backend and SHALL NOT be derived. `SQL_ALL_TABLES_ARE_SELECTABLE` SHALL hold only while the endpoint
  gates no entity's `SELECT`: it is a statement about the *absence* of per-table read authorization,
  not about the catalog listing being restricted to what the caller may read — the catalog filters
  columns, not entities. A change that introduces a per-table read grant SHALL flip this value in the
  same change;
- **three inherited under-claims, recorded rather than fixed** — `SQL_SUPPORTED_SUBQUERIES` and
  `SQL_OUTER_JOIN_SUPPORT_LEVEL` SHALL keep declaring the empty set, and `SQL_MAX_TABLES_IN_SELECT` the
  value `1`, even though subqueries, outer joins and multi-entity statements are all supported. Each
  under-claims in the safe direction (a client that believes it generates simpler SQL that ADAS
  accepts), each was left behind by the change that widened the subset rather than introduced here, and
  correcting them means one derivation across all three — Flight's subquery bitmask distinguishes
  comparison, `EXISTS`, `IN` and quantified forms and the last is still rejected — which is a change of
  its own. Their values SHALL NOT be read as statements that the constructs are rejected, and a client
  reading `SQL_MAX_TABLES_IN_SELECT` alongside the union declaration above SHALL NOT be assumed to have
  been told anything coherent about how many entities a statement may name;
- **two deliberate under-claims** — `SQL_SUPPORTS_EXPRESSIONS_IN_ORDER_BY` and
  `SQL_SUPPORTS_ORDER_BY_UNRELATED` SHALL be advertised as `false` even though ADAS supports each in
  part: `ORDER BY count(*)` resolves through a select output and works while `ORDER BY price * 2` is
  rejected as arithmetic, and ordering by an unselected column works in row mode but not in aggregate
  mode. `false` steers a client to name the output column, which always works; `true` would let it
  generate the half that does not. Together with the two inherited under-claims above, these are the
  only values this requirement declares narrower
  than reality, and they SHALL NOT be widened on the observation that part of the construct succeeds —
  only evidence that the rejected half is gone, or a measurement of how clients act on a partial
  answer, justifies flipping them.

Which values are admissible SHALL be governed by a rule, so that the set can be extended without
re-deriving the analysis: a value SHALL be advertised only when its answer follows from behaviour
this capability or `sql-query-translation` already specifies (or from the total absence of a
feature) or from a measurement of the backend pinned by a test, **and** it cannot cause a client to
generate SQL the front end then rejects. That admits a negative declaration, a limit of `0`, a
positive declaration covered by a test, and a **conservative `false` on a partially supported
feature** where the narrower answer steers the client to a form that always works. Every such
under-claim SHALL be recorded here with its reason, so that a later reader re-derives neither the
value nor the omission. A value that fails both the determinacy and the non-steering test SHALL be
left unadvertised rather than guessed.

The **function-support sets** — `SQL_STRING_FUNCTIONS`, `SQL_NUMERIC_FUNCTIONS`,
`SQL_SYSTEM_FUNCTIONS`, `SQL_DATETIME_FUNCTIONS` — and the grammar-level claims
`SQL_SUPPORTED_GRAMMAR`, `SQL_ANSI92_SUPPORTED_LEVEL`, and `SQL_SUPPORTED_GROUP_BY` SHALL NOT be
advertised under this requirement. They are claims about what ADAS can *execute*, and a client that
believes one folds an expression into a generated statement: over-claiming converts a computation
that succeeded locally into a rejected query. Advertising them requires mapping each set against the
closed function catalog and measuring how clients act on a partial answer, which is a separate change.
This withholding is a deliberate trade rather than a neutral omission — because an unanswered code
fails the whole call, a client that asks for a function set alongside supported values loses those
too. A broken *query* is worse than broken *discovery*, and discovery already fails today, so the
trade holds; it is recorded here so it is re-decided rather than inherited.

Every value this requirement declares as **supported** SHALL be covered by a test that exercises the
behaviour through a statement, not by a declaration alone; a value whose answer depends on the
backend rather than on ADAS SHALL be pinned by a test that fails if the backend's behaviour changes.
Where the front end makes a declared value **unobservable** — `SQL_NULL_PLUS_NULL_IS_NULL`, which no
statement can reach because arithmetic is rejected outright — a wire-level assertion on the advertised
value SHALL suffice, and the value SHALL be named here so the exemption is enumerated rather than
open-ended.

#### Scenario: Schema namespace declared absent

- **WHEN** a client requests `SQL_SCHEMA_TERM` and `SQL_SCHEMAS_SUPPORTED_ACTIONS` via
  `CommandGetSqlInfo`
- **THEN** both are present in the stream, the term is the empty string, and the supported-actions
  bitmask is zero — so an ODBC client reports `SQL_SCHEMA_TERM=''` and `SQL_SCHEMA_USAGE=0`

#### Scenario: Catalog namespace declared absent

- **WHEN** a client requests `SQL_CATALOG_TERM` and `SQL_CATALOGS_SUPPORTED_ACTIONS`
- **THEN** both are present, the term is the empty string, and the supported-actions bitmask is zero

#### Scenario: Generated statements carry no qualifier

- **WHEN** a metadata-driven client opens a table from a navigator after reading `GetSqlInfo` and
  `GetTables`
- **THEN** the statement it generates names the entity unqualified (`FROM "rate_analytics"`), never
  qualified by an empty identifier (`FROM ""."rate_analytics"`), which the SQL front end would reject as
  a parse error

#### Scenario: Identifier metadata answers instead of erroring

- **WHEN** a client requests `SQL_EXTRA_NAME_CHARACTERS` — the value an ODBC driver surfaces as
  `SQL_SPECIAL_CHARACTERS`, and one that previously failed the `SQLGetInfo` call outright
- **THEN** the stream carries the value as the empty string, and the same call for
  `SQL_QUOTED_IDENTIFIER_CASE`, `SQL_SEARCH_STRING_ESCAPE`, and `SQL_KEYWORDS` likewise returns a
  value rather than nothing

#### Scenario: Rejected constructs are declared absent

- **WHEN** a client requests `SQL_SUPPORTS_CONVERT`, `SQL_SUPPORTED_POSITIONED_COMMANDS`, and
  `SQL_SUPPORTS_LIKE_ESCAPE_CLAUSE`
- **THEN** each is present and declares no support, matching the SQL front end, which rejects a
  `CONVERT`, a positioned command, and a `LIKE … ESCAPE` clause before contacting ClickHouse

#### Scenario: Single-entity FROM is advertised as a one-table limit

- **WHEN** a client requests `SQL_MAX_TABLES_IN_SELECT`
- **THEN** the value is `1`, an inherited under-claim this requirement records: a join or a set
  operation may in fact name any number of entities

#### Scenario: Union support is declared

- **WHEN** a client requests `SQL_SUPPORTED_UNIONS`
- **THEN** the bitmask declares `SQL_UNION` and `SQL_UNION_ALL`, and a statement generated from that
  answer (`… UNION SELECT …`, `… UNION ALL SELECT …`) streams successfully rather than failing with
  `INVALID_ARGUMENT`

#### Scenario: Subquery and outer-join support stay under-claimed

- **WHEN** a client requests `SQL_SUPPORTED_SUBQUERIES` and `SQL_OUTER_JOIN_SUPPORT_LEVEL`
- **THEN** both are present and empty, which is an under-claim recorded in this requirement and not a
  statement that the front end rejects a subquery or a `LEFT JOIN` — both execute

#### Scenario: Unbounded name lengths are declared as no limit

- **WHEN** a client requests the `SQL_MAX_*` identifier-length values — table name, column name,
  catalog name, schema name, cursor name
- **THEN** each is present and zero, the encoding for "no limit imposed", because ADAS never rejects
  an identifier for its length

#### Scenario: A declared capability is exercised, not merely asserted

- **WHEN** `SQL_SUPPORTS_TABLE_CORRELATION_NAMES` is advertised as supported
- **THEN** a statement using a table correlation name (`SELECT t.deployment FROM dial_usage_log t`)
  translates and streams successfully, so the advertisement matches executable behaviour

#### Scenario: Every listed table is selectable by the caller it was listed to

- **WHEN** `SQL_ALL_TABLES_ARE_SELECTABLE` is advertised as supported and a non-admin caller reads the
  entity names `GetTables` streams to it
- **THEN** a statement selecting from each of those names, issued with the same credentials, streams
  successfully — the declaration is checked against executed statements rather than inferred from the
  catalog's filtering, which narrows columns and not entities

#### Scenario: Partially supported ORDER BY forms are under-claimed on purpose

- **WHEN** a client requests `SQL_SUPPORTS_EXPRESSIONS_IN_ORDER_BY` and
  `SQL_SUPPORTS_ORDER_BY_UNRELATED`
- **THEN** both are present and `false`, even though `ORDER BY count(*)` resolves and ordering by an
  unselected column works in row mode — the conservative answer keeps a client from folding the
  arithmetic and aggregate-mode forms the front end rejects, and it stays `false` until the rejected
  half is gone

#### Scenario: Default null ordering is measured against the backend

- **WHEN** `SQL_NULL_ORDERING` is advertised
- **THEN** its value matches the placement an actual sort of a nullable column with no explicit
  `NULLS FIRST`/`LAST` produces, so the declaration fails here if the backend's default changes rather
  than misleading a client

#### Scenario: An unadvertised code fails the whole call

- **WHEN** a client requests `SQL_DATETIME_FUNCTIONS` — deliberately still unadvertised — alongside
  codes ADAS does supply
- **THEN** the call fails rather than returning the supported values with the unsupported one
  omitted, which is the cost the function-set exclusion knowingly accepts and the reason the excluded
  set is kept as small as the admissibility rule allows
