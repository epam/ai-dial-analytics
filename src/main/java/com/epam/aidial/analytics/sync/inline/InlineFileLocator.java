package com.epam.aidial.analytics.sync.inline;

import com.epam.aidial.analytics.sync.inline.InlineFileLocations.Condition;
import com.epam.aidial.analytics.sync.inline.InlineFileLocations.Location;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.core.json.JsonFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Finds the inline files in one JSON document — a whole body, or one SSE event — with a Jackson 3 streaming parse
 * over its characters. No tree is built and a candidate's text is never materialized: its start is the token
 * location of its opening quote, and its end a raw scan for the next unescaped quote, so a multi-MB file is read
 * once by the scan and once by the parser's own skip, and never copied.
 *
 * <p>A candidate is a string at a {@link Location} of the API's set. Its {@link Condition} may depend on a sibling
 * ({@code type}, {@code mimeType}) that comes after it, so such a candidate is decided when its object closes, or,
 * if the document ends first, with whatever siblings were read by then: a sibling not read is then unknown, not
 * missing, so it never makes the candidate a file.
 *
 * <p>The parse never throws. A document that stops parsing — truncated at Core's 4 MiB cut, or malformed — keeps
 * the files found before the error; a candidate whose string has no closing quote is reported as {@link Scan#open()},
 * which is what the caller cuts back over for a truncated body.
 */
final class InlineFileLocator {

    private static final JsonFactory FACTORY = new JsonFactory();

    /** How far into a value the {@code data:...;base64,} prefix is looked for. */
    private static final int MAX_DATA_URL_PREFIX = 512;

    /**
     * One file's spans in the scanned text.
     *
     * @param quote        index of the value's opening quote
     * @param payloadStart index of the first payload character: after the data-URL prefix, or after the quote
     * @param end          index of the closing quote, or {@code -1} when the string is still open at the end of the text
     */
    record Span(int quote, int payloadStart, int end) {

        boolean isOpen() {
            return end < 0;
        }
    }

    /**
     * The result of scanning one document.
     *
     * @param files      complete files, in document order
     * @param open       a file whose string is still open at the end of the text, or {@code null}
     * @param parseError whether the document stopped parsing before its end
     */
    record Scan(List<Span> files, Span open, boolean parseError) {
    }

    /** A candidate awaiting its object's siblings. */
    private record Pending(Condition condition, int quote, int end) {
    }

    /** The siblings read so far in one object, and the candidates waiting on them. Arrays get a frame too, unused. */
    private static final class Frame {
        /** Whether the object's {@code END_OBJECT} was read; when not, a sibling not read is unknown, not missing. */
        boolean closed;
        boolean typePresent;
        String type;
        String mimeType;
        List<Pending> pending;
    }

    private InlineFileLocator() {
    }

    /**
     * Scans {@code text[from, to)} as one JSON document.
     *
     * @param text      the body
     * @param from      start of the document within {@code text}
     * @param to        end (exclusive) of the document within {@code text}
     * @param locations the API's locations for the body's side; must not be empty
     * @return the files found, with spans as indexes into {@code text}
     */
    static Scan scan(String text, int from, int to, List<Location> locations) {
        String doc = from == 0 && to == text.length() ? text : text.substring(from, to);
        List<Span> files = new ArrayList<>();
        Span[] open = new Span[1];
        Deque<Frame> frames = new ArrayDeque<>();
        boolean parseError = false;
        try (JsonParser parser = FACTORY.createParser(ObjectReadContext.empty(), doc)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                switch (token) {
                    case START_OBJECT, START_ARRAY -> frames.push(new Frame());
                    case END_OBJECT -> {
                        Frame frame = frames.pop();
                        frame.closed = true;
                        decide(frame, doc, from, files, open);
                    }
                    case END_ARRAY -> frames.pop();
                    case VALUE_STRING -> onString(parser, doc, from, locations, frames.peek(), files, open);
                    default -> recordSibling(parser, frames.peek(), null);
                }
            }
        } catch (JacksonException ex) {
            parseError = true;
        }
        // Objects the text ended inside: decide their candidates with the siblings read before the end. Those frames
        // stay unclosed, so a sibling not read is unknown rather than missing.
        while (!frames.isEmpty()) {
            decide(frames.pop(), doc, from, files, open);
        }
        files.sort((a, b) -> Integer.compare(a.quote(), b.quote()));
        return new Scan(files, open[0], parseError);
    }

    private static void onString(JsonParser parser, String doc, int base, List<Location> locations, Frame frame,
                                 List<Span> files, Span[] open) {
        String name = parser.currentName();
        if (frame == null || !parser.streamReadContext().inObject()) {
            return; // a root scalar, or an array element: no location ends there
        }
        if ("type".equals(name) || "mimeType".equals(name)) {
            recordSibling(parser, frame, name);
            return;
        }
        if (!InlineFileLocations.isCandidateName(name)) {
            return;
        }
        Location location = match(parser.streamReadContext(), locations);
        if (location == null) {
            return;
        }
        int quote = (int) parser.currentTokenLocation().getCharOffset();
        int end = closingQuote(doc, quote + 1);
        Pending pending = new Pending(location.condition(), quote, end);
        if (needsSiblings(location.condition())) {
            if (frame.pending == null) {
                frame.pending = new ArrayList<>(2);
            }
            frame.pending.add(pending);
        } else {
            accept(pending, null, doc, base, files, open);
        }
    }

    /** Records a {@code type}/{@code mimeType} sibling; {@code name} is {@code null} for a non-string token. */
    private static void recordSibling(JsonParser parser, Frame frame, String name) {
        if (frame == null || !parser.streamReadContext().inObject()) {
            return;
        }
        String property = name != null ? name : parser.currentName();
        if ("type".equals(property)) {
            frame.typePresent = true;
            frame.type = parser.currentToken() == JsonToken.VALUE_STRING ? parser.getString() : null;
        } else if ("mimeType".equals(property)) {
            frame.mimeType = parser.currentToken() == JsonToken.VALUE_STRING ? parser.getString() : null;
        }
    }

    private static void decide(Frame frame, String doc, int base, List<Span> files, Span[] open) {
        if (frame.pending == null) {
            return;
        }
        for (Pending pending : frame.pending) {
            accept(pending, frame, doc, base, files, open);
        }
    }

    private static void accept(Pending pending, Frame frame, String doc, int base, List<Span> files, Span[] open) {
        int payloadStart = dataUrlPayloadStart(doc, pending.quote(), pending.end());
        boolean dataUrl = payloadStart >= 0;
        boolean file = switch (pending.condition()) {
            case ANY -> true;
            case DATA_URL -> dataUrl;
            case DIAL_ATTACHMENT -> InlineFileLocations.isBinaryType(frame.type)
                    || dataUrl && frame.closed && !frame.typePresent;
            case BASE64_SOURCE -> "base64".equals(frame.type);
            case MCP_MEDIA -> "image".equals(frame.type) || "audio".equals(frame.type);
            case BINARY_MIME_TYPE -> InlineFileLocations.isBinaryType(frame.mimeType);
        };
        if (!file) {
            return;
        }
        int start = dataUrl ? payloadStart : pending.quote() + 1;
        Span span = new Span(base + pending.quote(), base + start, pending.end() < 0 ? -1 : base + pending.end());
        if (span.isOpen()) {
            open[0] = span;
        } else {
            files.add(span);
        }
    }

    private static boolean needsSiblings(Condition condition) {
        return condition != Condition.ANY && condition != Condition.DATA_URL;
    }

    /** Builds the value's path from the parser context and returns the first location it matches. */
    private static Location match(TokenStreamContext context, List<Location> locations) {
        List<String> path = new ArrayList<>(8);
        for (TokenStreamContext c = context; c != null && !c.inRoot(); c = c.getParent()) {
            path.add(c.inObject() ? c.currentName() : Integer.toString(c.getCurrentIndex()));
        }
        Collections.reverse(path);
        for (Location location : locations) {
            if (location.matches(path)) {
                return location;
            }
        }
        return null;
    }

    /** Index of the unescaped quote closing a string that starts at {@code from}, or {@code -1} if the text ends first. */
    static int closingQuote(String doc, int from) {
        int length = doc.length();
        for (int i = from; i < length; i++) {
            char c = doc.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /**
     * When the value at {@code quote} is a base64 data URL, the index just after its prefix's comma; else {@code -1}.
     * The prefix is the raw text up to the first {@code ,}: it must start with {@code data:} and end with
     * {@code ;base64}, ignoring case, after a JSON escape such as {@code \/} in it is unescaped.
     */
    static int dataUrlPayloadStart(String doc, int quote, int end) {
        int limit = end < 0 ? doc.length() : end;
        int max = Math.min(limit, quote + 1 + MAX_DATA_URL_PREFIX);
        int comma = -1;
        for (int i = quote + 1; i < max; i++) {
            if (doc.charAt(i) == ',') {
                comma = i;
                break;
            }
        }
        if (comma < 0) {
            return -1;
        }
        String prefix = doc.substring(quote + 1, comma);
        if (prefix.indexOf('\\') >= 0) {
            prefix = unescape(prefix);
        }
        String lower = prefix.toLowerCase(Locale.ROOT);
        return lower.startsWith("data:") && lower.endsWith(";base64") ? comma + 1 : -1;
    }

    /** Undoes the JSON escapes a data-URL prefix can carry; enough for {@code \/}, {@code \\} and {@code \"}. */
    private static String unescape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                out.append(raw.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
