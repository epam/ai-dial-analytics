package com.epam.aidial.analytics.openspec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Asserts that every in-flight delta spec files each requirement under the section that matches what it
 * actually does to the shipped spec.
 *
 * <p><b>Why a test and not a review step.</b> {@code openspec validate --strict} checks a delta's shape —
 * that sections parse and every requirement carries a scenario — but never compares a heading against the
 * shipped spec, so a misfiled block passes it. Two got through on this change: {@code Opaque token
 * introspection} sat under {@code REMOVED} while reproducing a requirement the code still implements, which
 * archive would have <em>deleted</em>; and a requirement that exists verbatim upstream sat under
 * {@code ADDED}, which archive would have duplicated or silently left stale. Both came from generating
 * blocks without a section header and appending them, so whichever section happened to be last in the file
 * claimed them — a mistake no amount of care at the keyboard prevents, and one that only shows up at
 * archive time, which is exactly when it is expensive.
 *
 * <p>Four rules, each the statement of what a section means:
 *
 * <ul>
 *   <li><b>MODIFIED</b> replaces a shipped requirement, so its heading must exist verbatim upstream.</li>
 *   <li><b>ADDED</b> introduces one, so its heading must not.</li>
 *   <li><b>REMOVED</b> deletes one, so it must say why — a {@code **Reason**} line — and reproducing the
 *       requirement body there instead is the shape that hid the first defect.</li>
 *   <li><b>RENAMED</b> renames one, so its {@code FROM} heading must exist upstream and its {@code TO} heading
 *       must not — unless the rename was already synced ({@code FROM} gone, {@code TO} present), which archive
 *       treats as a no-op. Archive applies the rename first, so the other sections are judged against the
 *       shipped headings with the rename applied.</li>
 * </ul>
 *
 * <p>Scoped to {@code openspec/changes} and never {@code archive}: an archived change's deltas have already
 * been merged, so its headings are expected to match the shipped spec whatever section they sat in.
 */
class DeltaSpecStructureTest {

    private static final Path CHANGES = Path.of("openspec", "changes");
    private static final Path SPECS = Path.of("openspec", "specs");
    private static final String REQUIREMENT = "### Requirement: ";
    /** One side of a {@code RENAMED} entry: {@code - FROM: `### Requirement: <name>`} or {@code - TO: …}. */
    private static final Pattern RENAME = Pattern.compile("- (FROM|TO): `### Requirement: (.+)`");

    @Test
    @DisplayName("every delta requirement is filed under the section that matches the shipped spec")
    void deltaRequirementsAreFiledCorrectly() throws IOException {
        assumeTrue(Files.isDirectory(CHANGES), "no openspec/changes directory in this working tree");
        final List<String> problems = new ArrayList<>();
        try (Stream<Path> deltas = Files.walk(CHANGES)) {
            deltas.filter(path -> path.getFileName().toString().equals("spec.md"))
                    .filter(path -> !path.toString().replace('\\', '/').contains("/archive/"))
                    .sorted()
                    .forEach(path -> problems.addAll(check(path)));
        }
        assertThat(problems)
                .as("each violation would corrupt the shipped spec at archive time, not merely read oddly")
                .isEmpty();
    }

    @Test
    @DisplayName("a rename applies to the shipped headings the other sections are judged against")
    void renameAppliesToShippedHeadings() {
        final Set<String> shipped = new LinkedHashSet<>(List.of("Old", "Other"));
        final List<String> problems = new ArrayList<>();
        applyRenames("cap", renamed("Old", "New"), shipped, problems);
        assertThat(problems).isEmpty();
        assertThat(shipped).containsExactlyInAnyOrder("New", "Other");
    }

