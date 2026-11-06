package com.epam.aidial.analytics.clickhouse;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class QueryParameterChunksTest {

    @Test
    void splitsIntoOrderedChunksOfAtMostTheBoundSize() {
        final List<Integer> values = IntStream.range(0, QueryParameterChunks.SIZE * 2 + 1).boxed().toList();

        final List<List<Integer>> chunks = QueryParameterChunks.of(values);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(QueryParameterChunks.SIZE).startsWith(0);
        assertThat(chunks.get(1)).hasSize(QueryParameterChunks.SIZE).startsWith(QueryParameterChunks.SIZE);
        assertThat(chunks.get(2)).containsExactly(QueryParameterChunks.SIZE * 2);
    }

    @Test
    void noValuesMeansNoStatement() {
        assertThat(QueryParameterChunks.of(List.of())).isEmpty();
    }
}
