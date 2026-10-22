## Context

See proposal.md — Why. The relevant current state of `service/BlobPrefixResolver.java`:

- `dayPrefix(day)` is `basePrefix + dateSegment(datePath, day) + "/"`. The trailing separator is
  documented as being there "so it cannot match a sibling directory by name".
- `bandPrefix(day, digits)` is `dayPrefix(day) + fileNameHead(day) + digits`, and
  `checkBandShape`'s `expected` is `dayPrefix(day) + fileNameHead(day)`. **Both are written against
  `dayPrefix` plus the head.**
- `validatedDatePath(String)` is `static`: it refuses blank, parses, then sweeps
  2024-01-01..2027-12-31 rendering `dateSegment` and refusing the first collision, naming the rendered
  segment and the colliding pair of dates.
- `validatedFileNamePrefix(String)` is `static` too: it accepts blank (returning `null`), parses, and
  format-probes one date. Deliberately no sweep and no blank refusal.
- The startup line is `"Blob discovery prefix template: {}<{}>/, e.g. {}; band prefix example: {}"`.
- `BlobPrefixResolverTest` has 43 tests; 40 of them configure a non-blank date layout.
- The three call sites (`DataSyncService:142`, `DataBackfillService` through it,
  `BlobPollingService:239,242`) pass `dayPrefix` straight to `blobClient.list`, which lists by prefix
  with `recursive()` and no delimiter.

The target keys, from a real flat bucket: `logs/date=2025-10-22<10-digit epoch>-<uuid>.log.gz`, and a
second head `logs/data=<date>…`. In every sampled key the head is followed
immediately by an epoch whose UTC date equals the head's date, so the band math and
`checkBandShape`'s digit test both hold on the flat layout unchanged.

## Goals / Non-Goals

**Goals:**

- A flat bucket becomes ingestible by configuration alone, on every discovery path at once.
- The shipped objection to a blank layout — "it would widen every whole-day listing to the entire base
  path" — is *answered*, not overruled.
- Byte-identical prefixes in the normal mode, on all three producers of a prefix (whole-day, band,
  band-shape expectation).
- Every way the relaxed mode can silently over-list is refused at startup, not discovered as a
  re-ingest.

**Non-Goals:**

- Supporting more than one file-name head in a single run. A flat bucket may hold two (`data=` current,
  `date=` a one-off import); the imported set needs its own backfill run with its own configuration.
- Making the embedded timestamp configurable. Its unit, width and position are what make a band prefix
  a numeric range at all.
- Any change to band width, the finalization lag, or the listing modes.

## Decisions

### D1 — The whole-day prefix absorbs the head; it does not lose a segment

The obvious reading of "blank means no date directory" is *omit the empty segment and its separator*,
i.e. `dayPrefix(day) = basePrefix`. **Rejected, and it is the whole reason this change needs a design
note.** That prefix is independent of `day`, so:

- the scheduled daily run, the operator backfill and the poller's `day` fallback would each list the
  **entire base path** and re-ingest every day the container holds, on every run;
- `checkBandShape` would compare each key against `basePrefix + head(day)` while the listing returned
  every day's keys, so **every other day's key would count as a mismatch** — the metric and the
  once-per-process warning would fire on a correctly configured deployment;
- the poller's band mode would be the only path still bounded, which is exactly the "one path left
  behind on the old layout" failure the *Uniform date-directory layout across discovery paths*
  requirement exists to rule out.

That is the shipped spec's own objection, and it stands. So the prefix must keep carrying the date,
and in a flat bucket the only text that carries it is the file name's leading head. Hence
`dayPrefix(day) = basePrefix + fileNameHead(day)`, ending **mid-file-name** and with **no trailing
separator** — `logs/date=2025-10-22`. A prefix listing does not care that it ends inside a name;
`blobClient.list` sends it verbatim with no delimiter.

The cost of dropping the separator is D3.

### D2 — Extracting `dateDirPrefix` is mandatory, not a tidy-up

`bandPrefix` and `checkBandShape` both build `dayPrefix(day) + fileNameHead(day)`. Once `dayPrefix`
absorbs the head, that expression **doubles the head** — `logs/date=2025-10-22date=2025-10-22…` — and
band listing would discover nothing while whole-day listing worked, which is the hardest failure in
this component to notice.

So a private `dateDirPrefix(day)` is extracted — `basePrefix + dateSegment + "/"` normally, plain
`basePrefix` when the date layout is blank — and both are re-expressed against **it**:

| | date directory configured | date layout blank |
|---|---|---|
| `dateDirPrefix(day)` | `logs/2026/06/24/` | `logs/` |
| `dayPrefix(day)` | `logs/2026/06/24/` | `logs/date=2026-06-24` |
| `bandPrefix(day, d)` | `dateDirPrefix + head + d` | `dateDirPrefix + head + d` |
| `checkBandShape` expects | `dateDirPrefix + head` | `dateDirPrefix + head` |

