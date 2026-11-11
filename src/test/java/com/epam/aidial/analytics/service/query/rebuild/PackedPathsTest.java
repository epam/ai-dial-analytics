package com.epam.aidial.analytics.service.query.rebuild;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackedPathsTest {

    private static final UUID FIRST = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SECOND = UUID.fromString("ffffffff-ffff-4fff-bfff-fffffffffffe");

    @Test
    void returnsThePathsInOrderAndEqualsAnOrdinaryList() {
        final List<UUID> packed = PackedPaths.of(List.of(FIRST, SECOND));

        assertThat(packed).containsExactly(FIRST, SECOND);
        assertThat(packed).isEqualTo(List.of(FIRST, SECOND));
        assertThat(packed.stream().filter(SECOND::equals).count()).isEqualTo(1);
    }

    @Test
    void emptyStaysEmpty() {
        assertThat(PackedPaths.of(List.of())).isEmpty();
    }

    @Test
    void isReadOnlyAndBoundsChecked() {
        final List<UUID> packed = PackedPaths.of(List.of(FIRST));

        assertThatThrownBy(() -> packed.add(SECOND)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> packed.get(1)).isInstanceOf(IndexOutOfBoundsException.class);
    }
}
