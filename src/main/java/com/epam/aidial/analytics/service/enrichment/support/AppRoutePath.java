package com.epam.aidial.analytics.service.enrichment.support;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The grammar of a DIAL application route, the path an {@code llm} transform with preset {@code dial_app_route} calls
 * under {@code /v1/deployments/{model}/route/}. One grammar serves the pipeline declaration and the
 * {@code app-routes} instance setting, so any pair the setting accepts is one a declaration can name.
 *
 * <p>The grammar is deliberately narrow. It accepts one or more {@code [A-Za-z0-9._~-]+} segments separated by single
 * {@code /}, with no leading or trailing {@code /}, no {@code .} or {@code ..} segment, and at most 256 characters.
 * {@code ?}, {@code #} and {@code %} fall outside the character class, so a query, a fragment and any percent-encoding
 * are refused rather than decoded: an encoded {@code ..} or {@code /} has no second spelling that could reach the URL.
 * Case is preserved, because DIAL Core matches routes case-sensitively.
 */
public final class AppRoutePath {

    public static final int MAX_LENGTH = 256;

    private static final Pattern GRAMMAR = Pattern.compile("[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*");

    private AppRoutePath() {
    }

    /**
     * Why {@code route} is not a valid application route, or empty when it is.
     *
     * @param route the route as authored; null and blank are reported as missing
     */
    public static Optional<String> problem(String route) {
        if (route == null || route.isBlank()) {
            return Optional.of("is required");
        }
        if (route.length() > MAX_LENGTH) {
            return Optional.of("is longer than " + MAX_LENGTH + " characters");
        }
        if (!GRAMMAR.matcher(route).matches()) {
            return Optional.of("must be a relative path of '/'-separated segments of letters, digits and '._~-',"
                    + " with no leading or trailing '/', no empty segment, and no '?', '#' or '%'");
        }
        for (String segment : route.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return Optional.of("must not contain a '.' or '..' segment");
            }
        }
        return Optional.empty();
    }
}
