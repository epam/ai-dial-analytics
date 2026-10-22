## 1. Settings

- [x] 1.1 Replace `InlineFilesMode` with `InlineFilesPolicy(boolean dropTruncated, long maxFileSize)` (`NO_LIMIT = Long.MAX_VALUE`, `rewrites()`, `defaults()`)
- [x] 1.2 `SyncProperties`: replace `inlineFilesMode` with `Boolean inlineFilesDropTruncated` (null → `true`) and `DataSize inlineFilesMaxSize` (null → no limit, negative → fail), plus an `inlineFilesPolicy()` accessor
- [x] 1.3 `application.yml`: replace `inline-files-mode` with `inline-files-drop-truncated: ${SYNC_INLINE_FILES_DROP_TRUNCATED:true}` and `inline-files-max-size: ${SYNC_INLINE_FILES_MAX_SIZE:}`

## 2. Stripping

- [x] 2.1 `InlineFileStripper.strip` takes the policy: skip when the body is not longer than the limit and needs no repair; remove complete files whose `end - payloadStart` exceeds the limit; handle the open file per the repair flag (cut at the quote, or drop its payload when over the limit)
- [x] 2.2 `InlineFileRewrite` and `MappingEngine`/`MappingConfiguration` take the policy instead of the mode; no rewrite is built when `rewrites()` is false
- [x] 2.3 Update Javadoc that names the old mode (`ColumnMapping`, `MappingFileLoader`, `MappingConfiguration`, `MappingEngine`)

## 3. Tests

- [x] 3.1 `SyncPropertiesTest`: defaults, `false`, `256KB`, a bare number as bytes, `0`, a negative value and a non-size fail startup, the old variable ignored
- [x] 3.2 `InlineFileStripperTest`: limit boundary (at and one over), body-not-longer-than-limit skip, half file with the repair off over and under the limit, old `DROP`/`DROP_TRUNCATED`/`KEEP` cases moved to the equivalent policies
- [x] 3.3 `MappingEngineTest` and `InlineFileIngestTest` moved to the policy

## 4. Docs

- [x] 4.1 `docs/configuration.md`: replace the `SYNC_INLINE_FILES_MODE` row with one row each for `SYNC_INLINE_FILES_DROP_TRUNCATED` and `SYNC_INLINE_FILES_MAX_SIZE`
- [x] 4.2 `docs/column-mapping.md`, `README.md`, and the comments of `mapping/dial-usage-log-payload.yaml` reworded to the two settings
- [x] 4.3 `docs/release-0.4-upgrade.md`: describe the two settings and note that `SYNC_INLINE_FILES_MODE` is gone and ignored if left set

## 5. Verify

- [x] 5.1 `openspec validate split-inline-files-flags --strict`
- [x] 5.2 `./gradlew clean build` passes
