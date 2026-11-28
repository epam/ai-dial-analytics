package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.PageSpec;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published schemas of the structured query DSL's polymorphic types — the name-discriminated {@link Expr} and
 * {@link PageSpec}, and the {@link FilterNode} union — describe the body Jackson reads, so a client generated from
 * the document builds a body the service accepts ({@code openspec/specs/openapi-document/spec.md}). Each guard
 * derives what it expects from the Java model, so a new subtype or member is covered without editing the test.
 */
class OpenApiQueryDslSchemaIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final List<Class<?>> NAME_DISCRIMINATED = List.of(Expr.class, PageSpec.class);
    private static final String SCHEMAS_REF = "#/components/schemas/";

    @Test
    void eachDiscriminatorMapsTheJacksonSubtypeNames() throws Exception {
        final JsonNode schemas = document().path("components").path("schemas");

        for (final Class<?> base : NAME_DISCRIMINATED) {
            final Map<String, String> expected = new LinkedHashMap<>();
            for (final JsonSubTypes.Type type : base.getAnnotation(JsonSubTypes.class).value()) {
                expected.put(type.name(), SCHEMAS_REF + type.value().getSimpleName());
            }
            final Map<String, String> published = new LinkedHashMap<>();
            final JsonNode mapping = schemas.path(base.getSimpleName()).path("discriminator").path("mapping");
            for (final String name : mapping.propertyNames()) {
                published.put(name, mapping.get(name).asString());
            }

            assertThat(published).as("%s discriminator mapping", base.getSimpleName()).containsExactlyEntriesOf(expected);
        }
    }

    @Test
    void eachSubtypePublishesEveryMemberJacksonReadsWithItsType() throws Exception {
        final JsonNode schemas = document().path("components").path("schemas");

        for (final Class<?> subtype : subtypes()) {
            final Map<String, JsonNode> members = publishedMembers(schemas.path(subtype.getSimpleName()));
            assertThat(members.keySet()).as("%s publishes every record component", subtype.getSimpleName())
                    .containsAll(wireNames(subtype));
            for (final String name : wireNames(subtype)) {
                assertThat(describesType(members.get(name))).as("%s.%s publishes its type: %s", subtype.getSimpleName(), name, members.get(name))
                        .isTrue();
            }
        }
    }

    @Test
    void noPropertyIsBareReferenceToDiscriminatedBase() throws Exception {
        final List<String> bare = new ArrayList<>();
        collectBareReferences(document().path("components").path("schemas"), "", false, bare);

        assertThat(bare).as("references to Expr or PageSpec outside a subtype's allOf link").isEmpty();
    }

    @Test
    void nullLiteralMayCarryNullValue() throws Exception {
        final JsonNode value = ownMembers(document().path("components").path("schemas").path("ValueExpr")).path("properties").path("value");

        assertThat(value.path("type").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("string", "null");
    }

    @Test
    void everyFilterPropertyRefersToTheOneFilterNodeUnion() throws Exception {
        final JsonNode schemas = document().path("components").path("schemas");
        final List<String> nodeTypes = new ArrayList<>();
        schemas.path("FilterNode").path("oneOf").forEach(member -> nodeTypes.add(member.path("$ref").asString()));
        final List<String> permitted = new ArrayList<>();
        for (final Class<?> node : FilterNode.class.getPermittedSubclasses()) {
            permitted.add(SCHEMAS_REF + node.getSimpleName());
        }

        assertThat(nodeTypes).as("FilterNode oneOf").containsExactlyInAnyOrderElementsOf(permitted);
        for (final String property : List.of("filter", "having")) {
            assertThat(schemas.path("StructuredQuery").path("properties").path(property).path("$ref").asString(""))
                    .as("StructuredQuery.%s", property).isEqualTo(SCHEMAS_REF + "FilterNode");
        }
    }

    private JsonNode document() throws Exception {
        return OpenApiDocuments.served(mockMvc);
    }

    /** Every subtype of the name-discriminated types, and every filter node type. */
    private static List<Class<?>> subtypes() {
        final List<Class<?>> subtypes = new ArrayList<>();
        for (final Class<?> base : NAME_DISCRIMINATED) {
            for (final JsonSubTypes.Type type : base.getAnnotation(JsonSubTypes.class).value()) {
                subtypes.add(type.value());
            }
        }
        subtypes.addAll(List.of(FilterNode.class.getPermittedSubclasses()));
        return subtypes;
    }

    /** The properties a subtype publishes, from its own {@code allOf} member or its top level. */
    private static Map<String, JsonNode> publishedMembers(JsonNode subtype) {
        final Map<String, JsonNode> members = new LinkedHashMap<>();
        final JsonNode own = subtype.path("properties");
        own.propertyNames().forEach(name -> members.put(name, own.get(name)));
        for (final JsonNode member : subtype.path("allOf")) {
            final JsonNode properties = member.path("properties");
            properties.propertyNames().forEach(name -> members.put(name, properties.get(name)));
        }
        return members;
    }

    /*
     * Whether a published member schema says what it holds: a type, a reference or a composition, and for an
     * array, items that do too. An empty schema ({}) accepts anything and tells a generator nothing.
     */
    private static boolean describesType(JsonNode schema) {
        if (schema == null || !Set.of("type", "$ref", "oneOf", "anyOf", "allOf", "enum", "const").stream().anyMatch(schema::has)) {
            return false;
        }
        final JsonNode type = schema.path("type");
        final boolean array = type.isArray() ? type.valueStream().anyMatch(t -> "array".equals(t.asString())) : "array".equals(type.asString(""));
        return !array || describesType(schema.get("items"));
    }

    /** The JSON names of a record's components, as Jackson reads them. */
    private static List<String> wireNames(Class<?> record) {
        final List<String> names = new ArrayList<>();
        for (final RecordComponent component : record.getRecordComponents()) {
            final JsonProperty property = component.getAccessor().getAnnotation(JsonProperty.class);
            names.add(property == null || property.value().isEmpty() ? component.getName() : property.value());
        }
        return names;
    }

    private static JsonNode ownMembers(JsonNode subtype) {
        for (final JsonNode member : subtype.path("allOf")) {
            if (member.has("properties")) {
                return member;
            }
        }
        return subtype;
    }

    private static void collectBareReferences(JsonNode node, String path, boolean inAllOf, List<String> bare) {
        if (node.isObject()) {
            final String ref = node.path("$ref").asString("");
            if (!inAllOf && NAME_DISCRIMINATED.stream().anyMatch(base -> ref.equals(SCHEMAS_REF + base.getSimpleName()))) {
                bare.add(path);
            }
            for (final String name : node.propertyNames()) {
                collectBareReferences(node.get(name), path + "/" + name, false, bare);
            }
        } else if (node.isArray()) {
            final boolean allOf = path.endsWith("/allOf");
            for (int i = 0; i < node.size(); i++) {
                collectBareReferences(node.get(i), path + "/" + i, allOf, bare);
            }
        }
    }
}
