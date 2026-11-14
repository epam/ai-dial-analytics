package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.EnrichmentFollowsSourceException;
import com.epam.aidial.analytics.exception.SystemTableException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.table.TableAccessService.AccessReplacement;
import com.epam.aidial.analytics.service.table.TableAccessService.IneffectiveReason;
import com.epam.aidial.analytics.service.table.grant.TableCallerPermissions;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyCodec;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.ProviderRolePrivileges;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit behaviour of {@link TableAccessService}: the merge (absent keeps, {@code []} revokes),
 * normalization, blank-role rejection (422), unknown-table handling (404), and the system-table guard —
 * which is now <b>per key</b>: read-side keys are storable, and {@code write}/{@code modify} are refused
 * only when non-empty — and the enrichment refusal, which comes before both.
 *
 * <p>The merge is the load-bearing case. A full replace would have turned every client written against the
 * old two-key body into one that silently revokes all four read keys on every call.
 */
class TableAccessServiceTest {

    private final TableDefinitionRepository definitions = mock(TableDefinitionRepository.class);
    private final TableCallerPermissions callerPermissions = mock(TableCallerPermissions.class);
    private final ProviderRolePrivileges providerRolePrivileges = mock(ProviderRolePrivileges.class);
    private final TableAccessService service = new TableAccessService(definitions, callerPermissions, providerRolePrivileges,
            new RowPolicyCodec(JsonMapper.builder().build()));

    private static TableDefinitionEntity table(String name) {
        final TableDefinitionEntity def = new TableDefinitionEntity();
        def.setName(name);
        def.setType(TableType.SOURCE);
        return def;
    }

