## Context

See proposal.md — Why. `V10__create_dial_usage_log_payload.sql`, the mapping split and Postgres
`V38__split_payload_columns.sql` all shipped in `2026-09-10-add-dial-usage-log-payload-table` and are
unaffected here. The only thing this change adds is the `ALTER TABLE ... DROP COLUMN` that the
original three-migration design ended with and that
`2026-09-11-remove-payload-backfill-migrations` removed along with the backfill it depended on.

## Goals / Non-Goals

- Goal: remove the five verbatim-content columns from `dial_usage_log`'s physical schema, and leave
  the spec, docs and local tooling describing that reality rather than the previous one.
- Goal: assert the result against a real ClickHouse, since nothing in the suite reads that table's
  physical column list today.
- Non-goal: reinstating the historical backfill in any form, including as documented out-of-band
  SQL. The pre-split content is accepted as lost (see the decision below).
- Non-goal: touching `dial_usage_log_payload`, its retention, or the catalog. The five columns are
  already published from the payload table; the read surface does not change at all.

## Decisions

**Drop without a backfill, accepting permanent loss of pre-split content.** The archived design
rejected exactly this shape — "keep `V12` but drop `V11`" — on the grounds that it "accepts silent,
permanent loss of the pre-split body/claims/header content". Two things make it the right call now
rather than then. First, the loss is not silent: it is the headline of this proposal, of the
capability spec's ADDED requirement, of `docs/heavy-columns.md`'s upgrade section and of the README
bullet, each of which tells an operator to copy the content out first if they want it. The migration
file carries a two-line warning of it as well, because a destructive statement should say so where it
is read. Second, the
alternative is not "keep the data" but "run the backfill", and the backfill is the specific artifact
that can crashloop a ClickHouse node with `Code: 241` under a server memory ceiling of a few GiB. Its
memory-safe redesign (`SETTINGS final = 0` carrying `_ingested_at` into `_updated_at`) worked, but it
remains a one-shot startup-time data migration that doubles the content on disk until the drop
follows it, and a previous change already decided not to carry it.

What tips the balance is that the data is **already unreachable**. `V38` re-keyed the five
`column_mapping` rows onto `dial_usage_log_payload`, so `request_body` and friends resolve on no ADAS
surface — not the DSL, not `execute-sql`, not the translation endpoints, not Flight SQL — and the
qualified names read the payload table, which has never held a pre-split row. From the service's
point of view the content has been gone since the split shipped; only a direct ClickHouse session can
see it. The drop converts storage nothing can read into storage nothing pays for.

**Alternative considered and rejected: document an optional out-of-band backfill in
`docs/heavy-columns.md` for operators who want the history.** It reads like a safety net and is not
one: an operator who skips it loses the data just as completely, and one who runs it takes the full
OOM risk with none of the rehearsal the shipped-migration path would have had. The honest form of the
same offer is what the docs say instead — *copy it out before you deploy* — which lets an operator use
whatever export they already trust.

**Numbered `V11`, reusing the slot the deleted backfill briefly held.** The repo's ClickHouse set
tops out at `V10` and the standing rule is to number against merged history rather than skipping
ahead. The question worth asking was whether any deployed environment had applied the old `V11`/`V12`
during the brief window they existed in the history and so carries
`flyway_schema_history` rows that the new file would collide with. It has not: the only place those
rows can exist is a local developer volume, which is disposable. Renumbering to `V13` to dodge a collision
that no deployed environment has would leave two permanent gaps in the sequence for nothing.

**The migration is a plain multi-clause `ALTER`, with `IF EXISTS` on every clause.** Verified against
`V1`/`V3`: none of the five is in `ORDER BY (project_id, deployment, request_time, trace_id,
core_span_id)`, none carries or feeds a `DEFAULT` expression, and the skip indexes are on `trace_id`,
`core_span_id`, `chat_id`, `user_hash` and `_ingested_at`. So the drop needs no `MATERIALIZE` pass
and cannot disturb deduplication or scan reads. ClickHouse removes the dropped columns' files from
each part rather than rewriting the survivors, so there is no free-space precondition — but the
`ALTER` returns before the reclaim finishes, which `docs/heavy-columns.md` says so nobody reads a flat
disk graph as a failed migration.

