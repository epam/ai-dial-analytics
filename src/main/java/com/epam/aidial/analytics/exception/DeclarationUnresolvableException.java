package com.epam.aidial.analytics.exception;

/**
 * A stored pipeline declaration cannot be resolved, so the laundering subset rule cannot be evaluated over
 * the edges that declaration carries.
 *
 * <p>Raised only where a missing answer would be <b>unsafe</b>, not merely unhelpful. The two questions the
 * rule asks of the registry — which enrichments a target's pipeline reads through the grain join, and which
 * targets read a given enrichment — are answered by parsing every stored declaration. Both were already
 * asked elsewhere, by the freshness diagnostics and the table-delete guard, and those callers degrade to
 * "no edges" when a declaration has drifted, deliberately: a broken pipeline must not make an unrelated
 * table undeletable or a diagnostic fail.
 *
 * <p>The same degradation under the grant rule reads as "this enrichment has no readers", which <em>accepts
 * a grant the rule exists to refuse</em>. One drifted row would silently reopen the laundering path the
 * grain-join edge closes. The opposite default is no better: letting the parse failure escape turns every
 * grant edit and every sensitive-column schema patch, on any table, into a 500 naming a pipeline the
 * operator was not touching.
 *
 * <p>So the answer is neither: refuse the edit, name the declaration that cannot be read, and let the
 * operator repair or delete it. Mapped to HTTP 409 with the {@code pipeline_declaration_unresolvable} code
 * — a conflict with stored state rather than a fault in the request, which is what it is.
 */
public class DeclarationUnresolvableException extends RuntimeException {

    public DeclarationUnresolvableException(String message, Throwable cause) {
        super(message, cause);
    }
}
