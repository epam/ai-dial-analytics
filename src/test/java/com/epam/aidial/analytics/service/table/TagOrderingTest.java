package com.epam.aidial.analytics.service.table;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit coverage for the shared {@link TagOrdering} comparator used by both read surfaces. */
class TagOrderingTest {

    private record Item(String tag, String name) {}

    private static List<String> order(List<String> tagOrder, List<Item> items) {
        return items.stream()
                .sorted(TagOrdering.comparator(tagOrder, Item::tag, Item::name))
                .map(Item::name)
                .toList();
    }

    @Test
    void groupsListedThenUnlistedAlphaThenUntaggedWithAlphaWithinEachGroup() {
        final List<Item> items = List.of(
                new Item("cost", "price"),
                new Item("cost", "amount"),
                new Item("identifier", "widget_id"),
                new Item("beta", "b1"),
                new Item("alpha", "a1"),
                new Item(null, "note"),
                new Item(null, "aaa"));

        assertThat(order(List.of("identifier", "cost"), items))
                .containsExactly("widget_id", "amount", "price", "a1", "b1", "aaa", "note");
    }

    @Test
    void unlistedTagsAreAlphabeticalAndFollowEveryListedGroup() {
        final List<Item> items = List.of(
                new Item("zeta", "z"),
                new Item("alpha", "a"),
                new Item("listed_tag", "in_order"));

        assertThat(order(List.of("listed_tag"), items)).containsExactly("in_order", "a", "z");
    }

    @Test
    void untaggedItemsAlwaysComeLast() {
        final List<Item> items = List.of(
                new Item(null, "z_untagged"),
                new Item("anything", "tagged"));

        assertThat(order(List.of(), items)).containsExactly("tagged", "z_untagged");
    }
}
