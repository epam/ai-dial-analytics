package com.epam.aidial.analytics.service.query.arrow;

import com.epam.aidial.analytics.service.query.sql.ResultColumn;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Type mapping, null preservation, value coercion, and batch boundary behavior (design D4/D5). */
class ArrowBatchWriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final List<ResultColumn> ALL_KINDS = List.of(
            new ResultColumn("s", ResultColumnKind.STRING),
            new ResultColumn("i", ResultColumnKind.INT32),
            new ResultColumn("l", ResultColumnKind.INT64),
            new ResultColumn("f", ResultColumnKind.FLOAT64),
            new ResultColumn("d", ResultColumnKind.DECIMAL),
            new ResultColumn("b", ResultColumnKind.BOOL),
            new ResultColumn("dt", ResultColumnKind.DATE),
            new ResultColumn("ts", ResultColumnKind.TIMESTAMP));

    @Test
    void schemaMapsEveryKindNullable() {
        final Schema schema = ArrowTypeMapper.schema(ALL_KINDS);
        assertThat(schema.getFields()).hasSize(8);
        assertThat(schema.getFields()).allMatch(f -> f.getFieldType().isNullable());
        assertThat(schema.findField("s").getType()).isEqualTo(ArrowType.Utf8.INSTANCE);
        assertThat(schema.findField("i").getType()).isEqualTo(new ArrowType.Int(32, true));
        assertThat(schema.findField("l").getType()).isEqualTo(new ArrowType.Int(64, true));
        assertThat(schema.findField("d").getType()).isEqualTo(new ArrowType.Decimal(38, 9, 128));
        assertThat(schema.findField("ts").getType()).isEqualTo(new ArrowType.Timestamp(TimeUnit.MILLISECOND, "UTC"));
    }

    @Test
    void coercesClickHouseNativeValuesAndPreservesNulls() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("s", UUID.fromString("00000000-0000-4000-8000-000000000001")); // uuid surfaces as string
        row.put("i", (short) 7); // narrower numeric type than declared
        row.put("l", java.math.BigInteger.valueOf(123L)); // UInt64 surfaces as BigInteger
        row.put("f", 2.5d);
        row.put("d", new BigDecimal("12.34")); // rescaled to (38, 9)
        row.put("b", (byte) 1); // ClickHouse Bool may surface numerically
        row.put("dt", LocalDate.of(2026, 7, 1));
        row.put("ts", LocalDateTime.of(2026, 7, 1, 12, 0)); // zoneless DateTime64 is UTC by convention
        final Map<String, Object> nulls = new LinkedHashMap<>();
        ALL_KINDS.forEach(c -> nulls.put(c.name(), null));

        try (BufferAllocator allocator = new RootAllocator();
                VectorSchemaRoot root = VectorSchemaRoot.create(ArrowTypeMapper.schema(ALL_KINDS), allocator)) {
            final List<Integer> batchSizes = new ArrayList<>();
            final List<Object> firstRow = new ArrayList<>();
            ArrowBatchWriter.drain(root, List.of(row, nulls), ALL_KINDS, 1024, 1 << 22, allocator, JSON, batch -> {
                batchSizes.add(batch.getRowCount());
                if (firstRow.isEmpty()) {
                    for (int col = 0; col < ALL_KINDS.size(); col++) {
                        firstRow.add(batch.getVector(col).getObject(0));
                    }
                    for (int col = 0; col < ALL_KINDS.size(); col++) {
                        assertThat(batch.getVector(col).isNull(1))
                                .as("row 1 column %s must be null", ALL_KINDS.get(col).name())
                                .isTrue();
                    }
                }
            });
            assertThat(batchSizes).containsExactly(2);
            assertThat(firstRow.get(0)).hasToString("00000000-0000-4000-8000-000000000001");
            assertThat(firstRow.get(1)).isEqualTo(7);
            assertThat(firstRow.get(2)).isEqualTo(123L);
            assertThat(firstRow.get(3)).isEqualTo(2.5d);
            assertThat(firstRow.get(4)).isEqualTo(new BigDecimal("12.34").setScale(9));
            assertThat(firstRow.get(5)).isEqualTo(true);
            assertThat(firstRow.get(6)).isEqualTo((int) LocalDate.of(2026, 7, 1).toEpochDay());
            assertThat(timestampMillis(firstRow.get(7)))
                    .isEqualTo(Instant.parse("2026-07-01T12:00:00Z").toEpochMilli());
        }
    }

    @Test
    void writesArrayCellsAsListValues() {
        final List<ResultColumn> columns =
                List.of(new ResultColumn("a", ResultColumnKind.ARRAY, ResultColumnKind.STRING));
        final List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("a", List.of("core", "assistant")));
        rows.add(Map.of("a", List.of())); // empty array stays an empty, non-null list
        final Map<String, Object> nullRow = new LinkedHashMap<>();
        nullRow.put("a", null);
        rows.add(nullRow);
        rows.add(Map.of("a", new Object[] {"tail"})); // Object[] cells are accepted too

        try (BufferAllocator allocator = new RootAllocator();
                VectorSchemaRoot root = VectorSchemaRoot.create(ArrowTypeMapper.schema(columns), allocator)) {
            final List<List<String>> values = new ArrayList<>();
            final List<Boolean> nulls = new ArrayList<>();
            // batchRows 3 exercises list writing across a flush/clear boundary.
            ArrowBatchWriter.drain(root, rows, columns, 3, Long.MAX_VALUE, allocator, JSON, batch -> {
                final ListVector vector = (ListVector) batch.getVector(0);
                for (int i = 0; i < batch.getRowCount(); i++) {
                    nulls.add(vector.isNull(i));
                    values.add(vector.isNull(i) ? null : toStrings(vector.getObject(i)));
                }
            });
            assertThat(nulls).containsExactly(false, false, true, false);
            assertThat(values).containsExactly(
                    List.of("core", "assistant"), List.of(), null, List.of("tail"));
        }
    }

    @Test
    void writesNumericArrayElementsWithScalarCoercion() {
        final List<ResultColumn> columns =
                List.of(new ResultColumn("a", ResultColumnKind.ARRAY, ResultColumnKind.INT64));
        final List<Map<String, Object>> rows =
                List.of(Map.of("a", List.of((short) 1, java.math.BigInteger.valueOf(2L), 3L)));

        try (BufferAllocator allocator = new RootAllocator();
                VectorSchemaRoot root = VectorSchemaRoot.create(ArrowTypeMapper.schema(columns), allocator)) {
            final List<Object> values = new ArrayList<>();
            ArrowBatchWriter.drain(root, rows, columns, 1024, Long.MAX_VALUE, allocator, JSON, batch ->
                    values.addAll(((ListVector) batch.getVector(0)).getObject(0)));
            assertThat(values).containsExactly(1L, 2L, 3L);
        }
    }

    private static List<String> toStrings(List<?> items) {
        return items.stream().map(Object::toString).toList();
    }

    @Test
    void writesObjectCellsAsJsonText() {
        // The ClickHouse client surfaces a JSON column as a Map; toString would emit Java map
        // notation ({sub=user-1}), so the cell must go through Jackson.
        final List<ResultColumn> columns = List.of(new ResultColumn("o", ResultColumnKind.OBJECT));
        final Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("sub", "user-1");
        nested.put("roles", List.of("admin", "dev"));
        final List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("o", nested));
        rows.add(Map.of("o", Map.of())); // empty object stays a non-null "{}"
        rows.add(Map.of("o", "plain-text")); // any Java type goes through Jackson — no pass-through
        final Map<String, Object> nullRow = new LinkedHashMap<>();
        nullRow.put("o", null);
        rows.add(nullRow);

        try (BufferAllocator allocator = new RootAllocator();
                VectorSchemaRoot root = VectorSchemaRoot.create(ArrowTypeMapper.schema(columns), allocator)) {
            final List<String> values = new ArrayList<>();
            ArrowBatchWriter.drain(root, rows, columns, 1024, Long.MAX_VALUE, allocator, JSON, batch -> {
                final VarCharVector vector = (VarCharVector) batch.getVector(0);
                for (int i = 0; i < batch.getRowCount(); i++) {
                    values.add(vector.isNull(i) ? null : vector.getObject(i).toString());
                }
            });
            assertThat(values).containsExactly(
                    "{\"sub\":\"user-1\",\"roles\":[\"admin\",\"dev\"]}",
                    "{}",
                    "\"plain-text\"",
                    null);
        }
    }

    @Test
    void flushesOnRowBoundary() {
        final List<ResultColumn> columns = List.of(new ResultColumn("v", ResultColumnKind.INT64));
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (long i = 0; i < 5; i++) {
            rows.add(Map.of("v", i));
        }
        try (BufferAllocator allocator = new RootAllocator();
                VectorSchemaRoot root = VectorSchemaRoot.create(ArrowTypeMapper.schema(columns), allocator)) {
            final List<Integer> batchSizes = new ArrayList<>();
            final List<Long> values = new ArrayList<>();
            ArrowBatchWriter.drain(root, rows, columns, 2, Long.MAX_VALUE, allocator, JSON, batch -> {
                batchSizes.add(batch.getRowCount());
                final BigIntVector vector = (BigIntVector) batch.getVector(0);
                for (int i = 0; i < batch.getRowCount(); i++) {
                    values.add(vector.get(i));
                }
            });
            assertThat(batchSizes).containsExactly(2, 2, 1);
            assertThat(values).containsExactly(0L, 1L, 2L, 3L, 4L);
        }
    }

    @Test
    void emptyResultInvokesSinkNever() {
        final List<ResultColumn> columns = List.of(new ResultColumn("v", ResultColumnKind.STRING));
        try (BufferAllocator allocator = new RootAllocator();
                VectorSchemaRoot root = VectorSchemaRoot.create(ArrowTypeMapper.schema(columns), allocator)) {
            final List<Integer> batchSizes = new ArrayList<>();
            ArrowBatchWriter.drain(
                    root, List.of(), columns, 1024, Long.MAX_VALUE, allocator, JSON, b -> batchSizes.add(b.getRowCount()));
            assertThat(batchSizes).isEmpty();
        }
    }

    @Test
    void allocatorIsCleanAfterUse() {
        final List<ResultColumn> columns = List.of(new ResultColumn("v", ResultColumnKind.STRING));
        try (BufferAllocator allocator = new RootAllocator()) {
            try (VectorSchemaRoot root = VectorSchemaRoot.create(ArrowTypeMapper.schema(columns), allocator)) {
                final VarCharVector unused = (VarCharVector) root.getVector(0);
                ArrowBatchWriter.drain(root, List.of(Map.of("v", "x")), columns, 1024, Long.MAX_VALUE, allocator,
                        JSON, batch -> assertThat(unused.getObject(0)).hasToString("x"));
            }
            assertThat(allocator.getAllocatedMemory()).isZero();
        }
    }

    private static long timestampMillis(Object value) {
        return switch (value) {
            case Long millis -> millis;
            case LocalDateTime local -> local.toInstant(ZoneOffset.UTC).toEpochMilli();
            default -> Instant.parse(value.toString()).toEpochMilli();
        };
    }
}
