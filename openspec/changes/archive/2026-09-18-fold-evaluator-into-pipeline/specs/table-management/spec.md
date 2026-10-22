## MODIFIED Requirements

### Requirement: Provenance columns are injected into an enrichment's schema definition

The system SHALL append the six provenance columns to the schema defined by
`POST /v1/tables/{name}/schema` when the table is an **enrichment**, so that a target's lineage
columns are one canonical declaration rather than each author's own. The six SHALL be
`evaluator_version`, `pipeline_generation`, `model`, `enriched_at`, `group_version` and `truncated`,
injected with canonical types, descriptions, `display_name` and the `system` tag, in a **fixed
declared order** taken from an ordered list — never from an unordered set, whose iteration order is
unspecified and can differ between runs of the same process.

**`pipeline_generation` is the sixth**, and it is injected rather than left to the author because the
write path drops a provenance column the target does not physically carry. A target created without
it would silently record no lineage at all for every row written after the evaluator fold, which is
the opposite of what adding the stamp was for. Its canonical type SHALL be `long`, matching what a
pipeline `generation` is — a 64-bit counter — and matching `group_version`, the other integral
provenance column the write path stamps from a long. It SHALL be appended **after**
`evaluator_version` in the declared order, so the two lineage integers read together.

`evaluator_version` SHALL keep being injected even though nothing writes it after the fold. Removing
it would make a new target's provenance differ in shape from every existing one for no gain, and the
column costs an always-empty column exactly as `model`, `group_version` and `truncated` already do on
a Class-1 target. Its canonical description SHALL say that it is stamped only by rows written before
the evaluator fold, so an analyst reading an empty column on a new target is told why.

Injection SHALL **yield to a caller who declared one**: a provenance name the request declares is
left **exactly** as declared — never duplicated, never overwritten, never reordered — and injection
appends only the names the request did not declare, in canonical order. Stated as an invariant:
injection can only ever *add* columns to the schema the caller sent. Without this rule every existing
authoring script, and any existing target that declares the original five with its own wording, would become a
duplicate-column 422.

The canonical `evaluator_version` type SHALL be `integer`. The type is load-bearing rather than
cosmetic: the write path switches on the **declared** type, stamping `name:version` into a `string`
column and the bare version number into an integral one, and skipping a column of any other type
with a debug log rather than failing the write. Injecting a different type would therefore make a new
target's provenance read differently from every existing one. The same rule is why
`pipeline_generation` must be integral: a `string` declaration would stamp `name:generation` instead
of the bare counter, and any non-integral, non-string type would be skipped silently.

All six SHALL be injected **unconditionally**, including `model`, `group_version` and `truncated`,
which a Class-1 target can never populate — the materialize path skips exactly those three. Injecting
selectively would require the table to know its pipeline's kind and trigger, which the catalog
deliberately does not; three always-empty columns on a Class-1 target is the cheaper of the two costs.
The canonical descriptions SHALL say so outright, rather than leaving an analyst to wonder why the
column is empty. The canonical `truncated` description SHALL also state that it is **not** a quality
signal, since a member-selection policy of one row sets it on every multi-hop group by design.

Injection SHALL reach **new tables only**. `POST /v1/tables/{name}/schema` is refused with HTTP 409
on an already-materialized table, so an existing enrichment is not retro-fitted by this requirement,
and `PATCH /v1/tables/{name}/schema` SHALL NOT inject: it is an explicit operation list, and silently
widening it into a six-column DDL on a populated table would be the opposite of a caller declaring
what they want. Both statements stand unchanged. The consequence for `pipeline_generation` — that
**every enrichment that exists today lacks it**, and a best-effort write drops what the target does
not carry — SHALL be closed by the reconciler below rather than by an operator patching each target.

The endpoint's own operation description SHALL name all six columns and SHALL type `enriched_at`
consistently with what is injected.

#### Scenario: An enrichment declaring no provenance receives all six

- **WHEN** `POST /v1/tables/{name}/schema` defines the schema of a `PENDING` enrichment whose
  `columns` declare none of the provenance names
- **THEN** the materialized table carries all six provenance columns, appended after the caller's own
  columns in canonical order, each with its canonical type, description, `display_name` and the
  `system` tag

#### Scenario: An enrichment declaring all six keeps its own declarations

- **WHEN** the request declares all six provenance columns itself, with its own descriptions, tag and
  positions
- **THEN** the schema is accepted with no duplicate columns, and each declared column keeps its own
  description, tag and position exactly as sent

#### Scenario: An enrichment declaring some provenance receives only the rest

- **WHEN** the request declares `enriched_at` and `evaluator_version` and none of the other four
- **THEN** those two are left verbatim and `pipeline_generation`, `model`, `group_version` and
  `truncated` are appended in canonical order

#### Scenario: The injected evaluator_version is integer-typed

- **WHEN** provenance is injected into an enrichment's schema
- **THEN** `evaluator_version` is declared `integer`, so a write stamps the bare version number, as it
  does on every existing target

#### Scenario: The injected pipeline_generation is long-typed

