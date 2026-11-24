package com.epam.aidial.analytics.web.dto.catalog;

import com.epam.aidial.analytics.exception.InvalidRequestParameterException;
import com.epam.aidial.analytics.web.dto.StrictStringListDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * One object selected for export, by kind and name, with the selectors that narrow what it brings in (see
 * {@code openspec/specs/catalog-export-import/spec.md}, "Selective export"): {@code dependencies} on a pipeline,
 * {@code access_only} and {@code roles} on a table. A selector on the other kind, an empty {@code roles} or a blank role
 * is refused while the body is bound, so before any privilege check.
 */
public record ExportComponent(
        @Schema(description = "Object kind", example = "table")
        @NotNull Type type,

        @Schema(description = "Table or pipeline name", example = "usage_sentiment")
        @NotBlank String name,

        @Schema(description = "Pipeline only. False exports the pipeline alone, without its inputs, target or the "
                + "enrichments it reads; import then needs each on the target. Default true")
        Boolean dependencies,

        @Schema(description = "Table only, with include_access. True carries only the table's access entries: no "
                + "definition, and nothing it depends on or that depends on it. Default false")
        @JsonProperty("access_only") Boolean accessOnly,

        @ArraySchema(arraySchema = @Schema(description = "Table only, with include_access. Carries only these roles' entries "
                + "of the table; absent carries every role's"), minItems = 1)
        @JsonDeserialize(using = StrictStringListDeserializer.class) List<String> roles) {

    public ExportComponent {
        if (type == Type.TABLE && dependencies != null) {
            throw new InvalidRequestParameterException("dependencies applies to a pipeline component only; a table "
                    + "component takes access_only and roles");
        }
        if (type == Type.PIPELINE && (accessOnly != null || roles != null)) {
            throw new InvalidRequestParameterException("access_only and roles apply to a table component only; a "
                    + "pipeline component takes dependencies");
        }
        if (roles != null && (roles.isEmpty() || roles.stream().anyMatch(StringUtils::isBlank))) {
            throw new InvalidRequestParameterException("roles must list at least one role, none of them blank; omit it "
                    + "to carry every role's entry");
        }
    }

    /** Whether this pipeline component brings in its closure: {@code dependencies} absent or true. */
    public boolean withDependencies() {
        return !Boolean.FALSE.equals(dependencies);
    }

    /** Whether this table component carries only the table's access. */
    public boolean accessOnlySelected() {
        return Boolean.TRUE.equals(accessOnly);
    }

    /** The listed roles, trimmed and de-duplicated, or {@code null} when {@code roles} is absent. */
    public Set<String> roleSet() {
        if (roles == null) {
            return null;
        }
        final Set<String> trimmed = new TreeSet<>();
        roles.forEach(role -> trimmed.add(role.trim()));
        return trimmed;
    }

    /** The kinds of object a bundle carries. */
    public enum Type {
        TABLE("table"),
        PIPELINE("pipeline");

        private final String code;

        Type(String code) {
            this.code = code;
        }

        @JsonValue
        public String code() {
            return code;
        }
    }
}
