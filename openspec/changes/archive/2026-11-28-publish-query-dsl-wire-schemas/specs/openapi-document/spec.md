## ADDED Requirements

### Requirement: Discriminated request types publish their wire discriminator

For every request type Jackson routes by a `@JsonTypeInfo` name discriminator (the structured query's `Expr` and
`PageSpec`), the base's `discriminator` SHALL map each `@JsonSubTypes` name to that subtype's schema. A property,
array item or map value typed by the base SHALL be the `oneOf` of its subtypes, never a bare `$ref` to the base;
a subtype's `allOf` link to its base is exempt. The build SHALL fail on a mapping or reference that breaks this.

#### Scenario: The expression discriminator uses the wire names

- **WHEN** a generator reads the `Expr` schema
- **THEN** its discriminator maps `field`, `value`, `param`, `fn`, `array`, `case`, `window`, `lambda` and
  `lambda_param` to `FieldExpr`, `ValueExpr`, `ParamExpr`, `FnExpr`, `ArrayExpr`, `CaseExpr`, `WindowExpr`,
  `LambdaExpr` and `LambdaParamExpr`

#### Scenario: The page discriminator uses the wire names

- **WHEN** a generator reads the `PageSpec` schema
- **THEN** its discriminator maps `offset` to `OffsetPage` and `cursor` to `CursorPage`

#### Scenario: A property typed by the base lists the subtypes

- **WHEN** a generator reads `FnExpr.args`
- **THEN** its items are the `oneOf` of the expression types, the same `oneOf` as `ArrayExpr.items`

#### Scenario: A new subtype is covered without another edit

- **WHEN** a change adds a subtype to `Expr`'s `@JsonSubTypes` and regenerates `docs/openapi.json`
- **THEN** the mapping and the `oneOf` of every property typed by `Expr` include the new subtype

### Requirement: Polymorphic request subtypes publish every member with its type

Every subtype of a name-discriminated request type, and every filter node type, SHALL publish every member
Jackson reads, under its JSON name and with its type: an empty schema, or an array with empty items, does not
qualify. A member whose value may be null on the wire SHALL admit `null`. The build SHALL fail, naming the subtype
and member, when the published document breaks this.

#### Scenario: A function call publishes its arguments

- **WHEN** a generator reads the `FnExpr` schema
- **THEN** it publishes `name`, `distinct` and `args`, and `args` is an array of expressions

#### Scenario: A null literal is described

- **WHEN** a generator reads the `ValueExpr` schema
- **THEN** `value` admits both a string and `null`, because it is null or absent when `value_type` is `null`

#### Scenario: A member the document drops or leaves untyped

- **WHEN** the generated document omits a record component of an `Expr` or `PageSpec` subtype or a filter node
  type, or publishes it as `{}` or as an array with empty items
- **THEN** `./gradlew build` fails, naming the subtype and member

### Requirement: Filter trees refer to one published union of the node types

The document SHALL publish `FilterNode` as the `oneOf` of its permitted node types (`ComparisonNode`,
`LogicalNode`, `SubqueryNode`), and every property that holds a filter tree SHALL refer to it. The build SHALL
fail when the union differs from the permitted subclasses or when `StructuredQuery.filter` or `having` does not
refer to it.

#### Scenario: The query filter is typed

- **WHEN** a generator reads `StructuredQuery.filter` and `StructuredQuery.having`
- **THEN** both refer to `FilterNode`, whose `oneOf` lists `ComparisonNode`, `LogicalNode` and `SubqueryNode`

#### Scenario: A node type added to the sealed interface only

- **WHEN** a change adds a node type to `FilterNode`'s `permits` but not to its `@Schema(oneOf)`
- **THEN** `./gradlew build` fails on the union check
