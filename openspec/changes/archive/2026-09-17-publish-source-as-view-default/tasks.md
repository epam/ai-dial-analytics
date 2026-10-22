## 1. Pin the published contract with a test first

- [x] 1.1 Add a test that reads the live OpenAPI document (`GET /v3/api-docs`) and asserts, for both
      `GET /v1/pipelines` and `GET /v1/pipelines/{name}`, that the `view` parameter publishes
      `schema.default == "source"` **and** still publishes both accepted values in its `enum`. Assert the
      `enum` is present explicitly — the design's rejected alternative fails by dropping it, so a test
      that only checks the default would pass a broken document.
- [x] 1.2 In the same test, assert the `kind` parameter on `GET /v1/pipelines` publishes **no** `default`
      key and that `external` is not the first or middle member of its `enum`.
- [x] 1.3 Confirm 1.1 and 1.2 **fail** against the current code before changing anything. This is the
      assertion whose absence let the defect ship; a test that passes before the fix proves nothing.

## 2. Publish the default

- [x] 2.1 Reorder `web/dto/pipeline/PipelineView.java` so `SOURCE` is declared before `COMPILED`, keeping
      both javadoc blocks with the value they describe.
- [x] 2.2 In `web/controller/pipeline/PipelineController.java`, give both `view` parameters
      `defaultValue = "source"` on their `@RequestParam` and change both `@Parameter` examples from
      `compiled` to `source`. Leave the descriptions' prose default claim in place — it now agrees with
      the document.
- [x] 2.3 Keep `viewOf` (`:395-397`) as written and note in its javadoc that the HTTP path no longer
      reaches its `null` branch, so the next reader does not delete it as dead code.
- [x] 2.4 Run 1.1. If `defaultValue` did not produce a `default` key, fall back to
      `@Parameter(schema = @Schema(defaultValue = "source"))` per the design, and re-run 1.1 to confirm
      the `enum` array survived.

## 3. Stop advertising the unimplemented kind first

- [x] 3.1 Reorder `service/pipeline/PipelineKind.java` so `EXTERNAL` is declared last (`AGGREGATE`,
      `ENRICH`, `EXTERNAL`), leaving its `implemented` flag and `code` untouched. Add no `defaultValue` to
      the `kind` parameter.
- [x] 3.2 Run 1.2.

## 4. Confirm nothing else moved

- [x] 4.1 Run the existing `view`/`kind` behaviour tests unchanged — `PipelineControllerTest`
      (`:105`, `:120`, `:132`, `:153`, `:183`, `:200`, `:214`) and
      `integration/CompiledProjectionIntegrationTest` (notably `:776`, which asserts the refusal message
      names both accepted values without asserting their order). None should need editing; if one does,
      the reorder was not as safe as the design claims and that needs explaining, not patching.
- [x] 4.2 Verify by hand that a listing with **no** `view` still answers 200 with the authored projection,
      that `view=compiled` without `kind=enrich` still answers 400 `bad_request`, and that a compiled read
      of an `aggregate` pipeline still answers 422 `pipeline_validation_failed`.
- [x] 4.3 Update `docs/sql-reference.md` / `README.md` only if either names the `view` default; no
      configuration property is added or renamed by this change, so `docs/configuration.md` gains nothing.

## 5. Verification

- [x] 5.1 Record in this change that `adas-mcp`'s bundled snapshot
      (`src/adas_mcp/swagger/adas-openapi.json`) is now stale by one more field and must be regenerated in
      that repo, with `--sort-keys`, after this ships.
- [x] 5.2 Run `./gradlew clean build` and confirm it passes (checkstyle, `-Werror`, and the full test
      suite included). Docker must be available for the integration tests.
