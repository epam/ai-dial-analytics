package com.epam.aidial.analytics.service.setting;

import com.epam.aidial.analytics.exception.SettingValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppRoutesValidatorTest {

    private static AppRoutesSetting one(String deployment) {
        return new AppRoutesSetting(List.of(new AppRoute(deployment, "insights")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"insights app", " insights-app", "insights-app\t", "insights\napp", "insights\u0000app",
        "insights app", "insights-app?x=1", "insights-app#top", "insights%2Fapp"})
    void deploymentCarryingWhitespaceControlOrUrlDelimiterIsRefused(String deployment) {
        assertThatThrownBy(() -> AppRoutesValidator.validate(one(deployment)))
                .isInstanceOf(SettingValidationException.class)
                .hasMessageContaining("routes[0].deployment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a/../../x", "..", ".", "team/./insights-app", "team//insights-app", "/insights-app",
        "insights-app/", "team\\insights-app", "insights​app"})
    void deploymentThatCouldLeaveTheDeploymentsPathOrLookLikeAnotherIsRefused(String deployment) {
        assertThatThrownBy(() -> AppRoutesValidator.validate(one(deployment)))
                .isInstanceOf(SettingValidationException.class)
                .hasMessageContaining("routes[0].deployment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"insights-app", "team/insights-app", "insights_app.v2", "Insights-App", "a..b"})
    void deploymentIdsDialAcceptsAreAllowedSlashIncluded(String deployment) {
        assertThatCode(() -> AppRoutesValidator.validate(one(deployment))).doesNotThrowAnyException();
    }

    @Test
    void theOffendingEntryIsNamedByIndex() {
        final AppRoutesSetting setting = new AppRoutesSetting(List.of(
                new AppRoute("insights-app", "insights"), new AppRoute("triage app", "classify")));
        assertThatThrownBy(() -> AppRoutesValidator.validate(setting)).hasMessageContaining("routes[1].deployment");
    }
}
