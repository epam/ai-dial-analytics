package com.epam.aidial.analytics.web.security.apikey;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RoleBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ApiKeyProperties#validate()}: a blank Core URL is rejected while enabled, a valid
 * mapping parses into privileges, and an unknown mapping value fails startup.
 *
 * <p>What is deliberately <b>not</b> rejected any more is two mappings that yield nothing. A deployment
 * whose API keys are authorized entirely by per-table grants is legitimate — that is the BI-tool persona
 * per-table read grants exist for — so the old "at least one role must map to an application authority"
 * check would have refused exactly the configuration this change makes possible.
 */
class ApiKeyPropertiesTest {

    private final JsonMapper jsonMapper = new JsonMapperConfiguration().jsonMapper();

    private ApiKeyProperties properties(String defaultRolesJson) {
        ApiKeyProperties properties = new ApiKeyProperties(jsonMapper, defaultRolesJson);
        properties.setEnabled(true);
        return properties;
    }

    @Test
    void blankCoreUrlIsRejected() {
        ApiKeyProperties properties = properties("");
        properties.setRolesMapping("{\"admin\":[\"FULL_ADMIN\"]}");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core-url");
    }

    @Test
    @DisplayName("a grant-only deployment maps nothing and still starts")
    void bothMappingsEmptyIsAccepted() {
        ApiKeyProperties properties = properties("");
        properties.setCoreUrl("https://core.test");
        properties.setRolesMapping("");

        properties.validate();

        assertThat(properties.getParsedRolesMapping()).isEmpty();
        assertThat(properties.getParsedDefaultRolesMapping()).isEmpty();
    }

    @Test
    void validProjectKeyMappingParses() {
        ApiKeyProperties properties = properties("");
        properties.setCoreUrl("https://core.test");
        properties.setRolesMapping("{\"admin\":[\"FULL_ADMIN\"],\"viewer\":[\"READ_ONLY_ADMIN\"]}");

        properties.validate();

        assertThat(properties.getParsedRolesMapping())
                .containsEntry("admin", RoleBundle.FULL_ADMIN.privileges())
                .containsEntry("viewer", RoleBundle.READ_ONLY_ADMIN.privileges());
        assertThat(properties.getParsedDefaultRolesMapping()).isEmpty();
    }

    @Test
    @DisplayName("a mapping may name privileges directly, closed under implication")
    void privilegeNamesParse() {
        ApiKeyProperties properties = properties("");
        properties.setCoreUrl("https://core.test");
        properties.setRolesMapping("{\"exporter\":[\"TABLE_WRITE\"]}");

        properties.validate();

        assertThat(properties.getParsedRolesMapping())
                .containsEntry("exporter", java.util.Set.of(Privilege.TABLE_WRITE));
    }

    @Test
    void defaultsMappingAloneParses() {
        ApiKeyProperties properties = properties("{\"admin\":[\"FULL_ADMIN\"]}");
        properties.setCoreUrl("https://core.test");
        properties.setRolesMapping("");

        properties.validate();

        assertThat(properties.getParsedDefaultRolesMapping())
                .containsEntry("admin", RoleBundle.FULL_ADMIN.privileges());
        assertThat(properties.getParsedRolesMapping()).isEmpty();
    }

    @Test
    void invalidJsonIsRejected() {
        ApiKeyProperties properties = properties("");
        properties.setCoreUrl("https://core.test");
        properties.setRolesMapping("{not-json");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid");
    }

    @Test
    @DisplayName("an unknown mapping value fails startup rather than being dropped")
    void unknownMappingValueIsRejected() {
        ApiKeyProperties properties = properties("");
        properties.setCoreUrl("https://core.test");
        properties.setRolesMapping("{\"admin\":[\"SUPER_ADMIN\"]}");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPER_ADMIN")
                .hasMessageContaining("security.api-key.roles-mapping");
    }
}
