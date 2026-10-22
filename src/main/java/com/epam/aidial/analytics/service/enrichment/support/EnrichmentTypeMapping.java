package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Shared mapping from a transform variable's logical type (a free-form JSON type token) to the
 * catalog's {@link QueryFieldType}, plus a compatibility check used by the pipeline governance gate to
 * verify an output binding's target column can hold the output variable's value. Accepts the
 * catalog wire codes ({@code string}, {@code long}, {@code integer}, {@code decimal}, {@code
 * boolean}, {@code date}, {@code timestamp}, {@code uuid}) and a few common aliases
 * ({@code double}/{@code float} → decimal, {@code datetime} → timestamp, {@code int} → integer).
 * {@code enum} is deliberately NOT accepted as a variable type — declare {@code string} and let the
 * target column carry the domain; see {@link #resolve} and {@link #isCompatible}.
 */
@Component
public class EnrichmentTypeMapping {

    private static final Map<String, QueryFieldType> ALIASES = Map.of(
            "double", QueryFieldType.DECIMAL,
            "float", QueryFieldType.DECIMAL,
            "datetime", QueryFieldType.TIMESTAMP,
            "int", QueryFieldType.INTEGER,
            "bool", QueryFieldType.BOOLEAN);

    // The numeric types are mutually assignable for enrichment outputs (integer/long/decimal
    // widening); every other type must match exactly.
    private static final Set<QueryFieldType> NUMERIC =
            Set.of(QueryFieldType.INTEGER, QueryFieldType.LONG, QueryFieldType.DECIMAL);

    /**
     * Resolves a logical type token to a {@link QueryFieldType}.
     *
     * @throws IllegalArgumentException if the token is blank or unrecognized
     */
    public QueryFieldType resolve(String logicalType) {
        if (StringUtils.isBlank(logicalType)) {
            throw new IllegalArgumentException("output variable type must not be blank");
        }
        String token = logicalType.trim().toLowerCase();
        QueryFieldType alias = ALIASES.get(token);
        if (alias != null) {
            return alias;
        }
        // A parameterized array spelling is refused rather than aliased to ARRAY. The catalog carries an
        // element type on a declared column, but this mapping compares shapes only (see #isCompatible), so
        // accepting array<string> would advertise a check that does not happen — and would read as passing
        // when bound to an array<long> column. Say so instead of failing with "unknown field type code".
        if (token.startsWith(QueryFieldType.ARRAY.code() + "<")) {
            throw new IllegalArgumentException("declare an array output variable as '"
                    + QueryFieldType.ARRAY.code() + "', not '" + logicalType.trim() + "': element types are"
                    + " not compared against the target column, so naming one would be misleading");
        }
        // Refused for the same reason as a parameterized array spelling: a pipeline declares the value it
        // produces, while the domain belongs to the target column. Accepting 'enum' here would advertise
        // a domain check that does not happen — the value is checked by ClickHouse at insert, not by the
        // governance gate. A string output binds to an enum column (see #isCompatible).
        if (token.equals(QueryFieldType.ENUM.code())) {
            throw new IllegalArgumentException("declare the output variable as '"
                    + QueryFieldType.STRING.code() + "' and let the target column declare the domain: '"
                    + QueryFieldType.ENUM.code() + "' names a set of values that is not compared against"
                    + " the target column, so naming it here would be misleading");
        }
        return QueryFieldType.fromCode(token);
    }

    /**
     * True if a value of {@code varType} can be written into a column of {@code columnType}.
     *
     * <p><b>Element types are deliberately not compared for {@code array}.</b> {@link QueryFieldType#ARRAY}
     * names the shape only, and a Class-1 expression that computes an array has no declared
     * {@code element_type} to compare against — the same reason {@code group_uniq_array}'s element kind
     * follows its operand rather than a declaration. So an array expression binds to any array-typed column,
     * and an element mismatch surfaces as a ClickHouse insert failure on the first materialize call rather
     * than at registration. Threading an element kind through {@link QueryFieldType} is the fix, and it is a
     * larger change than the one column that wants it; this is not an oversight to close in passing.
     *
     * <p><b>A {@code string} output binds to an {@code enum} column.</b> An enum column holds strings
     * drawn from a declared domain, and a pipeline declares the value it produces, not the domain — so
     * requiring an exact match here would make an enum column unwritable by any enrichment. The domain
     * itself is enforced where it can be: ClickHouse rejects an out-of-domain value at insert, so a
     * mismatch surfaces on the first materialize rather than at registration, exactly as an array's
     * element mismatch does above. The relation is one-way — a non-{@code string} output against an
     * {@code enum} column is still rejected.
     */
    public boolean isCompatible(QueryFieldType varType, QueryFieldType columnType) {
        return varType == columnType
                || (NUMERIC.contains(varType) && NUMERIC.contains(columnType))
                || (varType == QueryFieldType.STRING && columnType == QueryFieldType.ENUM);
    }
}
