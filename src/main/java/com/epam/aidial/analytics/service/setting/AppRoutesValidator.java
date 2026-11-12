package com.epam.aidial.analytics.service.setting;

import com.epam.aidial.analytics.exception.SettingValidationException;
import com.epam.aidial.analytics.service.enrichment.support.AppRoutePath;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates an app-routes body on write. The route grammar is the declaration's own ({@link AppRoutePath}), so a pair
 * the setting accepts is a pair a pipeline can name. Nothing is checked against DIAL Core, which ADAS cannot see.
 */
final class AppRoutesValidator {

    static final int MAX_ROUTES = 100;
    static final int MAX_DEPLOYMENT_LENGTH = 256;

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
            final int index = i;
            AppRoutePath.problem(entry.route()).ifPresent(problem -> {
                throw new SettingValidationException("routes[" + index + "].route " + problem);
            });
            if (!seen.add(entry)) {
                throw new SettingValidationException(at + " repeats the pair (" + entry.deployment() + ", "
                        + entry.route() + ")");
            }
        }
    }
}
