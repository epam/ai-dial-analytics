## Context

See proposal.md - Why. `V10__create_dial_usage_log_payload.sql` and the mapping split
(`mapping/dial-usage-log.yaml` no longer declaring the five verbatim-content columns,
`mapping/dial-usage-log-payload.yaml` capturing them into `dial_usage_log_payload`) already shipped
in the `add-dial-usage-log-payload-table` change and are unaffected by this change. Only
`V11__backfill_dial_usage_log_payload.sql` (historical copy) and
`V12__drop_dial_usage_log_payload_columns.sql` (column drop) are being removed, along with the
tests, docs and spec text that exist solely to describe or pin their behavior.

## Goals / Non-Goals

- Goal: delete `V11`/`V12` and everything that is *only* about them, leaving the shipped payload
  split (`V10` + the mapping change) exactly as it is today.
- Goal: leave `openspec/specs/data-sync/spec.md` internally consistent — no requirement or
  scenario may still assert that `dial_usage_log` "no longer declares" the five columns, since
  without `V12` it still does.
- Non-goal: reverting the payload split itself. `dial_usage_log_payload` and its dual-write
  mapping keep working exactly as before; this change only removes the historical-copy step and
  the cleanup step that depended on it.
- Non-goal: adding a replacement cleanup path (e.g. a later `ALTER TABLE ... DROP COLUMN`
  revisited once the columns have naturally emptied via the 1-year `TTL`). Out of scope; can be a
  future change if the columns should be physically removed eventually.

## Decisions

**Keep `dial_usage_log`'s five legacy columns physically present, forever unpopulated by new
ingest, rather than reverting the mapping split or adding a new drop.** The scope is
explicit: delete both `V11` and `V12`, not just `V11`. Dropping the columns (`V12`) without first
copying the data they hold (`V11`) would silently delete whatever body/claims/header content still
exists in `dial_usage_log` for rows the backfill would have covered — that is exactly the data-loss
`V11` existed to prevent. Since both are being removed together, `dial_usage_log` simply keeps the
columns: new rows write nothing into them (the mapping already only writes to
`dial_usage_log_payload`), existing historical rows keep whatever they already have, and that
historical content eventually ages out under the table's own one-year `TTL` — no migration needed
to make that happen. This trades a small amount of permanently-idle schema (five columns that are
`NULL`/`{}` on every future row) for removing the OOM-prone startup migration entirely.

**Alternative considered and rejected: also revert the mapping split (re-declare the five columns
in `mapping/dial-usage-log.yaml`).** This would restore full duplication of body content between
`dial_usage_log` (1-year retention) and `dial_usage_log_payload` (90-day retention) forever, which
defeats the storage/PII-retention reason the split exists for in the first place (see the archived
`add-dial-usage-log-payload-table` design). Rejected: the scope is only the backfill and drop
migrations, not the split.

**Alternative considered and rejected: keep `V12` (drop the columns) but drop `V11` (skip the
backfill).** This accepts silent, permanent loss of the pre-split body/claims/header content for
every row `V11` would have covered. Rejected: out of scope (both migrations are being removed),
and materially riskier than the chosen option — the chosen option loses nothing, only leaves dead
columns in place.

**Spec edits are surgical in content, but REMOVED+ADDED in form.** Three main-spec requirements —
"Target table provisioning and schema", "Body columns are ingested into a separate retention-scoped
table", and "The payload table carries a shorter retention than its parent" — each mix
payload-split behavior (which stays, `V10`-backed) with backfill/drop-specific scenarios (which go).
`openspec validate` enforces that a MODIFIED requirement retain every scenario name the main spec
already has for it (archive replaces the whole block), so a scenario cannot be silently dropped
through MODIFIED. Each of the three is instead REMOVED and replaced by an ADDED requirement under a
new, closely-related name (e.g. "Target tables are provisioned by versioned ClickHouse migrations")
carrying identical content minus the scenario(s) that no longer apply — verified by running
`openspec validate` locally rather than discovering the constraint at archive time. "The existing
bodies are copied before the columns are dropped" is wholly about the copy-then-drop sequence, so
it is a plain REMOVED requirement with no replacement.

**Never edit `V10__create_dial_usage_log_payload.sql` or the Postgres `V38__split_payload_columns.sql`,
even their comments.** Both are already-shipped, already-applied migrations (`V10` is already
applied on existing deployments; `V38` shipped in the same archived change), and this
repo's own convention (stated in `V7`'s header comment) is that an applied migration's checksum is
fixed — a comment edit changes the file's bytes and therefore its Flyway checksum, which fails
validation on the next real deploy unless an operator runs `flyway repair`. Their headers describe
the original three-migration design and now read as stale once `V11`/`V12` are gone; that staleness
is accepted rather than risking a checksum break. `mapping/*.yaml`, `docs/heavy-columns.md` and
`README.md` are not migrations and are corrected instead.

## Risks / Trade-offs

- [Risk] A reader of `dial_usage_log`'s physical schema (e.g. via `DESCRIBE TABLE` or
  `INFORMATION_SCHEMA.COLUMNS`) sees five columns that are never populated and may assume they are
  live. → Mitigation: the mapping file's own comments already document that these columns moved to
  `dial_usage_log_payload`; this change adds a note that they are intentionally left in place
  rather than dropped, and the spec now says so explicitly instead of claiming they are gone.
- [Risk] Someone re-adds `V13__drop_dial_usage_log_payload_columns.sql` later without re-deriving
  the backfill-before-drop reasoning this change removes. → Mitigation: the REMOVED requirement's
  **Reason**/**Migration** text stays in `openspec/specs/archive/` history and is linked from the
  proposal; a future drop-only migration would need its own design covering the resulting data
  loss for un-backfilled history, which this change's proposal calls out explicitly.
- [Trade-off] `dial_usage_log` never sheds the storage cost of the five columns' definitions (though
  new rows cost nothing extra since the columns default to `NULL`/`{}`), and existing historical
  rows keep the full body/claims/header content until they individually age past the one-year
  `TTL`, rather than the 90-day payload retention applying to them. Accepted: this is strictly the
  status quo before `V11`/`V12` were ever proposed, and it is what "not doing the backfill" means.

## Migration Plan

Deleting `V11`/`V12` from the repository does not touch any environment that already ran them
(Flyway does not re-run or un-apply an applied migration). It only changes what a **fresh**
environment provisions, and what an operator applying the DDL out of band is told to do (`V10`
only). No rollback beyond a normal git revert is needed; there is no data movement in this change.

## Open Questions

None — the ambiguity (revert the whole split vs. just the backfill/drop) is resolved above.
