## Why

`docs/openapi.json` is the contract client generators read, but its schemas of the structured query body do not
describe what `POST /v1/queries/execute` accepts. A client generated from it cannot build a valid query:

- **`Expr` and `PageSpec` publish a discriminator without a `mapping`.** Jackson routes both by the
  `@JsonSubTypes` names (`field`, `value`, `param`, `fn`, `array`, `case`, `window`, `lambda`, `lambda_param`;
  `offset`, `cursor`). Without a mapping, generators fall back to the schema names and type `type` as
  `'FieldExpr'`, `'OffsetPage'` and so on. The service refuses those values with `400 bad_request`.
- **`FnExpr` publishes no `args`.** The record has `List<Expr> args`, but the document lists only `name` and
  `distinct`. Every function call (`count`, `sum`, `to_string`, `date_trunc`, …) needs `args`.
- **`ValueExpr.value` is not nullable.** `value_type` includes `null`, whose `value` is null or absent, but the
  schema allows only a string.
- **`StructuredQuery.filter` publishes an empty schema (`{}`)**, and so does `FilterNode` itself, while `having` and
  every other filter property, which have the same Java type, list the filter node types.

A TypeScript client generated from the document inherits all of these, so its users keep hand-written wire types
and cast them to the generated type.

## What Changes

- The `Expr` and `PageSpec` discriminators carry a `mapping` from each `@JsonSubTypes` name to that subtype's
  schema. An `OpenApiCustomizer` derives it from the annotation, so the names stay declared in one place.
- `FnExpr.args` is published as an array of expressions. Left implicit, the generator dropped it, or with the
  filter fix below left its items empty (see design). An explicit array schema keeps it and gives it a description.
- A property typed by a discriminated base itself is published as the `oneOf` of that base's subtypes, as springdoc
  already publishes every other such property. springdoc does this only when the subtypes are resolved before the
  property, so the same customizer spells out any bare `$ref` to the base that remains.
- `FilterNode` is published once, as the `oneOf` of its node types, and every filter property (`filter`, `having`,
  `CaseBranch.when`, `JoinRelation.on`, `LogicalNode.args`, the row policy's `filter`) refers to it. The node types
  lose their `allOf` link to the empty `FilterNode` schema, which described nothing.
- `ValueExpr.value` is published as `string` or `null`, with a description.
- `docs/openapi.json` is regenerated. No endpoint changes behaviour, and no request a client sends today is
  answered differently. Only the published description of the query body changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `openapi-document`: adds three requirements on the query body's polymorphic types. A name-discriminated base
  publishes its wire discriminator mapping and is never referenced bare. Every subtype publishes every member Jackson
  reads, with its type, nullable where the wire allows null. Filter trees refer to one published union of the node
  types. A build guard derived from the Java model enforces all three.

## Impact

- **Code**: `QueryDslSchemas`, a springdoc `OpenApiCustomizer` in `web/openapi`. `@Schema(oneOf = …)` on
  `FilterNode`, `@ArraySchema` on `FnExpr.args` and `@Schema(nullable = true)` on `ValueExpr.value`. No change to
  the Java model's shape or to Jackson binding.
- **Tests**: a unit test of the customizer, and `OpenApiQueryDslSchemaIntegrationTest` over the served document.
  It derives each expectation from `@JsonSubTypes`, the permitted subclasses and the record components, so a new
  subtype or member is covered without editing it. It runs in the existing `AbstractPostgresIntegrationTest` context.
- **Docs**: `docs/openapi.json`, regenerated with `./gradlew updateOpenApi`.
- **Consumers**: a client regenerated from the new document types the `type` discriminators with their wire
  names, gains `FnExpr.args` and a typed `filter`, and accepts a null literal. Filter properties change from an
  inline `oneOf` to a `$ref` to `FilterNode` with the same members. A generated client that relied on the schema
  names as discriminator values could never send an accepted body, so nothing that worked stops working.
