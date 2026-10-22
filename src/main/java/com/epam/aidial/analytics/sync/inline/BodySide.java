package com.epam.aidial.analytics.sync.inline;

import java.util.Locale;

/** Which side of a call a body column holds, which selects the location set it is scanned against. */
public enum BodySide {

    REQUEST,
    RESPONSE;

    /**
     * Parses the mapping token ({@code request} or {@code response}).
     *
     * @param token the token from the mapping file
     * @return the side, or {@code null} when the token is neither
     */
    public static BodySide fromToken(String token) {
        if (token == null) {
            return null;
        }
        return switch (token.trim().toLowerCase(Locale.ROOT)) {
            case "request" -> REQUEST;
            case "response" -> RESPONSE;
            default -> null;
        };
    }
}
