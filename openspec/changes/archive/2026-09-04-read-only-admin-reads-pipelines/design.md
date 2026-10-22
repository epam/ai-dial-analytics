## Context

ADAS has two application roles, `FULL_ADMIN` and `READ_ONLY_ADMIN`, mapped from provider roles. The
URL rule admits either for `/v1/**` (`OidcSecurityConfiguration:114`); narrower gates are method
annotations. `PipelineController` carries `@FullAdminOnly` on **all six** methods, so the registry is
the only catalog surface whose reads are closed to the read-only role.

Three facts found while scoping shape the design, and each one shrinks it:

1. **Evaluator reads are already open.** `EvaluatorController`'s `list`, `getLatest` and `getVersion`
   carry no annotation; only `register` and the two 409-returning verbs do. Prompts are therefore
   already readable by a read-only admin. Widening the pipeline read exposes the *same* prompt a
   second time (inlined into an `enrich` entry) rather than exposing it for the first time.
2. **Sensitive column *names* are already visible.** `GET /v1/tables/{name}` returns every column with
   its `sensitive` flag, and `table-management/spec.md:196` scopes role-based hiding to the
   query/discovery surface. So the stated rationale for the gate — the declaration names sensitive
   columns — protects information the read-only role can already obtain.
3. **A scan cursor column can never be `sensitive`.** `ScanMetadataRules.requireUsable` refuses it at
   declaration and `requireNotSensitive` refuses it on a metadata patch, spec'd at
   `table-management/spec.md:445` and `:1496`. This is the one that changed the design: the field that
   looked like it needed redaction (`state.cursor_identity`, a literal value from a source row) is
   already guaranteed non-sensitive by a rule that exists for an unrelated reason — the scan has to
   project and page by that column.

The user constraint for this change is "sensitive data won't be shown". Fact 3 is what lets that hold
with **no redaction code on the read path**: the response is identical for every role. One field did
vary by role, and worse, could fail the read outright — fixed under D7 rather than documented.

Fine-grained roles are a separate design. This change is explicitly an interim widening under the
two-role model, not the final authorization design.

## Goals / Non-Goals

**Goals:**

- `GET /v1/pipelines`, `GET /v1/pipelines/{name}`, `GET /v1/pipelines/{name}/revision/{revision}`
  authorized by any mapped application role.
- Pipeline audit activities and snapshots follow that read, per the audit spec's own derivation rule.
- The "no row values in a pipeline read" property written down as a requirement, with its dependency
  on the cursor-column rule made explicit so it cannot be relaxed by accident.
- Zero change to the data plane, to mutations, and to `preview`/`refresh`.

**Non-Goals:**

- Fine-grained roles, per-pipeline grants, or a `pipeline:read` permission. Out of scope; this change
  should be easy to supersede.
- A redacted or role-varying pipeline response. Explicitly rejected below.
- Widening the Enrichment Runner's authorization, or letting a global read-only role reach a
  pipeline-scoped data-plane endpoint.
- Touching sensitive-column hiding on the query/discovery surface.

## Decisions

### D1: Drop `@FullAdminOnly` from the three read methods; keep the annotation model

Remove the annotation from `listPipelines`, `get` and `snapshot`. Nothing is added: the `/v1/**`
application-role rule in `OidcSecurityConfiguration` already admits them once the method gate is gone,
so authorization for these reads becomes exactly what a table read's is.

*Rejected — a URL matcher in `OidcSecurityConfiguration`.* The four data-plane matchers already have to
sit ahead of the `/v1/**` rule, and the config comment warns that ordering is load-bearing. Adding
`GET`-specific matchers on the same prefix would put method-and-path-shaped rules in two places for
one controller. Method annotations keep the rule next to the endpoint it governs.

*Rejected — a `@PreAuthorize` naming both roles.* Equivalent to the URL rule that already exists, and
it would read as a narrower gate than it is.

### D2: `preview` and `refresh` stay `FULL_ADMIN`

They live on `PipelineDataController` and are untouched. `preview` executes the declared query against
the input — a read the caller may not be entitled to make, since a preview projects the columns the
declaration names, `sensitive` ones included. `refresh` writes rows to the target. Neither reports
registered state, so neither belongs in this widening; `preview` in particular is the endpoint where
sensitive *values* would actually flow.

