package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.web.security.CallerAuthorities;
import com.epam.aidial.analytics.web.security.Privilege;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Truth table for {@link TableAccessEvaluator}: a caller is authorized when it holds the privilege
 * globally, or when one of its raw provider roles appears in a grant list that grants or <b>implies</b> it.
 *
 * <p>There is deliberately no role-name case here any more. The evaluator used to short-circuit on
 * {@code FULL_ADMIN}, which made the whole object layer conditional on a name — and is the reason a read
 * grant underneath it would have delivered a fraction of its value. A fully-privileged caller now passes
 * for the ordinary reason: it holds the privilege.
 */
class TableAccessEvaluatorTest {

    private final TableDefinitionRepository definitions = mock(TableDefinitionRepository.class);
    private final TableAccessEvaluator evaluator =
            new TableAccessEvaluator(definitions, new GoverningAccess(definitions), new SecurityClaimsExtractor());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(Set<Privilege> privileges, String... providerRoles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "caller", "n/a", CallerAuthorities.build(privileges, List.of(providerRoles))));
    }

    /** A table whose grant lists are exactly {@code grants}. */
    private TableDefinitionEntity stubTable(String name, Map<Privilege, List<String>> grants) {
        final TableDefinitionEntity def = new TableDefinitionEntity();
        def.setName(name);
        def.setType(TableType.SOURCE);
        final TableAccess access = new TableAccess();
        grants.forEach(access::setRolesFor);
        def.setAccess(access);
        when(definitions.findById(name)).thenReturn(Optional.of(def));
        return def;
    }

    /** An enrichment {@code e} of {@code src} storing exactly {@code grants}, and {@code src} storing {@code sourceGrants}. */
    private void stubEnrichment(Map<Privilege, List<String>> grants, Map<Privilege, List<String>> sourceGrants) {
        stubTable("src", sourceGrants);
        final TableDefinitionEntity enrichment = stubTable("e", grants);
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("src");
    }

    @Test
    @DisplayName("a reader of the source reads an enrichment it holds nothing on")
    void sourceReaderReadsTheEnrichment() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "viewer");
        stubEnrichment(Map.of(), Map.of(Privilege.TABLE_READ, List.of("viewer")));
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_READ)).isTrue();
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_GET)).isTrue();
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_SENSITIVE)).isFalse();
        assertThat(evaluator.canWrite("e")).isFalse();
    }

    @Test
    @DisplayName("the source's clearance is the enrichment's")
    void sourceClearanceReachesTheEnrichment() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "pii");
        stubEnrichment(Map.of(), Map.of(Privilege.TABLE_READ, List.of("pii"), Privilege.TABLE_SENSITIVE, List.of("pii")));
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_SENSITIVE)).isTrue();
    }

    @Test
    @DisplayName("a modify holder on the source gets the enrichment, without reading it")
    void sourceModifyConfersGetOnTheEnrichment() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "editor");
        stubEnrichment(Map.of(), Map.of(Privilege.TABLE_MODIFY, List.of("editor")));
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_GET)).isTrue();
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_READ)).isFalse();
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_MODIFY))
                .as("modify on the source is not modify on the enrichment")
                .isFalse();
    }

    @Test
    @DisplayName("the enrichment's own modify confers get on it, not a read of its source")
    void enrichmentModifyConfersGetOnItOnly() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "maintainer");
        stubEnrichment(Map.of(Privilege.TABLE_MODIFY, List.of("maintainer")), Map.of());
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_MODIFY)).isTrue();
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_GET)).isTrue();
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_READ)).isFalse();
        assertThat(evaluator.isAllowed("src", Privilege.TABLE_GET)).isFalse();
    }

    @Test
    @DisplayName("an enrichment's write grant lists neither table")
    void enrichmentWriteListsNeitherTable() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "labeler");
        stubEnrichment(Map.of(Privilege.TABLE_WRITE, List.of("labeler")), Map.of());
        assertThat(evaluator.canWrite("e")).isTrue();
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_LIST)).isFalse();
        assertThat(evaluator.isAllowed("src", Privilege.TABLE_LIST)).isFalse();
    }

    @Test
    @DisplayName("a read-side list left on an enrichment confers nothing")
    void leftoverReadSideListConfersNothing() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "r");
        stubEnrichment(Map.of(Privilege.TABLE_GET, List.of("r"), Privilege.TABLE_READ, List.of("r"),
                Privilege.TABLE_SENSITIVE, List.of("r")), Map.of());
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_GET)).isFalse();
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_READ)).isFalse();
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_SENSITIVE)).isFalse();
    }

    @Test
    @DisplayName("a '*' stored in the source's modify confers no get on its enrichment")
    void wildcardInSourceModifyConfersNoGet() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "anyone-else");
        stubEnrichment(Map.of(), Map.of(Privilege.TABLE_MODIFY, List.of(SecurityClaimsExtractor.ANYONE)));
        assertThat(evaluator.isAllowed("src", Privilege.TABLE_GET)).isFalse();
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_GET)).isFalse();
    }

    @Test
    @DisplayName("an enrichment whose source is missing grants no read side")
    void missingSourceFailsClosed() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "viewer", "labeler");
        stubEnrichment(Map.of(Privilege.TABLE_READ, List.of("viewer"), Privilege.TABLE_WRITE, List.of("labeler")), Map.of());
        when(definitions.findById("src")).thenReturn(Optional.empty());
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_READ)).isFalse();
        assertThat(evaluator.isAllowed("e", Privilege.TABLE_GET)).isFalse();
        assertThat(evaluator.canWrite("e")).isTrue();
    }

    @Test
    @DisplayName("a global privilege holder is authorized with no list entry")
    void globalHolderIsAuthorizedWithoutAnyGrant() {
        authenticateWith(EnumSet.of(Privilege.TABLE_WRITE));
        stubTable("t", Map.of());
        assertThat(evaluator.canWrite("t")).isTrue();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_MODIFY)).isFalse();
    }

    @Test
    @DisplayName("a write grant allows writing and nothing else")
    void writeGrantAllowsWriteOnly() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "writer");
        stubTable("t", Map.of(Privilege.TABLE_WRITE, List.of("writer")));
        assertThat(evaluator.canWrite("t")).isTrue();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_MODIFY)).isFalse();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_READ)).isFalse();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_LIST))
                .as("WRITE implies nothing, so an ingest-only identity stays invisible in the catalog")
                .isFalse();
    }

    @Test
    @DisplayName("a modify grant allows modifying and reading the schema, but not the rows")
    void modifyGrantConfersSchemaVisibility() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "editor");
        stubTable("t", Map.of(Privilege.TABLE_MODIFY, List.of("editor")));
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_MODIFY)).isTrue();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_GET)).isTrue();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_LIST)).isTrue();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_READ)).isFalse();
        assertThat(evaluator.canWrite("t")).isFalse();
    }

    @Test
    @DisplayName("a read grant is not expanded in storage but confers the rungs below it")
    void readGrantConfersGetAndList() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "viewer");
        stubTable("t", Map.of(Privilege.TABLE_READ, List.of("viewer")));
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_READ)).isTrue();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_GET)).isTrue();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_LIST)).isTrue();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_SENSITIVE)).isFalse();
    }

    @Test
    @DisplayName("the clearance grants nothing but itself")
    void clearanceAloneConfersNoVerb() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "pii-readers");
        stubTable("t", Map.of(Privilege.TABLE_SENSITIVE, List.of("pii-readers")));
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_SENSITIVE)).isTrue();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_READ)).isFalse();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_GET)).isFalse();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_LIST)).isFalse();
    }

    @Test
    @DisplayName("no matching role is denied")
    void noMatchingRoleIsDenied() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "other");
        stubTable("t", Map.of(
                Privilege.TABLE_WRITE, List.of("writer"),
                Privilege.TABLE_MODIFY, List.of("editor")));
        assertThat(evaluator.canWrite("t")).isFalse();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_MODIFY)).isFalse();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_READ)).isFalse();
    }

    @Test
    @DisplayName("entirely empty grant lists deny every caller without a global privilege")
    void emptyAccessIsDeniedForReadAsWellAsWrite() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "writer");
        stubTable("t", Map.of());
        assertThat(evaluator.canWrite("t")).isFalse();
        assertThat(evaluator.isAllowed("t", Privilege.TABLE_READ))
                .as("an empty list grants nothing, uniformly for every key")
                .isFalse();
    }

    @Test
    @DisplayName("an absent table is denied")
    void absentTableIsDenied() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "writer");
        when(definitions.findById("missing")).thenReturn(Optional.empty());
        assertThat(evaluator.canWrite("missing")).isFalse();
    }

    @Test
    @DisplayName("a global-only privilege cannot be checked per table")
    void globalOnlyPrivilegeIsRejected() {
        assertThatThrownBy(() -> evaluator.isAllowed("t", Privilege.TABLE_CREATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TABLE_CREATE");
    }
}
