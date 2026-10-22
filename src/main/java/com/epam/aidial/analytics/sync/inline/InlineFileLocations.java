package com.epam.aidial.analytics.sync.inline;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The fixed JSON locations at which each API carries an inline file, per side of the call — the data-sync spec's
 * location table: the chat, Responses and Anthropic Messages file fields, plus three response locations fixed by the
 * MCP and OpenAI Embeddings specs. A value is a file only when it is a JSON string at one of these locations <em>and</em>
 * its {@link Condition} holds; how the string looks never matters, so text anywhere else is kept however it is
 * encoded. A new provider field is a new row here.
 *
 * <p>A pattern is matched against the path from the root of a JSON body, or of one SSE event; {@code *} matches
 * any array index or object key. Every pattern ends in a fixed property name, which {@link #isCandidateName} uses
 * as a cheap pre-filter before a path is built.
 */
public final class InlineFileLocations {

    /** What, besides its location, makes a string value a file. Siblings are read from the value's own object. */
    public enum Condition {
        /** None: the location alone makes it a file. */
        ANY,
        /** The value is a base64 data URL ({@code data:...;base64,...}). */
        DATA_URL,
        /** DIAL attachment: sibling {@code type} is a binary type, or the value is a data URL and {@code type} is missing or binary. */
        DIAL_ATTACHMENT,
        /** Anthropic source: sibling {@code type} is {@code base64}. */
        BASE64_SOURCE,
        /** MCP content block: sibling {@code type} is {@code image} or {@code audio}. */
        MCP_MEDIA,
        /** MCP resource: sibling {@code mimeType} is a binary type. */
        BINARY_MIME_TYPE
    }

    /** One location: path segments ({@code *} = any) and the condition. */
    public record Location(List<String> segments, Condition condition) {

        boolean matches(List<String> path) {
            if (path.size() != segments.size()) {
                return false;
            }
            for (int i = 0; i < segments.size(); i++) {
                String expected = segments.get(i);
                if (!"*".equals(expected) && !expected.equals(path.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final Map<Api, List<Location>> REQUEST = new EnumMap<>(Api.class);
    private static final Map<Api, List<Location>> RESPONSE = new EnumMap<>(Api.class);
    private static final Set<String> CANDIDATE_NAMES = new HashSet<>();

    static {
        add(REQUEST, Api.CHAT_COMPLETIONS, Condition.DATA_URL, "messages/*/content/*/image_url/url");
        add(REQUEST, Api.CHAT_COMPLETIONS, Condition.ANY, "messages/*/content/*/input_audio/data");
        add(REQUEST, Api.CHAT_COMPLETIONS, Condition.DATA_URL, "messages/*/content/*/file/file_data");
        add(REQUEST, Api.CHAT_COMPLETIONS, Condition.DIAL_ATTACHMENT,
                "messages/*/custom_content/attachments/*/data",
                "messages/*/custom_content/stages/*/attachments/*/data");
        add(RESPONSE, Api.CHAT_COMPLETIONS, Condition.ANY,
                "choices/*/message/audio/data",
                "choices/*/delta/audio/data");
        add(RESPONSE, Api.CHAT_COMPLETIONS, Condition.DIAL_ATTACHMENT,
                "choices/*/message/custom_content/attachments/*/data",
                "choices/*/message/custom_content/stages/*/attachments/*/data",
                "choices/*/delta/custom_content/attachments/*/data",
                "choices/*/delta/custom_content/stages/*/attachments/*/data");

        add(REQUEST, Api.RESPONSES, Condition.DATA_URL,
                "input/*/content/*/image_url",
                "input/*/content/*/file_data",
                "input/*/output/image_url",
                "input/*/output/*/image_url",
                "input/*/output/*/file_data");
        add(REQUEST, Api.RESPONSES, Condition.ANY, "input/*/result");
        add(RESPONSE, Api.RESPONSES, Condition.ANY,
                "output/*/result",
                "item/result",
                "response/output/*/result",
                "partial_image_b64");

        add(REQUEST, Api.ANTHROPIC_MESSAGES, Condition.BASE64_SOURCE,
                "messages/*/content/*/source/data",
                "messages/*/content/*/content/*/source/data",
                "messages/*/content/*/content/content/source/data");

        add(RESPONSE, Api.MCP, Condition.MCP_MEDIA, "result/content/*/data");
        add(RESPONSE, Api.MCP, Condition.BINARY_MIME_TYPE, "result/content/*/resource/blob");

        add(RESPONSE, Api.EMBEDDINGS, Condition.ANY, "data/*/embedding");
    }

    private InlineFileLocations() {
    }

    private static void add(Map<Api, List<Location>> side, Api api, Condition condition, String... patterns) {
        for (String pattern : patterns) {
            List<String> segments = List.of(pattern.split("/"));
            side.computeIfAbsent(api, a -> new ArrayList<>()).add(new Location(segments, condition));
            CANDIDATE_NAMES.add(segments.getLast());
        }
    }

    /**
     * The locations of an API on one side of the call.
     *
     * @return the locations; empty when that side of that API carries no files (e.g. an Anthropic response)
     */
    public static List<Location> of(Api api, BodySide side) {
        return (side == BodySide.REQUEST ? REQUEST : RESPONSE).getOrDefault(api, List.of());
    }

    /** Whether a property name ends any location, so a string under it is worth locating. */
    static boolean isCandidateName(String name) {
        return name != null && CANDIDATE_NAMES.contains(name);
    }

    /**
     * Whether a media type is binary: {@code image/*} except {@code image/svg+xml}, {@code audio/*}, {@code video/*},
     * {@code application/pdf}, {@code octet-stream}, {@code zip}, {@code msword}, {@code vnd.ms-*} and
     * {@code vnd.openxmlformats-officedocument.*}. Parameters after {@code ;} and case are ignored.
     */
    static boolean isBinaryType(String type) {
        if (type == null) {
            return false;
        }
        int semicolon = type.indexOf(';');
        String bare = (semicolon < 0 ? type : type.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
        if (bare.startsWith("image/")) {
            return !"image/svg+xml".equals(bare);
        }
        return bare.startsWith("audio/")
                || bare.startsWith("video/")
                || "application/pdf".equals(bare)
                || "application/octet-stream".equals(bare)
                || "application/zip".equals(bare)
                || "application/msword".equals(bare)
                || bare.startsWith("application/vnd.ms-")
                || bare.startsWith("application/vnd.openxmlformats-officedocument.");
    }
}
