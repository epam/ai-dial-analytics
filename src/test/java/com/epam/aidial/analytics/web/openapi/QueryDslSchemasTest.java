package com.epam.aidial.analytics.web.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryDslSchemasTest {

    private static final String EXPR = "#/components/schemas/Expr";
    private static final List<String> EXPRESSION_TYPES = List.of("ArrayExpr", "CaseExpr", "FieldExpr", "FnExpr", "LambdaExpr",
            "LambdaParamExpr", "ParamExpr", "ValueExpr", "WindowExpr");

    @Test
    void writesTheJacksonSubtypeNamesAsTheDiscriminatorMapping() {
        final OpenAPI openApi = document();

        new QueryDslSchemas().customise(openApi);

        assertThat(schema(openApi, "Expr").getDiscriminator().getMapping()).containsExactly(
                Map.entry("field", "#/components/schemas/FieldExpr"),
                Map.entry("value", "#/components/schemas/ValueExpr"),
                Map.entry("param", "#/components/schemas/ParamExpr"),
                Map.entry("fn", "#/components/schemas/FnExpr"),
                Map.entry("array", "#/components/schemas/ArrayExpr"),
                Map.entry("case", "#/components/schemas/CaseExpr"),
                Map.entry("window", "#/components/schemas/WindowExpr"),
                Map.entry("lambda", "#/components/schemas/LambdaExpr"),
                Map.entry("lambda_param", "#/components/schemas/LambdaParamExpr"));
        assertThat(schema(openApi, "PageSpec").getDiscriminator().getMapping()).containsExactly(
                Map.entry("offset", "#/components/schemas/OffsetPage"),
                Map.entry("cursor", "#/components/schemas/CursorPage"));
    }

    @Test
    void spellsBareBaseReferencesAsTheOneOfItsSubtypes() {
        final OpenAPI openApi = document();

        new QueryDslSchemas().customise(openApi);

        final Schema<?> items = ownMembers(schema(openApi, "FnExpr")).getProperties().get("args").getItems();
        assertThat(items.get$ref()).isNull();
        assertThat(refs(items.getOneOf())).containsExactlyElementsOf(EXPRESSION_TYPES.stream().map(name -> "#/components/schemas/" + name).toList());
        assertThat(refs(schema(openApi, "Holder").getProperties().get("page").getOneOf()))
                .containsExactly("#/components/schemas/CursorPage", "#/components/schemas/OffsetPage");
    }

    @Test
    void keepsTheSubtypeLinkToItsBase() {
        final OpenAPI openApi = document();

        new QueryDslSchemas().customise(openApi);

        assertThat(schema(openApi, "FnExpr").getAllOf().getFirst().get$ref()).isEqualTo(EXPR);
    }

    @Test
    void leavesDocumentsWithoutTheDslTypesUnchanged() {
        final OpenAPI openApi = new OpenAPI().components(new Components().addSchemas("Other", new JsonSchema().$ref(EXPR)));

        new QueryDslSchemas().customise(openApi);

        assertThat(schema(openApi, "Other").get$ref()).isEqualTo(EXPR);
    }

    /* The shape springdoc publishes: discriminated bases, a subtype linked by allOf, and bare references to the bases. */
    private static OpenAPI document() {
        final Schema<?> fnMembers = new JsonSchema().addProperty("name", new JsonSchema().typesItem("string"))
                .addProperty("args", new JsonSchema().typesItem("array").items(new JsonSchema().$ref(EXPR)));
        final Schema<?> fnExpr = new JsonSchema().addAllOfItem(new JsonSchema().$ref(EXPR)).addAllOfItem(fnMembers);
        return new OpenAPI().components(new Components()
                .addSchemas("Expr", new JsonSchema().discriminator(new Discriminator().propertyName("type")))
                .addSchemas("PageSpec", new JsonSchema().discriminator(new Discriminator().propertyName("type")))
                .addSchemas("FnExpr", fnExpr)
                .addSchemas("Holder", new JsonSchema().addProperty("page", new JsonSchema().$ref("#/components/schemas/PageSpec"))));
    }

    private static Schema<?> schema(OpenAPI openApi, String name) {
        return openApi.getComponents().getSchemas().get(name);
    }

    private static List<String> refs(List<Schema> schemas) {
        return schemas.stream().map(Schema::get$ref).toList();
    }

    private static Schema<?> ownMembers(Schema<?> subtype) {
        return subtype.getAllOf().get(1);
    }
}
