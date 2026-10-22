package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared syntactic rules for user-declared columns, used by both the create flow and {@code PATCH}.
 * Enforces the column vocabulary: snake_case names with no reserved {@code _}-prefix, a non-null type,
 * and for {@code array} columns a required scalar {@code element_type} with mandatory
 * non-nullability (ClickHouse has no {@code Nullable(Array(...))}). A {@code map} column follows the
 * same non-nullability rule for the same reason (no {@code Nullable(Map(...))}) and takes no
 * {@code element_type}: it is the fixed {@code Map(String, String)}. An {@code enum} column is the
 * other parameterized type: it requires a non-empty {@code enum_values} and no other type may declare
 * one, but it carries no nullability restriction, since ClickHouse does allow
 * {@code Nullable(Enum8(...))} — until such a column is used as a physical key, where
 * {@link #requireKeyable} applies the separate rule that a key column of any type must be non-nullable
 * (ClickHouse refuses a nullable sorting or partition key while {@code allow_nullable_key} is disabled).
 */
public final class TableColumnRules {

    /** Max length of a single declared value of an {@code enum} column. */
    static final int MAX_ENUM_VALUE_LENGTH = 64;

    /**
     * Max number of values an {@code enum} column may declare. Well inside {@code Enum16}'s range; the
     * cap exists to bound the {@code CREATE TABLE} statement rather than to track a ClickHouse limit.
     */
    static final int MAX_ENUM_VALUES = 512;

    /** Max length of a column's free-form identifying tag. */
    static final int MAX_TAG_LENGTH = 64;

    /** Max length of a column's user-friendly display name. */
    static final int MAX_DISPLAY_NAME_LENGTH = 128;

    /** Max length of a column's free-form description. */
    static final int MAX_DESCRIPTION_LENGTH = 1024;

    /** Max number of entries in a per-table tag ordering. */
    static final int MAX_TAG_ORDER_ENTRIES = 100;

    private TableColumnRules() {
    }

    /**
     * The per-table column cap, counted after provenance injection. Public so the catalog import can check
     * every bundle table up front, with the same rule define-schema applies.
     */
    public static void requireColumnCount(int count, int maxColumnsPerTable) {
        if (count > maxColumnsPerTable) {
            throw new TableValidationException("a table may declare at most " + maxColumnsPerTable + " columns");
        }
    }

    /** Validates one declared column's names, type, and tag; throws {@link TableValidationException} if invalid. */
    static void requireValidUserColumn(ColumnInput col) {
        Identifiers.requireUserIdentifier(col.sourceName());
        if (col.name() == null || col.name().isBlank()) {
            throw new TableValidationException("column '" + col.sourceName() + "' has a blank exposed name");
        }
        Identifiers.requireUserIdentifier(col.name());
        if (col.type() == null) {
            throw new TableValidationException("column '" + col.sourceName() + "' has no type");
        }
        if (col.type() == QueryFieldType.ARRAY) {
            requireValidArrayColumn(col);
        } else if (col.type() == QueryFieldType.MAP) {
            requireValidMapColumn(col);
        } else if (col.type() == QueryFieldType.OBJECT) {
            requireValidObjectColumn(col);
        } else if (col.elementType() != null) {
            throw new TableValidationException(
                    "element_type is only valid for array columns (column '" + col.sourceName() + "')");
        }
        requireValidEnumColumn(col);
        normalizeDisplayName(col.displayName());
        normalizeTag(col.tag());
        normalizeDescription(col.description());
    }

    /**
     * An {@code enum} column must declare a non-empty {@code enum_values}, and no other type may declare
     * one — the same required-iff rule {@code element_type} follows for {@code array}. Unlike an array or
     * a map, an enum column has no nullability restriction: ClickHouse allows {@code Nullable(Enum8(…))},
     * so it follows the ordinary kind-based default.
     */
    private static void requireValidEnumColumn(ColumnInput col) {
        if (col.type() == QueryFieldType.ENUM) {
            if (col.enumValues() == null || col.enumValues().isEmpty()) {
                throw new TableValidationException(
                        "enum column '" + col.sourceName() + "' requires a non-empty enum_values");
            }
            normalizeEnumValues(col.enumValues());
        } else if (col.enumValues() != null) {
            throw new TableValidationException("enum_values is only valid for enum columns (column '"
                    + col.sourceName() + "' is of type '" + col.type().code() + "')");
        }
    }

    /**
     * The column as it is <em>stored and materialized</em>: its declared {@code enum_values} replaced by the
     * trimmed list {@link #normalizeEnumValues} produces, and every other attribute left alone. Returned
     * unchanged for a column that declares no domain.
     *
     * <p>It exists so a caller of the <b>live</b>-schema add path can render the {@code ALTER} and write the
     * catalog row from one list. The catalog row is always written from the normalized form
     * ({@code CatalogSchemaWriter.columnEntity}); rendering the DDL from the raw declaration instead would
     * materialize {@code Enum8(' high ' = 1)} while the read and discovery surfaces advertise {@code high} —
     * a column that refuses the very value it publishes, with no repair, since the domain is immutable.
     */
    static ColumnInput withNormalizedEnumValues(ColumnInput col) {
        final List<String> normalized = normalizeEnumValues(col.enumValues());
        if (normalized == null) {
            return col;
        }
        return new ColumnInput(col.sourceName(), col.name(), col.type(), col.elementType(), normalized,
                col.nullable(), col.description(), col.displayName(), col.tag(), col.sensitive(), col.heavy());
    }

    /**
     * Normalizes and validates an {@code enum} column's declared domain. Trims each value, and rejects an
     * empty list, a null or blank value, a duplicate (after trimming), a value longer than
     * {@link #MAX_ENUM_VALUE_LENGTH}, and a list longer than {@link #MAX_ENUM_VALUES}. Returns the
     * trimmed list, or {@code null} when the input is null.
     *
     * <p><b>Order is preserved, never sorted.</b> Each value's position becomes its ClickHouse enum id,
     * which is what makes {@code ORDER BY} on the column follow the declared order (say,
     * {@code low}/{@code medium}/{@code high}) rather than alphabetical order. Sorting here would throw
     * away the one piece of caller intent an enum is uniquely good at carrying.
     */
    static List<String> normalizeEnumValues(List<String> enumValues) {
        if (enumValues == null) {
            return null;
        }
        if (enumValues.isEmpty()) {
            throw new TableValidationException("enum_values must declare at least one value");
        }
        if (enumValues.size() > MAX_ENUM_VALUES) {
            throw new TableValidationException(
                    "enum_values may contain at most " + MAX_ENUM_VALUES + " values (was " + enumValues.size() + ")");
        }
        final List<String> normalized = new ArrayList<>(enumValues.size());
        final Set<String> seen = new LinkedHashSet<>();
        for (final String value : enumValues) {
            if (value == null || value.strip().isEmpty()) {
                throw new TableValidationException("enum_values entries must be non-blank");
            }
            final String trimmed = value.strip();
            if (trimmed.length() > MAX_ENUM_VALUE_LENGTH) {
                throw new TableValidationException("enum_values entry must be at most "
                        + MAX_ENUM_VALUE_LENGTH + " characters (was " + trimmed.length() + ")");
            }
            if (!seen.add(trimmed)) {
                throw new TableValidationException("enum_values contains duplicate value '" + trimmed + "'");
            }
            normalized.add(trimmed);
        }
        return normalized;
    }

    /**
     * Normalizes a column's free-form tag: trims it, treats blank as absent ({@code null}), and rejects
     * anything longer than {@link #MAX_TAG_LENGTH} characters. Tags are catalog-only labels; duplicates
     * within a table are allowed and no identifier grammar is imposed.
     */
    static String normalizeTag(String tag) {
        if (tag == null) {
            return null;
        }
        final String trimmed = tag.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_TAG_LENGTH) {
            throw new TableValidationException(
                    "tag must be at most " + MAX_TAG_LENGTH + " characters (was " + trimmed.length() + ")");
        }
        return trimmed;
    }

    /**
     * Normalizes a column's display name: trims it, treats blank as absent ({@code null}), and rejects
     * anything longer than {@link #MAX_DISPLAY_NAME_LENGTH} characters. Display names are catalog-only;
     * duplicates within a table are allowed and no identifier grammar is imposed.
     */
    static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        final String trimmed = displayName.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new TableValidationException(
                    "display_name must be at most " + MAX_DISPLAY_NAME_LENGTH
                            + " characters (was " + trimmed.length() + ")");
        }
        return trimmed;
    }

    /**
     * Normalizes a column's description: trims it, treats blank as absent ({@code null}), and rejects
     * anything longer than {@link #MAX_DESCRIPTION_LENGTH} characters. Descriptions are catalog-only
     * free-form text; duplicates within a table are allowed and no identifier grammar is imposed.
     */
    static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        final String trimmed = description.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new TableValidationException(
                    "description must be at most " + MAX_DESCRIPTION_LENGTH
                            + " characters (was " + trimmed.length() + ")");
        }
        return trimmed;
    }

    /**
     * An {@code array} column must declare a scalar {@code element_type} (nested arrays and JSON
     * elements are excluded) and may not be declared nullable: ClickHouse has no
     * {@code Nullable(Array(...))}, and silently dropping the flag would leave the catalog claiming a
     * nullability the physical column does not have.
     */
    private static void requireValidArrayColumn(ColumnInput col) {
        if (col.elementType() == null) {
            throw new TableValidationException(
                    "array column '" + col.sourceName() + "' requires an element_type");
        }
        // ENUM joins the non-scalar exclusions for a different reason than the others: an array element
        // has nowhere to declare a domain, so an Array(Enum8(...)) could not be rendered even though
        // ClickHouse would accept one. Out of scope rather than impossible.
        if (col.elementType() == QueryFieldType.ARRAY
                || col.elementType() == QueryFieldType.OBJECT
                || col.elementType() == QueryFieldType.MAP
                || col.elementType() == QueryFieldType.ENUM) {
            throw new TableValidationException(
                    "array element_type must be scalar (column '" + col.sourceName()
                            + "' declares '" + col.elementType().code() + "')");
        }
        if (Boolean.TRUE.equals(col.nullable())) {
            throw new TableValidationException(
                    "array column '" + col.sourceName() + "' cannot be nullable (ClickHouse has no "
                            + "Nullable(Array); use an empty array [] for absent values)");
        }
    }

    /**
     * A {@code map} column carries no {@code element_type} (its key and value types are fixed to
     * {@code String}) and may not be declared nullable: ClickHouse has no {@code Nullable(Map(...))},
     * and silently dropping the flag would leave the catalog claiming a nullability the physical column
     * does not have. An absent map is the empty map.
     */
    private static void requireValidMapColumn(ColumnInput col) {
        if (col.elementType() != null) {
            throw new TableValidationException(
                    "element_type is only valid for array columns (column '" + col.sourceName() + "')");
        }
        if (Boolean.TRUE.equals(col.nullable())) {
            throw new TableValidationException(
                    "map column '" + col.sourceName() + "' cannot be nullable (ClickHouse has no "
                            + "Nullable(Map); use an empty map {} for absent values)");
        }
    }

    /**
     * An {@code object} column carries no {@code element_type} (a JSON document declares no member
     * types) and may not be declared nullable: ClickHouse has no {@code Nullable(JSON)}, and silently
     * dropping the flag would leave the catalog claiming a nullability the physical column does not
     * have. An absent document is the empty document {@code {}}.
     *
     * <p>This joins {@code array} and {@code map} late. {@link ClickHouseTypeMapper} has always refused
     * to wrap {@code JSON} in {@code Nullable}, so the flag was already unrenderable — but nothing
     * refused it on the way in, which let an enrichment record {@code nullable = true} for a column
     * materialized non-nullable. That divergence is exactly what
     * {@link com.epam.aidial.analytics.service.enrichment.support.EntityFields#absenceIsUnrepresentable}
     * reads to decide whether an unmatched grain join renders NULL, so the lie disabled the guard for
     * the one type whose physical form can never satisfy it.
     */
    private static void requireValidObjectColumn(ColumnInput col) {
        if (col.elementType() != null) {
            throw new TableValidationException(
                    "element_type is only valid for array columns (column '" + col.sourceName() + "')");
        }
        if (Boolean.TRUE.equals(col.nullable())) {
            throw new TableValidationException(
                    "object column '" + col.sourceName() + "' cannot be nullable (ClickHouse has no "
                            + "Nullable(JSON); use an empty document {} for absent values)");
        }
    }

    /**
     * A physical key ({@code ordering_key} entry, {@code partition_by}, or an enrichment's
     * {@code grain_key}) may not name a {@code map} or an {@code object} column and may not be nullable.
     * ClickHouse's {@code Map} has no comparison, so it refuses a sorting-key or partition expression over
     * one; its {@code JSON} type is refused in a sorting key and a partition key on the same grounds (a
     * document has no total order); and it refuses a nullable column in either key while
     * {@code allow_nullable_key} is disabled, which ADAS never enables. All three checks belong at
     * validation time because the catalog columns and keys are committed <em>before</em> the {@code CREATE}
     * is issued — letting ClickHouse be the one to refuse would surface its raw error and leave a
     * {@code FAILED} definition behind instead of persisting nothing.
     *
     * <p>{@code nullable} describes the <em>physical</em> column the key is rendered over, which is not
     * always the column the caller named: an enrichment's grain column is materialized non-nullable
     * whatever its source column declares, so that call site passes {@code false} rather than the source's
     * flag.
     */
    static void requireKeyable(String field, String columnName, QueryFieldType type, boolean nullable) {
        if (type == QueryFieldType.MAP) {
            throw new TableValidationException(field + " column '" + columnName + "' is of type '"
                    + type.code() + "'; a map cannot key a table (ClickHouse has no comparison over Map)");
        }
        if (type == QueryFieldType.OBJECT) {
            throw new TableValidationException(field + " column '" + columnName + "' is of type '"
                    + type.code() + "'; an object cannot key a table (ClickHouse has no ordering over JSON)");
        }
        if (nullable) {
            throw new TableValidationException(field + " column '" + columnName
                    + "' must be non-nullable (ClickHouse rejects a nullable " + clickHouseKeyKind(field) + ")");
        }
    }

    /**
     * Maps {@code field} to ClickHouse's own name for the kind of key it becomes, so an operator who
     * searches the engine's refusal ({@code Sorting key contains nullable columns} / {@code Partition key
     * contains nullable columns}) finds our message too.
     */
    private static String clickHouseKeyKind(String field) {
        return "partition_by".equals(field) ? "partition key" : "sorting key";
    }

    /**
     * Normalizes and validates a per-table tag ordering. Trims each entry, rejects blank entries and
     * entries longer than {@link #MAX_TAG_LENGTH}, rejects duplicates (after trimming), and caps the
     * list at {@link #MAX_TAG_ORDER_ENTRIES}. Returns the trimmed list, or {@code null} when the input
     * is null or empty (empty means "clear the ordering"). Entries are intentionally NOT validated
     * against the tags currently present on the table's columns — the ordering is a decoupled display
     * hint, so an entry may name a tag no column carries.
     */
    static List<String> normalizeTagOrder(List<String> tagOrder) {
        if (tagOrder == null || tagOrder.isEmpty()) {
            return null;
        }
        if (tagOrder.size() > MAX_TAG_ORDER_ENTRIES) {
            throw new TableValidationException(
                    "tag_order may contain at most " + MAX_TAG_ORDER_ENTRIES + " entries (was " + tagOrder.size() + ")");
        }
        final List<String> normalized = new ArrayList<>(tagOrder.size());
        final Set<String> seen = new LinkedHashSet<>();
        for (final String entry : tagOrder) {
            if (entry == null || entry.strip().isEmpty()) {
                throw new TableValidationException("tag_order entries must be non-blank");
            }
            final String trimmed = entry.strip();
            if (trimmed.length() > MAX_TAG_LENGTH) {
                throw new TableValidationException(
                        "tag_order entry must be at most " + MAX_TAG_LENGTH + " characters (was " + trimmed.length() + ")");
            }
            if (!seen.add(trimmed)) {
                throw new TableValidationException("tag_order contains duplicate entry '" + trimmed + "'");
            }
            normalized.add(trimmed);
        }
        return normalized;
    }

    /**
     * Resolves a column's nullability: explicit when set, else enrichment columns default nullable.
     * An {@code array}, {@code map} or {@code object} column is always non-nullable regardless of table
     * kind (ClickHouse has no {@code Nullable(Array(...))}, {@code Nullable(Map(...))} or
     * {@code Nullable(JSON)}; an explicit {@code nullable: true} was already rejected).
     *
     * <p>{@code OBJECT} matters most on an {@code ENRICHMENT}, where the kind default is nullable: the
     * catalog would otherwise record a flag {@link ClickHouseTypeMapper} never renders, and every rule
     * reading it would believe an unmatched grain join yields NULL when it yields {@code {}}.
     */
    static boolean resolveNullable(ColumnInput col, TableType kind) {
        if (col.type() == QueryFieldType.ARRAY || col.type() == QueryFieldType.MAP
                || col.type() == QueryFieldType.OBJECT) {
            return false;
        }
        return col.nullable() != null ? col.nullable() : kind == TableType.ENRICHMENT;
    }

    /** Whether {@code name} is a reserved system column (the {@code _}-prefix is ADAS-owned). */
    static boolean isSystemColumn(String name) {
        return name != null && name.startsWith("_");
    }
}
