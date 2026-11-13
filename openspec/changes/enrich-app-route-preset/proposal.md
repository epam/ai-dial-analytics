## Why

An `llm` transform can only reach a deployment through `POST /openai/deployments/{model}/chat/completions`.
A DIAL application that exposes its function as a custom **route**
(`POST /v1/deployments/{id}/route/{path}`) taking and returning plain JSON has no way in: its owner
has to add and maintain a chat-completions shim, with the output wrapped as a JSON string in
`choices[0].message.content`, just to be called by an enrichment pipeline. The app's real contract is
then served twice, and the shim's only purpose is to satisfy the runner.

## What Changes

- `transform.preset` gains a second value, **`dial_app_route`**, beside `chat_completion`. Under it the
  Enrichment Runner POSTs the rendered body to `/v1/deployments/{model}/route/{route}` and reads the
  whole response body as the transform's output. There is no chat envelope, no `response_format`, no
  `api-version` and no conversational repair retry. The output is still validated against the
  compiled `response_schema`.
- A new authored member, **`transform.route`**, is the path under the deployment's `route/` prefix.
  It is required for `dial_app_route` and refused for `chat_completion` and for `sql`. Its grammar is
  checked at write time (relative, no `.`/`..` segment, no query or fragment, bounded length).
- Each output of a `dial_app_route` transform gains an optional **`source`**: a small JSONata expression,
  usually a dotted path such as `intent_counts.check`, that reads the output's value from the route's
  response body. An output without one takes the response member of its own name. An application keeps its
  own response contract (nested objects, extra members such as usage), and every output is mapped by
  construction. `prose` and `values` keep their meaning, and a route output's `jsonata` is refused.
- For a `dial_app_route` transform the composed schema takes nullability from each column: an output bound to
  a nullable column may be null or absent. The strict-mode rule that requires every property applies only to
  model calls.
- A new instance setting, **`app-routes`** (`GET`/`PUT /v1/settings/app-routes`), is the allowlist of
  `(deployment, route)` pairs a pipeline may call. When the setting is absent, no route is allowed.
  - Arming a `dial_app_route` pipeline whose pair is not listed is refused with 422
    `pipeline_validation_failed`.
  - A `PUT` that would drop a pair still used by an **enabled** pipeline is refused with 409
    `app_route_in_use`, naming the pipelines.
  - So every enabled route pipeline is always allowed, and admins can permit a new route without a
    redeploy.
- The request template gains a **raw-JSON placeholder form, `{{name:json}}`**, for both presets and both
  grains. It renders the bound value as a JSON literal in value position, unescaped. The existing
  `{{name}}` form still escapes the value into a JSON string. A route body can then carry members as
  structured JSON, for example `{"members": {{members:json}}}`.
- The compiled feed carries `transform.route`, and `preset` may now be `dial_app_route`.
  **BREAKING (runner contract):** the runner contract version goes from `4` to `5`. A runner that
  cannot decode the new preset is refused by the exact-match check instead of failing on one entry.
  ADAS and the Enrichment Runner ship together.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `enrichment-registry`:
  - the transform shape now has `preset: dial_app_route`, `transform.route` and a per-output `source`;
  - the composed schema follows column nullability for a route transform;
  - an arm-gate rule for the route allowlist;
  - the `:json` placeholder suffix in the template placeholder rule.
- `instance-settings`:
  - the closed setting-name set gains `app-routes`;
  - its body, validation, API, in-use guard and authorization.
- `enrichment-runtime`: the runner contract version this build serves is `5`.
- `catalog-export-import`: instance settings do not travel in a bundle, so an imported route pipeline
  whose pair the target does not allow reports not armable, naming `app-routes`.

## Impact

- **API:**
  - `POST`/`PATCH /v1/pipelines`: new `transform.route`, new `preset` value.
  - New `GET`/`PUT /v1/settings/app-routes`.
  - The compiled feed echoes `transform.route`.
  - New error code `app_route_in_use` (409).
- **Code:**
  - `TransformPreset`, `EnrichSpec.Transform`, the pipeline request and response DTOs, `PipelineMapper`;
  - `EnrichSpecValidator` (shape and arm gate), `TemplatePlaceholderRule` (`:json` suffix);
  - `service/setting/` (new setting, validator, in-use guard), `SettingsController`, `DefaultExceptionHandler`;
  - `RunnerContract.VERSION_VALUE` and `RunnerContractShapeTest`.
- **Storage:** one Flyway migration widens the `instance_setting.name` CHECK constraint to admit `app-routes`.
  The transform lives in the pipeline's JSON `spec` and needs no schema change.
- **Catalog export/import:** pipelines carry `transform.route` unchanged and are imported disabled. The
  allowlist is instance configuration and does not travel, so arming an imported route pipeline needs
  the pair allowed on the target instance first.
- **Paired change:** the Enrichment Runner implements the call path under the same contract version.
  The admin UI and the MCP tools follow separately.
