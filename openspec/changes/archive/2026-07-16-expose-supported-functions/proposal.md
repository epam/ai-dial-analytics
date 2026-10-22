## Why

Clients composing structured queries can discover entities and their flat field schemas
(`GET /v1/queries/entities`, `GET /v1/queries/entities/schema/{name}`), but the closed function
catalog (`date_bin`, `width_bucket`, `percentile_cont`, aggregates, …) is documented only in the
spec — a client has no runtime way to learn which functions the DSL accepts, their signatures, or
their constraints (e.g. `date_bin` units). Query builders and LLM agents must hardcode the list and
break silently when it evolves.

## What Changes

- Add a read-only discovery endpoint `GET /v1/queries/functions` returning the supported function
  catalog: name, group (`scalar` / `aggregate` / `ordered_set_aggregate`), signature (arguments with
  names and kinds), return type, whether `distinct` is supported, and machine-readable constraints
  (e.g. `date_bin` allowed units, `percentile_*` fraction range).
- The catalog served is a single in-code source of truth kept in lockstep with the
  `ExprTranslator` allowlist: every advertised function is accepted by the translator
  (test-enforced); the reverse direction is guarded by a negative test and a cross-referencing
  comment on the translator's function switch (see design.md decision 2).
- No change to query execution or validation behavior; existing endpoints are untouched.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `query-schema-discovery`: adds a requirement for a supported-function catalog endpoint under the
  existing `/v1/queries` discovery namespace, mirroring the closed catalog defined by
  `structured-query`'s "Supported function catalog" requirement.

## Impact

- New controller method on `web.controller.query.QuerySchemaController` (`GET /v1/queries/functions`).
- New DTOs under `web.dto.query` (function, argument, constraints views).
- New catalog descriptor in `service.query` consumed by the endpoint; `ExprTranslator` behavior
  unchanged, with a parity test binding the two.
- Spec update: `openspec/specs/query-schema-discovery/spec.md` (delta in this change).
- No config, no migrations, no auth changes (endpoint follows the same access rules as the other
  discovery endpoints).
