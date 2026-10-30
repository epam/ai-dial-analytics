package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A resolved flat field set: every name a declaration may reference, and what the catalog says about it.
 *
 * <p>Built by {@link EntityFieldResolver}, which decides membership. This record only answers questions
 * about what it was given, so the two callers that resolve a declaration against an entity — the
 * aggregate pipeline's gate and the enrich declaration's gate — ask them of one implementation. A second
 * copy is how a pipeline and a rollup would start disagreeing about what a column means.
 *
 * <p>Names are the <b>flat</b> ones: a source column under its exposed name, an enrichment column under
 * the qualified {@code "<enrichment>.<column>"} — the same namespace
 * {@code GET /v1/queries/entities/{name}} publishes, so a declaration and a query name a column
 * identically.
 */
public record EntityFields(Map<String, Field> byName) {

    /**
     * One resolvable field, and the table it belongs to. The owning table is carried because a
     * refusal must name the enrichment a qualified column came from, and because the sensitive gate
     * is enforced per table.
     *
     * @param enrichment whether the field is reached through the entity's grain join rather than
     *     declared on the base table itself
     * @param computed   whether the field is a service-defined computed field ({@code ComputedFields}), whose
     *     {@code column} is a transient descriptor: it may be bound as a transform input and named nowhere else
     */
    public record Field(String owner, ColumnMappingEntity column, boolean enrichment, boolean computed) {

        /** A field backed by a catalog column. */
        public Field(String owner, ColumnMappingEntity column, boolean enrichment) {
            this(owner, column, enrichment, false);
        }
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    /** Whether {@code name} is a computed field: completed after a read's statement runs, so no SQL can name it. */
    public boolean isComputed(String name) {
        final Field field = byName.get(name);
        return field != null && field.computed();
    }

    /** The table {@code name} belongs to, or null when it resolves to nothing. */
    public String ownerOf(String name) {
        final Field field = byName.get(name);
        return field == null ? null : field.owner();
    }

    /** Whether {@code name} is reached through the grain join rather than declared on the base table. */
    public boolean isEnrichmentSourced(String name) {
        final Field field = byName.get(name);
        return field != null && field.enrichment();
    }

    public QueryFieldType typeOf(String name) {
        final ColumnMappingEntity column = columnOf(name);
        return column == null ? null : column.getType();
    }

    /** The declared element type of an {@code array} column; null for any other column. */
    public QueryFieldType elementTypeOf(String name) {
        final ColumnMappingEntity column = columnOf(name);
        return column == null ? null : column.getElementType();
    }

    public boolean isSensitive(String name) {
        final ColumnMappingEntity column = columnOf(name);
        return column != null && column.isSensitive();
    }

    /**
     * Whether a value read from {@code name} can be NULL.
     *
     * <p><b>An enrichment-sourced column is nullable whatever it declares.</b> It is reached through an
     * outer grain join, so a base row the enrichment has not reached yields NULL for every one of its
     * columns; the declaration describes the value when the row exists, and the join decides whether it
     * exists. Deciding this here rather than in each rule is what lets the measure-nullability and
     * group-key rules inherit it with no enrichment-specific branch — and reading the declared flag
     * alone would defeat both in exactly the case they were written for, since a non-nullable
     * enrichment column would bind to a non-nullable target and store the type default for every row
     * the enrichment has not reached.
     */
    public boolean isNullable(String name) {
        final Field field = byName.get(name);
        return field != null && (field.enrichment() || field.column().isNullable());
    }

    /**
     * Whether {@code name} is declared nullable in the catalog, ignoring what the grain join does to
     * it. {@link #isNullable} is the question every rule about <em>values</em> asks; this is the
     * narrower question {@link #absenceIsUnrepresentable} asks, because whether an unmatched join renders
     * NULL or a type default depends on the physical column's own nullability and on nothing else.
     */
    public boolean declaresNullable(String name) {
        final ColumnMappingEntity column = columnOf(name);
        return column != null && column.isNullable();
    }

