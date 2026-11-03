package com.epam.aidial.analytics.web.security;

import com.epam.aidial.analytics.service.dashboard.DashboardAccess;
import com.epam.aidial.analytics.service.query.saved.SavedQueryAccess;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the proviso that admits {@code SAVED_QUERY_WRITE → SAVED_QUERY_READ} and
 * {@code SAVED_QUERY_PUBLISH → SAVED_QUERY_READ} ({@link PrivilegeImplications}): <b>no read check consults
 * either write privilege</b>. While that holds, a write privilege adds exactly the read to what a caller may
 * see. A read path that consulted one would let it widen visibility again, as the removed publisher bypass
 * of the owner rule did; that bypass was a method on {@code SavedQueryAccess} that read paths called without
 * naming the privilege.
 *
 * <p>Three structural checks, all over main sources with comments and literals stripped:
 *
 * <ol>
 *   <li>Only the privilege model ({@code Privilege}, {@code PrivilegeImplications}, {@code RoleBundle}) and
 *       the two access classes name either privilege. A controller may name one only in the deferred gate
 *       of its {@code create}, {@code replace} or {@code delete} handler, which checks nothing itself.</li>
 *   <li>The access classes expose no method beyond a reviewed list, so a new one — a
 *       {@code maySeeEverySavedQuery} — cannot appear unnoticed.</li>
 *   <li>The access methods that consult a write privilege are called only where they belong: the
 *       write checks from the {@code create}, {@code replace} and {@code delete} of the two services, and
 *       the {@code permissions} flags from {@code DashboardMapper}, which reports them and filters nothing.</li>
 * </ol>
 *
 * <p>What this cannot see: a read decision made from the flags inside one of the allowed methods (for
 * instance {@code DashboardMapper} dropping list items whose {@code write} flag is false), or a privilege
 * reached reflectively or by name. Those are left to review. The behaviour of today's read paths is pinned
 * separately by the auth integration tests, where a {@code FULL_ADMIN} caller holding both write privileges
 * still gets {@code 404} for another user's personal saved query or private dashboard and sees none of its
 * activities.
 */
class WritePrivilegeConfinementTest {

    private static final Path BASE = Path.of("src", "main", "java", "com", "epam", "aidial", "analytics");

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

    private static final Set<String> WRITE_HANDLERS = Set.of("create", "replace", "delete");

    private static final Pattern DEFERRED_GATE =
            Pattern.compile("@RequiresPrivilege\\(value = Privilege\\.SAVED_QUERY_WRITE, deferred = true\\)");

    private static final Pattern WRITE_MAPPING = Pattern.compile("@(Post|Put|Delete)Mapping\\b");

    /**
     * Every non-private method each access class declares. A method added to either fails this test until it
     * is reviewed: if it consults a write privilege, it joins {@link #WRITE_CHECK_CALLERS} with its callers.
     */
    private static final Map<Class<?>, Set<String>> REVIEWED_ACCESS_METHODS = Map.of(
            SavedQueryAccess.class, Set.of("currentAuthor", "currentAuthorEmail", "isSecurityEnabled",
                    "requireAttributable", "requireAnyWriteAuthority", "requireWritable"),
            DashboardAccess.class, Set.of("currentAuthor", "currentAuthorEmail", "isSecurityEnabled",
                    "requireAttributable", "orphans", "requireAnyWriteAuthority", "requireWritable", "permissions",
                    "createPermissions"));

    /** For each access method that consults a write privilege: file → the methods it may be called from. */
    private static final Map<String, Map<String, Set<String>>> WRITE_CHECK_CALLERS = Map.of(
            "requireAnyWriteAuthority", writePaths(),
            "requireWritable", writePaths(),
            "permissions", flagReporters(),
            "createPermissions", flagReporters());

    /** Names unique to the access classes, so a call counts whatever its receiver expression is. */
    private static final Set<String> UNIQUE_WRITE_CHECKS = Set.of("requireAnyWriteAuthority", "requireWritable", "createPermissions");

    private static final Pattern ACCESS_VARIABLE = Pattern.compile("\\b(?:SavedQueryAccess|DashboardAccess)\\s+(\\w+)\\b");

    private static final Pattern WRITE_CHECK_CALL = Pattern.compile(
            "(?:\\b(\\w+)\\s*)?(?:\\.|::)\\s*(requireAnyWriteAuthority|requireWritable|permissions|createPermissions)\\b");

    /** A class-member declaration that opens a parameter list: a method or constructor, at member indent. */
    private static final Pattern MEMBER = Pattern.compile("^ {4}(?![ @}])(?:[\\w<>\\[\\],.? ]+\\s)?(\\w+)\\s*\\(");

