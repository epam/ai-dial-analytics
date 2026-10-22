## ADDED Requirements

### Requirement: Seeded column nullability matches the physical table

The catalog's `nullable` flag on a built-in source table SHALL agree with the column's physical
ClickHouse type: a column materialized as `Nullable(T)` SHALL be seeded `nullable = true`, and one
materialized as a bare `T` SHALL be seeded `nullable = false`.

The `dial_usage_log` seed SHALL therefore record as nullable exactly the columns its DDL declares
`Nullable(...)`: `core_parent_span_id`, `parent_deployment`, `response_upstream_uri`, `prompt_tokens`,
`completion_tokens`, `total_tokens`, `cached_prompt_tokens`, `total_price`, `deployment_price`,
`request_body`, and `response_body`.

The agreement is a requirement rather than a data detail because two independent consumers read the flag
and neither can detect that it is wrong:

- **Discovery publishes it.** A consumer told a column cannot be null, which then returns null, holds a
  schema its own data contradicts.
- **Governance reasons from it.** A rule that decides anything from a column's nullability — such as the
  aggregate pipeline's requirement that a nullable measure land in a nullable target column — is inert on
  precisely the columns that need it if the catalog understates nullability. That is the quiet direction
  of the error: nothing is rejected, and nothing reports that nothing was checked.

The flag defaults to `FALSE`, so a column seeded without an explicit value claims non-nullability it may
not have. A seed SHALL therefore set it from the DDL rather than relying on the default.

Nullability is fixed at DDL, so aligning the catalog SHALL be a `column_mapping` update only and SHALL
NOT materialize as an `ALTER`. This is the same shape as the seeded `sensitive` flag, and unlike it, the
value is not an operator's to choose — the physical column decides it.

#### Scenario: The seeded flag agrees with the physical column

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log` reports `total_tokens`, `total_price` and the other physically-`Nullable`
  columns with `nullable: true`, and its non-nullable columns with `nullable: false`

#### Scenario: A governance rule keyed on nullability sees the true value

- **WHEN** an aggregate pipeline declares a `sum` over `dial_usage_log.total_tokens` into a non-nullable
  target column
- **THEN** it is rejected, because the catalog reports the input column as nullable — the refusal the
  rule exists for, which an understated flag would have skipped silently
