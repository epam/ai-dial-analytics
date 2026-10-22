package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared flat field view both governance gates resolve against. Its answers decide whether a
 * declaration is admitted, so each one is pinned here rather than only through the paths that ask it.
 */
class EntityFieldsTest {

    private static ColumnMappingEntity column(String table, String name, QueryFieldType type, boolean nullable) {
        final ColumnMappingEntity col = new ColumnMappingEntity();
        col.setTableName(table);
        col.setName(name);
        col.setSourceName(name);
        col.setType(type);
        col.setNullable(nullable);
        return col;
    }

    private static EntityFields fields(EntityFields.Field... entries) {
        final Map<String, EntityFields.Field> byName = new LinkedHashMap<>();
        for (final EntityFields.Field field : entries) {
            byName.put(field.enrichment()
                    ? field.owner() + "." + field.column().getName()
                    : field.column().getName(), field);
        }
        return new EntityFields(byName);
    }

    private static EntityFields.Field source(String name, QueryFieldType type, boolean nullable) {
        return new EntityFields.Field("src", column("src", name, type, nullable), false);
    }

    private static EntityFields.Field enrichment(String name, QueryFieldType type, boolean nullable) {
        return new EntityFields.Field("enr", column("enr", name, type, nullable), true);
    }

    @Test
    void anEnrichmentFieldIsNullableWhateverItDeclares() {
        final EntityFields entity = fields(enrichment("k", QueryFieldType.STRING, false));

        // The outer grain join decides whether the row exists; the declaration only describes the value
        // when it does.
        assertThat(entity.isNullable("enr.k")).isTrue();
        assertThat(entity.declaresNullable("enr.k")).isFalse();
    }

    @Test
    void nonNullableEnrichmentScalarCannotRepresentItsOwnAbsence() {
        final EntityFields entity = fields(enrichment("k", QueryFieldType.STRING, false));

        assertThat(entity.absenceIsUnrepresentable("enr.k")).isTrue();
    }

    @Test
    void nullableEnrichmentScalarCanRepresentItsOwnAbsence() {
        final EntityFields entity = fields(enrichment("k", QueryFieldType.STRING, true));

        assertThat(entity.absenceIsUnrepresentable("enr.k")).isFalse();
    }

    /** ClickHouse has no Nullable(Array), and an unmatched join renders one empty — which is absence. */
    @Test
    void arrayAndMapEnrichmentFieldsAreExempt() {
        final EntityFields entity = fields(
                enrichment("tags", QueryFieldType.ARRAY, false),
                enrichment("attrs", QueryFieldType.MAP, false));

        assertThat(entity.absenceIsUnrepresentable("enr.tags")).isFalse();
        assertThat(entity.absenceIsUnrepresentable("enr.attrs")).isFalse();
    }

    /** A source column is never subject to the rule: nothing joins it, so it is never absent. */
    @Test
    void nonNullableSourceColumnIsNotSubjectToTheRule() {
        final EntityFields entity = fields(source("k", QueryFieldType.STRING, false));

        assertThat(entity.absenceIsUnrepresentable("k")).isFalse();
        assertThat(entity.isEnrichmentSourced("k")).isFalse();
    }

    /**
     * The widening is monotonic only because an enrichment column has no bare spelling. Pinned because a
     * bare alias would silently reinterpret a stored rule that resolved to the source before.
     */
    @Test
    void enrichmentColumnHasNoBareSpellingAndCannotShadowSourceColumn() {
        final EntityFields entity = fields(
                source("k", QueryFieldType.STRING, false),
                enrichment("k", QueryFieldType.LONG, true));

        assertThat(entity.contains("k")).isTrue();
        assertThat(entity.isEnrichmentSourced("k")).isFalse();
        assertThat(entity.ownerOf("k")).isEqualTo("src");
        assertThat(entity.ownerOf("enr.k")).isEqualTo("enr");
    }

    @Test
    void lastSegmentIsTheNameTheTargetIsKeyedOn() {
        assertThat(EntityFields.lastSegment("usage_client_identity.client_session_id"))
                .isEqualTo("client_session_id");
        assertThat(EntityFields.lastSegment("client_session_id")).isEqualTo("client_session_id");
    }

    @Test
    void enrichmentOwnersNamesOnlyTheJoinedTables() {
        final EntityFields entity = fields(
                source("a", QueryFieldType.STRING, false),
                enrichment("b", QueryFieldType.STRING, true));

        assertThat(entity.enrichmentOwners()).containsExactly("enr");
    }

    @Test
    void anUnknownNameResolvesToNothingRatherThanThrowing() {
        final EntityFields entity = fields(source("a", QueryFieldType.STRING, false));

        assertThat(entity.contains("nope")).isFalse();
        assertThat(entity.ownerOf("nope")).isNull();
        assertThat(entity.typeOf("nope")).isNull();
        assertThat(entity.isSensitive("nope")).isFalse();
        assertThat(entity.absenceIsUnrepresentable("nope")).isFalse();
    }
}