- **WHEN** provenance is injected into an enrichment's schema
- **THEN** `pipeline_generation` is declared `long`, so a write stamps the bare generation counter
  rather than a `name:generation` string, and it is ordered directly after `evaluator_version`

#### Scenario: An existing enrichment gains only the provenance column

- **WHEN** an enrichment materialized before this change, and targeted by an `ENRICH` pipeline, is read
  after a startup that ran the reconciler
- **THEN** it carries `pipeline_generation`, every other column is exactly as it was, and no
  `PATCH /v1/tables/{name}/schema` was issued to get there

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
- **THEN** both carry the six provenance columns in the same order

## ADDED Requirements

### Requirement: The provenance stamp column is reconciled onto existing enrich targets at startup

The system SHALL reconcile the `pipeline_generation` provenance column onto every existing enrichment
that an `enrich` pipeline targets, at startup, without operator action. Creation-time injection reaches
new tables only and `PATCH /v1/tables/{name}/schema` deliberately does not inject, so without this
requirement every enrichment materialized before the column existed would silently drop the stamp on
every write, and lineage would stop at the deploy instead of improving. This is the first time the
system adds a column to an already-materialized enrichment, which is why it is a requirement of its own
rather than a widening of injection.

Reconciliation SHALL be scoped to enrichments that are `ACTIVE` **and** named as the `target` of a
`kind = 'ENRICH'` pipeline. A non-target enrichment can never carry the stamp, so the column would be
dead weight; and the narrower scope keeps the reconciler away from a system-owned enrichment, which the
schema API itself refuses to modify.

The physical `ALTER` SHALL be issued for **every** target in that scope on every startup, and the
catalog check SHALL decide only whether the catalog row is appended. Gating the `ALTER` on the catalog
instead would leave one divergence permanently unhealable: a target whose catalog row exists but whose
physical column does not is skipped forever, and that state is not inert, because the physical column
set is derived from the catalog rather than from the store — so the write path believes the column is
there, names it in every write, and every write-back and materialize to that target fails with an
unknown-column error. The `ALTER` is a metadata-only no-op when the column is present, so issuing it
unconditionally costs one statement per target per boot and closes the direction the catalog check
cannot see.

Reconciliation SHALL be **idempotent** in both directions, and SHALL apply the physical `ALTER` before
the catalog row, using `ADD COLUMN IF NOT EXISTS`. The order matters and the conditional is what makes it safe: a
failure after the `ALTER` and before the catalog write leaves the two diverged, and only a replayable
`ALTER` lets the next startup finish the job. This is the one respect in which the reconciler SHALL
differ from `PATCH /v1/tables/{name}/schema`, whose unconditional `ADD COLUMN` cannot be retried after
such a failure — the catalog check passes, the `ALTER` is re-issued, and the store refuses it.

Reconciliation SHALL NOT bump any pipeline's `generation` or advance any `updated_at`. A provenance
column binds to no output, so neither the composed response schema nor the derived output mapping
changes, and there is nothing for a consumer to re-read. This is a deliberate difference from an added
**user** column, which does bump an `llm` pipeline.

Reconciliation SHALL NOT fail startup. A failure SHALL be logged and reported through a metric, and
SHALL leave the service serving: the degraded mode is already defined — the stamp is dropped
best-effort for targets that do not carry the column — and the next startup retries. Failing instead
would be worse than the condition it reports, because liveness reads the aggregate health endpoint, so
a boot-failing reconciler would restart-loop the service over a lineage column.

Reconciliation SHALL be controlled by one configuration property, defaulting to **enabled**. A
disabled reconciler is a silently degrading stamp, which is the failure mode this requirement exists to
remove, so the default SHALL NOT be `false`.

#### Scenario: A target missing the column gains it on startup

- **WHEN** the service starts with an `ACTIVE` enrichment targeted by an `enrich` pipeline whose
  catalog records no `pipeline_generation`
- **THEN** the column is added to the physical table and to the catalog, typed `long` and tagged
  `system`, and the next enrich write stamps it

#### Scenario: A second startup changes nothing

- **WHEN** the service starts again against the same targets
- **THEN** no DDL is issued, no catalog row is written, and no pipeline's `generation` changes

#### Scenario: Reconciliation bumps no generation

- **WHEN** the reconciler adds the column to an `llm` pipeline's target
- **THEN** that pipeline's `generation` and `updated_at` are unchanged, and a Class-1 pipeline's
  `materialized_through_version` is preserved

#### Scenario: An enrichment no pipeline targets is left alone

- **WHEN** the service starts with an `ACTIVE` enrichment that is not the target of any pipeline, and a
  system-owned enrichment
- **THEN** neither is altered

#### Scenario: A physical column without a catalog row is completed rather than refused

- **WHEN** the service starts against a target whose physical table already carries
  `pipeline_generation` but whose catalog has no row for it
- **THEN** the conditional `ALTER` succeeds without error, the catalog row is written, and the two
  agree afterwards

#### Scenario: A failure degrades rather than blocking startup

- **WHEN** the reconciler cannot reach the data store, or its `ALTER` is refused
- **THEN** the service completes startup and serves traffic, the failure is logged and counted, writes
  to that target continue with the stamp dropped, and the next startup retries
