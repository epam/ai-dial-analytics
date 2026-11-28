package com.epam.aidial.analytics.web.openapi;

import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.PageSpec;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes the structured query DSL's name-discriminated types ({@link Expr}, {@link PageSpec}) the way Jackson
 * reads them, so a client generated from the document builds a body the service accepts.
 *
 * <p>springdoc takes each type's discriminator property from {@code @JsonTypeInfo} but writes no {@code mapping},
 * so a generator falls back to schema names ({@code FieldExpr}, {@code OffsetPage}) where the wire uses the
 * {@code @JsonSubTypes} names ({@code field}, {@code offset}). The mapping is written here from those names, which
 * stay the only place they are declared.
 *
 * <p>springdoc spells a property typed by such a base as the {@code oneOf} of its subtypes, but only when the
 * subtypes are already resolved; one resolved earlier ({@code FnExpr.args}, reached through {@code WindowExpr.fn})
 * keeps a bare {@code $ref} to the base, which describes no subtype's members. Every such reference outside a
 * subtype's own {@code allOf} link is spelled out here as that {@code oneOf}, so the document doesn't depend on
 * resolution order. See {@code openspec/specs/openapi-document/spec.md}.
 */
@Component
public class QueryDslSchemas implements OpenApiCustomizer {

    /** The DSL types Jackson routes by a {@code @JsonTypeInfo} NAME discriminator. */
    private static final List<Class<?>> NAME_DISCRIMINATED = List.of(Expr.class, PageSpec.class);

    @Override
    public void customise(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null || components.getSchemas() == null) {
            return;
        }
        Map<String, Schema> schemas = components.getSchemas();
        for (Class<?> base : NAME_DISCRIMINATED) {
            Schema<?> baseSchema = schemas.get(base.getSimpleName());
            if (baseSchema == null || baseSchema.getDiscriminator() == null) {
                continue;
            }
            List<Class<?>> subtypes = subtypes(base);
            Map<String, String> mapping = new LinkedHashMap<>();
            for (JsonSubTypes.Type type : base.getAnnotation(JsonSubTypes.class).value()) {
                mapping.put(type.name(), ref(type.value()));
            }
            baseSchema.getDiscriminator().setMapping(mapping);
            String baseRef = ref(base);
            schemas.values().forEach(schema -> expandBareReferences(schema, baseRef, subtypes));
        }
    }

    /** The subtypes of {@code base} that its {@code @JsonSubTypes} names, in schema-name order as springdoc lists them. */
    private static List<Class<?>> subtypes(Class<?> base) {
        return Arrays.stream(base.getAnnotation(JsonSubTypes.class).value())
                .<Class<?>>map(JsonSubTypes.Type::value)
                .sorted(Comparator.comparing(Class::getSimpleName))
                .toList();
    }

    private static String ref(Class<?> type) {
        return Components.COMPONENTS_SCHEMAS_REF + type.getSimpleName();
    }

    /*
     * Rewrites each property, array item or additional-properties schema that is only a reference to the base. The
     * members of allOf are not rewritten: there the reference is a subtype's link to its base.
     */
    private static void expandBareReferences(Schema<?> schema, String baseRef, List<Class<?>> subtypes) {
        if (schema == null) {
            return;
        }
        if (schema.getProperties() != null) {
            schema.getProperties().replaceAll((name, property) -> expanded(property, baseRef, subtypes));
            schema.getProperties().values().forEach(property -> expandBareReferences(property, baseRef, subtypes));
        }
        if (schema.getItems() != null) {
            schema.setItems(expanded(schema.getItems(), baseRef, subtypes));
            expandBareReferences(schema.getItems(), baseRef, subtypes);
        }
        if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
            schema.setAdditionalProperties(expanded(additional, baseRef, subtypes));
            expandBareReferences((Schema<?>) schema.getAdditionalProperties(), baseRef, subtypes);
        }
        for (List<Schema> members : Arrays.asList(schema.getAllOf(), schema.getOneOf(), schema.getAnyOf())) {
            if (members != null) {
                members.forEach(member -> expandBareReferences(member, baseRef, subtypes));
            }
        }
    }

    private static Schema expanded(Schema<?> schema, String baseRef, List<Class<?>> subtypes) {
        if (!isBareReference(schema, baseRef)) {
            return schema;
        }
        JsonSchema oneOf = new JsonSchema();
        oneOf.setDescription(schema.getDescription());
        subtypes.forEach(subtype -> oneOf.addOneOfItem(new JsonSchema().$ref(ref(subtype))));
        return oneOf;
    }

    private static boolean isBareReference(Schema<?> schema, String baseRef) {
        return schema != null && baseRef.equals(schema.get$ref()) && schema.getProperties() == null && schema.getItems() == null
                && schema.getAllOf() == null && schema.getOneOf() == null && schema.getAnyOf() == null;
    }
}
