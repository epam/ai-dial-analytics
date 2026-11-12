package com.epam.aidial.analytics.service.setting;

import com.epam.aidial.analytics.exception.SettingValidationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates a sessions-source setting against the catalog <b>as the writing caller sees it</b>.
 *
 * <p>The base entity is resolved through {@link CatalogQueryService#findModel} at {@code TABLE_GET}, the bar of
 * entity-schema discovery, so the setting is checked against exactly the flat schema the writer could read. That
 * model holds only {@code ACTIVE} sources the caller may get, with enrichments it may not get and sensitive
 * columns it has no clearance for already absent; "not a source", "hidden" and "does not exist" therefore take
 * one path and produce one message, and nothing here can tell a hidden field from an absent one.
 *
 * <p>A sensitive field the writer <i>can</i> see is still refused: the setting is read by every analyst, most of
 * whom hold no {@code TABLE_SENSITIVE}, and the field would be absent from their model.
 */
@Component
@RequiredArgsConstructor
public class SessionsSourceValidator {

    private final CatalogQueryService catalog;

    /** Throws {@link SettingValidationException} naming every offending member, or returns normally. */
    public void validate(SessionsSourceSetting setting) {
        final List<String> problems = new ArrayList<>();
        final Optional<QueryEntityModel> model = resolveBase(setting.baseTable(), problems);
        final SessionsSourceRoles roles = setting.roles();
        if (roles == null) {
            problems.add("roles is required");
        } else {
            final Map<String, QuerySchemaFieldDto> fields = model
                    .map(m -> m.baseSchema().stream()
                            .collect(Collectors.toMap(QuerySchemaFieldDto::name, Function.identity(), (a, b) -> a)))
                    .orElse(null);
            for (final SessionRole role : SessionRole.values()) {
                checkRole(role, role.boundIn(roles), fields, problems);
            }
        }
        if (!problems.isEmpty()) {
            throw new SettingValidationException(String.join("; ", problems));
        }
    }

    private Optional<QueryEntityModel> resolveBase(String baseTable, List<String> problems) {
        if (baseTable == null || baseTable.isBlank()) {
            problems.add("base_table is required");
            return Optional.empty();
        }
        final Optional<QueryEntityModel> model = catalog.findModel(baseTable, Privilege.TABLE_GET);
        if (model.isEmpty()) {
            problems.add("base_table '" + baseTable + "' is not a queryable source table");
        }
        return model;
    }

    /** {@code fields} is null when the base table did not resolve: then only presence can be checked. */
    private static void checkRole(SessionRole role, String fieldName, Map<String, QuerySchemaFieldDto> fields, List<String> problems) {
        final String member = "roles." + role.wireName();
        if (fieldName == null) {
            if (role.required()) {
                problems.add(member + " is required");
            }
            return;
        }
        if (fields == null) {
            return;
        }
        final QuerySchemaFieldDto field = fields.get(fieldName);
        if (field == null) {
            problems.add(member + " names unknown field '" + fieldName + "'");
        } else if (field.type() == QueryFieldType.ARRAY) {
            problems.add(member + " names array field '" + fieldName + "'; an array field cannot be bound to a role");
        } else if (field.projectionOnly()) {
            problems.add(member + " names projection-only field '" + fieldName + "'; it cannot be filtered, sorted or grouped");
        } else if (field.sensitive()) {
            problems.add(member + " names sensitive field '" + fieldName + "'; a sensitive field cannot be bound to a role");
        } else if (!role.accepted().contains(field.type())) {
            problems.add(member + " names field '" + fieldName + "' of type " + field.type().code()
                    + "; accepted: " + role.accepted().stream().map(QueryFieldType::code).sorted().collect(Collectors.joining(", ")));
        }
    }
}
