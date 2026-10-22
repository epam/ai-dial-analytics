package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies logical-type resolution and output-binding type compatibility — including the property that
 * makes this change's collapse of two type checks into one safe. See
 * {@link #compatibilityIsTransitiveSoTheTwoHopCheckCollapsesSafely}.
 */
class EnrichmentTypeMappingTest {

    private final EnrichmentTypeMapping mapping = new EnrichmentTypeMapping();

    @Test
    void resolvesCatalogCodesAndAliases() {
        assertThat(mapping.resolve("string")).isEqualTo(QueryFieldType.STRING);
        assertThat(mapping.resolve("LONG")).isEqualTo(QueryFieldType.LONG);
        assertThat(mapping.resolve("double")).isEqualTo(QueryFieldType.DECIMAL);
        assertThat(mapping.resolve("datetime")).isEqualTo(QueryFieldType.TIMESTAMP);
        assertThat(mapping.resolve("int")).isEqualTo(QueryFieldType.INTEGER);
    }

    @Test
    void rejectsBlankAndUnknownTypes() {
        assertThatThrownBy(() -> mapping.resolve(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapping.resolve("widget")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compatibilityIsExactOrNumericWidening() {
        assertThat(mapping.isCompatible(QueryFieldType.STRING, QueryFieldType.STRING)).isTrue();
        assertThat(mapping.isCompatible(QueryFieldType.INTEGER, QueryFieldType.LONG)).isTrue();
        assertThat(mapping.isCompatible(QueryFieldType.LONG, QueryFieldType.DECIMAL)).isTrue();
        assertThat(mapping.isCompatible(QueryFieldType.STRING, QueryFieldType.LONG)).isFalse();
        assertThat(mapping.isCompatible(QueryFieldType.BOOLEAN, QueryFieldType.STRING)).isFalse();
    }

    /**
     * An enum column holds strings from a declared domain, and a rule declares the value rather than the
     * domain — so requiring an exact match would leave enum columns unwritable by any enrichment. The
     * relation is deliberately one-way.
     */
    @Test
    void stringOutputBindsToAnEnumColumn() {
        assertThat(mapping.isCompatible(QueryFieldType.STRING, QueryFieldType.ENUM)).isTrue();
        assertThat(mapping.isCompatible(QueryFieldType.ENUM, QueryFieldType.STRING)).isFalse();
        assertThat(mapping.isCompatible(QueryFieldType.TIMESTAMP, QueryFieldType.ENUM)).isFalse();
        assertThat(mapping.isCompatible(QueryFieldType.LONG, QueryFieldType.ENUM)).isFalse();
    }

    /**
     * Refused for the same reason a parameterized {@code array<...>} spelling is: the gate compares
     * shapes, so naming a domain here would advertise a check that never runs.
     */
    @Test
    void rejectsEnumAsAnOutputVariableType() {
        assertThatThrownBy(() -> mapping.resolve("enum"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("let the target column declare the domain");
    }

    /**
     * <b>The collapse of the two type checks into one is provably one-directional, and this proves it
     * exhaustively rather than by sampling.</b>
     *
     * <p>A Class-1 output used to be checked twice, with the declared logical type as the middle hop:
     * {@code inferred ~ declared} at the expression gate, then {@code declared ~ column} at the binding
     * gate. Dropping the declared token collapses those into {@code inferred ~ column} directly. That is
     * safe if and only if compatibility is <b>transitive</b>: for all {@code a}, {@code y}, {@code c}, if
     * {@code a~y} and {@code y~c} then {@code a~c} — because then everything the two-hop check accepted
     * the single check still accepts, and <em>no stored declaration can begin to fail</em>.
     *
     * <p>The plan argued this by hand over the relation's three rules (equality, both-numeric, and
     * {@code string → enum}). The relation is small enough to settle by enumeration instead, so this
     * checks all {@code 12³} triples and leaves nothing to the argument. Written as the property rather
     * than as a list of live output vars on purpose: a list of live vars samples whichever pairings the
     * registry happens to contain today, while the property covers the ones it might contain tomorrow.
     */
    @Test
    void compatibilityIsTransitiveSoTheTwoHopCheckCollapsesSafely() {
        for (final QueryFieldType a : QueryFieldType.values()) {
            for (final QueryFieldType y : QueryFieldType.values()) {
                if (!mapping.isCompatible(a, y)) {
                    continue;
                }
                for (final QueryFieldType c : QueryFieldType.values()) {
                    if (!mapping.isCompatible(y, c)) {
                        continue;
                    }
                    assertThat(mapping.isCompatible(a, c))
                            .as("two hops %s~%s~%s accepted, so the collapsed check must accept %s~%s",
                                    a.code(), y.code(), c.code(), a.code(), c.code())
                            .isTrue();
                }
            }
        }
    }

    /**
     * The other half of the claim: the collapse can only <b>loosen</b>, never tighten. There exists at
     * least one triple the two-hop check refused and the single check accepts — a declaration whose
     * declared token disagreed with its own expression — which is exactly the point, since the token
     * carried no information either end did not already carry.
     */
    @Test
    void theCollapseLoosensRatherThanTightens() {
        // inferred string, declared integer, column enum: hop one (string ~ integer) refused it, while the
        // collapsed check (string ~ enum) accepts. No live declaration relies on being refused here.
        assertThat(mapping.isCompatible(QueryFieldType.STRING, QueryFieldType.INTEGER)).isFalse();
        assertThat(mapping.isCompatible(QueryFieldType.STRING, QueryFieldType.ENUM)).isTrue();
    }

    /**
     * The relation is <b>not</b> symmetric, and the collapse must not quietly make it so: a non-string
     * value into an {@code enum} column stays refused. Worth pinning next to the transitivity proof,
     * because "transitive" and "symmetric" are easy to conflate when reading the three rules.
     */
    @Test
    void theRelationStaysOneWayIntoEnumColumns() {
        assertThat(mapping.isCompatible(QueryFieldType.STRING, QueryFieldType.ENUM)).isTrue();
        assertThat(mapping.isCompatible(QueryFieldType.ENUM, QueryFieldType.STRING)).isFalse();
        for (final QueryFieldType other : List.of(QueryFieldType.TIMESTAMP, QueryFieldType.LONG,
                QueryFieldType.BOOLEAN, QueryFieldType.ARRAY, QueryFieldType.DATE)) {
            assertThat(mapping.isCompatible(other, QueryFieldType.ENUM))
                    .as("%s must not bind to an enum column", other.code())
                    .isFalse();
        }
    }

    /**
     * {@code array} binds on <b>shape alone</b>, and that is deliberate: the inferred type carries no
     * element type, so an array expression binds to any array column and an element mismatch surfaces at
     * the ClickHouse insert rather than at registration. Dropping the declared token changes neither half
     * of that — the same reason the mapping refuses the parameterized {@code array<...>} spelling outright.
     */
    @Test
    void arrayBindsOnShapeAloneAndNotToAnythingElse() {
        assertThat(mapping.isCompatible(QueryFieldType.ARRAY, QueryFieldType.ARRAY)).isTrue();
        for (final QueryFieldType scalar : List.of(QueryFieldType.STRING, QueryFieldType.LONG,
                QueryFieldType.ENUM, QueryFieldType.TIMESTAMP)) {
            assertThat(mapping.isCompatible(QueryFieldType.ARRAY, scalar))
                    .as("an array result must not collapse into a %s column", scalar.code())
                    .isFalse();
        }
    }
}