This is also the sharpest line to state in the spec: the registry read tells you what a pipeline
would compute; preview tells you what it computes over real rows. The first is now open, the second
is not.

### D3: No per-role response filtering — the invariant carries it

No field is redacted by role, and after D7 no field varies by role either. Justified by fact 3 above
for the identity fields, and by D4 for `last_error`.

*Rejected — omit `state.cursor_identity` for a non-full-admin caller.* This was the original plan. It
would mean threading `SensitiveColumnAccess` and an input-table catalog lookup into `PipelineMapper`,
on a path currently free of both, and the listing would need it per entry. All of that to guard a state
the catalog refuses to create. Dead machinery that a reader would later mistake for evidence that
sensitive cursor columns exist.

*Rejected — leave the invariant undocumented since it happens to hold.* It holds because of a rule in
another capability written for a different reason. Undocumented, a future relaxation of the
cursor-column rule (say, to let a source page by an opaque token that someone wants hidden) would
silently open a disclosure here. So the requirement states the dependency and says the two must change
together. That is the whole cost of the alternative: one paragraph instead of a mapper branch.

### D4: `state.last_error` stays verbatim

A failure string can in principle quote a fragment of a failing row — ClickHouse error text sometimes
does. It is kept unmodified anyway: the same strings already reach a read-only admin from the table
and scan surfaces, the field's only purpose is diagnosing a stall without log access, and any
truncation aggressive enough to guarantee no row content would remove the message. Recorded in the
spec as a deliberate decision with the constraint that the field must not be *constructed* to embed
input content.

*Rejected — full admin only for `last_error`.* It would make the widened read useless for the one
question a read-only admin most needs answered: why did this stop.

### D5: The audit change is part of this change, not a follow-up

`audit-trail`'s requirement says the audit surface for a resource "requires exactly what reading that
resource's current state requires", and then derives the `Pipeline` narrowing from the live read being
full-admin-only. Widening the live read while leaving `pipelineNarrowing()` in place would leave the
spec contradicting itself and produce a visibly odd surface: a read-only admin able to read a pipeline
but not to see that it was created. Remove the narrowing from both the feed specification and the
single-activity `get()`.

The snapshot needs no filtering: the runtime-position fields are `@NotAudited` (excluded so per-tick
cursor writes raise no revision), so a reconstructed pipeline carries no scan position at all.

### D6: `authentication` gets a one-line delta

The read-endpoint enumeration in **Mutating endpoints require FULL_ADMIN** is the single place that
lists what an application role may read. Pipeline reads are added there, and pipeline
mutations/preview/refresh to the `FULL_ADMIN` list, so that requirement does not silently go stale —
it is the requirement a reviewer checks first when asking "who can call what".

### D7: Fix the freshness resolution — it 500s, and the fix also makes the read role-invariant

Found while verifying, then confirmed by a failing test during apply.
`PipelineFreshnessDiagnostics.of()` resolves which enrichments a declaration reads, and for an
`AGGREGATE` pipeline it did so via `AggregateSpecValidator.referencedEnrichments(String, String,
AggregateSpec)`, which re-runs the SQL front end through `PipelineFilters.analyze(..., sensitiveGuard,
...)`. That has two consequences, and the second is a bug:

1. It throws for a caller not entitled to a `sensitive` column named in the `filter`, making the report
   role-dependent.
2. That resolver is `@Transactional`, so its throw marks `of()`'s own transaction **rollback-only**.
   `of()` catches the exception and returns `Report.NONE` as designed — and then its commit fails with
   `UnexpectedRollbackException`. The read answers **HTTP 500**, not the degraded 200 the class contract
   promises.

(2) is **pre-existing**: the same 500 fires for a `FULL_ADMIN` whose stored declaration has drifted,
which is the other case the javadoc claims to degrade. It was simply unreachable via the entitlement
trigger while the read was full-admin-only, so widening the read surfaced it. Reproduced by
`PipelineRegistryAuthIntegrationTest.freshnessDiagnosticsDegradeForNonEntitledCaller`.

