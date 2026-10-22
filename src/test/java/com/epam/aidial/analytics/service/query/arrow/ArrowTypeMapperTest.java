package com.epam.aidial.analytics.service.query.arrow;

import com.epam.aidial.analytics.service.query.sql.ResultColumn;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The normative kind → Arrow mapping, including the List encoding of array columns. */
class ArrowTypeMapperTest {

    @Test
    void arrayColumnMapsToListWithTypedItemChild() {
        final Field field = ArrowTypeMapper.field(
                new ResultColumn("execution_path", ResultColumnKind.ARRAY, ResultColumnKind.STRING));
        assertThat(field.getType()).isEqualTo(ArrowType.List.INSTANCE);
        assertThat(field.getFieldType().isNullable()).isTrue();
        assertThat(field.getChildren()).hasSize(1);
        final Field item = field.getChildren().getFirst();
        assertThat(item.getName()).isEqualTo(ArrowTypeMapper.LIST_ITEM_NAME);
        assertThat(item.getType()).isEqualTo(ArrowType.Utf8.INSTANCE);
        assertThat(item.getFieldType().isNullable()).isTrue();
    }

    @Test
    void everyScalarKindWorksAsArrayElement() {
        // Totality: the element child mapping must cover every scalar kind, so a user-declared
        // array of any element type (string/integer/long/decimal/boolean/date/timestamp/uuid)
        // has an Arrow encoding by construction.
        for (final ResultColumnKind kind : ResultColumnKind.values()) {
            // ARRAY and OBJECT are not declarable element types — the catalog rejects nested arrays
            // and JSON elements at declaration time.
            if (kind == ResultColumnKind.ARRAY || kind == ResultColumnKind.OBJECT) {
                continue;
            }
            final Field field = ArrowTypeMapper.field(new ResultColumn("c", ResultColumnKind.ARRAY, kind));
            assertThat(field.getChildren().getFirst().getType())
                    .as("element kind %s", kind)
                    .isEqualTo(ArrowTypeMapper.arrowType(kind));
        }
    }

    @Test
    void nonScalarKindsHaveNoScalarArrowType() {
        // arrowType is the scalar-only table: both non-scalar kinds must fail here, so an array
        // declaring one as its element kind fails at schema time rather than mid-stream.
        assertThatThrownBy(() -> ArrowTypeMapper.arrowType(ResultColumnKind.ARRAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArrowTypeMapper.arrowType(ResultColumnKind.OBJECT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArrowTypeMapper.field(
                new ResultColumn("c", ResultColumnKind.ARRAY, ResultColumnKind.OBJECT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void objectColumnMapsToUtf8WithNoChildren() {
        // A ClickHouse JSON column is schemaless and row-heterogeneous, so Arrow carries it as
        // JSON text rather than a Struct (one fixed field set per batch) or a Map.
        final Field field = ArrowTypeMapper.field(new ResultColumn("jwt_claims", ResultColumnKind.OBJECT));
        assertThat(field.getType()).isEqualTo(ArrowType.Utf8.INSTANCE);
        assertThat(field.getFieldType().isNullable()).isTrue();
        assertThat(field.getChildren()).isEmpty();
    }

    @Test
    void schemaMixesScalarArrayAndObjectColumns() {
        final Schema schema = ArrowTypeMapper.schema(List.of(
                new ResultColumn("request_id", ResultColumnKind.STRING),
                new ResultColumn("execution_path", ResultColumnKind.ARRAY, ResultColumnKind.STRING),
                new ResultColumn("jwt_claims", ResultColumnKind.OBJECT)));
        assertThat(schema.findField("request_id").getType()).isEqualTo(ArrowType.Utf8.INSTANCE);
        assertThat(schema.findField("execution_path").getType()).isEqualTo(ArrowType.List.INSTANCE);
        assertThat(schema.findField("execution_path").getChildren()).hasSize(1);
        assertThat(schema.findField("jwt_claims").getType()).isEqualTo(ArrowType.Utf8.INSTANCE);
        assertThat(schema.findField("jwt_claims").getChildren()).isEmpty();
    }
}
