## Why

`POST /v1/pipelines/{name}/preview` returns two things that answer **different questions**, and says so
nowhere.

`statement` is the `INSERT … SELECT` a refresh would run *now*, carrying the real window — the membership
filter, the safety lag, and the seek past any stored cursor. `rows` is a sample of what the declaration
produces over the **entire input**, deliberately not window-restricted, because the question a sample
answers is "are these the right numbers" and a window would obscure it. Both behaviours are intentional
and the code records why (`AggregateRefreshService:157-160`).

The spec does not. It describes the sample as "a bounded sample of its `SELECT` half" — which is not what
it is — and the response gives a client no way to tell the two apart. On a pipeline that has already
drained its input the divergence is total: the returned `statement` selects **zero** rows while `rows`
confidently shows full-input totals no refresh would ever write. Observed live: a reader comparing the two
reasonably concludes the rollup is broken and goes looking for a bug that does not exist.

The dry-run gate's whole value is that an operator trusts what it shows them before letting a declaration
write. A response whose two halves disagree, with nothing explaining why, spends that trust.

## What Changes

- The spec is corrected to state what preview actually returns: a windowed statement and an
  **unwindowed** sample, why each is shaped that way, and that this divergence is intentional rather
  than a defect to be "fixed" later by someone reading only one half.
- `PreviewDto` gains the sample's **own** rendered statement, so the response carries the provenance of
  both halves and an operator can copy either one knowingly. Today the sample's SQL is not visible
  anywhere — only its output is — which is the specific gap that makes the divergence look like a bug.
- No behavioural change to what is computed. The statement keeps its window, the sample keeps its whole
  input.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `aggregate-pipeline`: the "Register, preview, enable lifecycle" requirement's preview paragraph is
  rewritten to describe both halves of the response accurately, and gains scenarios pinning the
  divergence and the new field.

## Impact

- **Code**: `PipelineResponses.PreviewDto` gains a member; `AggregateRefreshService.PreviewResult` and
  `preview` carry the sample's rendered SQL alongside its rows; `PipelineDataController` maps it.
- **API**: additive — one new response field on an existing endpoint. No breaking change.
- **Docs**: the endpoint's OpenAPI `@Operation` description is reworded to match, since it currently
  repeats the spec's inaccurate "sample of the rows it would produce".
- **Not affected**: the compiler, the refresh path, the scheduler, governance, and every other
  capability. This change alters what preview *reports about itself*, not what it does.
- **Related**: `aggregate-preview-budget` constrains the same endpoint's cost. The two are independent —
  one bounds the work, this one describes the result — and touch adjacent lines in
  `AggregateRefreshService.preview`, so whichever lands second will need a trivial rebase.
