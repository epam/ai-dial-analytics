## Context

`dial_usage_log` has two `JSON`-typed columns, `jwt_claims` and `request_tags`, provisioned but never
populated (the "blocked" columns). The producer emits the source data as top-level `claims` and
`headers` objects in each usage-log record. The mapping engine's extraction kinds today are
`STRING`, `LONG`, `DOUBLE`, `BOOL`, `ARRAY_STRING` — all coerce a scalar or a string array; none can
carry a whole JSON object subtree. The `UsageLogWriter` serializes each row map to `JSONEachRow` via
Jackson `jsonMapper.writeValueAsBytes(row)`, so any value that is a Jackson `JsonNode` object already
serializes as a nested JSON object on the insert line. `SchemaValidator` classifies ClickHouse types
into families and currently returns `UNKNOWN` for `JSON` (warn-and-allow).

## Goals / Non-Goals

**Goals:**
- Populate `jwt_claims` from `claims` and `request_tags` from `headers`, verbatim, as JSON objects.
- Add a minimal object-passthrough extraction kind that reuses the existing writer serialization.
- Make schema validation recognize `JSON` as a real type family rather than the warn-and-allow path.

**Non-Goals:**
- No header allowlist/denylist in this service — filtering is a DIAL Core producer concern.
- No historical backfill; fix-forward only (separate backfill endpoint if needed).
- No schema/DDL change (the columns already exist), no new configuration property, no change to dedup,
  the sort key, TTL, or any other column.

## Decisions

### D1. New `OBJECT` extraction kind that passes the `JsonNode` through

Add `OBJECT("object")` to `ColumnKind`. In `MappingEngine.coerce`, the object case returns the source
`JsonNode` unchanged; the existing `resolve` already maps a missing/null node to `null` before coerce,
so absence is handled uniformly. The writer serializes the retained `JsonNode` as a nested JSON object
with no further work.

- *Alternative — a `json-passthrough` deriver instead of a kind*: rejected. These are plain
  single-source mapped columns; a scalar-shaped "read this object" is exactly what a kind expresses,
  mirroring how `array<string>` declares array shape. A deriver would over-generalize.
- *Alternative — serialize the object to a `String` column*: rejected. The columns are ClickHouse
  `JSON`; passing the node through keeps them queryable as JSON.

### D2. Non-nullable object zero value is the empty object `{}`

The `JSON` columns are not `Nullable`, so a column declared `nullable: false` must fall back to a zero
value when the source is absent. `MappingEngine.zero(OBJECT)` returns an empty object (an empty
`Map`/`ObjectNode`) which serializes to `{}` — matching the ClickHouse `JSON` column default. Extend
`isBlank` only as needed: a resolved object node is treated as present (so a populated `claims`/`headers`
is emitted as-is); a missing source resolves to `null` before the fallback and becomes `{}`.

### D3. `JSON` becomes a first-class type family in the validator

Add `TypeFamily.JSON`; `classify` maps the `JSON` spelling to it and `expectedFamily(OBJECT)` returns
it, so an `object`-kind column reconciles cleanly against a `JSON` table column instead of taking the
UNKNOWN warn-and-allow branch. The `JSON` columns are not `Nullable`, and the mapping declares the two
columns `nullable: false`, so the existing nullability check is satisfied.

### D4. Headers captured verbatim

`request_tags` maps directly from `headers` with no filtering. The sample records carry transport/SDK
noise (`X-Stainless-*`, `Content-Length`, `Connection`, …); excluding these is deferred to DIAL Core.
Recorded as a deliberate scope boundary, not an oversight.

## Risks / Trade-offs

- **PII / sensitive headers and claims persisted** → `claims` and full `headers` may carry sensitive
  values. Mitigation: the existing whole-row `TTL` bounds retention and column-level access controls
  gate reads; the same governance already applied to the body columns applies here.
- **Header volume / cardinality** → verbatim headers add JSON payload per row. Accepted; the `JSON`
  type stores this compactly and Core-side filtering can trim noise later without a schema change.
- **Empty-object vs. NULL semantics** → chosen empty `{}` for absence (columns are non-nullable),
  consistent with the table default; documented so consumers do not expect SQL NULL.
- **JSONEachRow serialization of a `JsonNode` into a `JSON` column** → grounded in the existing writer
  path, but guarded by a `UsageLogWriter`/serialization test asserting the object lands as JSON.

## Migration Plan

1. Ship code + mapping + spec together; `./gradlew clean build` green.
2. No table migration required — the `JSON` columns already exist. On deploy, `SchemaValidator`
   reconciles the two newly mapped columns against the live `JSON` columns and starts cleanly.
3. Rollback: revert the jar + mapping; the columns return to their unpopulated default. No DDL either
   direction.

## Open Questions

- None blocking. Backfill of historical rows, if requested, is a separate change against the backfill
  endpoint.
