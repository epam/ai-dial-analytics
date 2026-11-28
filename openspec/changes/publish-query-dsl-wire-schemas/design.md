## Context

springdoc 3.0.3 builds the schemas of the structured query body from the request model in
`service/query/model` through swagger-core 2.2.47. Four properties of that pipeline matter here:

- swagger-core takes a discriminator's property name from `@JsonTypeInfo`, but builds `discriminator.mapping`
  only from `@Schema(discriminatorMapping = …)`. It never reads the `@JsonSubTypes` names.
- springdoc registers `SpringDocSealedClassModule` on its Jackson 2 mapper unconditionally. For a sealed type
  without `@JsonSubTypes` it reports the permitted subclasses as subtypes. `FilterNode` is such a type: it is
  routed by `FilterNodeDeserializer`, because its `op` key is both discriminator and operator data.
- When swagger-core composes a subtype under its base (`ModelResolver.resolveSubtypes`), it removes every
  subtype member that `equals` a member of the schema it composes under (`removeParentProperties`). Schemas are
  compared by value, not identity.
- springdoc's `PolymorphicModelConverter` turns a property typed by a base into the `oneOf` of the subtypes it has
  already defined. A property it reaches before the subtypes keeps whatever the base resolved to at that point.

`FnExpr.args` and `ComparisonNode.args` have the same shape (an array of `Expr`). With `FilterNode` expanded into
`ComparisonNode`, `LogicalNode` and `SubqueryNode`, the comparison removes `FnExpr.args`. This was reproduced
outside the application: resolving `StructuredQuery` with plain swagger-core keeps `args`, registering springdoc's
sealed-class module on the same mapper drops it, and leaving `FilterNode` out of the module's expansion brings it
back. `FilterNode` itself resolves to an empty schema, and `StructuredQuery.filter`, the first filter property
springdoc reaches, is published as that `{}`, while the later ones list the node types.

## Goals / Non-Goals

**Goals**

- The published schemas of `Expr`, `PageSpec`, `FilterNode` and their subtypes describe the body Jackson reads.
- The result does not depend on the order in which springdoc happens to resolve the types.
- A build guard that fails on the next member or subtype that drifts, derived from the Java model rather than a
  list of names.

**Non-Goals**

- Describing `FilterNode` with a discriminator. Its `op` key is shared by the node types' operator enums, so no
  `mapping` can route it; a `oneOf` of the node types is what the document already publishes for most filter
  properties.
- Changing the Java model, Jackson binding or any endpoint's behaviour.
- Fixing the springdoc or swagger-core defects upstream.

## Decisions

### D1. Write the discriminator mapping from `@JsonSubTypes` in an `OpenApiCustomizer`

`QueryDslSchemas` lists the name-discriminated types (`Expr`, `PageSpec`) and writes each mapping from the
type's own `@JsonSubTypes`.

*Rejected:* `@Schema(discriminatorProperty = …, discriminatorMapping = {…})` on `Expr` and `PageSpec`. It repeats
every name a second time, in a second annotation that nothing ties to the first, and a subtype added to
`@JsonSubTypes` alone would publish an incomplete mapping.

### D2. Publish `FilterNode` once, as `@Schema(oneOf = {…})` on the interface

The annotation takes `FilterNode` out of the sealed-class expansion and gives it a schema of its own: the `oneOf`
of its permitted node types, which is what most filter properties published inline already. Every filter property
then becomes a `$ref` to it, so none depends on resolution order, and `StructuredQuery.filter` is no longer `{}`.
The node types lose their `allOf` link to the empty base. swagger-core does not inherit a `@Schema` on an interface
into the records that implement it, so the node schemas themselves do not change otherwise.

*Rejected:* `@Schema(oneOf = {…})` on `StructuredQuery.filter` only. The property is fixed, but the emptiness moves
to whichever filter property springdoc reaches first next (`having`).

### D3. Declare `FnExpr.args` explicitly

Without an annotation, `FnExpr.args` is dropped while `FilterNode` is expanded (Context), and is published with
empty items once D2 stops the expansion. An explicit
`@ArraySchema(schema = @Schema(implementation = Expr.class), arraySchema = @Schema(description = …))` publishes
it as an array whose items are `Expr`. The description is worth having on its own.

*Rejected:* `@Schema(oneOf = {…})` on the items. The list includes `FnExpr` itself, so swagger-core recurses
until the stack overflows.

### D4. Spell a bare `$ref` to a discriminated base as the `oneOf` of its subtypes

`FnExpr` is reached through `WindowExpr.fn` while `Expr`'s subtypes are still being resolved, so the
`PolymorphicModelConverter` leaves the items of `FnExpr.args` a bare `$ref` to `Expr`. That is valid OpenAPI, but
a client generated from it types each argument as the base schema, whose only member is `type`, and no real
expression fits that type. `QueryDslSchemas` replaces each such reference, outside a subtype's own `allOf` link to
its base, with the `oneOf` of the subtypes in schema-name order. That is the order springdoc uses, so the result
equals what springdoc publishes for `ArrayExpr.items`.

### D5. `@Schema(nullable = true)` on `ValueExpr.value`

The same annotation the settings DTOs already use. In OpenAPI 3.1 it publishes `"type": ["string", "null"]`.

### D6. Guards derived from the model

`OpenApiQueryDslSchemaIntegrationTest` reads the served document and checks:

- for every name-discriminated type, that the mapping equals its `@JsonSubTypes` names;
- for every subtype and every filter node type, that each record component is published under its Jackson name
  with a type (an empty schema, or an array with empty items, fails);
- that no bare reference to a discriminated base remains outside an `allOf` link;
- that `FilterNode` is the `oneOf` of its permitted subclasses, and `filter` and `having` refer to it;
- that `ValueExpr.value` admits null.

Reverting D3 makes the member check fail on `FnExpr.args`. `QueryDslSchemasTest` covers the customizer on a
hand-built document without a Spring context.

## Risks / Trade-offs

- **The customizer's list of types.** A new `@JsonTypeInfo` type in the query model must be added to
  `QueryDslSchemas.NAME_DISCRIMINATED`, or it publishes no mapping. The two types are the ones the package
  documentation names as Jackson NAME discriminators.
- **The `FilterNode` node list.** `@Schema(oneOf = {…})` names the permitted subclasses a second time. The guard
  compares it with `getPermittedSubclasses()`, so a node type added to `permits` alone fails the build.
- **Upstream changes.** A later springdoc or swagger-core may fix the dropped member or start writing mappings.
  The guards keep passing in either case, and the explicit annotations stay correct.
