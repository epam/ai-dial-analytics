## MODIFIED Requirements

### Requirement: Class-1 expression governance

The system SHALL statically validate every `sql` output-variable expression at rule register and
enable, against the rule's **read source** — its declared `source` when it has one, otherwise the
target enrichment's `source_table` (see [enrichment-registry](../enrichment-registry/spec.md)). Each
failure SHALL be rejected with HTTP 422 `rule_validation_failed` carrying the failing detail.
Validation SHALL wrap the expression as `SELECT <expr> FROM <source>` through the **same** Calcite SQL
front end used for rule filters, so the bounded grammar (no joins, subqueries, or CTEs), the closed
function catalog, and unknown-column rejection apply identically. On top of that the gate SHALL enforce:

1. **Scalar, not aggregate.** An expression that resolves to an aggregate or window computation SHALL
   be rejected — an aggregating expression is an *aggregation*, not a Class-1 enrichment, and would
   silently break the target's `1:0..1` grain.
2. **A single projected value.** The expression SHALL yield exactly one column.
3. **Type-compatible.** The result type inferred by the front end SHALL be compatible with the output
   variable's declared `type` under the shared enrichment type mapping, and the declared type SHALL in
   turn be compatible with the bound target column's type (the existing output-binding check). An
   `object`-typed result SHALL be rejected: it is projection-only in the SQL subset and cannot be
   collapsed into a column bound to a grain. An `array`-typed result SHALL be **accepted** when the
   bound target column is itself array-typed, and rejected otherwise. The **element** type SHALL NOT
   be checked: `QueryFieldType.ARRAY` carries no element type, and a computed array has no declared
   `element_type` to compare against (the same reason `group_uniq_array`'s element kind follows its
   operand). The accepted consequence is that an array expression whose elements do not match the
   target column's element type is rejected by ClickHouse at insert time rather than at registration.
   One value per grain still holds: an array cell is a single value, not many rows. An output variable
   SHALL declare an array result as the bare token `array`; a **parameterized** spelling such as
   `array<string>` SHALL be rejected with HTTP 422 `rule_validation_failed`, and the rejection SHALL name
   `array` as the token to write instead. Aliasing the parameterized form would advertise an element-type
   check that does not happen, and would read as having passed one when bound to a column of a different
   element type.
4. **Entitled.** The expression's referenced read-source columns SHALL run the same sensitive-column
   entitlement check as a rule `filter`: a `sensitive` column is admitted only for a caller entitled to
   see sensitive columns, and rejected with HTTP 403 `sensitive_column_not_entitled` otherwise.
5. **Row-grain trigger only.** A `type=sql` rule SHALL declare `on_ingest` or `schedule`; the `group`
   trigger SHALL be rejected, because a group-grain deterministic rule is an aggregation.
6. **No input bindings.** A `type=sql` rule SHALL declare no input bindings — there is no request to
   render.
7. **At least one output binding.** A `type=sql` rule SHALL bind at least one output column: with none
   there is nothing to materialize, and the rule would silently do no work.
8. **Row-grain target.** The rule's target enrichment SHALL be keyed on the **read source's** declared
   `identity_column` (its scan metadata, see [enrichment-runtime](../enrichment-runtime/spec.md)). A
   Class-1 derivation produces one value per read-source row, so against a coarser grain one window would
   insert many rows carrying the same grain key and the target's `ReplacingMergeTree` would collapse them
   to an arbitrary survivor — a label that is neither wrong nor reproducible. A per-group deterministic
   value is an aggregation, which this capability does not provide. This also means a read source with no
   scan metadata is refused at registration rather than at the first materialize call. Resolving this
   against the read source is what keeps the check meaningful once a rule may read a table other than its
   target enrichment's `source_table`: an enrichment whose grain is coarser than the rows its rule reads
   (a conversation key over rate events, say) stays a `group` rule and is rejected here.

