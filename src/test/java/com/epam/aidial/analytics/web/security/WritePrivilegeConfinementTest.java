package com.epam.aidial.analytics.web.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the proviso that admits {@code SAVED_QUERY_WRITE → SAVED_QUERY_READ} and
 * {@code SAVED_QUERY_PUBLISH → SAVED_QUERY_READ} ({@link PrivilegeImplications}): <b>no read check consults
 * either write privilege</b>. While that holds, a write privilege adds exactly the read to what a caller may
 * see. A read path that tested one would let it widen visibility again, as the removed publisher bypass of
 * the owner rule did.
 *
 * <p>The check is structural, so it also covers read paths that do not exist yet: outside the classes that
 * declare the privilege model and the classes that enforce saved-query and dashboard <em>writes</em>, main
 * code may not name either privilege. A controller may name one only in the deferred gate of a
 * {@code POST}, {@code PUT} or {@code DELETE} handler, which checks nothing itself and hands the decision to
 * the write chokepoint. Comments and string literals are ignored, so documentation and error messages may
 * still name the privileges.
 *
 * <p>The behaviour of today's read paths is pinned by the auth integration tests, where a {@code FULL_ADMIN}
 * caller holding both write privileges still gets {@code 404} for another user's personal saved query or
 * private dashboard and sees none of its activities.
 */
class WritePrivilegeConfinementTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    private static final Pattern WRITE_PRIVILEGE = Pattern.compile("\\bSAVED_QUERY_(WRITE|PUBLISH)\\b");

    /** The privilege model itself, and the two classes that decide saved-query and dashboard writes. */
    private static final Set<String> MAY_NAME_WRITE_PRIVILEGES = Set.of(
            "web/security/Privilege.java",
            "web/security/PrivilegeImplications.java",
            "web/security/RoleBundle.java",
            "service/query/saved/SavedQueryAccess.java",
            "service/dashboard/DashboardAccess.java");

    /** Controllers whose write handlers declare the deferred gate. */
    private static final Set<String> DECLARE_DEFERRED_WRITE_GATES = Set.of(
            "web/controller/query/SavedQueryController.java",
            "web/controller/dashboard/DashboardController.java");

    private static final Pattern DEFERRED_GATE =
            Pattern.compile("@RequiresPrivilege\\(value = Privilege\\.SAVED_QUERY_WRITE, deferred = true\\)");

    private static final Pattern WRITE_MAPPING = Pattern.compile("@(Post|Put|Delete)Mapping\\b");

    @Test
    void noReadPathConsultsWritePrivilege() throws IOException {
        final List<String> violations = new ArrayList<>();
        final Path base = MAIN.resolve(Path.of("com", "epam", "aidial", "analytics"));
        try (Stream<Path> files = Files.walk(base)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> violations.addAll(violationsIn(base.relativize(path).toString().replace('\\', '/'), path)));
        }
        assertThat(violations)
                .as("A read check must not consult SAVED_QUERY_WRITE or SAVED_QUERY_PUBLISH: that would let a write privilege "
                        + "widen what a caller may see, and break the proviso PrivilegeImplications admits both "
                        + "write -> read edges on. Decide visibility from scope/visibility and owner alone.")
                .isEmpty();
    }

    @Test
    void theScanSeesTheWriteChokepoints() throws IOException {
        // Guards the guard: if stripping or the path ever went wrong, the scan would pass vacuously.
        final Path access = MAIN.resolve(Path.of("com", "epam", "aidial", "analytics", "service", "dashboard", "DashboardAccess.java"));
        assertThat(WRITE_PRIVILEGE.matcher(code(Files.readString(access))).find()).isTrue();
    }

    @Test
    void stripsCommentsAndLiteralsButKeepsCode() {
        final String source = """
                /** SAVED_QUERY_WRITE */ int a; // SAVED_QUERY_PUBLISH
                String s = "SAVED_QUERY_WRITE \\" still a string"; char c = '"';
                String t = \"""
                    SAVED_QUERY_PUBLISH
                    \""";
                check(Privilege.SAVED_QUERY_PUBLISH);
                """;
        final Matcher matcher = WRITE_PRIVILEGE.matcher(code(source));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).isEqualTo("SAVED_QUERY_PUBLISH");
        assertThat(matcher.find()).isFalse();
    }

    private static List<String> violationsIn(String relative, Path path) {
        if (MAY_NAME_WRITE_PRIVILEGES.contains(relative)) {
            return List.of();
        }
        final String[] lines;
        try {
            lines = code(Files.readString(path)).split("\n", -1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        final List<String> violations = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (!WRITE_PRIVILEGE.matcher(lines[i]).find()) {
                continue;
            }
            if (DECLARE_DEFERRED_WRITE_GATES.contains(relative) && DEFERRED_GATE.matcher(lines[i]).find()
                    && i > 0 && WRITE_MAPPING.matcher(lines[i - 1]).find()) {
                continue;
            }
            violations.add(relative + ":" + (i + 1) + ": " + lines[i].strip());
        }
        return violations;
    }

    /**
     * {@code source} with comments, string and text-block literals and char literals blanked out. Newlines
     * are kept, so line numbers still match the file.
     */
    static String code(String source) {
        final StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            final char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                i = skip(source, i, source.indexOf('\n', i), out);
            } else if (source.startsWith("/*", i)) {
                final int end = source.indexOf("*/", i + 2);
                i = skip(source, i, end < 0 ? source.length() : end + 2, out);
            } else if (source.startsWith("\"\"\"", i)) {
                final int end = source.indexOf("\"\"\"", i + 3);
                i = skip(source, i, end < 0 ? source.length() : end + 3, out);
            } else if (c == '"' || c == '\'') {
                i = skip(source, i, endOfLiteral(source, i, c), out);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int endOfLiteral(String source, int start, char quote) {
        int i = start + 1;
        while (i < source.length() && source.charAt(i) != quote && source.charAt(i) != '\n') {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i + 1, source.length());
    }

    /** Blanks {@code [from, to)} into {@code out}, keeping newlines; {@code to < 0} means end of input. */
    private static int skip(String source, int from, int to, StringBuilder out) {
        final int end = to < 0 ? source.length() : to;
        for (int i = from; i < end; i++) {
            out.append(source.charAt(i) == '\n' ? '\n' : ' ');
        }
        return end;
    }
}
