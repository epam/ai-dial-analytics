## Why

`sync.inline-files-mode` bundles two unrelated decisions into one value: whether to repair a body DIAL Core
cut inside a file, and whether to keep the complete files. The only lossy choice it offers, `drop`, removes
every file, so an operator who wants to shed multi-megabyte images and PDFs but keep small screenshots has no
setting for it.

## What Changes

- **BREAKING**: remove `sync.inline-files-mode` (`SYNC_INLINE_FILES_MODE`) and its `keep`, `drop-truncated`
  and `drop` values, with no alias. A left-over variable is ignored like any unknown variable, so a
  deployment that set `drop` falls back to the defaults below (complete files kept).
- Add `sync.inline-files-drop-truncated` (`SYNC_INLINE_FILES_DROP_TRUNCATED`), a boolean, default `true`:
  a body DIAL Core cut at 4 MiB is cut back to before the inline file the cut landed in. This is what
  `drop-truncated` did.
- Add `sync.inline-files-max-size` (`SYNC_INLINE_FILES_MAX_SIZE`), a data size such as `256KB` or `1MB`
  (a bare number is bytes), unset by default. An inline file whose stored payload is longer than the
  limit has its payload removed; a file at or under it is kept. `0` removes every file; unset removes
  none; a negative value fails startup.
- The size is that of the payload text as it stands in the body (the base64 between the quotes, after a
  data URL's `data:...;base64,` prefix), not the decoded file, which is about three quarters of it.
- The two settings combine independently. With the truncated repair off, the half file at the cut is
  measured against the limit like any other file. The defaults (`true`, unset) behave exactly like the
  previous default `drop-truncated`; `false` with unset is the old `keep`, `true` with `0` the old `drop`.
- The four inline-file counters stay as they are, registered whenever either setting rewrites bodies.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `data-sync`: the inline-files requirement replaces the three-valued mode with the two settings and adds
  the size limit; the requirements that refer to the mode (assembled response, allowlist, observability,
  column mapping, body columns) are reworded to the new settings.

## Impact

- Config keys: `sync.inline-files-mode` removed; `sync.inline-files-drop-truncated` and
  `sync.inline-files-max-size` added (`application.yml`, `docs/configuration.md`).
- Code: `SyncProperties`, `MappingConfiguration`, `MappingEngine`, and the `sync.inline` package
  (`InlineFilesMode` replaced, `InlineFileRewrite`, `InlineFileStripper`), with their tests.
- Docs: `docs/configuration.md`, `docs/column-mapping.md`, `docs/release-0.4-upgrade.md`, `README.md`, and
  the comments of `mapping/dial-usage-log-payload.yaml`.
- No `/v1` endpoint, catalog schema or migration changes. Stored rows change only for deployments that set
  a limit; ingesting a record again under different settings replaces its row with the new result.
