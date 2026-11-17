## Context

See proposal.md for why. `permissions` is computed per table by the same evaluator enforcement uses. `TABLE_GRANT`
exists only as a global privilege. The access API's `PUT` builds `ineffective_grants` from the stored entry, the
table's other entries and the role mapping, none of which belong to the request itself, so the same report can be
built at any time from stored state.

## Goals / Non-Goals

**Goals:**
- Let a client decide before any request whether to show access-editing controls.
- Let a client show the backend's warnings on every load, not only after a save, including a standing warning for a
  table every caller reads in full.

**Non-Goals:**
- Changing the four existing reasons or their rules.
- Warnings on `GET /v1/tables/{name}`. That response is not gated on `TABLE_GRANT`, and a warning names roles and
  their privileges.
- Naming the tables pipelines build from a table. The maintainers judged it a hint the client can live without for
  now.

## Decisions

**`grant` lives in `permissions`, not on a caller endpoint.** Clients already read `permissions` to decide which
controls to show for a table, so the flag lands where they look. A separate "what may I do" endpoint would be a new
surface for one boolean. The flag is the same on every table today. If `TABLE_GRANT` ever becomes grantable per table,
the field already has the right shape. It is computed from the caller's global privileges in the same place as the
other flags, so the `none`-mode and `FULL_ADMIN` paths stay in one method.

**The read reuses the `PUT`'s report, run per stored entry.** One function decides every reason, so a warning shown
after a save and after a reload cannot disagree. The whole-configuration read runs it once per entry and concatenates
the results in role order. Rejected alternative: a separate "warnings" endpoint. It would be one more call per page
load for data the reads can carry.

**The report reflects state at the read, not at the save.** A `lifts_row_policies` warning on `*` can become true when
another role later gains a row policy, and false when that policy is removed. Computing on read is what makes the
warning appear on the entry it concerns, which the `PUT` path cannot do because it reports only on the entry it
writes.

**`everyone_reads_all_rows` is a new reason in the same array, not a new field.** `lifts_row_policies` fires only
when another role carries a row policy, so a table whose only policy was the one on `*`, once that policy is
removed, is read in full by every caller with nothing reported. An access editor needs a warning on exactly that
state.
The reason fires on `read` for `*` with `rows` `null`, whatever `columns`, because a column list does not narrow
rows. It is reported beside `lifts_row_policies` rather than instead of it, so one code identifies the state and the
other still explains which policies stop working. Enrichments are excluded: their rows are granted by the source, so
state an earlier build left there opens nothing. Rejected alternatives: a separate `warnings` array, which would be a
second list with the same item shape and the same consumers; and leaving the check to clients, which would put a
security-relevant rule in every client. The cost is that the array's name no longer fits every item, and that a
table meant to be public carries the warning permanently. `lifts_row_policies` already reports a grant that is
effective rather than ineffective, so the name was already loose, and a client can let an operator dismiss the
warning per table.

**Both reads carry it, the one-role read for that entry only.** An editor that loads one role's entry sees that
entry's warnings without fetching the whole configuration.

## Risks / Trade-offs

- [The whole-configuration read gets slower on a table with many entries] → most of each entry's check is in-memory
  work over the loaded definition and the role mapping, but `sensitive_without_verb` looks up the table's enrichments
  each time it is judged. The read loads them, and decodes the row and column policies, once per request and passes
  them to the report, so the cost stays one query whatever the number of entries.
- [Some clients deserialize strictly and fail on an unknown field] → `permissions` has grown before and the access
  responses are new in this release; clients of this API already tolerate added fields.
- [State left by an earlier build, such as read-side entries on an enrichment or `*` with `write`, produces a warning
  that reads oddly, or none where one would help] → the report applies the same rules as a `PUT` and the spec says so.
  `V56` clears an enrichment's read-side lists, so only an older pod writing during a rolling upgrade can leave them;
  a `*` with `write` or `modify` is named in the startup log.

**The order of items is fixed.** Privilege order, then a fixed order of reasons for one privilege, so a client and
the tests can rely on positions. The new check sits with the two `lifts_*` checks, before the point where the
current code stops judging a privilege that lifted a policy.

## Migration Plan

No catalog schema change, no migration and no configuration key. Rollback is a redeploy of the previous build; the
fields simply disappear.
