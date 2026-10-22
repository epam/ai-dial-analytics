package com.epam.aidial.analytics.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a request mapping requires authentication and <b>no privilege</b>, because it carries no
 * tenant data at all. The one endpoint that qualifies is {@code GET /v1/queries/functions}, which
 * describes the query language.
 *
 * <p>This is a declaration, not an exemption: the fail-closed coverage test requires every mapping under
 * {@code /v1} to carry exactly one of this or {@link RequiresPrivilege}, so "no privilege needed" has to
 * be stated deliberately and is visible in review. An unannotated mapping still fails the build.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedOnly {
}
