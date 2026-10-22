package com.epam.aidial.analytics.sync.inline;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Infers the API a usage-log record was served as from its request URI, the way DIAL Core decides it.
 *
 * <p>Core picks the controller, and so the API, by matching the request path against the anchored regexes of its
 * {@code RouteTemplate} enum, tried in {@code ControllerSelector}'s registration order, first match wins. The
 * patterns below are copies of the API ones ({@code POST_DEPLOYMENT}, {@code LLM_CHAT_COMPLETIONS_API},
 * {@code LLM_RESPONSES_API}, {@code LLM_RESPONSES_API_UNVERSIONED}, {@code LLM_MESSAGES_API_COUNT_TOKENS},
 * {@code LLM_MESSAGES_API}, {@code TOOL_SET_MCP_PROXY}, {@code APPLICATION_MCP_PROXY}), in that order. Keep them in
 * step with Core: a path Core adds later is simply not scanned here until it is copied in, which keeps more in the
 * log, never less.
 *
 * <p>Every other path is a route — an application route ({@code /v1/deployments/{id}/route/...}) or a global
 * route whose path an operator chose ({@code /claude_code_router/v1/messages}) — and gets no API, whatever its
 * last segments look like: a route proxies an arbitrary upstream, so a JSON location means nothing there. Matching
 * is on the raw path (percent-encoding kept, as Core matches it), case-sensitive, with the query string removed.
 */
public final class ApiClassifier {

    /** One Core route template and how a match maps to an API ({@code null} for a path that is no file API). */
    private record Route(Pattern pattern, Function<Matcher, Api> api) {
    }

    private static final List<Route> ROUTES = List.of(
            // POST_DEPLOYMENT: the deployment id is lazy and may span '/', exactly as in Core.
            new Route(Pattern.compile("^/+openai/deployments/(?<id>.+?)/(?<action>completions|chat/completions|embeddings)$"),
                    m -> switch (m.group("action")) {
                        case "chat/completions" -> Api.CHAT_COMPLETIONS;
                        case "embeddings" -> Api.EMBEDDINGS;
                        default -> null; // legacy text completions carry no files
                    }),
            new Route(Pattern.compile("^/+openai/v1/chat/completions$"), m -> Api.CHAT_COMPLETIONS),
            new Route(Pattern.compile("^/+openai/v1/responses$"), m -> Api.RESPONSES),
            new Route(Pattern.compile("^/+openai/responses$"), m -> Api.RESPONSES),
            new Route(Pattern.compile("^/+anthropic/v1/messages/count_tokens$"), m -> Api.ANTHROPIC_MESSAGES),
            new Route(Pattern.compile("^/+anthropic/v1/messages$"), m -> Api.ANTHROPIC_MESSAGES),
            new Route(Pattern.compile("^/v1/toolset/(?<id>.+?)/mcp$"), m -> Api.MCP),
            // Also matches /v1/deployments/{id}/route/.../mcp, because {id} spans '/': Core registers this ahead of
            // the deployment routes, so it serves that path as MCP too.
            new Route(Pattern.compile("^/v1/deployments/(?<id>.+?)/mcp$"), m -> Api.MCP));

    private ApiClassifier() {
    }

    /**
     * Classifies a request URI.
     *
     * @param uri the record's request URI, path plus optional query string; may be {@code null}
     * @return the API, or {@code null} when the path is not one on which Core serves a file-carrying API
     */
    public static Api classify(String uri) {
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        for (Route route : ROUTES) {
            Matcher matcher = route.pattern().matcher(path);
            if (matcher.matches()) {
                return route.api().apply(matcher);
            }
        }
        return null;
    }
}
