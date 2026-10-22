## Context

`SqlQueryFrontEnd.rootMessage()` builds the message for **four** call sites, wrapping whatever Calcite's
exception chain surfaces: the parse in `translate`, the parse in `screen`, and — sharing the same helper —
the validation and plan-conversion failures. Trimming inside the helper therefore touches all four, but
only a parse message carries the expectation marker, so the two non-parse sites are left alone by the
anchor itself rather than by a guard that has to be kept correct.

Calcite's `SqlParseException` message is structured by
convention: a first line of the form `Encountered "<token>" at line L, column C.`, then a literal
`Was expecting one of:` marker, then the expectation set. The head is what the caller needs; the tail is
what makes the response unusable.

Four surfaces inherit this message — `execute-sql`, `translate-sql`, enrichment rule filters, and
aggregate pipeline filters — because all four route through this one method. Fixing it once fixes all of
them, which is the main argument for doing it here rather than at each call site.

## Goals / Non-Goals

**Goals:**

- A parse failure's message is one readable line that locates the mistake.
- Nothing is actually lost — the full text moves to debug logging.
- All four surfaces improve from a single change.

**Non-Goals:**

- Touching validation errors. They already produce a targeted sentence and the spec has requirements
  pinning what they must name.
- Changing status codes, error codes, or which statements are rejected.
- Rewording Calcite's diagnosis into our own prose. Its head line is already precise; paraphrasing it
  would add a translation layer to maintain and could lose the position.

## Decisions

### Truncate at the expectation marker rather than by line count

Cut the message at Calcite's `Was expecting one of:` marker and keep what precedes it. A line-count cap
(say, first line only) would work for the common shape but silently truncate a multi-line diagnosis, and
a character cap would cut mid-token. Anchoring on the marker keeps exactly the semantic boundary that
exists in the text.

If the marker is absent — a parse failure shaped differently, or a Calcite version that rewords it — the
message passes through **unchanged**. The fallback is today's behaviour, so an unrecognised shape is never
worse than the status quo, only un-improved.

### Log the full text at debug, at the same site

The truncation and the logging belong together: separating them is how the full text gets lost in a later
refactor that only sees a string being shortened. Debug rather than info, because a rejected caller
statement is a client error, not a service event.

### Keep it in `rootMessage`, not in each capability's exception handler

The alternative is trimming in the pipeline validator, the enrichment validator, and the SQL endpoints
separately — four copies of one rule, three of which will drift. The message is constructed in one place
and should be constrained in one place. This also matches where the spec puts the requirement:
`sql-query-translation` owns the front end.

## Risks / Trade-offs

- **A caller currently reading the expectation set loses it.** → It is not a documented contract;
  `message` is human-facing prose and the stable `error` code is what clients switch on. The full text
  remains at debug for anyone who genuinely needs it.
- **Anchoring on an English marker string couples us to Calcite's wording.** → The pass-through fallback
  makes a wording change a no-op rather than a defect, and the marker has been stable across Calcite
  versions. A test pins the fallback so the coupling is visible if it ever breaks.
- **Debug logging a full parser dump on every parse failure.** → Only when debug is enabled, and a parse
  failure is already a low-frequency event on an authenticated surface.

## Migration Plan

Presentation-only and backward-compatible for anything switching on `status` or `error`. No config, no
schema, no data. Rollback is a code revert.

## Open Questions

None.
