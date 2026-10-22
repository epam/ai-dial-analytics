## Context

The `view` parameter shipped on 2026-09-14 (`355b645`) with `source` as its default, stated in the
parameter's description and implemented as `viewOf(null) → SOURCE`
(`web/controller/pipeline/PipelineController.java:395-397`). Neither statement reaches a machine:
`PipelineView` declares `COMPILED` (`:22`) before `SOURCE` (`:30`) and `@JsonValue code()` (`:41-44`)
makes springdoc enumerate in declaration order, while both `@Parameter` annotations carry
`example = "compiled"` and there is **no `@Schema` and no `defaultValue` anywhere in
`PipelineController.java`**. The document therefore publishes
`{"enum": ["compiled","source"], "example": "compiled"}` with no `default` key.

The DIAL Admin UI consumed that literally. It answered 200 until `92a0f13`'s `requireCompilableListing`
(`:334-341`) began refusing a compiled listing without `kind=enrich`, at which point its pipelines page
started returning `400 bad_request` on a request that had never changed. Before the guard, the same
request was served with authored declarations under the compiled name — entries with no inlined evaluator,
no `grain_key`, no `version_column` and no `outputs`, which a list view renders identically to an authored
listing. The guard did not cause the defect; it made a two-day-old silent mis-answer visible.

Constraints: the accepted value set, the refusal codes, and the runtime default are all correct already
and must not move. `RunnerContract.VERSION` must not be bumped — the compiled projection's shape is
untouched and the runner passes `view=compiled` explicitly.

## Goals / Non-Goals

**Goals:**

- A consumer that reads only the published document arrives at `source`.
- The document and the handler cannot drift apart again.
- The published `kind` enum stops offering the unimplemented `external` ahead of implemented kinds.
- An assertion exists against the published document, which is the class of defect that had none.

**Non-Goals:**

- Changing any endpoint's behaviour, accepted values, or error codes.
- Implementing `PipelineKind.EXTERNAL`, or removing it.
- Giving `kind` a default. An absent `kind` returns every kind; a published default would silently narrow
  a listing that asked to be unnarrowed.
- The `adas-mcp` snapshot and its tool docstrings. They must be regenerated *after* this ships, as a
  docs-only follow-up in that repo; doing it here would mean doing it twice.
- The front-end half. The UI should stop sending `view=compiled` on a cross-kind listing, or pair it with
  `kind=enrich`. This change is the half that stops the *next* client inheriting the same default.

## Decisions

**Publish the default by making it a real binding default, not a second declaration of it.**
Use `@RequestParam(name = "view", required = false, defaultValue = "source")` rather than annotating
`@Parameter(schema = @Schema(defaultValue = "source"))`.

Rationale: springdoc derives `default` from `@RequestParam#defaultValue` automatically, so the published
document is a *consequence* of the binding rather than a parallel assertion about it. The two cannot
disagree. An explicit `@Schema(defaultValue = …)` would restate in an annotation what `viewOf(null)`
already decides in code, which is one more place to forget — and it carries a concrete mechanical risk:
supplying `schema` on `@Parameter` for an enum-typed parameter can *replace* springdoc's derived schema
rather than merge into it, silently dropping the `enum` array from the document. That risk is why the
alternative is rejected, and the new document-level test is what would catch it either way.

Consequence to accept: `viewOf(null) → SOURCE` (`:395-397`) becomes unreachable from the HTTP path once
Spring supplies `"source"` for an absent parameter. It is **kept** rather than deleted — it is the guard
for any non-HTTP caller and for a future overload, and deleting a defensive branch because one caller
stopped reaching it is how the branch gets rediscovered. The `@InitBinder("view")` path
(`:299-311`) is unaffected: `defaultValue` supplies the raw string `"source"` before conversion, which
the binder matches case-insensitively against `code()`, and an explicitly *empty* `view=` is still a
present-but-blank value and still refused exactly as today.

**Reorder the enums rather than reordering only the examples.** The example alone does not fix a
generated client that reads the first enum member. Both reorders are verified safe:

