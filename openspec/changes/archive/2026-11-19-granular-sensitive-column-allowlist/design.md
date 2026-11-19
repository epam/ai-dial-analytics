## Context

The current switch, `sync.sensitive-columns-enabled`, is a `Boolean` on `SyncProperties`, defaulting to `true`.
Under `false`, `MappingConfiguration.syncTargets` calls `SensitiveColumnExclusion.resolve`. That method reads the
catalog in two batched lookups, fails on an undefined target table or on a sensitive key column, and returns every
sensitive physical column per table. `SensitiveColumnExclusion.without` then drops those columns from each
target's `MappingConfig` before the `MappingEngine` is compiled. `RequestDeltaStages` qualifies a target for
request-delta processing only when the excluded mapping still maps `request_body`, `project_id` and `user_hash`. It
also logs a dedicated warning when nothing qualifies under the `false` switch.

`request_cache_control` is not a mapped column. The delta stage writes it beyond the mapping, together with the
chain columns. Under the old switch, `request_body` and `request_cache_control` were always excluded together, so
the stage never wrote a column the switch meant to leave out. A per-column list breaks that coupling.

## Goals / Non-Goals

**Goals:**

- Let an operator name which sensitive columns ingest writes, per column and optionally per table.
- Keep the zero-cost default: with `*`, the catalog is not read.
- Make a misconfiguration fail at startup and never fail open: a removed variable, a typo, a mixed keyword, or an
  allowlist that would leak `request_cache_control`.

**Non-Goals:**

- Read-side masking, the catalog's `sensitive` flags, or role-based column visibility. They are unchanged.
- A denylist form (`!request_body`). An allowlist is the safe direction for a privacy setting: a sensitive column
  added later stays out until someone names it.
- Rewriting rows already stored.

## Decisions

**Rename to `sync.sensitive-columns` / `SYNC_SENSITIVE_COLUMNS`.** A list under a name ending in `_ENABLED` reads as a
boolean. `SYNC_SENSITIVE_COLUMNS_ENABLED=request_tags` looks like a mistake, and the maintainers would rather pay a
one-line migration than carry a misleading name. *Rejected:* keeping the name with `true`/`false` as aliases. This
needs no migration, but the name stays misleading for good. *Rejected:* `SYNC_WRITTEN_SENSITIVE_COLUMNS`, which is
more precise but longer, and `*`/`none` already make the meaning plain.

**The old variable fails startup; it is not honoured as an alias.** If it were ignored, `SYNC_SENSITIVE_COLUMNS_ENABLED=false`
would silently fall back to the default `*`, and the payload table would start storing raw prompts again. That is
the failure this setting exists to prevent. Honouring it as an alias means keeping two keys, a precedence rule and
a deprecation path, all for a variable that shipped one release ago. A loud startup failure that names the
replacement costs each affected deployment one edit. Implementation: `MappingConfiguration.syncTargets` first asks
Spring's `Binder` whether `sync.sensitive-columns-enabled` is bound, which covers the environment variable through
relaxed binding, and throws when it is. *Rejected:* keeping a `sensitiveColumnsEnabled` component on `SyncProperties`
only to detect it, which would grow the record and every one of its construction sites in the tests for a value that
is never used. *Rejected:* a `@DeprecatedConfigurationProperty`, which only produces metadata and a warning.

**Keywords `*` and `none`; an empty value also means none.** Spring binds a comma-separated string to
`List<String>`, which matches how the other list-valued variables are configured, and binds an empty value to an
empty list, not to unset, so it cannot fall back to the default. The JSON-style `["*"]` from the
original idea does not bind from an environment variable. An empty value is accepted as none, but `none` is the
documented spelling. Deployment tooling that prunes empty environment variables would quietly restore `*`.
`docs/configuration.md` already warns about this for `SYNC_FILE_NAME_PREFIX_PATTERN`, and the new row gets the
same warning. Keywords mixed with column entries fail startup instead of picking one meaning.

**Bare or `table.column` entries, matched case-sensitively.** In the seeded catalog every sensitive column name is
unique to its table, so bare names cover the shipped targets. A custom target that reuses a name such as
`request_body` on another table can be qualified. ClickHouse identifiers are case-sensitive and `source_name` is
stored as written, so entries are matched exactly. That gives one case rule, the physical one, for both parts.
Table names are matched against `sync.targets[*].table`.

