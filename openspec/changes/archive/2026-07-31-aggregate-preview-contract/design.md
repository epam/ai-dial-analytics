## Context

`AggregateRefreshService.preview` already builds both halves of the response and already knows they differ
— the divergence is deliberate and there is a comment explaining each side. What is missing is that none
of this reaches the client. `PreviewResult` carries `(statement, rows)`: the refresh statement's SQL and
the sample's *output*, never the sample's SQL. So a caller sees one statement and one set of numbers and
has every reason to assume the second came from the first.

The sample query is compiled by `AggregateCompiler.compilePreview` and passed to
`StructuredQueryExecutor.executeScan`, which renders it to SQL internally. Surfacing that SQL means
rendering it at the call site rather than letting the executor be the only place it exists.

This change is documentation and plumbing. No computation changes.

## Goals / Non-Goals

**Goals:**

- A reader of a preview response can tell which SQL produced which half.
- The spec describes what preview actually does, so the next person to compare the halves does not file
  the divergence as a bug — or, worse, "fix" it by windowing the sample.

**Non-Goals:**

- Changing what either half computes. The windowed statement and the unwindowed sample both stay as they
  are; this change makes them legible, not different.
- Bounding preview's cost — that is `aggregate-preview-budget`, which touches adjacent lines.

## Decisions

### Return the sample's statement rather than removing the divergence

The alternative is to make the sample window-restricted so both halves match. Rejected: it would defeat
the sample. A pipeline's cursor normally sits at the end of its input, so a windowed sample would return
nothing almost all the time, and the dry-run gate would answer "are these the right numbers" with an empty
list precisely when an operator most wants to check a declaration. The current design is right; only its
presentation is wrong.

The inverse — dropping the window from the returned statement so it matches the sample — is worse still,
and the existing spec already forbids it: a statement rendered with no window describes a full recompute
and is dangerous to copy out of a response.

### Render the sample's SQL at the call site

`executeScan` renders internally and returns only rows. Rather than change its contract for every caller
(the refresh probe and the enrichment scan path also use it), render the sample query to SQL in
`preview` — the same `getSQL(ParamType.INLINED)` call the executor makes — and pass it into
`PreviewResult`. Slightly redundant rendering, on an operator-driven endpoint where one extra render is
free, in exchange for leaving two unrelated callers untouched.

### Name the field for what distinguishes it

`statement` keeps its name and meaning; the new member is named for the sample, not the other way round.
Renaming `statement` would be a breaking change to an existing response for no gain, and the refresh
statement is the one a client is more likely to already consume.

## Risks / Trade-offs

- **The response now exposes a second piece of SQL.** → Both halves are already ADAS-built statements over
  a governed declaration on a `FULL_ADMIN`-only endpoint; the sample's SQL reveals nothing the refresh
  statement does not.
- **Two statements invite the reader to diff them.** That is the point, but it only helps if the response
  says why they differ. → The spec's new scenario pins the labelling, and the OpenAPI description is
  reworded in the same change rather than left repeating the old wording.
- **Adjacent-line conflict with `aggregate-preview-budget`.** → Both changes touch
  `AggregateRefreshService.preview`; whichever lands second rebases. Called out in both proposals.

## Open Questions

None. The behaviour is settled; this change writes it down and surfaces it.
