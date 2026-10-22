## MODIFIED Requirements

### Requirement: Class-1 expression governance

The system SHALL statically validate every `sql` output-variable expression at pipeline register and
enable, against the **entity** of the pipeline's read source — its declared `source` when it has one,
otherwise the target enrichment's `source_table` — where that entity comprises the read source's own
declared columns plus the columns of each of its `ACTIVE` enrichments under the qualified flat name
`<enrichment>.<column>` (see [enrichment-registry](../enrichment-registry/spec.md)). Each
failure SHALL be rejected with HTTP 422 `pipeline_validation_failed` carrying the failing detail.
Validation SHALL wrap the expression as `SELECT <expr> FROM <source>` through the **same** Calcite SQL
front end used for declaration filters, so the bounded grammar (no joins, subqueries, or CTEs), the closed
function catalog, and unknown-column rejection apply identically. The materialize statement SHALL emit a
single-key `LEFT JOIN` on the shared grain key for each enrichment its own expressions reference, and
none for an enrichment nothing references; each such join SHALL be keyed on the window's source-only
predicates (the cursor predicates always, the filter when it names no enrichment column), so the
enrichment side is restricted to the grain keys of the window rather than hashed whole. On top of that
the gate SHALL enforce:

1. **Scalar, not aggregate.** An expression that resolves to an aggregate or window computation SHALL
   be rejected — an aggregating expression is an *aggregation*, not a Class-1 enrichment, and would
   silently break the target's `1:0..1` grain.
2. **A single projected value.** The expression SHALL yield exactly one column.
3. **Type-compatible.** The result type inferred by the front end SHALL be compatible with the bound
   target column's type under the shared enrichment type mapping, in **one** check — see
   "SQL-computed evaluators" for why the declared per-output token that sat between them is gone and
   why removing it can only loosen the gate. An
   `object`-typed result SHALL be rejected: it is projection-only in the SQL subset and cannot be
   collapsed into a column bound to a grain. An `array`-typed result SHALL be **accepted** when the
   bound target column is itself array-typed, and rejected otherwise. The **element** type SHALL NOT
   be checked: the inferred array type carries no element type, and a computed array has no declared
   `element_type` to compare against (the same reason `group_uniq_array`'s element kind follows its
   operand). The accepted consequence is that an array expression whose elements do not match the
   target column's element type is rejected by ClickHouse at insert time rather than at registration.
   One value per grain still holds: an array cell is a single value, not many rows. Where a **stored
   old-shape** evaluator version still declares a per-output logical type, an array result SHALL be
   declared as the bare token `array`; a **parameterized** spelling such as
   `array<string>` SHALL be rejected with HTTP 422 `pipeline_validation_failed`, and the rejection SHALL name
   `array` as the token to write instead. Aliasing the parameterized form would advertise an element-type
   check that does not happen, and would read as having passed one when bound to a column of a different
   element type.
4. **Entitled.** The expression's referenced entity fields SHALL run the same sensitive-column
   entitlement check as a pipeline `filter`, whether the field is a read-source column or an enrichment
   column reached through the grain join: a `sensitive` field is admitted only for a caller entitled to
   see sensitive columns, and rejected with HTTP 403 `sensitive_column_not_entitled` otherwise. The
   check governs **who may reference** the field. The derived value SHALL NOT inherit its sensitivity
   and the bound target column's own flag SHALL NOT be changed, exactly as for a read-source column —
   see the "Runner identity and derived-column sensitivity posture" requirement in
   [enrichment-registry](../enrichment-registry/spec.md).
5. **Row-grain trigger only.** A `type=sql` pipeline SHALL declare `on_ingest` or `schedule`; the `group`
   trigger SHALL be rejected, because a group-grain deterministic pipeline is an aggregation.
6. **No vars.** A `type=sql` pipeline SHALL declare no `vars` — there is no request to
   render.
