package com.epam.aidial.analytics.web.flight;

import com.epam.aidial.analytics.service.query.CatalogQueryService;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Catalog row-building failure semantics (spec: catalog RPCs allocate from a bounded per-request child
 * allocator that is always closed). Row building reads the catalog — a database round trip, and one
 * more per row when {@code include_schema} is set — so it can fail after Arrow buffers are taken. When
 * it does, nothing may stay allocated: the half-built root never reaches the caller's
 * try-with-resources, so only the builder can close it, and an un-closeable request allocator would
 * wedge the root allocator's byte count above zero for the process's life.
 */
class FlightCatalogStreamsTest {

    private final CatalogQueryService catalog = mock(CatalogQueryService.class);
    private final FlightCatalogStreams streams = new FlightCatalogStreams(catalog);

    @Test
    void tablesReleasesArrowMemoryWhenTheCatalogListingFails() {
        when(catalog.queryableEntityNames(any())).thenThrow(new IllegalStateException("catalog unavailable"));

        try (RootAllocator allocator = new RootAllocator()) {
            assertThatThrownBy(() -> streams.tables(FlightSql.CommandGetTables.getDefaultInstance(), allocator))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(allocator.getAllocatedMemory()).isZero();
        }
    }

    @Test
    void tablesReleasesArrowMemoryWhenAnEntitySchemaCannotBeResolved() {
        // include_schema forces a per-row model lookup; an entity that vanished mid-request fails it
        // after the first rows have already been written into allocated buffers.
        when(catalog.queryableEntityNames(any())).thenReturn(List.of("dial_usage_log"));
        when(catalog.findModel(eq("dial_usage_log"), any())).thenReturn(Optional.empty());
        final FlightSql.CommandGetTables command = FlightSql.CommandGetTables.newBuilder()
                .setIncludeSchema(true)
                .build();

        try (RootAllocator allocator = new RootAllocator()) {
            assertThatThrownBy(() -> streams.tables(command, allocator)).isNotNull();
            assertThat(allocator.getAllocatedMemory()).isZero();
        }
    }

    @Test
    void successfulBuildHandsTheRootToTheCallerToClose() {
        try (RootAllocator allocator = new RootAllocator()) {
            try (VectorSchemaRoot root = streams.tableTypes(allocator)) {
                assertThat(root.getRowCount()).isEqualTo(1);
            }
            assertThat(allocator.getAllocatedMemory()).isZero();
        }
    }
}
