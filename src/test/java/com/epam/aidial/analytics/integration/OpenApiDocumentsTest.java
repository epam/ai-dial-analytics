package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** The committed document's normal form, and the drift check's first-difference pointer. */
class OpenApiDocumentsTest {

    @Test
    void sortsKeysKeepsArrayOrderAndDropsServers() {
        final JsonNode served = OpenApiDocuments.parse("""
                {"paths":{"/b":{},"/a":{"get":{"tags":["Z","A"]}}},"servers":[{"url":"http://localhost:8080"}],
                 "openapi":"3.1.0","info":{"version":"v1","title":"T"},"components":{"schemas":{}},"x":[]}""");

        assertThat(OpenApiDocuments.render(OpenApiDocuments.normalise(served))).isEqualTo("""
                {
                  "components": {
                    "schemas": {}
                  },
                  "info": {
                    "title": "T",
                    "version": "v1"
                  },
                  "openapi": "3.1.0",
                  "paths": {
                    "/a": {
                      "get": {
                        "tags": [
                          "Z",
                          "A"
                        ]
                      }
                    },
                    "/b": {}
                  },
                  "x": []
                }
                """);
    }

    @Test
    void keepsNestedServersMember() {
        final JsonNode served = OpenApiDocuments.parse("{\"paths\":{\"/a\":{\"servers\":[{\"url\":\"/x\"}]}}}");

        assertThat(OpenApiDocuments.normalise(served).path("paths").path("/a").has("servers")).isTrue();
    }

    @Test
    void firstDifferenceIsAnEscapedJsonPointer() {
        final JsonNode committed = OpenApiDocuments.parse("{\"paths\":{\"/v1/tables\":{\"post\":{\"operationId\":\"createTable\",\"tags\":[\"A\"]}}}}");
        final JsonNode renamed = OpenApiDocuments.parse("{\"paths\":{\"/v1/tables\":{\"post\":{\"operationId\":\"addTable\",\"tags\":[\"A\"]}}}}");
        final JsonNode retagged = OpenApiDocuments.parse("{\"paths\":{\"/v1/tables\":{\"post\":{\"operationId\":\"createTable\",\"tags\":[\"A\",\"B\"]}}}}");

        assertThat(OpenApiDocuments.firstDifference(committed, renamed)).contains("/paths/~1v1~1tables/post/operationId");
        assertThat(OpenApiDocuments.firstDifference(committed, retagged)).contains("/paths/~1v1~1tables/post/tags/1");
        assertThat(OpenApiDocuments.firstDifference(committed, committed)).isEmpty();
    }
}
