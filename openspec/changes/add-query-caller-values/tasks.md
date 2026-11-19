## 1. SQL spelling and placement

- [x] 1.1 Register a zero-argument, non-deterministic, dynamic operator per caller value returning `ReturnTypes.VARCHAR_FORCE_NULLABLE`, and rewrite the spelling `caller.user_hash` / `caller.project` (case-insensitive, at a word boundary, followed after optional whitespace by `(`) to its same-length internal name outside literals, quoted identifiers and comments, using the tokenizer shared with the `FINAL` rewrite (now aware of `//` comments as well as `E'…'` literals); name a rewrite-produced call by its caller-value spelling in every message, the arity error included; enable the rewrite through an explicit flag passed only by the user-statement call sites (`execute-sql`, `execute-sql-csv`, `validate-sql`, `translate-sql`, Flight SQL, saved-query and dashboard `sql` validation) and the policy condition, never by the pipeline validators
- [x] 1.2 On every statement the front end parses (user statements, policy conditions, `planInsert`, enrichment filter and expression SQL) refuse a call of an internal name, in any spelling, unless it stands where the statement as first parsed held a `caller.user_hash()` / `caller.project()` call (parser positions on both passes); refuse with "No match found for function signature" and the unquoted name (`unknown_function`); end line comments at `\r` as well as `\n`
- [x] 1.3 Before `RexToExprTranslator` translates a root (filter, join condition, projection), check every caller operator in it: after stripping character casts of a text operand (`TO_STRING_CAST` and identity casts), admit it only as a direct operand of `EQUALS` / `NOT_EQUALS` whose other operand is a `string` expression whose translation reads a column (typed by `ExprFieldType.ofDeclared`, holding no caller value) reached from the root through `AND` / `OR` / `NOT` alone, and raise the placement message for anything else (other casts, `LIKE`, `IS [NOT] NULL`, function and literal-only arguments, projections, `CASE` conditions, a comparison used as a value, window keys); then translate the operator into the `param`; `orFilter` keeps an `or` for a disjunction holding a caller value
- [x] 1.4 On every build, check placement in `FilterTranslator` against the block's own bindings (`eq`/`ne` against a `string` expression over a field, an aggregate in it only through `toHavingCondition`, in a predicate rather than a `case` branch; the other operand translated first, so an unknown field is reported for every caller) and refuse a caller value anywhere else from `ExprTranslator` with the same message; keep refusing every other `param` name, case variants included
- [x] 1.5 Refuse an `INFORMATION_SCHEMA` plan holding a caller value `400 bad_request`
- [x] 1.6 Word the placement message and the `INFORMATION_SCHEMA` refusal so `SqlIssueClassifier` reports `unsupported_construct` (its existing "is not supported by the query translator" rule)
- [x] 1.7 Policy condition: the call spelling takes the operator path; the marking pass skips a bare name followed after whitespace by `(`; the unmarking expands a marker inside an `in` array into the disjunction of equalities

## 2. Resolution

- [x] 2.1 Extract the caller-value rewrite of `RowPolicyPredicates` (known value to a string value node, unknown to the whole comparison as the always-`NULL` condition, the 512-byte and blank bounds) into `CallerValues`, used by policies and queries
- [x] 2.2 Give `FilterTranslator` the caller values of the current thread's scope: none (pipeline paths keep refusing), validating (placeholder, no identity read), resolving (execution, per request, never cached); every translator copy sees the same scope
- [x] 2.3 Open the resolving scope in `StructuredQueryService.execute` (DSL execute, both CSV twins, `execute-sql`, Flight SQL, single-block and composite, the `include_total` count build included) and the validating scope in `PortableQueryRenderer.render`, `StructuredQueryService.validate` and the composite validate
- [x] 2.4 Treat a caller value as a non-default string literal in `ReadShaping` (enrichment pushdown and partition preselection)
- [x] 2.5 Log at debug level and set the span attribute `adas.query.unknown.<name>` for an unknown caller value in a query
- [x] 2.6 Check that ClickHouse accepts the always-`NULL` condition inside an inner and an outer join's `ON` (it does; recorded in `design.md`)

## 3. Translation, saved queries, dashboards, discovery

- [x] 3.1 Render the two names as `caller.user_hash()` / `caller.project()` in `StructuredQueryToSqlRenderer`
- [x] 3.2 Let `SavedQueryBodyValidator` admit the two names and keep refusing every other `param` `422 validation_error`; dashboard inline bodies inherit it
- [x] 3.3 Add the `caller.user_hash` and `caller.project` entries to the SQL function catalog; the catalog parity tests hold with the internal names listed as markers
- [x] 3.4 Show the call spelling in the `RowsDto` OpenAPI description of `rows.where`

## 4. Tests

- [x] 4.1 Structured-query scenarios: administrator reads own rows, user and project callers through one body, no widening of a confined read, unknown and blank values, negation of unknown, misplaced values (`select`, function, `case` in a filter comparison, `in` item, `gt`, non-string and `uuid` fields, value, caller against caller), other `param` names
- [x] 4.2 SQL scenarios: `WHERE`; upper case, whitespace and a comment between the parentheses, a written `CAST(... AS VARCHAR)`; `ON`, inner and left join with an unknown value; `IN` subquery and scalar subquery; `HAVING` conjunctions with a literal on the same column (`=` and `<>`); disjunction and `IN` list with a literal; bare spelling against an alias `caller`; literal spelling the call; misplaced calls with `unsupported_construct` on `validate-sql`; misspelled calls with `unknown_function` / `function_arity`; internal names called directly; `INFORMATION_SCHEMA`; `translate-sql` returning the `param`; the rewrite keeps the statement's length
- [x] 4.3 Flight SQL: a ticket obtained by one caller and redeemed by another returns the redeemer's own rows
- [x] 4.4 Translation: DSL → SQL renders `caller.user_hash()` for a caller with and without a hash; SQL → DSL returns the `param`
- [x] 4.5 Saved queries: a caller value stored as written in both body kinds; misplaced `400`; other `param` `422`
- [x] 4.6 Policy condition: the call spelling stores the same filter as the bare one; `IN` lists in both spellings store the same disjunction (the existing case expecting `IN (caller.user_hash, …)` to be refused now expects it stored); a call of another name is refused
- [x] 4.7 An aggregate declaration with `caller.user_hash()` or an internal name is refused; a statement that does not opt in reaches no caller value; `none` mode yields no caller values
- [x] 4.8 SQL function catalog lists the two entries and each `example` translates

## 5. Specs and docs

- [x] 5.1 Document caller values in a query in `docs/sql-reference.md` (including the `NULL` rules of `NOT EXISTS` / `NOT IN`), and switch `docs/row-level-security.md` to the call spelling with a short "your own rows in a query" example
- [x] 5.2 Confirm the delta specs match the shipped behaviour, including error codes, and that no `docs/*.md` contradicts them
- [x] 5.3 Run `openspec validate add-query-caller-values --strict`

## 6. Verification

- [ ] 6.1 `./gradlew clean build` passes
