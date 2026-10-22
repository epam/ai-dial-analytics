package com.epam.aidial.analytics.service.query.model;

import java.util.List;

/**
 * A lambda: the per-element predicate or projection a higher-order array function applies, spelled
 * {@code x -> <body>} in SQL and {@code {"type": "lambda", "params": ["x"], "body": …}} on the wire.
 * Legal <b>only</b> as argument 0 of a higher-order call ({@code arrayExists} and its family); every other
 * position rejects it, since there is nothing else that could apply it.
 *
 * <p>{@code params} carries a list because ClickHouse's multi-parameter forms are spelled that way, but the
 * catalog admits exactly one: every function in the family takes one array. The name is validated at
 * translation ({@code LambdaScope}) rather than here, because it is the one caller-supplied token rendered
 * into the statement as a bare identifier — ClickHouse does not accept a quoted one in that position.
 *
 * <p>The body is an ordinary expression over the parameter ({@link LambdaParamExpr}) <em>and</em> the
 * enclosing row's columns ({@link FieldExpr}), which is ClickHouse's own scoping: a capture such as
 * {@code arrayExists(x -> starts_with(x, deployment), roles)} means here what it means there. A captured
 * column is an ordinary column read and is governed as one — it is why the parameter has a node kind of its
 * own rather than borrowing {@code field}, so that a walker collecting the columns a statement reads cannot
 * mistake a parameter for one.
 */
public record LambdaExpr(List<String> params, Expr body) implements Expr {}
