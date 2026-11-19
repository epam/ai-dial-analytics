package com.epam.aidial.analytics.web.controller.query;

/**
 * The one OpenAPI tag every {@code /v1/queries} controller declares. springdoc publishes one top-level tag per
 * distinct name and description, so a second description would list the tag twice; the six controllers take both
 * name and description from here rather than each spelling its own. The surface is experimental: the description
 * says so for readers, and every operation carries {@code x-preview: true} for tools. See {@code openspec/specs/openapi-document/spec.md}.
 */
public final class QueryApiTag {

    public static final String NAME = "Queries";

    public static final String DESCRIPTION = "Run structured (DSL) and SQL queries, export results as CSV, translate between "
            + "DSL and SQL, and discover the queryable schema. Experimental — may change.";

    private QueryApiTag() {
    }
}
