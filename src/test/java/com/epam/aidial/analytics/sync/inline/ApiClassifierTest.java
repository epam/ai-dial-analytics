package com.epam.aidial.analytics.sync.inline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the API classification to DIAL Core's own route patterns: the paths on which Core serves each API are
 * classified, whatever the deployment or application is called, and every other path — routes above all, whose
 * path an operator or user chooses — is not, however much its last segments look like an API.
 */
class ApiClassifierTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            /openai/deployments/gpt-5/chat/completions?api-version=2024-10-21                     | CHAT_COMPLETIONS
            /openai/deployments/public/pg/test/agent__1.0.0/chat/completions                      | CHAT_COMPLETIONS
            /openai/deployments/applications/public%2Fdial_scheduler__1.0.0/chat/completions      | CHAT_COMPLETIONS
            //openai/deployments/gpt-5/chat/completions                                           | CHAT_COMPLETIONS
            /openai/v1/chat/completions                                                           | CHAT_COMPLETIONS
            /openai/deployments/text-embedding-3-large/embeddings?api-version=2024-08-01-preview  | EMBEDDINGS
            /openai/v1/responses                                                                  | RESPONSES
            /openai/responses                                                                     | RESPONSES
            /anthropic/v1/messages?beta=true                                                      | ANTHROPIC_MESSAGES
            /anthropic/v1/messages/count_tokens?beta=true                                         | ANTHROPIC_MESSAGES
            /v1/toolset/toolsets/public/documents_helper_mcp_toolset__1.1.0/mcp                   | MCP
            /v1/deployments/statgpt-generic-rag/mcp                                               | MCP
            /v1/deployments/app/route/x/mcp                                                       | MCP
            """)
    void classifiesCorePaths(String uri, Api expected) {
        assertThat(ApiClassifier.classify(uri)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "/claude_code_router/v1/messages?beta=true",          // a global route with an operator-chosen path
        "/claude_code_router/v1/messages/count_tokens",
        "/v1/deployments/app/route/v1/messages",               // an application route
        "/v1/deployments/app/route/x/chat/completions",
        "/openai/deployments/gpt-35/completions",              // legacy text completions: no files
        "/openai/v1/responses/resp_123",
        "/v1/deployments/x/mcp/resources",
        "/v1/applications/public/pg/pg-agent__1.0.0/rate",
        "/OPENAI/deployments/gpt-5/chat/completions",          // Core matches case-sensitively
        "/openai/deployments/gpt-5/chat/completions/extra",
        ""
    })
    void leavesEveryOtherPathUnclassified(String uri) {
        assertThat(ApiClassifier.classify(uri)).isNull();
    }

    @Test
    void nullUriIsUnclassified() {
        assertThat(ApiClassifier.classify(null)).isNull();
    }
}
