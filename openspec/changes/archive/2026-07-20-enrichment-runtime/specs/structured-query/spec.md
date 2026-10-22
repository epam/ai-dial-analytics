## ADDED Requirements

### Requirement: Keyset scan mode

The query pipeline SHALL support a **keyset (seek) scan mode** in addition to offset/limit
querying, built inside the existing `StructuredQueryBuilder`/executor rather than as a separate
query path. In scan mode the builder SHALL: read the **base source only** with no enrichment LEFT
JOINs; push projection down to a caller-resolved column set; apply `final=1`; emit a keyset
predicate comparing an ordered cursor tuple (`(col_a, col_b, …) > (:c1, :c2, …)`); `ORDER BY` that
same tuple ascending; and apply `LIMIT`. Scan mode SHALL be reachable only through the governed
rule-scoped scan endpoint (`enrichment-runtime` capability); the general `POST /v1/queries/execute`
endpoint SHALL continue to reject cursor pagination (that surface stays deferred).

#### Scenario: Scan mode emits a keyset predicate and tuple ordering

- **WHEN** the builder produces a scan-mode query for a source with a cursor tuple `(v, id)`
- **THEN** the SQL reads the source only (no enrichment joins), selects the projected columns,
  applies `final=1`, includes `WHERE ... (v, id) > (:c1, :c2)` combined with the rule filter, and
  orders by `(v, id)` ascending with a `LIMIT`

#### Scenario: Cursor pagination on the general execute endpoint stays rejected

- **WHEN** a `CursorPage` request is submitted to `POST /v1/queries/execute`
- **THEN** the request is rejected as before (cursor pagination remains unsupported on the general endpoint)