**Decision: point the aggregate branch at the guard-free token twin**,
`AggregateSpecValidator.referencedEnrichments(PipelineDeclaration)`. That twin resolves from the
declaration's qualified column tokens (`declaredFieldsOf` covers the `filter` and each measure's
`where` via `QualifiedColumnTokens`), catches its own resolution failure, and never throws — so nothing
marks the transaction, the documented degradation finally works, and the report stops depending on the
caller. Its own javadoc already names "a state read's freshness report" as one of its two intended
callers, so the freshness path was calling the wrong twin. The `ENRICH` branch already resolved this
way, and both kinds now agree — which is the property that twin's javadoc asks for. Five lines, plus a
`PipelineDeclaration.of(PipelineEntity)` factory so the projection's field list is written once.

*Accepted cost.* Token extraction is lexical where the front end resolves, so the two can disagree on
some stored declaration and a `FULL_ADMIN`'s `unclamped_reads` could change. The disagreement window is
narrow — a qualified `enrichment.column` token is the only spelling an enrichment column has, which is
exactly what the twin scans for — and the catalog's own drop/rename guards already trust this resolver
for a stricter question. Chosen over the alternative below because it fixes the role dependence at the
same time.

*Rejected — a `REQUIRES_NEW` seam around the guarded resolver.* It would make the catch work without
touching what a full admin sees, but it leaves the report role-dependent (and its spec clause standing),
adds a second transaction and connection to every pipeline read, and treats a resolver that should not
be gated here as if the gating were correct.

## Risks / Trade-offs

- **A read-only admin can read every evaluator prompt inlined into a pipeline, and the filter/
  expression text of every pipeline.** → Accepted, and the explicit intent of the change. Prompts are
  already readable at `/v1/evaluators`; the declaration text is logic, not data. If prompt
  confidentiality later matters, it has to be solved at the evaluator registry, which is where prompts
  actually live — not by a second gate on pipelines.
- **The safety argument depends on a rule in another capability.** → Mitigated by writing the
  dependency into the `pipeline-registry` requirement in the terms a future change would trip over
  ("SHALL be changed together"), and by a scenario that asserts the cursor-column patch is refused.
- **`last_error` may echo a row fragment.** → Accepted per D4; the field already has this property on
  surfaces a read-only admin can reach today, so the change adds no new class of exposure.
- **Widening the audit feed makes pipeline churn visible to more callers.** → Intended. The feed
  already exposes table and column churn to that role.
- **A reviewer may read this as "read-only admin can now operate pipelines".** → Mitigated by keeping
  `preview`/`refresh` gated and by two spec scenarios that assert 403 for both, plus one asserting the
  data plane is unchanged.
- **The freshness resolver changed, so a full admin's `unclamped_reads` could differ on a declaration
  where token extraction and the SQL front end disagree.** → Accepted per D7. Bounded by a qualified
  token being the only spelling an enrichment column has, and by the same resolver already backing the
  catalog's drop/rename guards. The alternative was leaving a 500 on the read.
- **Regression risk of loosening the wrong thing.** → `PipelineDataAuthorizationIntegrationTest` is the
  guard and must pass untouched. A change to that file during implementation is a signal the widening
  went too far.

## Migration Plan

No data migration, no new config key, no new credential. Deploy is a code change only.

- **Forward**: ship the annotation removal, the `AuditActivityService` narrowing removal, and the spec
  updates together. No coordination with the Enrichment Runner is needed — it authenticates per target
  and is unaffected either way.
- **Rollback**: revert. Because the change is a pure widening, rollback cannot break a caller that was
  working before it; only a consumer built against the widened read during the window would notice,
  and there is none at ship time.
- **Ops note**: after deploy, anyone holding `READ_ONLY_ADMIN` can enumerate the registry. Worth a
  line in the release note, since the audience of that role is broader than the pipeline authors'.

## Open Questions

None blocking. Two deliberately deferred:

- Whether the eventual fine-grained model should split "read the declaration" from "read the prompt".
  That is a question for the roles design, and this change is written to be superseded by it rather
  than to anticipate it.
- Whether the two resolvers in `AggregateSpecValidator` should be reconciled to one. D7 makes the
  token twin the only caller-facing path for freshness, leaving the front-end variant to registration
  alone; whether registration wants the twin's answer too is a separate question this change does not
  settle.
