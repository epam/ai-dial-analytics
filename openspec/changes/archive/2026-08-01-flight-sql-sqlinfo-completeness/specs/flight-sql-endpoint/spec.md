## MODIFIED Requirements

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
  implement** — no unions, subqueries, outer joins, `CONVERT`, positioned commands, or transaction
  isolation levels (each an empty set), and `false` for stored procedures and call syntax, correlated
  subqueries, `SELECT FOR UPDATE`, batch updates, savepoints, named parameters, a `LIKE ESCAPE`
  clause, the integrity-enhancement facility, and non-nullable columns; an empty `SQL_PROCEDURE_TERM`
  and a `SQL_DEFAULT_TRANSACTION_ISOLATION` of none;
- **limits** — every `SQL_MAX_*` name-length and size value as `0`, the ODBC/JDBC encoding of "the
  source imposes no limit", which is ADAS's actual position: it validates neither identifier length
  nor statement size. `SQL_MAX_TABLES_IN_SELECT` SHALL be `1` — the translator accepts a
  single-entity `FROM` only, so this is a real bound rather than an absent one;
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
- **two deliberate under-claims** — `SQL_SUPPORTS_EXPRESSIONS_IN_ORDER_BY` and
  `SQL_SUPPORTS_ORDER_BY_UNRELATED` SHALL be advertised as `false` even though ADAS supports each in
  part: `ORDER BY count(*)` resolves through a select output and works while `ORDER BY price * 2` is
  rejected as arithmetic, and ordering by an unselected column works in row mode but not in aggregate
  mode. `false` steers a client to name the output column, which always works; `true` would let it
  generate the half that does not. These are the only two values this requirement declares narrower
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

- **WHEN** a client requests `SQL_SUPPORTED_UNIONS`, `SQL_SUPPORTED_SUBQUERIES`,
  `SQL_OUTER_JOIN_SUPPORT_LEVEL`, and `SQL_SUPPORTS_LIKE_ESCAPE_CLAUSE`
- **THEN** each is present and declares no support, matching the SQL front end, which rejects a
  `UNION`, a subquery predicate, a `JOIN`, and a `LIKE … ESCAPE` clause before contacting ClickHouse

#### Scenario: Single-entity FROM is advertised as a one-table limit

- **WHEN** a client requests `SQL_MAX_TABLES_IN_SELECT`
- **THEN** the value is `1`, so a client learns before generating a statement what a `JOIN` would
  otherwise discover as an `INVALID_ARGUMENT`

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
