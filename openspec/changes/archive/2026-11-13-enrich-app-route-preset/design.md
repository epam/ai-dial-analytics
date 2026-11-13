## Context

An `llm` transform's declaration names a DIAL deployment (`model`) and a request-shaping `preset`, and
`chat_completion` is the only preset. The Enrichment Runner turns the declaration into
`POST /openai/deployments/{model}/chat/completions` with `response_format: json_schema`. It reads the
output from `choices[0].message.content`, repairs a schema mismatch by appending a corrective message,
and counts spend in `usage.total_tokens`.

DIAL Core serves applications on that same chat-completions path, so an application can already be the
`model`, but only by speaking chat completions. An application whose function is a custom route
(`/v1/deployments/{id}/route/{path}`) taking and returning plain JSON has to grow a chat shim just to be
called. Two more limits make the shim awkward:

- `TemplateRenderer` JSON-escapes every placeholder into a string. A group's members therefore always
  reach the callee as one escaped string inside `messages[0].content`, never as JSON.
- Application calls do more work than one model call, so they tend to run longer.

This change is the ADAS half. The runner half lives in the Enrichment Runner repository, under the same
contract version. The admin UI and the MCP tools follow separately.

## Goals / Non-Goals

**Goals:**

- Let an `llm` transform call an application route with a JSON body and read a JSON body back.
- Keep calling a route an explicit, administrator-granted permission that can be changed without a
  redeploy.
- Let a template place a bound value in JSON value position, unescaped.
- Keep `chat_completion` declarations byte-for-byte unchanged on the wire.

**Non-Goals:**

- Other HTTP methods, query strings or headers on the route call. It is always `POST`, with a JSON body.
- Token or cost accounting for route calls. That is the runner's concern, and it counts calls.
- Discovering an application's routes from DIAL Core.

## Decisions

### A second `preset` value, not a new `transform.type` or a URL member

`preset` already exists to answer "how is this deployment called", and `type` answers "what grammar do
the outputs use" (`llm` prose against `sql` expressions). A route call has the `llm` output grammar, a
template, `inputs`, a composed `response_schema` and a runner that calls DIAL, so it belongs under
`type: llm` with a different preset.

- *Alternative: a free-form `transform.url`.* Rejected. It would let a declaration name any path on DIAL
  Core, admin and file APIs included, and push path safety onto every consumer.
- *Alternative: `type: app`.* Rejected. It duplicates every `llm` rule (template, inputs, placeholder
  rule, schema composition) under a second discriminator.

`model` keeps its name and now means "a DIAL deployment, model or application". Renaming it to
`deployment` would churn ADAS, the runner, catalog bundles, the UI and the MCP tools for no change in
behavior.

### `transform.route` is a shape-checked relative path

The grammar is one or more `[A-Za-z0-9._~-]+` segments, no `.`/`..`, no `?`/`#`/`%`, and at most 256
characters. It is checked where the transform's other shape checks run, which is when the pipeline is armed;
a disabled draft is stored as written. A disabled pipeline's route is never called, and the runner
re-checks the grammar at admission. Refusing `%` outright means no encoded traversal and no
decode-then-check ordering bug. The grammar lives in one class, used by both the declaration validator
and the `app-routes` setting validator.

### The allowlist is an instance setting, enforced at the two writes that could break it

The allowlist is `app-routes` under `/v1/settings`, with a `routes: [{deployment, route}]` body, governed
by `SETTINGS_WRITE` and audited as a `Setting` activity. It was chosen over a runner environment
variable so an administrator can allow a new route without a redeploy.

The invariant is that every **enabled** route pipeline's pair is listed. It is kept by two checks:

1. **Arm gate** (`EnrichSpecValidator`, gated requests only): refuse 422 when the pair is not listed.
2. **Setting write**: refuse 409 `app_route_in_use` when the new body drops a pair an enabled pipeline
   declares.

Because the invariant holds, nothing downstream re-checks it:

- the compiled feed carries no "allowed" flag;
- the data plane does not refuse scans;
- the runner knows nothing about the setting.

Alternatives rejected:

- *Re-check on the data plane.* That would refuse a running pipeline's scan, which the runner treats as
  a permanent 4xx. It also contradicts the gate's rule that the data plane does not re-run the gate.
- *Expose an `allowed` flag on the feed.* The runner syncs incrementally by `updated_since` and
  `generation`, and a setting write moves neither, so the flag would go stale. Bumping every affected
  pipeline's `generation` on a setting write was also considered. It is more machinery than a 409, and
  it silently stops a pipeline the operator may not have meant to stop.
- *Runner environment variable.* It needs a redeploy per route (the maintainers chose flexibility).

The setting is not a separate trust boundary. Today `SETTINGS_WRITE` and pipeline authoring both fall to
`FULL_ADMIN`. Its value is that it is deliberate, instance-wide and audited, and it would become a
separation the day those privileges split.

**Concurrency.** The arm check and the setting guard each read the other's table. Both writes take one
PostgreSQL transaction-scoped advisory lock keyed on the `app-routes` setting name:

- the setting `PUT` takes it always;
- a pipeline write takes it only when the pipeline will be armed with `preset: dial_app_route`.