**Rolling deploys are now safe in both directions, and the old draft's warning is deleted rather than
carried forward.** The original `V12` header warned that old pods still writing these columns would
fault until replaced. That ceased to be true when the mapping cutover shipped: every build since
writes `dial_usage_log_payload` and not these columns, so a not-yet-replaced pod is indifferent to
their removal. Only a rollback to a build from *before* the cutover needs `ALTER TABLE ... ADD COLUMN`
to restore the shape first, and it restores them empty.

**The migration file carries no prose beyond a pointer and a destructive-operation warning.** `V1`
through `V10` open with twenty to a hundred lines of rationale, and the first draft of this one
followed them. That is the wrong home for it: a migration's comment block cannot be validated, cannot
be linked to, and goes stale silently the moment the behaviour it describes moves — `V10`'s header,
which still describes a backfill that never shipped, is the proof sitting next to this file. The
rationale now lives where it can be checked: the capability spec owns the behaviour (an ADDED
requirement with four scenarios), `docs/heavy-columns.md` owns the operator procedure, and
`docs/clickhouse-server.md` owns the grant. The file keeps four lines naming what it does and where
that is written down, plus two saying it is destructive and unbacked — a reader about to run it needs
that at the statement, not a link away.

**Never edit `V10__create_dial_usage_log_payload.sql` or Postgres `V38__split_payload_columns.sql`,
even their comments.** Both are applied migrations and this repo's convention (stated in `V7`'s own
header) is that an applied migration's checksum is fixed. `V10`'s header describes the original
V10/V11/V12 trio and now half-matches reality again — it says `V11` copies and `V12` drops, where the
copy never shipped and the drop is `V11`. That staleness is accepted rather than risking a checksum
break, exactly as the previous change accepted it. `mapping/*.yaml`, `local_env/**` and `docs/` are
not migrations and are corrected instead.

**Spec edits are MODIFIED, not REMOVED+ADDED.** The previous change had to use REMOVED+ADDED because
it was deleting scenarios, and `openspec validate` requires a MODIFIED requirement to retain every
scenario name the main spec carries for it. Here no scenario disappears: the two affected
requirements keep their full scenario lists, and the scenario "The body columns are unpopulated on
dial_usage_log" keeps its name while its **THEN** changes from "the physical table still declares all
five columns" to the columns being absent. The name reads slightly loose for a column that no longer
exists — an insert still leaves no value for them, so it is not wrong — and the looseness is worth
avoiding a whole-requirement REMOVED+ADDED churn.

## Risks / Trade-offs

- [Risk] A local ClickHouse volume that applied the old `V11`/`V12` during the brief window they existed
  in the history fails startup on a version mismatch. → Mitigation: none needed in the repo — no
  deployed environment is in that state, and a local volume is disposable. The failure is a loud
  startup abort naming the version, not anything silent.
- [Risk] An operator deploys without reading the docs and loses pre-split content they wanted. →
  Mitigation: stated in the capability spec, the upgrade doc, the README bullet, this proposal, and
  as a two-line warning in the migration itself.
  It cannot be mitigated further without keeping the backfill, which is the thing being declined.
- [Trade-off] Local seed data no longer carries claims or headers at all, so the `json_extract_*`
  examples in `docs/sql-reference.md` are unexercised by seeded rows. Accepted: the seed script
  writes one table, and teaching it the payload grain is a separate change.

## Migration Plan

Deploy order is ordinary: the migration runs at startup ahead of `SchemaValidator`, and
`mapping/dial-usage-log.yaml` already stopped naming the five columns, so nothing needs to ship in
lockstep with it. With `clickhouse.migrations.enabled=false` an operator applies
`V11__drop_dial_usage_log_payload_columns.sql` by hand with a credential holding `ALTER DROP COLUMN`
on `dial_usage_log`, before starting the new build. Rollback is `ALTER TABLE dial_usage_log ADD
COLUMN` only if going back past the mapping cutover; the data does not come back either way.

## Open Questions

None. The two decisions that were open — whether to restore the backfill, and which version number
to take — were answered at the propose checkpoint: drop only, numbered `V11`.
