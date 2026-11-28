## 1. Schemas

- [x] 1.1 Add `QueryDslSchemas`, a springdoc `OpenApiCustomizer` in `web/openapi`: for `Expr` and `PageSpec` write
      `discriminator.mapping` from `@JsonSubTypes`, and replace every bare `$ref` to the base outside a subtype's
      `allOf` link with the `oneOf` of its subtypes in schema-name order
- [x] 1.2 Add `@Schema(oneOf = {ComparisonNode.class, LogicalNode.class, SubqueryNode.class})` to `FilterNode`, and
      say in its Javadoc why
- [x] 1.3 Declare `FnExpr.args` with `@ArraySchema(schema = @Schema(implementation = Expr.class), arraySchema = …)`
      and a description, and comment why it is explicit
- [x] 1.4 Add `@Schema(nullable = true)` and a description to `ValueExpr.value`

## 2. Tests

- [x] 2.1 `QueryDslSchemasTest`: mapping from `@JsonSubTypes`, bare references spelled as `oneOf`, the `allOf` link
      kept, a document without the DSL types left unchanged
- [x] 2.2 `OpenApiQueryDslSchemaIntegrationTest` over the served document: mapping equals `@JsonSubTypes`; every
      subtype and filter node type publishes every record component under its Jackson name with a type; no bare
      reference to a base outside an `allOf` link; `FilterNode` is the `oneOf` of its permitted subclasses and
      `filter` and `having` refer to it; `ValueExpr.value` admits null
- [x] 2.3 Check that the member guard fails when the `FnExpr.args` annotation is reverted

## 3. Document and verification

- [x] 3.1 Regenerate `docs/openapi.json` with `./gradlew updateOpenApi`, and check that the diff touches only the
      `Expr` and `PageSpec` mappings, `FnExpr.args`, `ValueExpr.value`, `FilterNode` and the properties and node
      types that refer to it
- [x] 3.2 Run `./gradlew clean build`
- [x] 3.3 Run `openspec validate publish-query-dsl-wire-schemas --strict`
