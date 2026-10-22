## ADDED Requirements

### Requirement: A generation change is logged with its old and new value and its cause

Every change to a pipeline's `generation` SHALL log one INFO line naming the pipeline, the old generation,
the new generation and the cause. The cause is the mutation that bumped it: `register`; a patch, as
`patch(…)` naming what the patch did (`re-declare`, `enable`, `disable`, or several of them, comma-separated); `version_column_repoint` for a
source's version-column repoint; or `target_columns_changed` for a target's column change. A patch
bumps the generation once however many of those it combines, so it logs one line. It SHALL name the acting principal
as `actor=` when the mutation carries one, and SHALL give `actor=-` rather than invent one when it does not, as
with a bump cascaded from a schema edit, or when `security.mode=none`. The field is always present, as the
Enrichment Runner's operator lines always carry `principal=`, so a line without a principal is still parsed the
same way. A bump that touches several pipelines at once
SHALL log one line per pipeline, not only a count.

The register, re-declare and enable lines SHALL also give the pipeline's resulting generation.

The generation qualifies the materialized position, the runner's scan state and every row's
`pipeline_generation` stamp. A bump nobody can see explains a rollup freeze or a runner re-scan only
after a database read.

#### Scenario: A re-declaration logs the bump

- **WHEN** an operator re-declares a pipeline at generation 4
- **THEN** one INFO line reports the pipeline, `4 -> 5`, the cause `re-declare`, and the operator when one
  is authenticated

#### Scenario: A cascaded bump names each pipeline

- **WHEN** a source's version column is repointed and three pipelines reading it are bumped
- **THEN** three INFO lines are logged, each with that pipeline's old and new generation, the cause and
  `actor=-`