    private void existing(TableDefinitionEntity def) {
        when(definitions.findById(def.getName())).thenReturn(Optional.of(def));
        when(definitions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void replaceTrimsAndDeduplicatesRoles() {
        existing(table("t"));

        final AccessReplacement result = service.replaceAccess("t", Map.of(
                Privilege.TABLE_WRITE, List.of("writer", " writer ", "other"),
                Privilege.TABLE_MODIFY, List.of()));

        assertThat(result.access().getWrite()).containsExactly("writer", "other");
        assertThat(result.access().getModify()).isEmpty();
    }

    @Test
    @DisplayName("an absent key keeps the stored list")
    void absentKeyKeepsStoredList() {
        final TableDefinitionEntity def = table("t");
        def.getAccess().setRolesFor(Privilege.TABLE_READ, List.of("analytics-viewers"));
        existing(def);

        final AccessReplacement result =
                service.replaceAccess("t", Map.of(Privilege.TABLE_WRITE, List.of("ingest-bot")));

        assertThat(result.access().getWrite()).containsExactly("ingest-bot");
        assertThat(result.access().getRead())
                .as("a two-key client must not silently revoke read grants it never mentioned")
                .containsExactly("analytics-viewers");
    }

    @Test
    @DisplayName("an explicit empty array revokes")
    void explicitEmptyArrayRevokes() {
        final TableDefinitionEntity def = table("t");
        def.getAccess().setRolesFor(Privilege.TABLE_READ, List.of("analytics-viewers"));
        existing(def);

        final AccessReplacement result = service.replaceAccess("t", Map.of(Privilege.TABLE_READ, List.of()));

        assertThat(result.access().getRead()).isEmpty();
    }

    @Test
    void blankRoleIsRejected() {
        when(definitions.findById("t")).thenReturn(Optional.of(table("t")));
        assertThatThrownBy(() -> service.replaceAccess("t", Map.of(Privilege.TABLE_WRITE, List.of("  "))))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    @DisplayName("a '*' already stored in 'write' does not block an edit of another list")
    void storedWildcardInWriteDoesNotBlockOtherEdits() {
        final TableDefinitionEntity def = table("t");
        def.getAccess().setRolesFor(Privilege.TABLE_WRITE, List.of("*"));
        existing(def);

        assertThat(service.replaceAccess("t", Map.of(Privilege.TABLE_READ, List.of("viewer"))).access().getRead())
                .containsExactly("viewer");
        assertThatThrownBy(() -> service.replaceAccess("t", Map.of(Privilege.TABLE_WRITE, List.of("*"))))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    @DisplayName("read-side keys are storable on a system table")
    void systemTableAcceptsReadGrants() {
        final TableDefinitionEntity def = table("sys");
        def.setSystem(true);
        existing(def);

        final AccessReplacement result = service.replaceAccess("sys", Map.of(
                Privilege.TABLE_READ, List.of("analytics-viewers"),
                Privilege.TABLE_WRITE, List.of(),
                Privilege.TABLE_MODIFY, List.of()));

        assertThat(result.access().getRead()).containsExactly("analytics-viewers");
        assertThat(result.access().getWrite()).isEmpty();
    }

    @Test
    @DisplayName("a non-empty write list on a system table is refused")
    void systemTableRefusesWriteGrant() {
        final TableDefinitionEntity def = table("sys");
        def.setSystem(true);
        when(definitions.findById("sys")).thenReturn(Optional.of(def));

        assertThatThrownBy(() -> service.replaceAccess("sys", Map.of(Privilege.TABLE_WRITE, List.of("writer"))))
                .isInstanceOf(SystemTableException.class)
                .hasMessageContaining("write");
        verify(definitions, never()).save(any());
    }

    @Test
    void systemTableAccessIsStillReadable() {
        final TableDefinitionEntity def = table("sys");
        def.setSystem(true);
        when(definitions.findById("sys")).thenReturn(Optional.of(def));

        assertThat(service.getAccess("sys").getWrite()).isEmpty();
    }

    @Test
    @DisplayName("a grant to a role that already holds the privilege globally is reported, not refused")
    void redundantGrantIsReported() {
        existing(table("t"));
        when(providerRolePrivileges.holdsGlobally("analysts", Privilege.TABLE_READ)).thenReturn(true);

        final AccessReplacement result =
                service.replaceAccess("t", Map.of(Privilege.TABLE_READ, List.of("analysts")));

        assertThat(result.access().getRead()).containsExactly("analysts");
        assertThat(result.ineffectiveGrants())
                .singleElement()
                .satisfies(grant -> {
                    assertThat(grant.role()).isEqualTo("analysts");
                    assertThat(grant.reason()).isEqualTo(IneffectiveReason.ALREADY_GLOBAL);
                });
    }

    /** An enrichment {@code usage_insights} of {@code dial_usage_log}, stored and found. */
    private TableDefinitionEntity enrichment() {
        final TableDefinitionEntity enrichment = table("usage_insights");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        existing(enrichment);
        return enrichment;
    }

    @Test
    @DisplayName("a non-empty read-side list on an enrichment is refused, naming its source")
    void readSideListOnAnEnrichmentIsRefused() {
        enrichment();
        for (final Privilege privilege : List.of(Privilege.TABLE_GET, Privilege.TABLE_READ, Privilege.TABLE_SENSITIVE)) {
            assertThatThrownBy(() -> service.replaceAccess("usage_insights", Map.of(privilege, List.of("analysts"))))
                    .isInstanceOf(EnrichmentFollowsSourceException.class)
                    .hasMessageContaining("dial_usage_log");
        }
        verify(definitions, never()).save(any());
    }

    @Test
    @DisplayName("the enrichment refusal precedes a blank role and the system-table refusal")
    void enrichmentRefusalComesFirst() {
        final TableDefinitionEntity payload = enrichment();
        payload.setSystem(true);

        assertThatThrownBy(() -> service.replaceAccess("usage_insights", Map.of(
                Privilege.TABLE_READ, List.of("analytics-viewers"), Privilege.TABLE_WRITE, List.of("ingest-bot"))))
                .isInstanceOf(EnrichmentFollowsSourceException.class);
        assertThatThrownBy(() -> service.replaceAccess("usage_insights", Map.of(
                Privilege.TABLE_READ, List.of("analytics-viewers"), Privilege.TABLE_MODIFY, List.of(" "))))
                .isInstanceOf(EnrichmentFollowsSourceException.class);
        assertThatThrownBy(() -> service.replaceAccess("usage_insights", Map.of(Privilege.TABLE_READ, List.of(" "))))
                .as("non-empty as sent, before trimming: 409, not 422")
                .isInstanceOf(EnrichmentFollowsSourceException.class);
        verify(definitions, never()).save(any());
    }

    @Test
    @DisplayName("a five-key body with empty read-side lists is accepted on an enrichment, and write is stored")
    void echoedBodyIsAcceptedOnAnEnrichment() {
        final TableDefinitionEntity enrichment = enrichment();
        enrichment.getAccess().setRolesFor(Privilege.TABLE_READ, List.of("left-by-an-earlier-build"));

        final AccessReplacement result = service.replaceAccess("usage_insights", Map.of(
                Privilege.TABLE_GET, List.of(), Privilege.TABLE_READ, List.of(), Privilege.TABLE_SENSITIVE, List.of(),
                Privilege.TABLE_WRITE, List.of("labeler"), Privilege.TABLE_MODIFY, List.of()));

        assertThat(result.access().getWrite()).containsExactly("labeler");
        assertThat(result.access().getRead()).as("a leftover list is cleared by sending it empty").isEmpty();
    }

    @Test
    @DisplayName("on any other table a blank role is still reported before the system-table refusal")
    void blankRoleBeforeSystemRefusalOnSource() {
        final TableDefinitionEntity def = table("dial_usage_log");
        def.setSystem(true);
        when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(def));

        assertThatThrownBy(() -> service.replaceAccess("dial_usage_log", Map.of(
                Privilege.TABLE_GET, List.of(" "), Privilege.TABLE_WRITE, List.of("ingest-bot"))))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    @DisplayName("a source clearance for a role holding modify on one of its enrichments is not reported")
    void sourceClearanceForAnEnrichmentMaintainerIsNotReported() {
        existing(table("dial_usage_log"));
        final TableDefinitionEntity enrichment = table("usage_insights");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        enrichment.getAccess().setRolesFor(Privilege.TABLE_MODIFY, List.of("maintainers"));
        when(definitions.findBySourceTable("dial_usage_log")).thenReturn(List.of(enrichment));

        final AccessReplacement result = service.replaceAccess("dial_usage_log",
                Map.of(Privilege.TABLE_SENSITIVE, List.of("maintainers", "nobody")));

        assertThat(result.ineffectiveGrants())
                .singleElement()
                .satisfies(grant -> {
                    assertThat(grant.role()).isEqualTo("nobody");
                    assertThat(grant.reason()).isEqualTo(IneffectiveReason.SENSITIVE_WITHOUT_VERB);
                });
    }

    @Test
    @DisplayName("the enrichment_read_inert reason no longer exists")
    void enrichmentReadInertIsGone() {
        assertThat(Arrays.stream(IneffectiveReason.values()).map(IneffectiveReason::code))
                .containsExactlyInAnyOrder("already_global", "sensitive_without_verb", "lifts_row_policies");
    }

    @Test
    @DisplayName("a derived table's sensitive list is not bounded by the tables it derives from")
    void derivedSensitiveGrantsAreNotBoundedByTheirInputs() {
        // A source whose enrichment a pipeline fills from another table, and a rollup target: each takes a
        // clearance for a role that nothing it derives from clears.
        existing(table("dial_usage_log"));
        when(definitions.findById("usage_by_claims")).thenReturn(Optional.of(table("usage_by_claims")));

        assertThat(service.replaceAccess("dial_usage_log", Map.of(Privilege.TABLE_SENSITIVE, List.of("analysts")))
                .access().getSensitive()).containsExactly("analysts");
        assertThat(service.replaceAccess("usage_by_claims", Map.of(Privilege.TABLE_SENSITIVE, List.of("analysts")))
                .access().getSensitive()).containsExactly("analysts");
    }

    @Test
    void unknownTableIsNotFound() {
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getAccess("missing")).isInstanceOf(TableNotFoundException.class);
        assertThatThrownBy(() -> service.replaceAccess("missing", Map.of()))
                .isInstanceOf(TableNotFoundException.class);
    }

    @Test
    @DisplayName("requestedLists leaves an absent key out so the merge can keep it")
    void requestedListsOmitsNulls() {
        final Map<Privilege, List<String>> requested = TableAccessService.requestedLists(
                null, List.of("viewer"), null, List.of(), null);
        assertThat(requested).containsOnlyKeys(Privilege.TABLE_READ, Privilege.TABLE_WRITE);
        assertThat(requested.get(Privilege.TABLE_READ)).containsExactly("viewer");
        assertThat(requested.get(Privilege.TABLE_WRITE)).isEmpty();
    }
}
