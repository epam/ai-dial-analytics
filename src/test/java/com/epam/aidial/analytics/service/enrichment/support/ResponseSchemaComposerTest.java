package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.TransformOutput;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link ResponseSchemaComposer} — the class that makes the drift unrepresentable.
 *
 * <p>The shapes here are <b>synthetic</b>, in the same invented vocabulary as the transforms
 * under {@code src/test/resources/fixtures}, and declared inline rather than loaded: a composer takes
 * columns and outputs, so building the two argument lists in the test that asserts on them is shorter
 * than a fixture plus the loader that reads it. They are chosen to carry the three structural drifts
 * this composer removes: an enum whose column order differs from the schema's, a closed domain on a column typed plain
 * {@code string}, and one numeric fact spelled differently at each end. The rationale for each is in
 * {@code openspec/changes/archive/2026-09-10-simplify-pipeline-authoring/design.md}; it is cited there
 * rather than reproduced here, so this test states the property it depends on instead of inheriting it.
 */
class ResponseSchemaComposerTest {

    private final ResponseSchemaComposer composer = new ResponseSchemaComposer();

    private static ColumnMappingEntity column(String name, QueryFieldType type) {
        final ColumnMappingEntity column = new ColumnMappingEntity();
        column.setTableName("widget_insights");
        column.setSourceName(name);
        column.setName(name);
        column.setType(type);
        column.setNullable(true);
        return column;
    }

    private static ColumnMappingEntity enumColumn(String name, List<String> values) {
        final ColumnMappingEntity column = column(name, QueryFieldType.ENUM);
        column.setEnumValues(values);
        return column;
    }

    private static ColumnMappingEntity arrayColumn(String name, QueryFieldType element) {
        final ColumnMappingEntity column = column(name, QueryFieldType.ARRAY);
        column.setElementType(element);
        return column;
    }

    private static TransformOutput prose(String name, String text) {
        final TransformOutput output = new TransformOutput();
        output.setName(name);
        output.setProse(text);
        return output;
    }