Determinism is guaranteed by construction rather than by a denylist: non-deterministic functions such
as `now()`, `rand()`, and `generateUUIDv4()` are absent from the closed function catalog and are
therefore already rejected as unsupported functions. This matters because the exactly-once *effect*
guarantee below depends on re-running a window producing the same values.

#### Scenario: An aggregate expression is rejected

- **WHEN** a `type=sql` rule declares an output-variable expression containing an aggregate (e.g.
  `sum(total_tokens)`)
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An unknown column in an expression is rejected

- **WHEN** an expression references a column not declared on the rule's read source
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An expression reaching outside the rule's read source is rejected

- **WHEN** an expression resolves against an entity other than the rule's read source (for instance by
  supplying its own `FROM` clause ahead of a SQL line comment), or references a column belonging to
  something other than that source — including a materialized enrichment's `table.column`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, so the
  sensitive-column entitlement check — which resolves referenced columns against the rule's read source —
  is never handed a reference it cannot see

#### Scenario: A non-catalog function in an expression is rejected

- **WHEN** an expression calls a function outside the closed query function catalog (including any
  non-deterministic function such as `now()`)
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A type-incompatible expression is rejected

- **WHEN** an expression's inferred result type is incompatible with the output variable's declared
  type, or the result is object-typed, or the result is array-typed and the bound target column is not
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A sensitive reference by an entitled author is accepted

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) registers a `type=sql` rule whose
  expression reads a `sensitive` read-source column such as `request_body`
- **THEN** the rule is accepted

#### Scenario: A sensitive reference by a non-entitled author is rejected

- **WHEN** a caller who may not see sensitive columns registers the same rule
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: A group trigger or input bindings on a sql rule are rejected

- **WHEN** a `type=sql` rule declares `trigger_kind: "group"`, or declares any input binding
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A sql rule with no output bindings is rejected

- **WHEN** a `type=sql` rule declares no output bindings
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A sql rule targeting a coarser-than-row grain is rejected

- **WHEN** a `type=sql` rule names a target enrichment whose grain key is not the read source's
  `identity_column` (a conversation key, say, rather than the event identity)
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, naming the
  identity column the target must be keyed on

#### Scenario: A sql rule declaring a read source is validated against it

- **WHEN** a `type=sql` rule declares a `source` other than its target enrichment's `source_table`, and
  its expressions reference only that source's columns, and the target is keyed on that source's
  `identity_column`
- **THEN** the rule is accepted, and the materialize path derives its values from the declared read source

#### Scenario: An array expression bound to an array column is accepted

- **WHEN** a `type=sql` rule binds an output variable whose expression is
  `json_extract_array(jwt_claims, 'roles')` to a target column of type `array<string>`
- **THEN** the rule is accepted, and materialize writes the extracted array into that column

#### Scenario: An array expression bound to a scalar column is rejected

- **WHEN** the same expression is bound to a `string` target column
- **THEN** the request is rejected with HTTP 422 `rule_validation_failed` naming the array result and
  the scalar target

#### Scenario: A parameterized array type token is rejected with the token to use

- **WHEN** a `type=sql` rule's output variable declares its type as `array<string>`
- **THEN** the request is rejected with HTTP 422 `rule_validation_failed`, and the message names `array` as
  the token to declare instead rather than reporting the spelling as merely unrecognized

#### Scenario: An array element-type mismatch is not caught at registration

- **WHEN** a `type=sql` rule binds an array-producing expression to an array column whose element type
  differs
- **THEN** the rule is accepted — element types are not modelled — and the mismatch surfaces as a
  ClickHouse insert failure on the first materialize call

#### Scenario: An object expression is still rejected

- **WHEN** a `type=sql` rule binds an output variable whose expression yields an `object`
- **THEN** the request is rejected with HTTP 422 `rule_validation_failed`

#### Scenario: A conditional expression is accepted

- **WHEN** a `type=sql` rule binds an output variable whose expression is
  `if(not_empty(json_extract_string(jwt_claims, 'user_id')), 'jwt', 'api_key')`
- **THEN** the rule is accepted — the expression is scalar, single-valued, deterministic, and every
  function is in the closed catalog
