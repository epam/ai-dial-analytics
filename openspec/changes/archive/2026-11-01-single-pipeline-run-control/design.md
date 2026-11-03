## Context

Two controls stop a pipeline today, and they do different things:

| | Registry `enabled: false` | Runner operator pause |
|---|---|---|
| Stored | Postgres, in the registry | runner process memory; cleared by a restart; ADAS cannot see it |
| Validation | enabling runs the declaration gate | resuming checks nothing |
| Queued runner work | dropped: chunks whose cursor already advanced are never re-read, already-paid upserts go to the dead-letter queue | allowed to finish |
| Cursor, group state, DLQ | kept | kept |
| Backfill | paused (`pipeline_absent`), resumed by hand | paused (`pipeline_paused_operator`), resumed by hand |
| `aggregate` pipelines | the only control; disabling also unlocks declaration edits | not applicable |
| Takes effect | on the runner's next feed poll | immediately |

The runner also pauses a pipeline on its own: its dead-letter circuit breaker stops new work for a window
that starts at minutes and widens on repeat trips, then lifts itself. It shares the pause registry with the
operator pause and differs only in origin.

The two operator controls can disagree: a paused pipeline reads `enabled: true` with no progress in ADAS, its
runner-contact staleness series climbs as though the runner were down, and an operator pause survives a
disable/enable and even a delete and re-create under the same name.

## Goals / Non-Goals

**Goals:**
- One run control per pipeline, with one meaning, read and changed in one place, the same for both kinds.
- Stopping loses nothing: the cursor, group state and queued work survive a stop and continue on restart.
- No breaking change to ADAS's API, its admin tools' use of `enabled`, or the runner contract version.

**Non-Goals:**
- Reporting the runner's runtime status (breaker pauses, holds, spend) to ADAS. The UI already reads the
  runner's runtime status, and keeps doing so for these runtime conditions.
- Changing the `aggregate` rule that a declaration may be edited only while the pipeline is stopped.
- Automatically resuming backfill runs paused because their pipeline was stopped.

## Decisions

### `enabled` is the single control, kept as a boolean

The registry flag is already durable, already the arming checkpoint, already the only control `aggregate`
pipelines have, and already what API clients and the admin tools drive. Making it the one control needs no
API change; a UI labels it Active / Stopped.

*Rejected — a new `state` enum (`active | stopped`)*: clearer on the wire, but it is a breaking change to
every client and to the compiled projection, so it would raise the runner contract version for no behaviour
a boolean cannot express.

*Rejected — the UI composing both calls behind one button*: the two states would still drift (a runner
restart clears one and not the other), and the UI would own an invariant neither service enforces.

*Rejected — the runner's pause endpoints forwarding to ADAS*: it needs a runner credential that can write the
registry, and keeps a second entry point for the same act.

### A flag-only patch does not bump `generation`

`generation` qualifies three things: the runner's queued work (its stale-declaration guard drops an item
built against another generation), the materialized position dependent rollups clamp on, and every row's
`pipeline_generation` stamp. All three describe the declaration, and a stop changes none of them. Bumping
on enable and disable meant a stop and restart dropped every parked item as stale — irreversibly on the row
lane, whose cursor already passed those rows — and voided the materialized position, so rollups wrote
nothing until the runner caught up. So `generation` moves only with the declaration, and `updated_at` still
moves with every accepted patch, so an `updated_since` reader sees the flag change. The runner notices the
flag by comparing `enabled`, since it re-reads the full listing on every poll.

*Rejected — a second `declaration_generation` member*: additive, but two counters with overlapping meaning,
and a reshaped compiled entry that raises the runner contract version.

*Rejected — the runner re-adopting parked items across a flag-only bump*: it has to infer "flag-only" from
two consecutive feed snapshots, and a missed or merged poll makes a re-declaration look like a restart.

### The runner follows `enabled` through the unfiltered feed

The runner must keep a stopped pipeline's work and discard a deleted pipeline's. Under `enabled=true` both
are simply absent from the feed, so the runner cannot tell them apart. The unfiltered listing already exists,
already carries `enabled` and the full resolved entry for a disabled pipeline, and resolves in a constant
number of queries, so the runner asks for it instead.

*Rejected — a per-pipeline `GET /v1/pipelines/{name}` on disappearance*: a second endpoint the runner does
not call today, and a race between the two reads.

*Rejected — a second poll of `enabled=false`*: two snapshots that can disagree with each other about one
pipeline.

Safety does not move. The unfiltered feed carries disabled pipelines that were never armed, so the contract
is that a consumer executes only `enabled: true` entries; incomplete declarations stay omitted whatever the
flag; and `…/rows` and `…/materialize` keep refusing a disabled pipeline with 409 as the backstop.

### A stop parks queued work; it does not drain it

The runner holds a stopped pipeline's queued work unexecuted and runs it on re-enable. Letting queued work
drain and write after the stop would need ADAS to accept writes from a disabled pipeline for a grace period,
weakening "a disabled pipeline writes by no route". Keeping the drop would keep losing rows the cursor had
already passed. Parking is the runner's to implement. ADAS's part is to keep its refusal unambiguous: an item
already past its model call when the stop lands gets 409 `pipeline_conflict`, and the runner holds it rather
than dead-lettering it, which today would also count every row against the breaker and could let a stop trip
it. So the spec pins `pipeline_conflict` on `…/rows` and `…/materialize` to mean "disabled" and nothing else.

### The breaker pause stays in the runner, as status

The breaker is the runner's inference, and it expires; turning it into `enabled: false` would make a bounded
outage an indefinite stop that a user has to notice and undo. It stays a runner runtime condition that the UI
reads from the runner. The registry spec says so, so that `enabled: true` is never read as "is making
progress".

## Risks / Trade-offs

- **The runner-contact series climbs during a breaker pause** → accurate (the runner is not calling) and
  stated in the spec; the runner's runtime status names the cause. Reporting runner status to ADAS is the
  follow-up if this proves noisy.
- **A larger feed** → the runner now receives disabled pipelines on every poll. The resolution cost is
  constant in queries, and the entry count is bounded by the registry.
- **A disabled pipeline whose declaration stops being servable** (for example, its last output removed so a
  column can be dropped) leaves the unfiltered feed and reads to the runner as deleted, so its queued work
  is dropped → accepted: that work targets outputs the declaration no longer has.
- **Something reads `generation` as "the pipeline changed"** → nothing inside ADAS does, beyond the
  materialized-position qualification this change means to keep; `updated_at` remains the any-change
  signal. A runner built before this change still works: it reads `enabled=true`, where a stopped pipeline
  is absent and a restarted one reappears.

## Migration Plan

- ADAS ships first. Alone it changes only that a flag-only patch leaves `generation` alone; it serves the
  unfiltered feed already. The runner change follows; shipped earlier, its parked work is dropped as stale
  on restart, as today, until ADAS catches up.
- Runner pauses are in memory only, so there is no stored state to migrate; deploying the new runner restarts
  it and clears every pause anyway. Before upgrading the runner, read its `GET /v1/pipelines/paused` and, for
  each entry whose origin is `operator`, set `enabled: false` in ADAS, so that a pipeline stopped on purpose
  stays stopped.
- Rollback: the previous runner reads `enabled=true` again and treats stopped pipelines as absent, as before.

## Open Questions

_None._