    private static TransformOutput withValues(String name, String text, List<String> values) {
        final TransformOutput output = prose(name, text);
        output.setValues(values);
        return output;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertyOf(Map<String, Object> schema, String name) {
        return (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get(name);
    }

    // --- the drifts this exists to remove -------------------------------------------------------------

    @Test
    void enumDomainAndOrderComeFromTheColumn() {
        // The drift this removes: a stored schema and its target column each declared the same enum
        // and were ordered differently, with nothing comparing them. The column's order assigns the numeric
        // ids and therefore the sort order, so composing from the column is what makes them unable to
        // disagree — and membership, not order, is the half that carries correctness.
        final Map<String, Object> schema = composer.compose(
                List.of(prose("sentiment", "The end user's overall sentiment.")),
                List.of(enumColumn("sentiment", List.of("negative", "neutral", "positive", "mixed"))),
                "widget_insights");

        assertThat(propertyOf(schema, "sentiment"))
                .containsEntry("type", "string")
                .containsEntry("enum", List.of("negative", "neutral", "positive", "mixed"));
    }

    @Test
    void declaredValuesCarryDomainTheColumnCannotHold() {
        // The `detail` column is plain `string` ON PURPOSE: enum_values is immutable once the column
        // exists (widening means drop and re-add), and this field's value set is expected to move. A
        // column that cannot express a domain cannot be its authority, so the output carries it.
        final Map<String, Object> schema = composer.compose(
                List.of(withValues("detail", "The kind of work inside the session.",
                        List.of("none", "other", "assembly"))),
                List.of(column("detail", QueryFieldType.STRING)),
                "widget_insights");

        assertThat(propertyOf(schema, "detail"))
                .containsEntry("type", "string")
                .containsEntry("enum", List.of("none", "other", "assembly"));
    }

    @Test
    void valuesOnColumnDeclaringItsOwnDomainIsRefusedNamingBoth() {
        assertThatThrownBy(() -> composer.compose(
                List.of(withValues("sentiment", "…", List.of("good", "bad"))),
                List.of(enumColumn("sentiment", List.of("negative", "positive"))),
                "widget_insights"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("sentiment")
                .hasMessageContaining("enum_values")
                .hasMessageContaining("one authority");
    }

    @Test
    void valuesAgainstNonStringColumnIsRefused() {
        // Checked against the COLUMN's type, not the composed property's: a timestamp column also composes
        // to a JSON string, and a declared value set is not a domain for one.
        assertThatThrownBy(() -> composer.compose(
                List.of(withValues("enriched_at", "…", List.of("a", "b"))),
                List.of(column("enriched_at", QueryFieldType.TIMESTAMP)),
                "widget_insights"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("only meaningful for a string column");
    }

    // --- order, which is the defect with money attached ----------------------------------------------

    @Test
    void propertyAndRequiredOrderFollowTheDeclaration() {
        // The defect this replaces: response_schema was stored as jsonb, which normalises an object's keys
        // to length-then-bytes — so a field instructed to derive itself from two siblings was generated
        // AHEAD of both of them, because its name happened to be shorter. Declared order is the only
        // spelling that lets an author put the inputs before the derivation.
        final Map<String, Object> schema = composer.compose(
                List.of(prose("exposure", "What the user supplied."),
                        prose("prohibited", "What the user asked for."),
                        prose("risk", "Severity DERIVED from the two fields above.")),
                List.of(column("risk", QueryFieldType.STRING),
                        column("exposure", QueryFieldType.STRING),
                        column("prohibited", QueryFieldType.STRING)),
                "widget_insights");

        // Declared order, NOT the column order above (which is deliberately the reverse) and not
        // length-then-bytes.
        assertThat(((Map<String, Object>) schema.get("properties")).keySet())
                .containsExactly("exposure", "prohibited", "risk");
        assertThat(schema.get("required"))
                .isEqualTo(List.of("exposure", "prohibited", "risk"));
    }

    @Test
    void everyDeclaredOutputIsRequiredAndNoOthersAreAllowed() {
        // Not derived from column nullability: strict mode requires every property to be required, so
        // there is no optional-output shape to express. A nullable column still yields a required field.
        final Map<String, Object> schema = composer.compose(
                List.of(prose("title", "…")),
                List.of(column("title", QueryFieldType.STRING)),
                "widget_insights");

        assertThat(schema).containsEntry("type", "object")
                .containsEntry("required", List.of("title"))
                .containsEntry("additionalProperties", false);
    }

    // --- the type mapping ----------------------------------------------------------------------------

    @Test
    void integralColumnsMapToIntegerAndFractionalToNumber() {
        final Map<String, Object> schema = composer.compose(
                List.of(prose("vote_count", "…"), prose("tag_length", "…"), prose("score", "…")),
                List.of(column("vote_count", QueryFieldType.INTEGER),
                        column("tag_length", QueryFieldType.LONG),
                        column("score", QueryFieldType.DECIMAL)),
                "widget_insights");

        assertThat(propertyOf(schema, "vote_count")).containsEntry("type", "integer");
        assertThat(propertyOf(schema, "tag_length")).containsEntry("type", "integer");
        // One numeric fact had three spellings — `double` on the output var, `decimal` on the column and
        // `number` in the schema. Now the column is the only declaration and `number` is derived from it.
        assertThat(propertyOf(schema, "score")).containsEntry("type", "number");
    }

    @Test
    void timestampCarriesNoFormatKeyword() {
        // Strict mode rejects `format`, so a timestamp property is a plain string and the value is parsed
        // on the write side, as it already is.
        assertThat(propertyOf(composer.compose(
                List.of(prose("enriched_at", "…")),
                List.of(column("enriched_at", QueryFieldType.TIMESTAMP)),
                "widget_insights"), "enriched_at"))
                .containsEntry("type", "string")
                .doesNotContainKey("format");
    }

    @Test
    void booleanColumnMapsToBoolean() {
        assertThat(propertyOf(composer.compose(
                List.of(prose("truncated", "…")),
                List.of(column("truncated", QueryFieldType.BOOLEAN)),
                "widget_insights"), "truncated"))
                .containsEntry("type", "boolean");
    }

    @Test
    void arrayColumnMapsItsElementType() {
        assertThat(propertyOf(composer.compose(
                List.of(prose("operator_roles", "…")),
                List.of(arrayColumn("operator_roles", QueryFieldType.STRING)),
                "widget_identity"), "operator_roles"))
                .containsEntry("type", "array")
                .containsEntry("items", Map.of("type", "string"));
    }

    @Test
    void columnTypeWithNoStrictModeRepresentationIsRefusedNamingIt() {
        for (final QueryFieldType unrepresentable : List.of(QueryFieldType.MAP, QueryFieldType.OBJECT)) {
            assertThatThrownBy(() -> composer.compose(
                    List.of(prose("payload", "…")),
                    List.of(column("payload", unrepresentable)),
                    "widget_insights"))
                    .isInstanceOf(PipelineValidationException.class)
                    .hasMessageContaining("payload")
                    .hasMessageContaining(unrepresentable.code());
        }
    }

    // --- name matching -------------------------------------------------------------------------------

    @Test
    void proseBecomesTheDescription() {
        assertThat(propertyOf(composer.compose(
                List.of(prose("title", "Title of the session, at most 8 words, English.")),
                List.of(column("title", QueryFieldType.STRING)),
                "widget_insights"), "title"))
                .containsEntry("description", "Title of the session, at most 8 words, English.");
    }

    @Test
    void anOutputMatchingNoColumnIsRefusedSayingWhyTheNamesAreCoupled() {
        assertThatThrownBy(() -> composer.compose(
                List.of(prose("score", "…")),
                List.of(column("title", QueryFieldType.STRING)),
                "widget_insights"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("score")
                .hasMessageContaining("target column of the same name");
    }

    @Test
    void anOutputMatchingOnlyThePhysicalNameNamesTheExposedSpelling() {
        // Matched against the exposed name ONLY, deliberately not through the catalog's usual
        // name-or-source_name lookup, which would make an output name ambiguous in principle. The refusal
        // names the spelling to write, which is more actionable than "no such column".
        final ColumnMappingEntity renamed = column("exposed_title", QueryFieldType.STRING);
        renamed.setSourceName("physical_title");

        assertThatThrownBy(() -> composer.compose(
                List.of(prose("physical_title", "…")), List.of(renamed), "widget_insights"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("matches only the physical name")
                .hasMessageContaining("exposed_title");
    }

    @Test
    void targetColumnNoOutputWritesIsAbsentFromTheSchema() {
        // How a deprecated column keeps its historical values: nothing writes it, so it is in neither the
        // schema nor the mapping.
        final Map<String, Object> schema = composer.compose(
                List.of(prose("detail", "…")),
                List.of(column("detail", QueryFieldType.STRING),
                        column("legacy_kind", QueryFieldType.STRING)),
                "widget_insights");

        assertThat(((Map<String, Object>) schema.get("properties"))).containsOnlyKeys("detail");
    }
}
