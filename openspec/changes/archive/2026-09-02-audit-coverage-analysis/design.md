# Audit Coverage Analysis — Current State Report

This is a current-state analysis. It records what exists today: which PostgreSQL-persisted
entities exist, which are audited, how the existing audit implementation works, and how the
implementation compares against the `audit-trail` capability's own delta spec. It does not
recommend future audit scope or next-increment candidates.

## 1. PostgreSQL entity inventory

| Entity | Table | Package |
|---|---|---|
| `TableDefinitionEntity` | `table_definition` | `service.table` |
| `ColumnMappingEntity` | `column_mapping` | `service.table` |
| `EvaluatorEntity` | `evaluator` | `service.enrichment.entity` |
| `EvaluatorVersionEntity` | `evaluator_version` | `service.enrichment.entity` |
| `RuleEntity` | `rule` | `service.enrichment.entity` |
| `PipelineEntity` | `pipeline` | `service.pipeline` |
| `SavedQueryEntity` | `saved_query` | `service.query.saved` |
| `OtelSinkEntity` | `otel_sink` | `service.otel` |
| `OtelSinkSignalEntity` | `otel_sink_signal` | `service.otel` |
| `AuditRevisionEntity` | `revinfo` | `service.audit` (audit-trail infrastructure itself) |
| `AuditActivityEntity` | `audit_activity` | `service.audit` (audit-trail infrastructure itself) |

Two tables existed historically — `enrichment_grain` and `table_ordering_key` — but were folded
into `table_definition` by `V25__denormalize_table_catalog.sql` and dropped in that same migration.
No live entity backs either table today.

## 2. Audit coverage matrix

| Entity | Audited (`@Audited`) | Shadow table |
|---|---|---|
| `TableDefinitionEntity` | Yes | `table_definition_aud` |
| `ColumnMappingEntity` | Yes | `column_mapping_aud` |
| `EvaluatorEntity` | No | — |
| `EvaluatorVersionEntity` | No | — |
| `RuleEntity` | No | — |
| `PipelineEntity` | No | — |
| `SavedQueryEntity` | No | — |
| `OtelSinkEntity` | No | — |
| `OtelSinkSignalEntity` | No | — |

`V27__audit_trail.sql` is the only migration that creates `*_aud` tables or `revinfo`/
`audit_activity`; no later migration adds audit machinery for any other entity.

## 3. Existing audit implementation details

- Auditing is implemented via Hibernate Envers: audited entities carry `@Audited`, and Envers
  mirrors their state into `_aud` shadow tables (`table_definition_aud`, `column_mapping_aud`) on
  every revision.
- Revision metadata is centralized in two tables introduced by `V27__audit_trail.sql`:
  - `revinfo`, backing `AuditRevisionEntity` — one row per revision (Envers' standard revision
    entity).
  - `audit_activity`, backing `AuditActivityEntity` — attribution/activity detail joined to a
    revision.
- Coverage is applied at the entity level (annotation on the class), not centrally toggled per
  table or per repository — a `JpaRepository` extension alone does not imply auditing; it is the
  presence of `@Audited` on the entity that does.

## 4. Comparison with the `audit-trail` specification

The `audit-trail` capability was implemented under the `audit-tables-and-rules` change
(`openspec/changes/audit-tables-and-rules/`, tasks complete) but has not been archived — its delta
spec at `openspec/changes/audit-tables-and-rules/specs/audit-trail/spec.md` has not been merged
into `openspec/specs/`, and no `openspec/specs/audit-trail/` directory exists today.

Comparing the shipped implementation against that delta spec:

- **Matches the spec.** The spec's "Audited resources" requirement scopes coverage explicitly to
  `table_definition` and `column_mapping`, and states that rules, evaluators, aggregate pipelines,
  and saved queries are outside this capability's coverage. A dedicated scenario ("Rules and
  pipelines are not tracked") pins that exclusion as intended behavior. The implementation matches
  this requirement precisely — no drift between code and the change's own spec for the entities it
  names.
- **Not addressed by the spec at all: `OtelSinkEntity` / `OtelSinkSignalEntity`.** These entities
  and their migration (`V30__otel_sink.sql`) postdate `V27__audit_trail.sql` and the
  `audit-tables-and-rules` change entirely, so the spec's exclusion list does not mention them one
  way or the other.

## 5. Identified gaps

1. **Spec/archive gap.** The `audit-trail` capability is implemented and shipped, but
   because `audit-tables-and-rules` was never archived, `openspec/specs/` — the documented source
   of truth for shipped behavior — currently has no record of the capability at all.
2. **Documented exclusion (not a gap in the implementation).** `EvaluatorEntity`,
   `EvaluatorVersionEntity`, `RuleEntity`, `PipelineEntity`, and `SavedQueryEntity` are unaudited,
   matching the `audit-trail` spec's own stated exclusions. Code and spec agree here.
3. **Undocumented exclusion.** `OtelSinkEntity` and `OtelSinkSignalEntity` are unaudited and did
   not exist when the `audit-trail` spec was written, so no artifact — spec or otherwise — records
   this exclusion.
