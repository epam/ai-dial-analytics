package com.epam.aidial.analytics.service.query.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A node in the recursive filter tree (CQL2-JSON shape). The {@code op} key is both the subtype
 * discriminator and operator data, so it cannot be expressed with {@code @JsonTypeInfo} (which would
 * write a second, ambiguous {@code op}); routing is done by {@link FilterNodeDeserializer}, wired at
 * each use site ({@code StructuredQuery.filter}/{@code having} via {@code using}, {@code
 * LogicalNode.args} via {@code contentUsing}) rather than on this interface — a Jackson annotation here
 * would be inherited by the subtypes and recurse. Serialization is automatic: each record writes its
 * own single {@code op} (lowercase via the enum's {@code @JsonValue}) plus {@code args}.
 *
 * <p>{@link SubqueryNode} is the one member with no wire spelling: the SQL front end constructs it for a
 * composite statement, and the deserializer above cannot produce it.
 *
 * <p>The OpenAPI document publishes this type once, as the {@code oneOf} of the node types, and every filter
 * property refers to it. Without the {@code @Schema}, springdoc publishes it as an empty schema, and whichever
 * filter property it resolves first ({@code StructuredQuery.filter}) inherits that emptiness while the others list
 * the node types ({@code openspec/specs/openapi-document/spec.md}).
 */
@Schema(oneOf = {ComparisonNode.class, LogicalNode.class, SubqueryNode.class})
public sealed interface FilterNode permits LogicalNode, ComparisonNode, SubqueryNode {}
