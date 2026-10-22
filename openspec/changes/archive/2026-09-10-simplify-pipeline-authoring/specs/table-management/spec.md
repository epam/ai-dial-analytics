## ADDED Requirements

### Requirement: Provenance columns are injected into an enrichment's schema definition

The system SHALL append the five provenance columns to the schema defined by
`POST /v1/tables/{name}/schema` when the table is an **enrichment**, so that a target's lineage
columns are one canonical declaration rather than each author's own. The five SHALL be
`evaluator_version`, `model`, `enriched_at`, `group_version` and `truncated`, injected with canonical
types, descriptions, `display_name` and the `system` tag, in a **fixed declared order** taken from an
ordered list — never from an unordered set, whose iteration order is unspecified and can differ
between runs of the same process.

Injection SHALL **yield to a caller who declared one**: a provenance name the request declares is
left **exactly** as declared — never duplicated, never overwritten, never reordered — and injection
appends only the names the request did not declare, in canonical order. Stated as an invariant:
injection can only ever *add* columns to the schema the caller sent. Without this rule every existing
authoring script, and any existing target that declares all five with its own wording, would become a
duplicate-column 422.

The canonical `evaluator_version` type SHALL be `integer`. The type is load-bearing rather than
cosmetic: the write path switches on the **declared** type, stamping `name:version` into a `string`
column and the bare version number into an integral one, and skipping a column of any other type
with a debug log rather than failing the write. Injecting a different type would therefore make a new
target's provenance read differently from every existing one.

All five SHALL be injected **unconditionally**, including `model`, `group_version` and `truncated`,
which a Class-1 target can never populate — the materialize path skips exactly those three. Injecting
selectively would require the table to know its pipeline's kind and trigger, which the catalog
deliberately does not; three always-empty columns on a Class-1 target is the cheaper of the two costs.
The canonical descriptions SHALL say so outright, rather than leaving an analyst to wonder why the
column is empty. The canonical `truncated` description SHALL also state that it is **not** a quality
signal, since a member-selection policy of one row sets it on every multi-hop group by design.

Injection SHALL reach **new tables only**. `POST /v1/tables/{name}/schema` is refused with HTTP 409
on an already-materialized table, so an existing enrichment is not retro-fitted by this requirement,
and `PATCH /v1/tables/{name}/schema` SHALL NOT inject: it is an explicit operation list, and silently
widening it into a five-column DDL on a populated table would be the opposite of a caller declaring
what they want.

The endpoint's own operation description SHALL name all five columns and SHALL type `enriched_at`
consistently with what is injected.

#### Scenario: An enrichment declaring no provenance receives all five

- **WHEN** `POST /v1/tables/{name}/schema` defines the schema of a `PENDING` enrichment whose
  `columns` declare none of the provenance names
- **THEN** the materialized table carries all five provenance columns, appended after the caller's own
  columns in canonical order, each with its canonical type, description, `display_name` and the
  `system` tag

#### Scenario: An enrichment declaring all five keeps its own declarations

- **WHEN** the request declares all five provenance columns itself, with its own descriptions, tag and
  positions
- **THEN** the schema is accepted with no duplicate columns, and each declared column keeps its own
  description, tag and position exactly as sent

#### Scenario: An enrichment declaring some provenance receives only the rest

- **WHEN** the request declares `enriched_at` and `evaluator_version` and none of the other three
- **THEN** those two are left verbatim and `model`, `group_version` and `truncated` are appended in
  canonical order

#### Scenario: The injected evaluator_version is integer-typed

- **WHEN** provenance is injected into an enrichment's schema
- **THEN** `evaluator_version` is declared `integer`, so a write stamps the bare version number, as it
  does on every existing target

#### Scenario: A source table receives no provenance

- **WHEN** `POST /v1/tables/{name}/schema` defines the schema of a `source` table
- **THEN** no provenance column is injected

#### Scenario: A schema patch injects nothing

- **WHEN** `PATCH /v1/tables/{name}/schema` adds a column to a materialized enrichment that carries no
  provenance columns
- **THEN** only the requested operation is applied and no provenance column is added

#### Scenario: The injected order is stable across runs

- **WHEN** two enrichments are given schemas in separate runs of the service, each declaring no
  provenance
- **THEN** both carry the five provenance columns in the same order