    @Test
    void noCodeOutsideTheWriteSideNamesWritePrivilege() throws IOException {
        final List<String> violations = new ArrayList<>();
        for (final SourceFile file : sources()) {
            if (MAY_NAME_WRITE_PRIVILEGES.contains(file.relative())) {
                continue;
            }
            for (int i = 0; i < file.lines().length; i++) {
                if (WRITE_PRIVILEGE.matcher(file.lines()[i]).find() && !isDeferredWriteGate(file, i)) {
                    violations.add(file.at(i));
                }
            }
        }
        assertThat(violations)
                .as("A read check must not consult SAVED_QUERY_WRITE or SAVED_QUERY_PUBLISH: that would let a write privilege "
                        + "widen what a caller may see, and break the proviso PrivilegeImplications admits both "
                        + "write -> read edges on. Decide visibility from scope/visibility and owner alone.")
                .isEmpty();
    }

    @Test
    void theAccessClassesExposeOnlyReviewedMethods() {
        REVIEWED_ACCESS_METHODS.forEach((type, reviewed) -> {
            final Set<String> declared = Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic() && !Modifier.isPrivate(method.getModifiers()))
                    .map(Method::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(declared)
                    .as(type.getSimpleName() + " gained or lost a method. If a new one consults SAVED_QUERY_WRITE or "
                            + "SAVED_QUERY_PUBLISH, it is a write check: list it in WRITE_CHECK_CALLERS with the write paths "
                            + "that may call it, and never call it from a read path.")
                    .isEqualTo(new TreeSet<>(reviewed));
        });
    }

    @Test
    void writeChecksAreCalledOnlyFromTheirWritePaths() throws IOException {
        final List<String> violations = new ArrayList<>();
        for (final SourceFile file : sources()) {
            if (file.relative().endsWith("/SavedQueryAccess.java") || file.relative().endsWith("/DashboardAccess.java")) {
                continue;
            }
            for (final WriteCheckCall call : writeCheckCalls(file)) {
                final Set<String> allowed = WRITE_CHECK_CALLERS.get(call.method()).getOrDefault(file.relative(), Set.of());
                if (!allowed.contains(call.enclosing())) {
                    violations.add(file.at(call.line()) + "  (" + call.method() + " in " + call.enclosing() + ")");
                }
            }
        }
        assertThat(violations)
                .as("An access method that consults a write privilege was called outside the write paths it belongs to. "
                        + "A read path must decide visibility from scope/visibility and owner alone.")
                .isEmpty();
    }

    @Test
    void theScansSeeTheKnownWriteChecks() throws IOException {
        // Guards the guards: if the stripping, the paths or the patterns broke, every scan would pass vacuously.
        final Map<String, SourceFile> byPath = sources().stream()
                .collect(Collectors.toMap(SourceFile::relative, file -> file));
        assertThat(WRITE_PRIVILEGE.matcher(String.join("\n", byPath.get("service/dashboard/DashboardAccess.java").lines())).find())
                .isTrue();
        assertThat(writeCheckCalls(byPath.get("service/dashboard/DashboardService.java")))
                .extracting(WriteCheckCall::enclosing).contains("create", "replace", "delete");
        assertThat(writeCheckCalls(byPath.get("service/query/saved/SavedQueryService.java")))
                .extracting(WriteCheckCall::enclosing).contains("create", "replace", "delete");
        assertThat(writeCheckCalls(byPath.get("web/mapper/dashboard/DashboardMapper.java")))
                .extracting(WriteCheckCall::method).contains("permissions", "createPermissions");
        final SourceFile controller = byPath.get("web/controller/dashboard/DashboardController.java");
        assertThat(IntStream.range(0, controller.lines().length)
                .filter(i -> WRITE_PRIVILEGE.matcher(controller.lines()[i]).find())
                .allMatch(i -> isDeferredWriteGate(controller, i))).isTrue();
    }

