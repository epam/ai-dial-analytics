package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps the abstract {@link QueryFieldType} vocabulary to ClickHouse DDL type spellings for tables ADAS
 * materializes. A column may be declared {@code nullable}, rendering {@code Nullable(T)} for scalar
 * types (ClickHouse does not allow {@code Nullable} over {@code Array}/{@code JSON}/{@code Map}, so
 * those ignore the flag). {@code ARRAY} maps to {@code Array(<element>)} and requires a scalar element
 * type — nested arrays and {@code JSON}/{@code Map} elements are rejected. {@code MAP} maps to the fixed
 * {@code Map(String, String)}: it carries no key/value parameters, which is all an attribute bag needs
 * and keeps the type from becoming a compatibility surface.
 *
 * <p><b>{@code ENUM} maps to {@code Enum8}/{@code Enum16}</b> over the column's declared
 * {@code enum_values} — the type parameter {@code enum} takes, as {@code element_type} is
 * {@code array}'s. Unlike {@code Array} and {@code Map}, ClickHouse <em>does</em> allow
 * {@code Nullable} over an {@code Enum}, so an enum column follows the ordinary scalar nullability
 * rule instead of being forced non-nullable. Values become string literals in the DDL rather than
 * identifiers, which is why this class carries its own literal escaping.
 *
 * <p><b>{@code TIMESTAMP} pins its zone: {@code DateTime64(3, 'UTC')}.</b> A {@code DateTime64} declared
 * without a zone resolves to the ClickHouse <em>server's</em> — deployment configuration, not something
 * ADAS controls — and two places already assume otherwise. The read path ({@code ArrowBatchWriter})
 * converts the zoneless {@code LocalDateTime} the driver surfaces to epoch millis at UTC, so on a
 * non-UTC server every timestamp ADAS returns would be off by that server's offset. And a bucket
 * materialized into such a column would re-bucket on read in the server's zone rather than the one it
 * was computed in. Declaring the zone makes the assumption true by construction, and matches
 * {@code dial_usage_log}, whose migration has always spelled it {@code DateTime64(3, 'UTC')}.
 * {@code DATE} needs no such treatment: ClickHouse's {@code Date} is a zoneless day number.
 */
@Component
public class ClickHouseTypeMapper {

    /**
     * The largest number of values an {@code Enum8} can carry here. Ids are assigned from 1, and
     * {@code Enum8} is {@code Int8}-backed, so 127 is the last id that fits; a wider domain needs
     * {@code Enum16}.
     */
    private static final int MAX_ENUM8_VALUES = 127;

    /** Total digits of the {@code DECIMAL} storage type; the row-write range check reads it from here. */
    static final int DECIMAL_PRECISION = 38;

    /** Fractional digits of the {@code DECIMAL} storage type. */
    static final int DECIMAL_SCALE = 12;