A pipeline write that is not a route arm does not contend. A catalog import preview or apply arms each
imported pipeline inside its validation pass, whose transaction is always rolled back. Once it reaches a route
pipeline it holds the lock to the end of that pass, so a concurrent `PUT /v1/settings/app-routes` waits for
the pass rather than racing it. Two concurrent `PUT`s are last-writer-wins, as for `sessions-source`: the lock
orders them and the guard checks each against the pipelines as they stand when it runs.

The lock is PostgreSQL's two-key form, keyed on `hashtext('instance_setting')` and `hashtext('app-routes')`.
PostgreSQL keeps the two-key space apart from the single-key one the enrichment relayout locks use, so a hash
collision between the two cannot make them wait on each other.

### Each route output reads its value with its own `source`

An application's route contract is its own. A route may answer with nested objects and extra members, for
example a usage block, while the composed schema is flat. Each output of a route transform therefore carries an
optional `source`, a JSONata expression over the route's response body, and defaults to the member of its own
name. The runner assembles one object from the outputs' values and validates that object, so extra members of
the response never reach validation.

- *Alternative: one transform-level `response_jsonata` reshaping the whole response.* Rejected. Mapping a
  response with dozens of fields becomes one long script that is hard to author in a form and hard to check,
  and nothing guarantees it produces every output. Per-output sources map each output by construction, are
  usually a dotted path, and let a parse failure or a bad value name the one output it belongs to.
- *Alternative: relax `additionalProperties` for route responses.* Rejected. It fixes extra members but not
  nesting, and it weakens the one check that catches a route answering the wrong shape.
- *Alternative: reuse the output's `jsonata`.* Rejected. On a chat output `jsonata` runs after validation over
  a response that already matches the schema; giving it a pre-validation meaning on one preset would make one
  member mean two things. Instead `jsonata` is refused on a route output, so each output has exactly one
  transform.
- `prose` stays on route outputs. It is the property's description in the composed schema, which the feed
  serves, so an application that is given the schema can use it.

### A route schema follows column nullability

A model call composes every property as required because strict mode demands it, and spells an optional field
as a sentinel plus a mapping to NULL. A route call sends no `response_format`, so that constraint is absent, and
a sentinel would only push an application's missing field through a second transform. For a route transform,
the composer leaves an output bound to a nullable column out of `required` and lets its property accept
`null`. A field the application omits is written as NULL where the column allows it, and refused where it does
not.

### `{{name:json}}` raw-JSON placeholders

The suffix is explicit, chosen over inferring "value position" from where the placeholder sits in the
template. Inference would need a JSON-aware scanner over a template that is not valid JSON before
rendering, in two repositories that must agree byte for byte. ADAS validates the name before the suffix
exactly as today, and refuses any other suffix so a typo is not left in the prompt literally. The
`TemplatePlaceholderRule` pattern gains an optional `(:json)?` group. The runner's renderer gains the
matching branch.

A bound value that is itself JSON text, such as a `request_body` string column, still renders as a JSON
string under `:json`. Parsing it is a binding concern: a JSONata input such as `$eval(...)`, where the
expression catalog allows it. This change does not cover it.

### Runner contract version 4 → 5

An older runner cannot deserialize `preset: dial_app_route`. Under the exact-match contract that is
correct: the feed is refused as a whole at the first sync after a skewed deploy, instead of failing
per entry. `RunnerContractShapeTest` already pins the projection's shape and fails the build until the
constant is raised.

## Risks / Trade-offs

- **A route call sends source data, possibly sensitive columns, to an application.**
  → It is the same exposure as a chat call to that application today. The allowlist makes it an explicit
  grant, and the existing sensitive-column entitlement still gates what the pipeline may read.
- **An administrator cannot revoke a route while a pipeline is enabled on it.** The 409 forces "disable
  first".
  → This is intended: revocation then visibly stops the pipeline through its own run control. The 409
  message names each pipeline so the step is obvious.
- **Imported route pipelines cannot be armed until the target instance allows the pair.** The allowlist is
  instance configuration and does not travel in a catalog bundle.
  → Pipelines are imported disabled already, and the arm refusal names the setting.
- **A route answering with more than the schema's properties fails validation.** The composed schema is
  strict, with `additionalProperties: false`.
  → The runner validates the object assembled from the outputs' sources, not the response itself, so extra
  response members never reach validation. Nested members are reached with a `source`.

## Migration Plan

- One Flyway migration widens the `instance_setting.name` CHECK constraint to admit `app-routes`. `transform`
  is stored in the pipeline's JSON `spec`, so it needs no schema change.
- Deploy ADAS and the Enrichment Runner together, because both carry contract `5`. During skew the runner
  refuses the feed and enrichment pauses; no wrong call is made.
- Existing `chat_completion` pipelines are unaffected, since their wire shape has no `route` member.
- To move an application off a chat shim:
  1. `PUT /v1/settings/app-routes` with the pair.
  2. Disable the pipeline.
  3. Patch `preset`, `route` and `request_template`.
  4. Enable it.
- Rollback: disable every `dial_app_route` pipeline, then roll both services back together.

## Open Questions

- None for ADAS. The route read timeout is decided in the runner change.