    @Test
    void stripsCommentsAndLiteralsButKeepsCode() {
        final String source = """
                /** SAVED_QUERY_WRITE */ int a; // SAVED_QUERY_PUBLISH
                String s = "SAVED_QUERY_WRITE \\" still a string"; char c = '"'; char d = '\\'';
                String t = \"""
                    SAVED_QUERY_PUBLISH \\\""" still the text block
                    \""";
                check(Privilege.SAVED_QUERY_PUBLISH);
                """;
        final Matcher matcher = WRITE_PRIVILEGE.matcher(code(source));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).isEqualTo("SAVED_QUERY_PUBLISH");
        assertThat(matcher.find()).isFalse();
        assertThat(code(source).split("\n", -1)).hasSameSizeAs(source.split("\n", -1));
    }

    @Test
    void attributesEachCallToItsEnclosingMethod() {
        final SourceFile file = new SourceFile("service/dashboard/DashboardService.java", code("""
                class X {
                    private final DashboardAccess guard;

                    @Transactional
                    public List<Y> list() {
                        return items.stream().filter(i -> guard.permissions(i.v(), i.o()).write()).toList();
                    }

                    public void create(Request request) {
                        guard.requireWritable(request.visibility());
                    }
                }
                """).split("\n", -1));
        assertThat(writeCheckCalls(file))
                .extracting(call -> call.method() + "@" + call.enclosing())
                .containsExactly("permissions@list", "requireWritable@create");
    }

    // ---------------------------------------------------------------- scanning

    private static boolean isDeferredWriteGate(SourceFile file, int line) {
        return DECLARE_DEFERRED_WRITE_GATES.contains(file.relative())
                && DEFERRED_GATE.matcher(file.lines()[line]).find()
                && line > 0 && WRITE_MAPPING.matcher(file.lines()[line - 1]).find()
                && WRITE_HANDLERS.contains(nextMember(file.lines(), line));
    }

    private static String nextMember(String[] lines, int from) {
        for (int i = from + 1; i < lines.length; i++) {
            final Matcher member = MEMBER.matcher(lines[i]);
            if (member.find()) {
                return member.group(1);
            }
        }
        return "";
    }

    private static List<WriteCheckCall> writeCheckCalls(SourceFile file) {
        final Set<String> accessVariables = new HashSet<>();
        for (final String line : file.lines()) {
            final Matcher declared = ACCESS_VARIABLE.matcher(line);
            while (declared.find()) {
                accessVariables.add(declared.group(1));
            }
        }
        final List<WriteCheckCall> calls = new ArrayList<>();
        String enclosing = "";
        for (int i = 0; i < file.lines().length; i++) {
            final Matcher member = MEMBER.matcher(file.lines()[i]);
            if (member.find()) {
                enclosing = member.group(1);
            }
            final Matcher call = WRITE_CHECK_CALL.matcher(file.lines()[i]);
            while (call.find()) {
                final String method = call.group(2);
                if (UNIQUE_WRITE_CHECKS.contains(method) || accessVariables.contains(call.group(1))) {
                    calls.add(new WriteCheckCall(method, enclosing, i));
                }
            }
        }
        return calls;
    }

    private static List<SourceFile> sources() throws IOException {
        try (Stream<Path> files = Files.walk(BASE)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> new SourceFile(BASE.relativize(path).toString().replace('\\', '/'), read(path)))
                    .toList();
        }
    }

    private static String[] read(Path path) {
        try {
            return code(Files.readString(path).replace("\r\n", "\n")).split("\n", -1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code source} with comments, string and text-block literals and char literals blanked out, escapes
     * inside literals included. Newlines are kept, so line numbers still match the file.
     */
    static String code(String source) {
        final StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                final int end = source.indexOf('\n', i);
                i = blank(source, i, end < 0 ? source.length() : end, out);
            } else if (source.startsWith("/*", i)) {
                final int end = source.indexOf("*/", i + 2);
                i = blank(source, i, end < 0 ? source.length() : end + 2, out);
            } else if (source.startsWith("\"\"\"", i)) {
                i = blank(source, i, endOfLiteral(source, i + 3, "\"\"\"", false), out);
            } else if (source.charAt(i) == '"') {
                i = blank(source, i, endOfLiteral(source, i + 1, "\"", true), out);
            } else if (source.charAt(i) == '\'') {
                i = blank(source, i, endOfLiteral(source, i + 1, "'", true), out);
            } else {
                out.append(source.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** The index just past the {@code close} that ends a literal starting at {@code from}, skipping escapes. */
    private static int endOfLiteral(String source, int from, String close, boolean endsAtLine) {
        int i = from;
        while (i < source.length()) {
            if (source.charAt(i) == '\\') {
                i += 2;
            } else if (source.startsWith(close, i)) {
                return i + close.length();
            } else if (endsAtLine && source.charAt(i) == '\n') {
                return i;
            } else {
                i++;
            }
        }
        return source.length();
    }

    /** Blanks {@code [from, to)} into {@code out}, keeping newlines. */
    private static int blank(String source, int from, int to, StringBuilder out) {
        final int end = Math.min(to, source.length());
        for (int i = from; i < end; i++) {
            out.append(source.charAt(i) == '\n' ? '\n' : ' ');
        }
        return end;
    }

    private static Map<String, Set<String>> writePaths() {
        return Map.of(
                "service/query/saved/SavedQueryService.java", WRITE_HANDLERS,
                "service/dashboard/DashboardService.java", WRITE_HANDLERS);
    }

    private static Map<String, Set<String>> flagReporters() {
        return Map.of("web/mapper/dashboard/DashboardMapper.java", Set.of("toDto", "toListDto", "toListItem"));
    }

    private record SourceFile(String relative, String[] lines) {
        String at(int line) {
            return relative + ":" + (line + 1) + ": " + lines[line].strip();
        }
    }

    private record WriteCheckCall(String method, String enclosing, int line) {}
}
