package com.epam.aidial.analytics.service.dashboard;

import java.util.function.Function;

/**
 * Reads a dashboard enum from its wire token, and from nothing else.
 *
 * <p>Plain Jackson enum binding also accepts a constant's position — {@code 1} and even {@code "1"} — and
 * {@code @JsonProperty} names on the constants do not stop it. Each dashboard enum therefore binds through
 * a {@code String}-only {@code @JsonCreator} that calls {@link #parse}: a number reaches it as text, matches
 * no token, and the {@code IllegalArgumentException} becomes the service-wide {@code 400 bad_request}
 * binding failure. Tokens match case-insensitively, as {@code ACCEPT_CASE_INSENSITIVE_ENUMS} makes every
 * other enum match.
 */
final class TokenEnums {

    private TokenEnums() {
    }

    static <E extends Enum<E>> E parse(E[] constants, Function<E, String> token, String value, String member) {
        if (value != null) {
            for (final E constant : constants) {
                if (token.apply(constant).equalsIgnoreCase(value)) {
                    return constant;
                }
            }
        }
        throw new IllegalArgumentException("unknown '" + member + "' token");
    }
}
