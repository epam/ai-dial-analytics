## Context

Ingest rewrites the `inline-files` body columns through `InlineFileRewrite`, which calls
`InlineFileStripper.strip(body, api, side, mode)` with the three-valued `InlineFilesMode`. The stripper
already finds every inline file as a `Span(quote, payloadStart, end)` over the raw body text, and the half
file at a 4 MiB cut as an open span. See proposal.md for why the mode is being split.

## Goals / Non-Goals

**Goals:**
- Two independent settings, `sync.inline-files-drop-truncated` and `sync.inline-files-max-size`, with the
  defaults reproducing today's `drop-truncated` byte for byte.
- Measuring a file costs O(1) once the scan has located it.
- A body that cannot hold an over-limit file is not parsed at all.

**Non-Goals:**
- Decoding files or measuring their decoded size.
- Keeping `sync.inline-files-mode` as an alias.
- Any change to which values count as inline files, to the body formats, or to the counters' names and tags.

## Decisions

**One value object for the two settings.** `InlineFilesMode` is replaced by a record
`InlineFilesPolicy(boolean dropTruncated, long maxFileSize)`, where "no limit" is `Long.MAX_VALUE`. A file
is removed exactly when its payload length is `> maxFileSize`, so "no limit", a limit and `0` share one
comparison and the stripper has no special case. `rewrites()` (`dropTruncated || maxFileSize != NO_LIMIT`)
replaces `mode != KEEP` in `MappingEngine`, so with both off no rewrite is built and no counter registered,
as under `keep` today. Rejected: keeping the enum and adding a size beside it (a `KEEP` plus a limit would
contradict itself); a nullable `Long` limit (a null check on every file).

**Bind the size as Spring `DataSize`.** `SyncProperties` gets `Boolean inlineFilesDropTruncated` (null →
`true`, so a record built in a test without it keeps the default) and `DataSize inlineFilesMaxSize`
(null → no limit). `application.yml` ships `${SYNC_INLINE_FILES_MAX_SIZE:}`; Spring converts an empty string
to a null `DataSize`. `DataSize` parses `256KB`/`1MB` with binary multiples and reads a bare number as bytes,
which is what the spec promises. A negative value is rejected in the compact constructor, which fails
binding and so startup. Rejected: a `-1` sentinel for "off" (an unset value says it more plainly and avoids
`${X:-1}`, which reads like shell syntax); a plain `long` of bytes (operators would write `1048576`).

**Measure characters between the payload start and the closing quote.** The size is
`span.end() - span.payloadStart()`, which the locator already computes; `payloadStart` is past a data URL's
prefix. Base64 is ASCII, so this is the stored byte count. Rejected: UTF-8 length (a pass over every file's
payload for a difference that only exists in text that is not base64); decoded length (a guess about
storage, not a measure of it).

**Skip the parse when the body is not longer than the limit.** No file in a body can be longer than the
body, so when `body.length() <= maxFileSize` and the body does not need the truncated repair, it is returned
unchanged before it is classified or scanned. With no limit (`Long.MAX_VALUE`) this test is what keeps the
default from parsing bodies that were not cut, so the existing `DROP_TRUNCATED && !isTruncated` early return
becomes a special case of it.

**The half file with the repair off.** The open span runs from `payloadStart` to the text end before the
`>>` marker. When that length is over the limit, the body is cut at `payloadStart` and the marker put back;
this is counted as a cut body and its bytes as removed bytes, not as a removed file (the removed-files count
stays for complete files). With the repair on, the cut stays at the opening quote, as today.

## Risks / Trade-offs

- [A deployment that set `SYNC_INLINE_FILES_MODE=drop` silently starts keeping files after the upgrade] →
  The upgrade doc says so and gives the replacement (`SYNC_INLINE_FILES_MAX_SIZE=0`); this is a deliberate
  choice to remove the variable outright rather than fail startup on it.
- [The size counts JSON escapes, so a payload written with `\/` reads slightly long] → Harmless at the
  sizes a limit is set at; the spec says so.
- [Re-ingesting old blobs under new settings rewrites their rows] → Already true of mode changes; the
  determinism rule covers it.

## Migration Plan

Remove `SYNC_INLINE_FILES_MODE` from the deployment. `keep` → `SYNC_INLINE_FILES_DROP_TRUNCATED=false`;
`drop-truncated` → nothing to set; `drop` → `SYNC_INLINE_FILES_MAX_SIZE=0`. Rollback is restoring the
previous image and variable; rows written in between keep the new settings' result until re-ingested.