7. **At least one resolvable output.** A `type=sql` pipeline's resolved evaluator SHALL declare at least
   one output that resolves to a column on the target: with none
   there is nothing to materialize, and the pipeline would silently do no work. The refusal SHALL say
   that the evaluator must declare an output resolving to a target column, which is the actionable
   form of the check now that the mapping is derived rather than declared.
8. **Row-grain target.** The pipeline's target enrichment SHALL be keyed on the **read source's** declared
   `identity_column` (its scan metadata, see [enrichment-runtime](../enrichment-runtime/spec.md)). A
   Class-1 derivation produces one value per read-source row, so against a coarser grain one window would
   insert many rows carrying the same grain key and the target's `ReplacingMergeTree` would collapse them
   to an arbitrary survivor — a label that is neither wrong nor reproducible. A per-group deterministic
   value is an aggregation, which this capability does not provide. This also means a read source with no
   scan metadata is refused at registration rather than at the first materialize call. Resolving this
   against the read source is what keeps the check meaningful once a pipeline may read a table other than its
   target enrichment's `source_table`: an enrichment whose grain is coarser than the rows its pipeline reads
   (a conversation key over rate events, say) stays a `group` pipeline and is rejected here. The
   `identity_column` SHALL be a column of the read source itself: a Class-1 pipeline's grain is the row grain
   of the table it pages, which no enrichment can supply.
9. **Enrichment reads are bounded and acyclic.** An expression reading an enrichment column SHALL
   additionally satisfy the entity-read requirements of
   [enrichment-registry](../enrichment-registry/spec.md): the enrichment column SHALL be declared
   `nullable`, the enrichment's materialized position SHALL be establishable, and the reference SHALL
   NOT close a dependency cycle.

10. **Deterministic.** An expression calling a function the query function catalog declares
    **non-deterministic** SHALL be rejected — the pipeline-wide rule in
    [enrichment-registry](../enrichment-registry/spec.md) ("Every governed SQL fragment is
    deterministic"), applied here to a Class-1 output expression and reported as the same HTTP 422
    `pipeline_validation_failed` as every other failure of this gate.

    Determinism was previously guaranteed by *construction*: no non-deterministic function existed in
    the closed catalog, so nothing had to reject one. That floor is gone now that the query surfaces
    need a clock (`now()`), so the guarantee is enforced explicitly — but still derived from the
    catalog's own declared property rather than from a list of names kept beside it. Functions the
    engine has and this catalog does not — `rand()`, `generateUUIDv4()` — remain rejected as
    unsupported names, as before.

    It matters most here of anywhere: the exactly-once *effect* guarantee below depends on re-running
    a window producing the same values, so an expression reading the clock would write a different
    label for the same row on a re-materialize, and the two would be indistinguishable afterwards.

#### Scenario: An aggregate expression is rejected

- **WHEN** a `type=sql` pipeline declares an output-variable expression containing an aggregate (e.g.
  `sum(total_tokens)`)
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An unknown column in an expression is rejected

- **WHEN** an expression references a column reachable on neither the pipeline's read source nor its
  `ACTIVE` enrichments
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An expression reaching outside the pipeline's read source's entity is rejected

- **WHEN** an expression resolves against an entity other than the pipeline's read source (for instance by
  supplying its own `FROM` clause ahead of a SQL line comment), or references a column belonging neither
  to that source nor to one of its `ACTIVE` enrichments under the qualified `<enrichment>.<column>`
  spelling
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, so the
  sensitive-column entitlement check — which resolves referenced fields against the pipeline's read source's
  entity — is never handed a reference it cannot see

#### Scenario: An expression over an enrichment column of the read source is accepted

- **WHEN** a `type=sql` pipeline's output expression reads a `nullable` column of an `ACTIVE` enrichment of
  its read source, whose materialized position is establishable and which closes no cycle
- **THEN** the pipeline is accepted, and the materialize statement emits the grain join for that enrichment