The resulting **strings are unchanged in the normal mode** — `dateDirPrefix == dayPrefix` there — which
is why 40 of the 43 existing tests stand untouched. The code change is still mandatory: the two
expressions only agree today because `dayPrefix` never carried the head.

### D3 — Prefix-freedom is a new refusal, and it is the price of dropping the slash

A whole-day prefix ending in `/` cannot be a proper prefix of another day's, because two distinct
same-length segments differ somewhere before it. Without the slash that protection is gone:
`'d='yyyy-M-d` renders `d=2025-1-1`, and a listing under it also returns `d=2025-1-10` …
`d=2025-1-19` — ten other days, silently, on a configuration that passes every existing check. The
strings are all distinct, so the distinctness sweep is blind to it.

The fix rides on the sweep that is already running: sort the rendered whole-day prefixes and refuse
when any entry is a proper prefix of its successor. Sorted order makes this complete rather than a
sampling — every entry that starts with a given prefix forms one contiguous block headed by that
prefix, so a nesting pair is always adjacent, and one linear pass over the sorted set suffices instead
of the quadratic all-pairs comparison. A fixed-width head such as `'date='yyyy-MM-dd` passes. The
refusal is only reachable in blank-date mode, and it names both days.

### D4 — The distinctness sweep moves onto the whole-day prefix, and that is the only rule needed

Sweeping `dayPrefix` instead of `dateSegment` does four things with one move:

1. `yyyy/MM` stays refused in the normal case, unchanged — the head is no part of `dayPrefix` while a
   date directory exists, so the swept strings are exactly today's plus a constant base path and a
   trailing slash.
2. A blank date layout is admitted **exactly when the head pins the day**, which is the correct
   condition and not a heuristic.
3. A blank *or* non-date head under a blank date layout falls out of the same sweep — both days render
   `logs/`, so it collides on day two. No separate "head must be dated when the date layout is blank"
   rule, and therefore no second place to keep in step.
4. `null` and blank are treated alike, as the head already is, so an unset variable and an empty one
   behave the same.

### D5 — `validatedDatePath` can no longer be `static`

The sweep now renders `dayPrefix`, which needs `basePrefix` and — in blank mode — the head formatter.
Both are instance state, so the method becomes an instance method and the constructor must resolve the
head and the base path **before** calling it. Constructor ordering becomes load-bearing and is
commented as such. (`validatedFileNamePrefix` stays static: it still only parses and format-probes.)

This is also why the head is validated first: a head that cannot render a date must fail with its own
message, not as a `DateTimeException` surfacing from inside the date-layout sweep.

### D6 — Refusals keep naming the configured text, not the prefix

The operator sets a *pattern*; the prefix is `basePrefix` prepended to what it renders. A message
quoting `logs/2026/06` for a `yyyy/MM` layout invites them to go looking at `SYNC_PATH_PREFIX`. So the
message keeps quoting the rendered **date segment** — or, in blank mode, the rendered **head** — and
names the setting the operator must actually edit, which in blank mode is
`SYNC_FILE_NAME_PREFIX_PATTERN`, not the date layout they deliberately left empty.
`refusalNamesTheCollidingDates` asserts `renders '01/01' for both …` and must keep passing verbatim.

### D7 — The startup log renders the head-absorbed form

The template is `"…prefix template: {}<{}>/…"`, which for a blank layout would print `logs/<>/` — an
empty segment and a slash that no key carries, i.e. the very reading D1 rejected. The blank-mode line
renders `<head pattern>` with no trailing slash instead, so an operator comparing the logged template
and example against a real key sees the same text. This is the offline verification step for a flat-layout
rollout: with the target configuration, startup must print `logs/date=<today>` and
`logs/date=<today><band digits>`.

## Risks / Trade-offs

- **A whole-day prefix that ends inside a file name is unusual.** Mitigated by it being unreachable
  unless the operator blanks the date layout, and by the two startup refusals that make an ambiguous
  head impossible to deploy.
- **The sweep does more string work** — it now renders the head as well as the date segment for each of
  ~1,460 days, and sorts the result. Once, at startup, on a few thousand short strings.
- **Two heads in one flat bucket** (`data=` and `date=`) and a single pattern. Out of scope by
  decision: the imported set gets its own backfill run. Recorded because it is a configuration trap,
  not a code one.
- **The band-shape metric is the tripwire** if any of this is wrong on the flat layout: a doubled head
  (D2) or an over-wide prefix (D1) both surface as `sync.blobs.band.shape.mismatch` climbing, which is
  what a flat-layout rollout watches.