- `PipelineView` — no `.ordinal()` anywhere on it, no `switch`, every use is `==`
  (`PipelineMapper.java:218`, `PipelineController.java:164,189,335,380,396`), and nothing persists it.
  `values()` is read twice: `PipelineController.java:304` is an order-insensitive first-match, and `:308`
  builds the refusal message, whose test (`integration/CompiledProjectionIntegrationTest.java:776`)
  asserts both names are present without asserting their order.
- `PipelineKind` — stored by `Enum#name()` behind a DB CHECK constraint, never by ordinal (every
  `@Enumerated` in the repo is `EnumType.STRING`), and `implementedCodes()` (`:52-58`) already filters on
  the `implemented` flag for its messages.

**Move `EXTERNAL` last rather than marking it `@Schema(deprecated = true)`.** Deprecated is the wrong
word: `external` is not on its way out, it is not yet in — it is declared, rejected at every creation path
(`PipelineDataController.java:159`, `PipelineSpecCodec.java:58`) and intended to arrive. Ordering solves
the actual problem (a form picking the first or middle member) without making a false claim about the
value's lifecycle.

## Risks / Trade-offs

- **springdoc may not emit `default` for an enum-typed `@RequestParam`, or may emit it alongside a
  re-derived enum in an unexpected shape** → this is exactly what the new `/v3/api-docs` test asserts. If
  `defaultValue` does not produce a `default` key, fall back to `@Parameter(schema = @Schema(...))` and let
  the same test prove the `enum` array survived. The test is written before the annotation is chosen.
  **Resolved at implementation: no fallback needed.** springdoc 3.0.3 emitted `default: "source"` from
  `@RequestParam#defaultValue` and kept the full `enum`, so the preferred decision stands as written and
  the `@Schema` alternative was never applied.
- **`defaultValue` changes what the handler receives** (`SOURCE` instead of `null`) → behaviourally
  identical because `viewOf` mapped `null` to `SOURCE` anyway; covered by the existing
  `PipelineControllerTest` cases for an absent `view` and by the spec scenario asserting an absent `view`
  still returns the authored projection.
- **A reorder is a diff that looks larger than it is**, and enum order is the kind of thing a reviewer
  assumes is load-bearing → the design records the verification above so the review does not have to redo
  it.
- **The FE keeps sending `view=compiled` until it is changed**, so the Admin UI page stays broken after
  this ships → stated in Non-Goals; this change removes the cause for future clients and must be
  communicated to whoever owns that page rather than assumed to fix it.

## Migration Plan

None required. No migration, no config key, no new credential, no contract-version bump. The change is
additive to the published document and behaviour-preserving at the endpoint, so it can deploy in any
order relative to the runner and to `adas-mcp`.

## Open Questions

- Should `example` be dropped entirely rather than set to `source`? An example that duplicates the default
  adds nothing, and a *different* example is what caused this. **Resolved: keep `example = "source"`.** It
  now agrees with both the default and the enum ordering, so it can no longer be the thing a consumer
  reads instead of the default; and the new test asserts all three together, so a future edit that makes
  them disagree again fails the build. Dropping it would have been equally safe and slightly less
  informative in the rendered docs.

## Downstream follow-up recorded at implementation

`adas-mcp` bundles a snapshot of this document at `src/adas_mcp/swagger/adas-openapi.json` and it is now
stale by this change's fields: its `view` blocks carry `"enum": ["compiled","source"]`, `"example":
"compiled"` and no `default`, and its two descriptions were already truncated before the refusal sentences
the controller carries. Regenerating it is a docs-only follow-up **in that repo**, and it must be dumped
with sorted keys — the documented command (`README.md:135-152`) pipes through `python3 -m json.tool` with
no `--sort-keys`, which churns ~1400 lines of hash-order noise and makes the diff unreviewable.

That same follow-up owes two docstring corrections in `src/adas_mcp/tools/pipelines.py`, which are the
model's entire knowledge of the endpoint and currently contradict the server: the sentence claiming
`compiled` "changes nothing" for an aggregate pipeline (it is a 422), and the absence of any mention of the
400 for a compiled listing without `kind=enrich`. `_PIPELINE_VIEWS` is already `("source", "compiled")`, so
no client-side change is needed there.