#### Scenario: A non-catalog function in an expression is rejected

- **WHEN** an expression calls a function outside the closed query function catalog (for instance
  `rand()` or `generateUUIDv4()`)
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A non-deterministic catalog function in an expression is rejected

- **WHEN** a `type=sql` pipeline declares an output-variable expression calling `now()`, directly or
  nested inside another call such as `date_sub('hour', 1, now())`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  the function and stating that a Class-1 derivation must be reproducible

#### Scenario: A type-incompatible expression is rejected

- **WHEN** an expression's inferred result type is incompatible with the bound target column's type,
  or the result is object-typed, or the result is array-typed and the bound target column is not
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A sensitive reference by an entitled author is accepted

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) registers a `type=sql` pipeline whose
  expression reads a `sensitive` read-source column such as `request_body`
- **THEN** the pipeline is accepted

#### Scenario: A sensitive reference by a non-entitled author is rejected

- **WHEN** a caller who may not see sensitive columns registers the same declaration
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: A group trigger or vars on a sql pipeline are rejected

- **WHEN** a `type=sql` pipeline declares `trigger_kind: "group"`, or declares any `vars` entry
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A sql pipeline whose outputs resolve to no target column is rejected

- **WHEN** a `type=sql` pipeline resolves an evaluator none of whose outputs names a column of the
  target enrichment
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, saying
  that the evaluator must declare an output resolving to a column on the target

#### Scenario: A sql pipeline targeting a coarser-than-row grain is rejected

- **WHEN** a `type=sql` pipeline names a target enrichment whose grain key is not the read source's
  `identity_column` (a conversation key, say, rather than the event identity)
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming the
  identity column the target must be keyed on

#### Scenario: A sql pipeline keyed on an enrichment column is rejected

- **WHEN** a `type=sql` pipeline names a target enrichment whose grain key resolves to an enrichment column
  rather than to the read source's own `identity_column`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` — a Class-1
  pipeline's grain is the row grain of the table it pages

#### Scenario: A sql pipeline declaring a read source is validated against it

- **WHEN** a `type=sql` pipeline declares a `source` other than its target enrichment's `source_table`, and
  its expressions reference only that source's columns, and the target is keyed on that source's
  `identity_column`
- **THEN** the pipeline is accepted, and the materialize path derives its values from the declared read source

#### Scenario: An array expression bound to an array column is accepted

- **WHEN** a `type=sql` pipeline's evaluator declares the output expression
  `json_extract_array(jwt_claims, 'roles')` against a target column of type `array<string>`
- **THEN** the pipeline is accepted, and materialize writes the extracted array into that column

#### Scenario: An array expression bound to a scalar column is rejected

- **WHEN** the same expression is bound to a `string` target column
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the array result and
  the scalar target

#### Scenario: A parameterized array type token on a stored declaration is rejected with the token to use

- **WHEN** a `type=sql` pipeline resolves a stored old-shape evaluator version whose output variable
  declares its type as `array<string>`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, and the message names `array` as
  the token to declare instead rather than reporting the spelling as merely unrecognized

#### Scenario: An array element-type mismatch is not caught at registration

- **WHEN** a `type=sql` pipeline binds an array-producing expression to an array column whose element type
  differs
- **THEN** the pipeline is accepted — element types are not modelled — and the mismatch surfaces as a
  ClickHouse insert failure on the first materialize call

#### Scenario: An object expression is still rejected

- **WHEN** a `type=sql` pipeline binds an output variable whose expression yields an `object`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`

#### Scenario: A conditional expression is accepted

- **WHEN** a `type=sql` pipeline binds an output variable whose expression is
  `if(not_empty(json_extract_string(jwt_claims, 'user_id')), 'jwt', 'api_key')`
- **THEN** the pipeline is accepted — the expression is scalar, single-valued, deterministic, and every
  function is in the closed catalog

