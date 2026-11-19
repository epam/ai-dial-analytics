## Why

`sync.sensitive-columns-enabled` is all-or-none: ingest writes every column the catalog flags `sensitive`, or none
of them. An operator who may keep request tags and JWT claims but must not keep raw prompts and responses has no
setting that fits. Today the only options are to store every body or to lose the claims and tags along with them.
The setting should name which sensitive columns ingest writes, and a list no longer fits a name ending in
`_ENABLED`.

## What Changes

- **BREAKING:** `sync.sensitive-columns-enabled` (`SYNC_SENSITIVE_COLUMNS_ENABLED`) is replaced by
  `sync.sensitive-columns` (`SYNC_SENSITIVE_COLUMNS`), a comma-separated list of the sensitive columns ingest writes:
  - `*`, the default, writes every sensitive column, and the catalog is not read for it. This is the old `true`.
  - `none`, or an empty value, writes none of them. This is the old `false`.
  - A list such as `request_tags,jwt_claims` writes only the named sensitive columns. Every other column the catalog
    flags `sensitive` on a target table is left out of new rows and takes its ClickHouse default, as the old `false`
    did.
  - An entry is a bare column name, which applies on every target table, or `table.column`, which applies on that
    table only.
- **BREAKING:** startup fails when the removed `SYNC_SENSITIVE_COLUMNS_ENABLED` is still set, whatever its value,
  and the error names the replacement. Ignoring it would turn an old `false` into the default `*`, so a deployment
  that set it would start writing raw content again after the upgrade.
- New startup failures, each naming the setting and the entry:
  - `*` or `none` combined with other entries;
  - an entry that matches no column the catalog flags `sensitive` on an ingest target table (a typo, or a
    non-sensitive column);
  - under `SYNC_REQUEST_BODY_MODE=delta`, a target that writes `request_body` but not `request_cache_control`.
    The delta stage moves cache markers out of the body into that column, so leaving it out would break rebuilds.
- The existing startup checks, the undefined target table and the excluded deduplication, grain, identity,
  ordering, partition, version or TTL column, apply to any setting other than `*`. They are checked only against
  the columns actually left out, so writing a sensitive key column is no longer refused.
- The request-body-mode "has no effect" warning, and the body-hash caveat, now refer to `request_body` being left
  out of ingest rather than to the switch being `false`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `data-sync`: "Sensitive columns can be excluded from ingest" becomes a per-column allowlist under the renamed
  setting. "The request body storage mode is configurable" gains the `request_cache_control` startup check and the
  reworded warning. "A large request body equal to an earlier one is stored once" gets the reworded re-ingest
  caveat.

## Impact

- Config: the new key `sync.sensitive-columns` / `SYNC_SENSITIVE_COLUMNS` (`List<String>`, default `*`). The old
  `sync.sensitive-columns-enabled` / `SYNC_SENSITIVE_COLUMNS_ENABLED` is removed from `application.yml` and fails
  startup when set.
- Code: `SyncProperties`, `MappingConfiguration`, `SensitiveColumnExclusion` (allowlist resolution and
  validation), `RequestDeltaStages` (warning condition, new `request_cache_control` check), and their tests
  (`SyncPropertiesTest`, `SensitiveColumnExclusionTest`, `RequestDeltaStagesTest`,
  `SensitiveColumnIngestIntegrationTest`, `SensitiveColumnsDisabledStartupIntegrationTest`).
- Docs: the `docs/configuration.md` row is renamed and rewritten, with a warning about empty values. The change ships
  as the 0.23.1 patch, so `docs/release-0.23-upgrade.md` gains a closing 0.23.1 section, leaving the 0.23.0
  notes as they are, with the mapping `true` → unset or `*`, `false` → `none`. The 0.24 note that names `SYNC_SENSITIVE_COLUMNS_ENABLED=false` is
  reworded to point at that section.
- No catalog or ClickHouse migration, no `/v1` endpoint change, no read-side masking change.
