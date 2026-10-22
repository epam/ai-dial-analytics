package com.epam.aidial.analytics.web.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code POST} handler under {@code /v1/pipelines/{name}} as <b>not</b> part of the Enrichment
 * Runner data plane, so {@link RunnerContractInterceptor} does not require the contract header on it.
 *
 * <p>An exemption rather than an opt-in, and the asymmetry is the point: a future runner endpoint that
 * forgets an opt-in annotation would be a silent hole of exactly the kind the contract exists to close,
 * while a future operator endpoint that forgets this marker is a 409 on its first call — found immediately,
 * fixed in one line. Deny-by-default is therefore the cheaper mistake in both directions.
 *
 * <p>Applied to the two operator surfaces, {@code preview} and {@code refresh}: those are reached by an
 * operator, the OpenAPI console and a management client, none of which knows a contract version.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RunnerContractExempt {
}
