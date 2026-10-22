package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.clickhouse.CatalogRowWriter;
import com.epam.aidial.analytics.configuration.TableLimitsProperties;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.table.relayout.RelayoutStateStore;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit coverage for {@link TableDataService#writeAll}, the uncapped write the OTel sink uses: every row is
 * validated before the first insert, so a value rejected anywhere writes nothing and is named by its index in
 * the whole batch, and the validated rows are inserted in {@code maxWriteBatchRows}-sized chunks.
 */
@ExtendWith(MockitoExtension.class)
class TableDataServiceTest {

    private static final String TABLE = "spans";
    private static final int MAX_WRITE_BATCH_ROWS = 3;

    @Mock
    private TableDefinitionRepository definitions;
    @Mock
    private ColumnMappingRepository columns;
    @Mock
    private CatalogRowWriter rowWriter;
    @Mock
    private RelayoutStateStore relayouts;

    private TableDataService service;

    /** Each insert call's rows, copied on receipt: the service hands the writer {@code subList} views. */
    private final List<List<Map<String, Object>>> inserted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new TableDataService(definitions, columns, rowWriter, new TableLimitsProperties(256, 1000, MAX_WRITE_BATCH_ROWS),
                new WriteValuePreparer(new WriteTimestampNormalizer(), new WriteDateValidator(), new WriteNumericRangeValidator()),
                relayouts);
        final TableDefinitionEntity definition = new TableDefinitionEntity();
        definition.setName(TABLE);
        definition.setType(TableType.SOURCE);
        definition.setStatus(TableStatus.ACTIVE);
        // Lenient: a batch refused before its columns are read, or before any insert, leaves these unused.
        lenient().when(definitions.findById(TABLE)).thenReturn(Optional.of(definition));
        lenient().when(columns.findByTableNameOrderByOrdinal(TABLE)).thenReturn(List.of(
                column("id", QueryFieldType.STRING), column("at", QueryFieldType.TIMESTAMP), column("n", QueryFieldType.INTEGER)));
        lenient().doAnswer(invocation -> inserted.add(List.copyOf(invocation.<List<Map<String, Object>>>getArgument(1))))
                .when(rowWriter).insert(anyString(), anyList());
    }

    @Test
    void insertsInMaxWriteBatchRowsSizedChunksInOrder() {
        final List<Map<String, Object>> rows = rows(8);

        assertThat(service.writeAll(TABLE, rows)).isEqualTo(8);

        assertThat(inserted).extracting(List::size).containsExactly(3, 3, 2);
        assertThat(inserted.stream().flatMap(List::stream).map(row -> row.get("id")))
                .containsExactly("r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8");
        // The chunks carry the prepared rows: the timestamp is in its storage spelling.
        assertThat(inserted.get(2).get(1)).containsEntry("at", "2026-08-11 10:00:00.000");
    }

    @Test
    void batchOfExactlyOneChunkIsOneInsert() {
        service.writeAll(TABLE, rows(MAX_WRITE_BATCH_ROWS));

        assertThat(inserted).extracting(List::size).containsExactly(MAX_WRITE_BATCH_ROWS);
    }

    /** Only {@link TableDataService#writeAll} chunks; {@link TableDataService#write} still refuses past the cap. */
    @Test
    void writeStillRefusesBatchOverTheCap() {
        assertThatThrownBy(() -> service.write(TABLE, rows(MAX_WRITE_BATCH_ROWS + 1)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("at most " + MAX_WRITE_BATCH_ROWS + " rows");
        verify(rowWriter, never()).insert(anyString(), anyList());
    }

    /**
     * A bad value in the last chunk is caught before the first chunk is inserted, and is named by its row
     * number in the whole batch, not its index within its chunk.
     */
    @Test
    void badValueInTheLastChunkRejectsTheWholeBatchNamingTheGlobalRow() {
        final List<Map<String, Object>> rows = rows(8);
        rows.get(7).put("n", 3000000000L);

        assertThatThrownBy(() -> service.writeAll(TABLE, rows))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("row 8, integer column 'n'")
                .hasMessageContaining("out of range");
        verify(rowWriter, never()).insert(anyString(), anyList());
    }

    @Test
    void badTimestampInTheLastChunkRejectsTheWholeBatch() {
        final List<Map<String, Object>> rows = rows(7);
        rows.get(6).put("at", "2026-08-11T10:00:00");

        assertThatThrownBy(() -> service.writeAll(TABLE, rows))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("row 7, timestamp column 'at'");
        verify(rowWriter, never()).insert(anyString(), anyList());
    }

    /** The caller's rows are never mutated: the storage spelling goes into copies. */
    @Test
    void leavesTheCallersRowsUntouched() {
        final List<Map<String, Object>> rows = rows(4);

        service.writeAll(TABLE, rows);

        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("at", "2026-08-11T13:00:00+03:00"));
    }

    /** Only an enrichment is ever relaid out, so a source write never reads the hold. */
    @Test
    void sourceWriteDoesNotConsultTheRelayoutHold() {
        service.write(TABLE, rows(1));

        verify(relayouts, never()).requireNotHeld(anyString());
    }

    @Test
    void refusesAnEmptyBatch() {
        assertThatThrownBy(() -> service.writeAll(TABLE, List.of()))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("at least one row");
        verify(rowWriter, never()).insert(anyString(), anyList());
    }

    private static List<Map<String, Object>> rows(int count) {
        return new ArrayList<>(IntStream.rangeClosed(1, count).mapToObj(i -> {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "r" + i);
            row.put("at", "2026-08-11T13:00:00+03:00");
            row.put("n", i);
            return row;
        }).toList());
    }

    private static ColumnMappingEntity column(String name, QueryFieldType type) {
        final ColumnMappingEntity column = new ColumnMappingEntity();
        column.setTableName(TABLE);
        column.setSourceName(name);
        column.setName(name);
        column.setType(type);
        return column;
    }
}
