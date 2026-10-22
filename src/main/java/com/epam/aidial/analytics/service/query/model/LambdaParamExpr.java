package com.epam.aidial.analytics.service.query.model;

/**
 * A reference to an enclosing {@link LambdaExpr}'s parameter, spelled {@code x} in SQL and
 * {@code {"type": "lambda_param", "name": "x"}} on the wire. Resolved against the parameters of the
 * enclosing lambdas, innermost first; a name bound by no enclosing lambda is rejected, as is a reference
 * outside any lambda body.
 *
 * <p><b>Why this is not a {@link FieldExpr}.</b> A lambda body may also reference the enclosing row's
 * columns, so a body holds both kinds of name. Every walker that asks which <em>columns</em> a statement
 * reads — the enrichment-join derivation, the heavy-column and partition-pruning advisories, the
 * sensitive-column scan — would otherwise have to subtract the names each enclosing lambda binds, and would
 * be wrong wherever that was forgotten. With a kind of its own the distinction is structural: a walker
 * after columns ignores this node, and one after parameters ignores {@code field}.
 */
public record LambdaParamExpr(String name) implements Expr {}
