package com.epam.aidial.analytics.sync.mapping;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The extraction strategy used to coerce a source {@code JsonNode} into the JSON shape a ClickHouse
 * column expects. The authoritative column type lives in the table DDL; this only selects how the
 * source value is read (string / number / bool / array), not the full ClickHouse type system.
 */
public enum ColumnKind {

    STRING("string"),
    LONG("long"),
    DOUBLE("double"),
    BOOL("bool"),
    ARRAY_STRING("array<string>"),
    OBJECT("object");

    private final String token;

    ColumnKind(String token) {
        this.token = token;
    }

    /** The token used to reference this kind in the mapping file. */
    public String token() {
        return token;
    }

    /**
     * Resolves a mapping-file kind token to a {@link ColumnKind}.
     *
     * @param token the kind token (e.g. {@code array<string>})
     * @return the matching kind
     * @throws IllegalArgumentException if the token is not a recognized kind
     */
    public static ColumnKind fromToken(String token) {
        for (ColumnKind kind : values()) {
            if (kind.token.equalsIgnoreCase(token)) {
                return kind;
            }
        }
        String known = Arrays.stream(values()).map(ColumnKind::token).collect(Collectors.joining(", "));
        throw new IllegalArgumentException("Unknown column kind '" + token + "'; expected one of: " + known);
    }
}
