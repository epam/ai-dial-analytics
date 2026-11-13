package com.epam.aidial.analytics.service.setting;

import com.epam.aidial.analytics.exception.SettingValidationException;
import com.epam.aidial.analytics.service.enrichment.support.AppRoutePath;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates an app-routes body on write. The route grammar is the declaration's own ({@link AppRoutePath}), so a pair
 * the setting accepts is a pair a pipeline can name. Nothing is checked against DIAL Core, which ADAS cannot see.
 */
final class AppRoutesValidator {

    static final int MAX_ROUTES = 100;
    static final int MAX_DEPLOYMENT_LENGTH = 256;

    /**
     * What a deployment id may not carry: whitespace, a control or format character (which makes two entries look
     * identical), a backslash, or a character that would end or re-encode the URL path. A {@code /} is allowed,
     * because DIAL application ids may contain one; {@link #segmentProblem} holds what may sit between them.
     */
    private static final Pattern FORBIDDEN_IN_DEPLOYMENT = Pattern.compile("[\\s\\p{Z}\\p{Cc}\\p{Cf}\\\\?#%]");

    private AppRoutesValidator() {
    }

    static void validate(AppRoutesSetting setting) {
        final List<AppRoute> routes = setting == null ? null : setting.routes();
        if (routes == null) {
            throw new SettingValidationException("routes is required; an empty list allows no route");
        }
        if (routes.size() > MAX_ROUTES) {
            throw new SettingValidationException("routes lists " + routes.size() + " pairs; at most " + MAX_ROUTES
                    + " are allowed");
        }
        final Set<AppRoute> seen = new HashSet<>();
        for (int i = 0; i < routes.size(); i++) {
            final String at = "routes[" + i + "]";
            final AppRoute entry = routes.get(i);
            if (entry == null) {
                throw new SettingValidationException(at + " is required");
            }
            if (entry.deployment() == null || entry.deployment().isBlank()) {
                throw new SettingValidationException(at + ".deployment is required");
            }
            if (entry.deployment().length() > MAX_DEPLOYMENT_LENGTH) {
                throw new SettingValidationException(at + ".deployment is longer than " + MAX_DEPLOYMENT_LENGTH
                        + " characters");
            }
            if (FORBIDDEN_IN_DEPLOYMENT.matcher(entry.deployment()).find()) {
                throw new SettingValidationException(at + ".deployment carries whitespace, a control or format"
                        + " character, '\\', '?', '#' or '%', none of which a DIAL deployment id carries");
            }
            segmentProblem(entry.deployment()).ifPresent(problem -> {
                throw new SettingValidationException(at + ".deployment " + problem);
            });
            AppRoutePath.problem(entry.route()).ifPresent(problem -> {
                throw new SettingValidationException(at + ".route " + problem);
            });
            if (!seen.add(entry)) {
                throw new SettingValidationException(at + " repeats the pair (" + entry.deployment() + ", "
                        + entry.route() + ")");
            }
        }
    }

    /**
     * The deployment id is spliced into {@code /v1/deployments/{model}/route/{route}}, and a declaration's
     * {@code model} has no grammar beyond equalling a listed pair, so this is the only guard on that half of the path.
     * A normalizing client or proxy would resolve a {@code .} or {@code ..} segment out of {@code /v1/deployments/}.
     */
    private static Optional<String> segmentProblem(String deployment) {
        if (deployment.startsWith("/") || deployment.endsWith("/")) {
            return Optional.of("must not start or end with '/'");
        }
        for (String segment : deployment.split("/", -1)) {
            if (segment.isEmpty()) {
                return Optional.of("must not contain an empty segment");
            }
            if (segment.equals(".") || segment.equals("..")) {
                return Optional.of("must not contain a '.' or '..' segment");
            }
        }
        return Optional.empty();
    }
}