    @Test
    @DisplayName("a rename onto a shipped heading, or from a missing one, is reported")
    void badRenamesAreReported() {
        final List<String> problems = new ArrayList<>();
        applyRenames("cap", renamed("Old", "Other"), new LinkedHashSet<>(List.of("Old", "Other")), problems);
        applyRenames("cap", renamed("Missing", "New"), new LinkedHashSet<>(List.of("Old")), problems);
        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).contains("already has");
        assertThat(problems.get(1)).contains("does not have");
    }

    @Test
    @DisplayName("an already-synced rename, CRLF endings and a lower-case section header are accepted")
    void syncedRenameAndCrlfAreAccepted() {
        final Set<String> shipped = new LinkedHashSet<>(List.of("New"));
        final List<String> problems = new ArrayList<>();
        applyRenames("cap", renamed("Old", "New").replace("\n", "\r\n"), shipped, problems);
        applyRenames("cap", renamed("Gone", "New").replace("RENAMED", "Renamed"), shipped, problems);
        assertThat(problems).isEmpty();
        assertThat(shipped).containsExactly("New");
    }

    @Test
    @DisplayName("rename lines outside the RENAMED section are ignored")
    void renameLinesOutsideTheSectionAreIgnored() {
        final Set<String> shipped = new LinkedHashSet<>(List.of("Old"));
        final List<String> problems = new ArrayList<>();
        applyRenames("cap", renamed("Old", "New").replace("## RENAMED Requirements", "## MODIFIED Requirements"),
                shipped, problems);
        assertThat(problems).isEmpty();
        assertThat(shipped).containsExactly("Old");
    }

    private static String renamed(String from, String to) {
        return "## RENAMED Requirements\n\n- FROM: `" + REQUIREMENT + from + "`\n- TO: `" + REQUIREMENT + to + "`\n";
    }

    /** Every filing problem in one delta file, so one run reports them all. */
    private static List<String> check(Path delta) {
        final String capability = delta.getParent().getFileName().toString();
        final Set<String> shipped = new LinkedHashSet<>(shippedRequirements(capability));
        final List<String> problems = new ArrayList<>();
        applyRenames(capability, read(delta), shipped, problems);
        String section = null;
        String heading = null;
        final StringBuilder body = new StringBuilder();
        for (final String line : read(delta).split("\n", -1)) {
            if (line.startsWith("## ") && line.strip().endsWith("Requirements")) {
                problems.addAll(judge(capability, section, heading, body.toString(), shipped));
                section = line.strip().substring(3).split(" ")[0].toUpperCase(java.util.Locale.ROOT);
                heading = null;
                body.setLength(0);
                continue;
            }
            if (line.startsWith(REQUIREMENT)) {
                problems.addAll(judge(capability, section, heading, body.toString(), shipped));
                heading = line.substring(REQUIREMENT.length()).strip();
                body.setLength(0);
                continue;
            }
            body.append(line).append('\n');
        }
        problems.addAll(judge(capability, section, heading, body.toString(), shipped));
        return problems;
    }

    /**
     * Applies the delta's {@code RENAMED} pairs to {@code shipped}, as archive does before the other sections. A
     * pair whose {@code FROM} is gone and whose {@code TO} is already shipped was synced early and is a no-op.
     */
    static void applyRenames(String capability, String delta, Set<String> shipped, List<String> problems) {
        boolean renamed = false;
        String from = null;
        for (final String line : delta.split("\n", -1)) {
            if (line.startsWith("## ")) {
                renamed = line.strip().equalsIgnoreCase("## RENAMED Requirements");
                continue;
            }
            final Matcher rename = RENAME.matcher(line.strip());
            if (!renamed || !rename.matches()) {
                continue;
            }
            final String name = rename.group(2).strip();
            if (rename.group(1).equals("FROM")) {
                from = name;
                continue;
            }
            if (from == null) {
                continue;
            }
            final String where = capability + " / " + from;
            if (shipped.contains(from)) {
                if (shipped.contains(name)) {
                    problems.add(where + ": renamed TO '" + name + "', which the shipped spec already has — archive"
                            + " would refuse the rename");
                } else {
                    shipped.remove(from);
                    shipped.add(name);
                }
            } else if (!shipped.contains(name)) {
                problems.add(where + ": renamed FROM a requirement the shipped spec does not have — archive would"
                        + " fail");
            }
            from = null;
        }
    }

    private static List<String> judge(
            String capability, String section, String heading, String body, Set<String> shipped) {
        if (section == null || heading == null) {
            return List.of();
        }
        final String where = capability + " / " + heading;
        return switch (section) {
            case "MODIFIED" -> shipped.contains(heading) ? List.of() : List.of(where
                    + ": filed as MODIFIED, but no such requirement exists in the shipped spec — archive"
                    + " would add it under a section that says it replaces something");
            case "ADDED" -> shipped.contains(heading) ? List.of(where
                    + ": filed as ADDED, but the shipped spec already has that requirement — archive would"
                    + " duplicate it, or leave the shipped text stale. It is a MODIFIED block") : List.of();
            case "REMOVED" -> body.contains("**Reason**") ? List.of() : List.of(where
                    + ": filed as REMOVED with no **Reason** line. A REMOVED block that reproduces the"
                    + " requirement body is a MODIFIED block in the wrong section, and archive would delete"
                    + " shipped behaviour");
            default -> List.of();
        };
    }

    private static Set<String> shippedRequirements(String capability) {
        final Path spec = SPECS.resolve(capability).resolve("spec.md");
        if (!Files.isRegularFile(spec)) {
            return Set.of();
        }
        final Set<String> headings = new LinkedHashSet<>();
        for (final String line : read(spec).split("\n", -1)) {
            if (line.startsWith(REQUIREMENT)) {
                headings.add(line.substring(REQUIREMENT.length()).strip());
            }
        }
        return headings;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }
}