**An entry that names no column fails startup; one that names only non-sensitive columns warns.** An allowlist
fails closed, so a typo (`request_tag`) would quietly drop a column the operator meant to keep. Startup failure
surfaces it. *Rejected for the typo case:* a warning, which on a privacy setting is the kind of message nobody reads.
An entry naming an existing column that the catalog does not flag `sensitive` is different: ingest writes that column
anyway, so the entry has no effect and nothing is lost. The flag can be cleared by a migration, by
`PATCH /v1/tables/{name}/schema` or by a catalog import, none of which is the operator's; failing there would let a
table owner stop the next startup while protecting nothing, so it is a `WARN`.

**Key-column and undefined-table checks apply to every non-`*` setting, against the excluded set only.** These
checks protect against leaving out a column ingest cannot do without. A sensitive key column that the list names is
written, so it is safe. The undefined-table check stays global: without a catalog definition the service cannot
tell which of the table's columns are sensitive, and that holds whatever the list names.

**Under `delta`, `request_body` without `request_cache_control` fails startup.** The stage strips `cache_control`
markers from history elements and stores them in `request_cache_control`, which rebuild reapplies. There are three
alternatives. Writing the column anyway would store a column the operator excluded. Dropping the markers would make
rebuilt bodies differ from the received ones, breaking rebuild equivalence. Leaving the markers in the body would
change the stored delta shape and the hashes. A startup failure is the only option that stays both private and
correct. `whole` and `whole-with-path` keep `request_cache_control` at `NULL`, so the check does not apply there.
The resolved excluded set is carried on `SyncTarget` (`excludedColumns`, with a three-argument constructor kept for
targets that leave nothing out), so `RequestDeltaStages` can run this check and decide the warning without re-reading
the catalog.

**`SensitiveColumnExclusion.resolve` takes the parsed allowlist.** A small value type, `SensitiveColumnPolicy`,
carries `ALL`, `NONE`, or the ordered column entries, each bare or qualified. It is parsed
and validated (keywords, blanks, shape) in `SyncProperties` at binding time. Resolution against the catalog stays
in `resolve`, where the unknown-entry check runs because it needs the catalog's flags. `MappingConfiguration`
skips the catalog entirely for `ALL`.

## Risks / Trade-offs

- [A deployment that set `SYNC_SENSITIVE_COLUMNS_ENABLED` fails to start after the upgrade] → The error names
  `SYNC_SENSITIVE_COLUMNS` and the value mapping, and the 0.23 upgrade notes' 0.23.1 section (§8) lists it with the value mapping.
- [An empty value is pruned by tooling and the default `*` takes over] → `none` is the documented spelling, and the
  configuration row says so.
- [Rolling back to a build without this change] → An older build does not know `SYNC_SENSITIVE_COLUMNS` and ignores
  it, so it writes every sensitive column. Its own `SYNC_SENSITIVE_COLUMNS_ENABLED` has to be set again for the
  rollback. The upgrade notes state this.
- [A migration, the table API or a catalog import unflags a column that the list names] → Startup logs a warning
  naming the entry and goes on; the column is written, as the entry asked.
- [Unqualified names spread to custom targets] → A bare entry also writes a same-named sensitive column on a custom
  target. The startup `INFO` line lists written and excluded columns per table, and `table.column` narrows the
  entry.

## Migration Plan

1. Replace `SYNC_SENSITIVE_COLUMNS_ENABLED=true` with nothing (the default) or `SYNC_SENSITIVE_COLUMNS=*`.
2. Replace `SYNC_SENSITIVE_COLUMNS_ENABLED=false` with `SYNC_SENSITIVE_COLUMNS=none`.
3. Optionally narrow to a list, for example `SYNC_SENSITIVE_COLUMNS=request_tags,jwt_claims`.
4. Rollback: restore `SYNC_SENSITIVE_COLUMNS_ENABLED` for the older build.

## Open Questions

- None blocking. If operators ask for "everything except bodies", a denylist form can be added later without
  changing the allowlist semantics.
