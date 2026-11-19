## 1. Schema composition

- [x] 1.1 In `ResponseSchemaComposer`, make the route flag preset-meaningful (rename `nullableFromColumn` or pass the `TransformPreset`) and update `EnrichResolver.composeSchema` and the Javadoc accordingly
- [x] 1.2 Map `OBJECT` → `{"type": "object"}` and `MAP` → `{"type": "object", "additionalProperties": {"type": "string"}}` for the route preset; both stay required, because the catalog never lets either column be nullable
- [x] 1.3 Keep refusing `OBJECT`/`MAP` for `chat_completion` with a message naming the output, column, type and the `chat_completion` preset, and pointing at `dial_app_route` or a scalar column; leave the generic refusal for any other unmapped type
- [x] 1.4 Run composition in the arming gate: `ResponseSchemaComposer.composeFor` holds the when-to-compose rule, `EnrichResolver` and `EnrichSpecValidator.validate` (llm branch) both call it, so a composer refusal is a 422 on every gated request

## 2. Tests

- [x] 2.1 `ResponseSchemaComposerTest`: route + `object` composes `{"type":"object"}` with no `properties` and is required; route + `map` composes the string-valued object and is required; `chat_completion` + `object`/`map` still throws with the new message (replace the current loop over both types)
- [x] 2.2 Pipeline registration test: `POST /v1/pipelines` with a `dial_app_route` output bound to an `object` column is accepted and `view=compiled` serves the open object property; the same with `chat_completion` answers 422 `pipeline_validation_failed` when armed, and a draft of it is stored while enabling it is refused
- [x] 2.3 Write-path test: writing a row with a nested object value (e.g. `{"fix": 2, "create": 1}`) to an enrichment's `object` column stores it intact, and `json_extract_int(col, 'plan')` reads `0` for the absent key (add only if no existing test already pins this)
- [x] 2.4 Confirm every existing composer and pipeline test still passes unchanged, which shows that previously accepted declarations compose the same schemas

## 3. Spec and docs

- [x] 3.1 Confirm the delta in `specs/enrichment-registry/spec.md` matches the shipped behaviour and error text
- [x] 3.2 Check `docs/*.md` for any statement that route outputs cannot bind `object`/`map` columns, and update it to agree with the spec (no `docs/configuration.md` change: no property is added); the upgrade note is `docs/release-0.24-upgrade.md` §18

## 4. Verification

- [x] 4.1 `./gradlew clean build`
