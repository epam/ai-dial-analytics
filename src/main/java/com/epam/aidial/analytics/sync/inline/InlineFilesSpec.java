package com.epam.aidial.analytics.sync.inline;

import java.util.Objects;

/**
 * The {@code inline-files} attribute of a mapped body column: which side of the call the column holds, and the
 * dotted source path of the record's request URI, from which the API is inferred (see {@link ApiClassifier}).
 *
 * @param body the side the column is scanned as
 * @param uri  the dotted source path of the request URI within the record (e.g. {@code request.uri})
 */
public record InlineFilesSpec(BodySide body, String uri) {

    public InlineFilesSpec {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(uri, "uri");
    }
}