    /**
     * Renders the ClickHouse type for a column.
     *
     * <p>Deliberately the <b>only</b> entry point, with no shorter overload defaulting the domain away.
     * A convenience form taking no {@code enumValues} compiles fine at a call site handling an
     * {@code ENUM} column and then fails when the DDL is rendered — deferring to runtime the one class
     * of mistake the exhaustive switches in this class and in {@code service/query/} exist to catch at
     * compile time. A caller with no domain passes {@code null} and says so.
     *
     * @param type        the column's declared type
     * @param elementType the array element type; required iff {@code type} is {@code ARRAY}, else must be null
     * @param enumValues  the declared value domain; required iff {@code type} is {@code ENUM}, else must be null
     * @param nullable    when true, wraps a scalar type in {@code Nullable(...)}
     */
    public String toDdlType(
            QueryFieldType type, QueryFieldType elementType, List<String> enumValues, boolean nullable) {
        if (type != QueryFieldType.ENUM && enumValues != null) {
            throw new TableValidationException("enum_values is only valid for ENUM columns");
        }
        if (type == QueryFieldType.ENUM) {
            // Unlike JSON and Map, ClickHouse does allow Nullable over an Enum, so an enum column
            // follows the ordinary scalar nullability rule rather than being forced non-nullable.
            final String enumType = enumType(enumValues);
            return nullable ? "Nullable(" + enumType + ")" : enumType;
        }
        if (type == QueryFieldType.ARRAY) {
            if (elementType == null) {
                throw new TableValidationException("ARRAY column requires an element_type");
            }
            if (elementType == QueryFieldType.OBJECT || elementType == QueryFieldType.MAP) {
                throw new TableValidationException(
                        elementType.name() + " is not a valid element type; array elements must be scalar");
            }
            return "Array(" + scalarType(elementType) + ")";
        }
        if (elementType != null) {
            throw new TableValidationException("element_type is only valid for ARRAY columns");
        }
        final String scalar = scalarType(type);
        // ClickHouse forbids Nullable over JSON and over Map; only wrap genuinely nullable-capable
        // scalar types.
        return nullable && type != QueryFieldType.OBJECT && type != QueryFieldType.MAP
                ? "Nullable(" + scalar + ")" : scalar;
    }

    /**
     * Renders {@code Enum8('a' = 1, 'b' = 2)} — or {@code Enum16} once the domain outgrows
     * {@link #MAX_ENUM8_VALUES} — assigning each value the 1-based position it was declared at. The
     * declared order is therefore load-bearing rather than cosmetic: ClickHouse compares an enum by its
     * id, so it is also the sort order of the column.
     *
     * <p>The count and per-value length are bounded by {@code TableColumnRules} before a declaration
     * reaches here; this method still refuses an empty or absent domain, since it cannot render a type
     * without one.
     */
    private String enumType(List<String> enumValues) {
        if (enumValues == null || enumValues.isEmpty()) {
            throw new TableValidationException("ENUM column requires a non-empty enum_values");
        }
        final StringBuilder rendered = new StringBuilder(
                enumValues.size() <= MAX_ENUM8_VALUES ? "Enum8(" : "Enum16(");
        for (int i = 0; i < enumValues.size(); i++) {
            if (i > 0) {
                rendered.append(", ");
            }
            rendered.append(quoteLiteral(enumValues.get(i))).append(" = ").append(i + 1);
        }
        return rendered.append(")").toString();
    }

    /**
     * Renders one enum value as a ClickHouse string literal. Enum values are free-form caller text
     * embedded into a {@code CREATE TABLE}, so this is the one place in the DDL path where caller text
     * becomes SQL syntax rather than an identifier — {@link Identifiers#quote} is deliberately not the
     * tool, since that produces a backtick-quoted <em>identifier</em>.
     *
     * <p>A backslash is doubled before a quote is, because ClickHouse treats backslash as an escape
     * introducer inside a string literal; doing it the other way round would re-escape the backslashes
     * this method just added.
     */
    private String quoteLiteral(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private String scalarType(QueryFieldType type) {
        return switch (type) {
            case STRING -> "String";
            // Unreachable: an ENUM never reaches the scalar renderer (toDdlType handles it ahead of
            // this, and it is refused as an array element type), but the case must exist for the switch
            // to stay exhaustive — which is what makes adding a type a compile error here.
            case ENUM -> throw new TableValidationException(
                    "ENUM is not a valid element type; array elements must be scalar");
            case INTEGER -> "Int32";
            case LONG -> "Int64";
            case DECIMAL -> "Decimal(" + DECIMAL_PRECISION + ", " + DECIMAL_SCALE + ")";
            case BOOLEAN -> "Bool";
            case DATE -> "Date";
            case TIMESTAMP -> "DateTime64(3, 'UTC')";
            case UUID -> "UUID";
            case OBJECT -> "JSON";
            case MAP -> "Map(String, String)";
            case ARRAY -> throw new TableValidationException(
                    "ARRAY is not a valid element type; array elements must be scalar");
        };
    }
}
