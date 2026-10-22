package com.epam.aidial.analytics.service.query.model;

/**
 * A scalar subquery in expression position — <b>internal</b> to the SQL surface, never on the wire. The
 * subquery produces exactly one column; its value for the (single) row is the expression's value. Legal
 * as a comparison operand or a select item only; the SQL front end refuses it as a function argument.
 *
 * <p>Deliberately absent from {@link Expr}'s wire subtypes: a request naming a {@code type} for it is
 * refused as an unknown expression kind.
 */
public record ScalarSubqueryExpr(CompositeQuery subquery) implements Expr {}