    /**
     * Whether {@code name} is an enrichment field that <b>cannot represent the absence</b> of its
     * enrichment row — the condition a value-producing reference to it must be refused on.
     *
     * <p>The design premise everywhere else is "the grain join is outer, so an unmatched base row carries
     * NULL". That is only true of a column the catalog declares nullable. ClickHouse's
     * {@code join_use_nulls} defaults to {@code 0}, and every statement the enrichment machinery emits — a
     * scan, a materialize, an aggregate refresh, a single-entity query — keeps that default, so an unmatched
     * LEFT JOIN renders a non-nullable right-hand column as its <em>type default</em> — {@code ''},
     * {@code 0}, {@code false}, the epoch — not as NULL. (The one surface that sets {@code join_use_nulls=1}
     * is a caller-written composite SQL statement, {@code StructuredQueryService.execute(CompositeQuery)};
     * it changes nothing for the exempt types below, since {@code Array}, {@code Map} and {@code JSON}
     * cannot be wrapped in {@code Nullable} and read as empty under either setting.) Every mitigation built
     * on the NULL premise then misses: a declared {@code IS NOT NULL} excludes nothing, a nullable target
     * column receives a zero rather than a NULL, and an aggregate silently absorbs one default per unreached
     * row.
     *
     * <p><b>{@code array}, {@code map} and {@code object} are exempt, and are not an oversight.</b>
     * {@code TableColumnRules.resolveNullable} makes them unconditionally non-nullable because ClickHouse
     * has no {@code Nullable(Array)}, {@code Nullable(Map)} or {@code Nullable(JSON)}, so the rule would
     * refuse them permanently. It does not need to: their type default is <em>empty</em>, which
     * contributes nothing, so absence and emptiness are already indistinguishable by declaration rather
     * than by accident.
     *
     * <p><b>Why {@code object} qualifies on the same terms</b>, measured on ClickHouse 26.3 over a
     * {@code LEFT JOIN} whose right side is a non-nullable {@code JSON} column. An unmatched row reads
     * as the empty document, and every accessor ADAS exposes for an {@code object} field then returns
     * precisely what it returns for a document that is present but lacks the key:
     * {@code toJSONString} → {@code '{}'}, subcolumn access ({@code json_extract_string} and the other
     * three scalars) → {@code NULL}, {@code json_extract_array} → {@code []},
     * {@code json_extract_string_ci} → {@code ''}. There is no accessor that could tell the two apart,
     * so there is no derived value the type default could silently corrupt — which is the whole hazard
     * this rule exists to prevent.
     *
     * <p>The alternative — storing such a column as {@code Nullable(String)} to earn a real NULL — is
     * not available either: {@code JSONExtract(<Nullable(String)>, 'k', 'Array(String)')} fails with
     * {@code Code 43: Nested type Array(String) cannot be inside Nullable type}, so it would buy the
     * NULL by removing array extraction from the column entirely.
     *
     * <p>The <em>refusal</em> belongs to each caller, not here: both kinds' gates raise
     * {@code PipelineValidationException} — one code for one registry — and each names the position the
     * reference appeared in. Only the condition is shared.
     */
    public boolean absenceIsUnrepresentable(String name) {
        if (!isEnrichmentSourced(name) || declaresNullable(name)) {
            return false;
        }
        final QueryFieldType type = typeOf(name);
        return type != QueryFieldType.ARRAY && type != QueryFieldType.MAP
                && type != QueryFieldType.OBJECT;
    }

    public Set<String> names() {
        return byName.keySet();
    }

    /** The enrichments contributing fields here, for a refusal to name what a qualified name may mean. */
    public Set<String> enrichmentOwners() {
        return byName.values().stream().filter(Field::enrichment).map(Field::owner)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The base-table (unqualified) spelling of {@code name}: the segment after the dot for an enrichment
     * field, the name itself otherwise. What a grain key resolves <em>to</em>, since a target enrichment is
     * physically keyed on the plain column name whatever the entity calls the field it came from.
     */
    public static String lastSegment(String name) {
        final int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private ColumnMappingEntity columnOf(String name) {
        final Field field = byName.get(name);
        return field == null ? null : field.column();
    }
}
