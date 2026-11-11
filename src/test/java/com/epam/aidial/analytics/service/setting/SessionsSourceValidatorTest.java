package com.epam.aidial.analytics.service.setting;

import com.epam.aidial.analytics.exception.SettingValidationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import com.epam.aidial.analytics.web.security.Privilege;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionsSourceValidatorTest {

    private static final String BASE = "sessions_rollup";

    private final CatalogQueryService catalog = mock(CatalogQueryService.class);
    private final SessionsSourceValidator validator = new SessionsSourceValidator(catalog);

    @BeforeEach
    void catalogWithOneSource() {
        when(catalog.findModel(anyString(), any())).thenReturn(Optional.empty());
        when(catalog.findModel(BASE, Privilege.TABLE_GET)).thenReturn(Optional.of(new QueryEntityModel(BASE, null, Map.of(), List.of(), List.of(
                field("sid", QueryFieldType.STRING, false),
                field("ts", QueryFieldType.TIMESTAMP, false),
                field("day", QueryFieldType.DATE, false),
                field("label", QueryFieldType.ENUM, false),
                field("uid", QueryFieldType.UUID, false),
                field("price", QueryFieldType.DECIMAL, false),
                field("n_int", QueryFieldType.INTEGER, false),
                field("n_long", QueryFieldType.LONG, false),
                field("tags", QueryFieldType.ARRAY, false),
                field("insights.title", QueryFieldType.STRING, false),
                field("insights.body_full", QueryFieldType.STRING, true),
                new QuerySchemaFieldDto("insights.body", QueryFieldType.STRING, null, null, "insights.body", null, null, null, true, false)))));
    }

    @Test
    void fullyBoundSettingValidatesAgainstTheGetBar() {
        assertThatCode(() -> validator.validate(setting(BASE, "sid", "ts", "sid", "insights.title", "price", "n_long")))
                .doesNotThrowAnyException();
        verify(catalog).findModel(BASE, Privilege.TABLE_GET);
    }

    @Test
    void optionalRolesMayBeUnset() {
        assertThatCode(() -> validator.validate(setting(BASE, "sid", "ts", null, null, null, null))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
        "cost, price, true", "cost, n_int, true", "cost, n_long, true", "cost, sid, false",
        "turns, n_int, true", "turns, n_long, true", "turns, price, false",
        "project, sid, true", "project, label, false", "project, uid, false",
        "title, insights.title, true", "title, ts, false",
        "time, ts, true", "time, day, false", "time, sid, false",
        "session_id, sid, true", "session_id, uid, false", "session_id, n_long, false",
    })
    void eachRoleAcceptsExactlyItsTypes(String role, String field, boolean accepted) {
        final SessionsSourceSetting setting = withRole(role, field);
        if (accepted) {
            assertThatCode(() -> validator.validate(setting)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> validator.validate(setting))
                    .isInstanceOf(SettingValidationException.class)
                    .hasMessageContaining("roles." + role + " names field '" + field + "' of type");
        }
    }

    @Test
    void requiredRolesMustBeBound() {
        assertThatThrownBy(() -> validator.validate(setting(BASE, null, null, null, null, null, null)))
                .hasMessageContaining("roles.session_id is required")
                .hasMessageContaining("roles.time is required");
        assertThatThrownBy(() -> validator.validate(new SessionsSourceSetting(BASE, null)))
                .hasMessage("roles is required");
    }

    @Test
    void anUnresolvedBaseTableIsOneMessage() {
        assertThatThrownBy(() -> validator.validate(setting("not_a_source", "sid", "ts", "x", null, null, null)))
                .isInstanceOf(SettingValidationException.class)
                .hasMessage("base_table 'not_a_source' is not a queryable source table");
        assertThatThrownBy(() -> validator.validate(setting(null, "sid", "ts", null, null, null, null)))
                .hasMessage("base_table is required");
    }

    @Test
    void unknownArraySensitiveAndProjectionOnlyFieldsAreRefused() {
        assertThatThrownBy(() -> validator.validate(withRole("project", "nope")))
                .hasMessage("roles.project names unknown field 'nope'");
        assertThatThrownBy(() -> validator.validate(withRole("project", "tags")))
                .hasMessageContaining("roles.project names array field 'tags'");
        assertThatThrownBy(() -> validator.validate(withRole("title", "insights.body_full")))
                .hasMessageContaining("roles.title names projection-only field 'insights.body_full'");
        assertThatThrownBy(() -> validator.validate(withRole("title", "insights.body")))
                .hasMessageContaining("roles.title names sensitive field 'insights.body'");
    }

    private static SessionsSourceSetting withRole(String role, String field) {
        return switch (role) {
            case "session_id" -> setting(BASE, field, "ts", null, null, null, null);
            case "time" -> setting(BASE, "sid", field, null, null, null, null);
            case "project" -> setting(BASE, "sid", "ts", field, null, null, null);
            case "title" -> setting(BASE, "sid", "ts", null, field, null, null);
            case "cost" -> setting(BASE, "sid", "ts", null, null, field, null);
            case "turns" -> setting(BASE, "sid", "ts", null, null, null, field);
            default -> throw new IllegalArgumentException(role);
        };
    }

    private static SessionsSourceSetting setting(String base, String sid, String time, String project, String title, String cost, String turns) {
        return new SessionsSourceSetting(base, new SessionsSourceRoles(sid, time, project, title, cost, turns));
    }

    private static QuerySchemaFieldDto field(String name, QueryFieldType type, boolean projectionOnly) {
        return new QuerySchemaFieldDto(name, type, type == QueryFieldType.ARRAY ? QueryFieldType.STRING : null, null, name,
                null, null, null, false, false, projectionOnly);
    }
}
