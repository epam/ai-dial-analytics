package com.epam.aidial.analytics.web.security;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * The service's security mode, resolved from {@code security.mode}. This enum is the single place the
 * property is interpreted: {@link #parse(String)} matches case-insensitively, treats a blank value as
 * {@link #NONE}, and rejects anything else by throwing — so a typo aborts startup with an error naming
 * the offending value rather than leaving a partially-secured service.
 *
 * <p>Before this existed the mode was derived nine ways in four semantics — {@code @ConditionalOnProperty}
 * (case-insensitive), SpEL equality against {@code 'oidc'} (case-sensitive), {@code equalsIgnoreCase} with
 * fail-open, and {@code blank || "none"} with fail-closed — so {@code SECURITY_MODE=OIDC} enforced over
 * HTTP while leaving the Flight port unauthenticated and the sensitive-column filter failing open. Every
 * gate now reads the mode from {@link SecurityModeResolver} (beans) or {@link ConditionalOnSecurityMode}
 * (bean conditions), both of which funnel through here.
 */
public enum SecurityMode {

    /** Bearer-JWT / API-key authentication and privilege-based authorization are enforced. */
    OIDC,

    /** No authentication; every authorization check fails open. The default. */
    NONE;

    /** The property that selects the mode. */
    public static final String PROPERTY = "security.mode";

    /** The mode a deployment gets when {@code security.mode} is absent or blank. */
    public static final SecurityMode DEFAULT = NONE;

    /** Whether this mode enforces authentication and authorization. */
    public boolean isEnforced() {
        return this == OIDC;
    }

    /** The lowercase configuration form ({@code oidc} / {@code none}). */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Interprets a raw {@code security.mode} value. A blank value is {@link #DEFAULT}; otherwise the value
     * is trimmed and matched case-insensitively against the enum names.
     *
     * @throws IllegalStateException when the value names no mode — the service must not start, because
     *                               every gate would then have to guess
     */
    public static SecurityMode parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            return DEFAULT;
        }
        final String trimmed = raw.trim();
        for (final SecurityMode mode : values()) {
            if (mode.name().equalsIgnoreCase(trimmed)) {
                return mode;
            }
        }
        throw new IllegalStateException(
                "Invalid " + PROPERTY + " value '" + raw + "'; expected one of 'oidc' or 'none' (case-insensitive)");
    }

    /** Resolves the mode from the environment, for use where no bean is available (bean conditions). */
    public static SecurityMode resolve(Environment environment) {
        return parse(environment.getProperty(PROPERTY));
    }
}
