package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.SystemTableException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.table.TableAccessService.AccessReplacement;
import com.epam.aidial.analytics.service.table.TableAccessService.IneffectiveReason;
import com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant;
import com.epam.aidial.analytics.service.table.grant.TableCallerPermissions;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.ProviderRolePrivileges;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * only when non-empty.
 *
 * <p>The merge is the load-bearing case. A full replace would have turned every client written against the
 * old two-key body into one that silently revokes all four read keys on every call.
 */
class TableAccessServiceTest {

    private final TableDefinitionRepository definitions = mock(TableDefinitionRepository.class);
    private final ColumnMappingRepository columns = mock(ColumnMappingRepository.class);
    private final PipelineRepository pipelines = mock(PipelineRepository.class);
    private final PipelineInputDependents pipelineReads = mock(PipelineInputDependents.class);
    private final TableCallerPermissions callerPermissions = mock(TableCallerPermissions.class);
    private final ProviderRolePrivileges providerRolePrivileges = mock(ProviderRolePrivileges.class);
    /** The real invariant, not a mock: the widening rule is part of what this service promises. */
    private final SensitiveGrantInvariant sensitiveGrants =
            new SensitiveGrantInvariant(definitions, columns, pipelines, pipelineReads, providerRolePrivileges);
    private final TableAccessService service =
            new TableAccessService(definitions, callerPermissions, providerRolePrivileges, sensitiveGrants);

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

    @Test
    @DisplayName("a read grant on an enrichment whose source the role cannot read is reported as inert")
    void enrichmentReadGrantIsReportedInert() {
        final TableDefinitionEntity enrichment = table("usage_insights");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        existing(enrichment);
        when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(table("dial_usage_log")));

        final AccessReplacement result =
                service.replaceAccess("usage_insights", Map.of(Privilege.TABLE_READ, List.of("analysts")));

        assertThat(result.ineffectiveGrants())
                .singleElement()
                .satisfies(grant -> assertThat(grant.reason()).isEqualTo(IneffectiveReason.ENRICHMENT_READ_INERT));
    }

    @Test
    @DisplayName("a sensitive grant wider than the read source's is refused")
    void wideningSensitiveGrantIsRefused() {
        final TableDefinitionEntity enrichment = table("usage_insights");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        when(definitions.findById("usage_insights")).thenReturn(Optional.of(enrichment));
        when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(table("dial_usage_log")));
        final ColumnMappingEntity sensitive = new ColumnMappingEntity();
        sensitive.setTableName("usage_insights");
        sensitive.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal("usage_insights")).thenReturn(List.of(sensitive));

        assertThatThrownBy(() -> service.replaceAccess(
                "usage_insights", Map.of(Privilege.TABLE_SENSITIVE, List.of("analysts"))))
                .isInstanceOf(com.epam.aidial.analytics.exception.SensitiveGrantWidensException.class)
                .hasMessageContaining("dial_usage_log");
        verify(definitions, never()).save(any());
    }

    @Test
    @DisplayName("a source clearance without a source read does not cover a derived sensitive grant")
    void sourceClearanceWithoutReadDoesNotCover() {
        // The clearance alone reads nothing, so a role cleared on the source but not granted its read never
        // could see the values the derived column carries — granting it them on the enrichment would.
        final TableDefinitionEntity source = table("dial_usage_log");
        source.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        final TableDefinitionEntity enrichment = table("usage_insights");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        existing(enrichment);
        when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(source));
        final ColumnMappingEntity sensitive = new ColumnMappingEntity();
        sensitive.setTableName("usage_insights");
        sensitive.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal("usage_insights")).thenReturn(List.of(sensitive));

        assertThatThrownBy(() -> service.replaceAccess(
                "usage_insights", Map.of(Privilege.TABLE_SENSITIVE, List.of("analysts"))))
                .isInstanceOf(com.epam.aidial.analytics.exception.SensitiveGrantWidensException.class)
                .hasMessageContaining("both 'read' and 'sensitive'");

        source.getAccess().setRolesFor(Privilege.TABLE_READ, List.of("analysts"));
        final AccessReplacement accepted = service.replaceAccess(
                "usage_insights", Map.of(Privilege.TABLE_SENSITIVE, List.of("analysts")));
        assertThat(accepted.access().getSensitive()).containsExactly("analysts");
    }

    @Test
    @DisplayName("revoking the source read is refused while a derived table still grants sensitive")
    void revokingTheSourceReadIsRefused() {
        final TableDefinitionEntity source = table("dial_usage_log");
        source.getAccess().setRolesFor(Privilege.TABLE_READ, List.of("analysts"));
        source.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        final TableDefinitionEntity enrichment = table("usage_pii");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        enrichment.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(source));
        when(definitions.findBySourceTable("dial_usage_log")).thenReturn(List.of(enrichment));
        final ColumnMappingEntity sensitive = new ColumnMappingEntity();
        sensitive.setTableName("usage_pii");
        sensitive.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal("usage_pii")).thenReturn(List.of(sensitive));

        assertThatThrownBy(() -> service.replaceAccess(
                "dial_usage_log", Map.of(Privilege.TABLE_READ, List.of())))
                .isInstanceOf(com.epam.aidial.analytics.exception.SensitiveGrantWidensException.class)
                .hasMessageContaining("usage_pii");
        verify(definitions, never()).save(any());
    }

    @Test
    @DisplayName("a globally cleared role may not read a derived sensitive column its source does not let it read")
    void globallyClearedRoleIsBoundedThroughItsReadGrant() {
        // The clearance can be held globally without any read — which the old rung could not express — and
        // then a read grant on the derived table alone reaches its sensitive values, while the role sits on
        // no sensitive list for the other half of the rule to see.
        final TableDefinitionEntity source = table("dial_usage_log");
        final TableDefinitionEntity enrichment = table("usage_insights");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        existing(enrichment);
        when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(source));
        final ColumnMappingEntity sensitive = new ColumnMappingEntity();
        sensitive.setTableName("usage_insights");
        sensitive.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal("usage_insights")).thenReturn(List.of(sensitive));
        when(providerRolePrivileges.holdsGlobally("pii-bots", Privilege.TABLE_SENSITIVE)).thenReturn(true);

        assertThatThrownBy(() -> service.replaceAccess(
                "usage_insights", Map.of(Privilege.TABLE_READ, List.of("pii-bots", "analysts"))))
                .isInstanceOf(com.epam.aidial.analytics.exception.SensitiveGrantWidensException.class)
                .hasMessageContaining("pii-bots")
                .hasMessageContaining("globally");

        source.getAccess().setRolesFor(Privilege.TABLE_READ, List.of("pii-bots"));
        final AccessReplacement accepted = service.replaceAccess(
                "usage_insights", Map.of(Privilege.TABLE_READ, List.of("pii-bots", "analysts")));
        assertThat(accepted.access().getRead())
                .as("an uncleared role's read grant is unconstrained, as before")
                .containsExactly("pii-bots", "analysts");
    }

    @Test
    @DisplayName("a clearance for a role with no verb on the table is reported as changing nothing")
    void clearanceWithoutVerbIsReported() {
        final TableDefinitionEntity def = table("finance_costs");
        def.getAccess().setRolesFor(Privilege.TABLE_MODIFY, List.of("finance-owners"));
        existing(def);

        final AccessReplacement result = service.replaceAccess("finance_costs",
                Map.of(Privilege.TABLE_SENSITIVE, List.of("pii-analysts", "finance-owners")));

        assertThat(result.ineffectiveGrants())
                .as("finance-owners holds modify, so its clearance extends a verb; pii-analysts holds none")
                .singleElement()
                .satisfies(grant -> {
                    assertThat(grant.role()).isEqualTo("pii-analysts");
                    assertThat(grant.reason()).isEqualTo(TableAccessService.IneffectiveReason.SENSITIVE_WITHOUT_VERB);
                });
    }

    @Test
    @DisplayName("the clearance is storable on a system table")
    void systemTableAcceptsTheClearance() {
        final TableDefinitionEntity def = table("sys");
        def.setSystem(true);
        existing(def);

        final AccessReplacement result = service.replaceAccess("sys",
                Map.of(Privilege.TABLE_SENSITIVE, List.of("pii-readers")));

        assertThat(result.access().getSensitive()).containsExactly("pii-readers");
    }

    @Test
    @DisplayName("narrowing the source is refused while an enrichment of it still grants sensitive")
    void narrowingTheSourceIsRefused() {
        // The other direction of the same invariant, and the one a "did this request widen anything?" check
        // would miss entirely: the enrichment's list is untouched and becomes wider than its origin.
        final TableDefinitionEntity source = table("dial_usage_log");
        source.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        final TableDefinitionEntity enrichment = table("usage_pii");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        enrichment.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(source));
        when(definitions.findBySourceTable("dial_usage_log")).thenReturn(List.of(enrichment));
        final ColumnMappingEntity sensitive = new ColumnMappingEntity();
        sensitive.setTableName("usage_pii");
        sensitive.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal("usage_pii")).thenReturn(List.of(sensitive));

        assertThatThrownBy(() -> service.replaceAccess(
                "dial_usage_log", Map.of(Privilege.TABLE_SENSITIVE, List.of())))
                .isInstanceOf(com.epam.aidial.analytics.exception.SensitiveGrantWidensException.class)
                .hasMessageContaining("usage_pii");
        verify(definitions, never()).save(any());
    }

    @Test
    @DisplayName("an enrichment with no sensitive column places no constraint on its source")
    void enrichmentWithoutSensitiveColumnIsUnconstrained() {
        final TableDefinitionEntity source = table("dial_usage_log");
        source.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        final TableDefinitionEntity enrichment = table("usage_plain");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        enrichment.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        existing(source);
        when(definitions.findBySourceTable("dial_usage_log")).thenReturn(List.of(enrichment));
        when(columns.findByTableNameOrderByOrdinal("usage_plain")).thenReturn(List.of());

        final AccessReplacement result = service.replaceAccess(
                "dial_usage_log", Map.of(Privilege.TABLE_SENSITIVE, List.of()));

        assertThat(result.access().getSensitive()).isEmpty();
    }

    @Test
    @DisplayName("a globally-entitled role is not exempt from the subset rule")
    void globalEntitlementIsNotAnExemption() {
        // The exemption used to be granted on ProviderRolePrivileges, which is a union across every
        // configured provider and documents itself as advice. Exempting a role name because SOME provider
        // maps it globally would admit a widening grant for a caller arriving through a provider that does
        // not. Granting a globally-entitled role is reported as ineffective anyway, so the remedy is to
        // leave it out of the list rather than to have the rule guess.
        final TableDefinitionEntity enrichment = table("usage_pii");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        when(definitions.findById("usage_pii")).thenReturn(Optional.of(enrichment));
        when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(table("dial_usage_log")));
        final ColumnMappingEntity sensitive = new ColumnMappingEntity();
        sensitive.setTableName("usage_pii");
        sensitive.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal("usage_pii")).thenReturn(List.of(sensitive));
        when(providerRolePrivileges.holdsGlobally("analysts", Privilege.TABLE_SENSITIVE)).thenReturn(true);

        assertThatThrownBy(() -> service.replaceAccess(
                "usage_pii", Map.of(Privilege.TABLE_SENSITIVE, List.of("analysts"))))
                .isInstanceOf(com.epam.aidial.analytics.exception.SensitiveGrantWidensException.class);
    }

    @Test
    @DisplayName("a rollup target may not grant sensitive more widely than the table it aggregates")
    void rollupTargetIsConstrainedByItsInputTable()   {
        // The aggregate path has no catalog edge at all: a rollup target is a SOURCE with no source_table,
        // so a rule that walked parentage never looked at it. And a rollup is addressable on its own, so
        // this is a more direct disclosure than the enrichment case — the caller needs no grant whatsoever
        // on the table being aggregated.
        final TableDefinitionEntity rollup = table("usage_by_claims");
        when(definitions.findById("usage_by_claims")).thenReturn(Optional.of(rollup));
        when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(table("dial_usage_log")));
        when(pipelines.findEffectiveInputTableOfTarget("usage_by_claims"))
                .thenReturn(Optional.of("dial_usage_log"));
        final ColumnMappingEntity sensitive = new ColumnMappingEntity();
        sensitive.setTableName("usage_by_claims");
        sensitive.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal("usage_by_claims")).thenReturn(List.of(sensitive));

        assertThatThrownBy(() -> service.replaceAccess(
                "usage_by_claims", Map.of(Privilege.TABLE_SENSITIVE, List.of("analysts"))))
                .isInstanceOf(com.epam.aidial.analytics.exception.SensitiveGrantWidensException.class)
                .hasMessageContaining("dial_usage_log");
        verify(definitions, never()).save(any());
    }

    @Test
    @DisplayName("narrowing a table narrows what a pipeline reading it may have already copied")
    void narrowingAnInputTableWalksItsPipelineTargets() {
        // The pipeline edge in the other direction. findBySourceTable cannot find this pair: the target is
        // not a catalog child of the table being narrowed, it is the target of a pipeline declaring it as
        // its read source.
        final TableDefinitionEntity source = table("dial_usage_log");
        source.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        final TableDefinitionEntity target = table("usage_by_claims");
        target.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        existing(source);
        when(definitions.findById("usage_by_claims")).thenReturn(Optional.of(target));
        when(pipelines.findTargetsByInputTable("dial_usage_log")).thenReturn(List.of("usage_by_claims"));
        final ColumnMappingEntity sensitive = new ColumnMappingEntity();
        sensitive.setTableName("usage_by_claims");
        sensitive.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal("usage_by_claims")).thenReturn(List.of(sensitive));

        assertThatThrownBy(() -> service.replaceAccess(
                "dial_usage_log", Map.of(Privilege.TABLE_SENSITIVE, List.of())))
                .isInstanceOf(com.epam.aidial.analytics.exception.SensitiveGrantWidensException.class)
                .hasMessageContaining("usage_by_claims");
        verify(definitions, never()).save(any());
    }

    @Test
    @DisplayName("a target is checked against an enrichment it reads through the grain join")
    void grainJoinReadEdgeIsChecked() {
        // The fourth edge, and the one neither repository query can see: a declaration addresses an
        // enrichment column as "<enrichment>.<column>", so the table whose values are copied is neither the
        // target's parent nor the pipeline's input_table. Missing it left the same disclosure the
        // input_table edge closes, one join further out.
        final TableDefinitionEntity rollup = table("usage_daily");
        when(definitions.findById("usage_daily")).thenReturn(Optional.of(rollup));
        when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(table("dial_usage_log")));
        final TableDefinitionEntity enrichment = table("usage_client_identity");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        enrichment.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("pii_team"));
        when(definitions.findById("usage_client_identity")).thenReturn(Optional.of(enrichment));
        when(pipelines.findEffectiveInputTableOfTarget("usage_daily"))
                .thenReturn(Optional.of("dial_usage_log"));
        when(pipelineReads.enrichmentsReadByPipelineTargeting("usage_daily"))
                .thenReturn(List.of("usage_client_identity"));
        final ColumnMappingEntity sensitive = new ColumnMappingEntity();
        sensitive.setTableName("usage_daily");
        sensitive.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal("usage_daily")).thenReturn(List.of(sensitive));

        // dial_usage_log grants nothing, so the input edge alone would already refuse; name the role on it
        // to isolate the grain-join edge as the only thing that can reject.
        final TableDefinitionEntity input = table("dial_usage_log");
        input.getAccess().setRolesFor(Privilege.TABLE_READ, List.of("analysts"));
        input.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(input));

        assertThatThrownBy(() -> service.replaceAccess(
                "usage_daily", Map.of(Privilege.TABLE_SENSITIVE, List.of("analysts"))))
                .isInstanceOf(com.epam.aidial.analytics.exception.SensitiveGrantWidensException.class)
                .hasMessageContaining("usage_client_identity");
        verify(definitions, never()).save(any());
    }

    @Test
    @DisplayName("narrowing an enrichment walks the targets that read it through the grain join")
    void narrowingAnEnrichmentWalksItsGrainJoinReaders() {
        // The reverse direction, independently reachable: the readers of an enrichment are not its catalog
        // children and do not name it as their input_table, so both repository queries return nothing.
        final TableDefinitionEntity enrichment = table("usage_client_identity");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        enrichment.getAccess().setRolesFor(Privilege.TABLE_READ, List.of("analysts", "pii_team"));
        enrichment.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts", "pii_team"));
        existing(enrichment);
        final TableDefinitionEntity rollup = table("usage_daily");
        rollup.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        when(definitions.findById("usage_daily")).thenReturn(Optional.of(rollup));
        when(pipelineReads.targetsReadingEnrichment("usage_client_identity"))
                .thenReturn(List.of("usage_daily"));
        final ColumnMappingEntity sensitive = new ColumnMappingEntity();
        sensitive.setTableName("usage_daily");
        sensitive.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal("usage_daily")).thenReturn(List.of(sensitive));

        assertThatThrownBy(() -> service.replaceAccess("usage_client_identity",
                Map.of(Privilege.TABLE_SENSITIVE, List.of("pii_team"))))
                .isInstanceOf(com.epam.aidial.analytics.exception.SensitiveGrantWidensException.class)
                .hasMessageContaining("usage_daily");
        verify(definitions, never()).save(any());
    }

    @Test
    @DisplayName("an enrichment whose pipeline declares another source is checked against that source")
    void declaredReadSourceIsTheSourceChecked() {
        // EnrichSpecValidator.requireReadSource lets a declaration name ANY active source, so the table a
        // pipeline reads need not be the target's catalog parent. Checking only the parent compared the
        // grants against the wrong table, which is how a sensitive column of s2 could be copied into an
        // enrichment of s1 and served to s1's grantees.
        final TableDefinitionEntity enrichment = table("usage_pii");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("s1");
        final TableDefinitionEntity lenient = table("s1");
        lenient.getAccess().setRolesFor(Privilege.TABLE_READ, List.of("analysts"));
        lenient.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        when(definitions.findById("usage_pii")).thenReturn(Optional.of(enrichment));
        when(definitions.findById("s1")).thenReturn(Optional.of(lenient));
        when(definitions.findById("s2")).thenReturn(Optional.of(table("s2")));
        when(pipelines.findEffectiveInputTableOfTarget("usage_pii")).thenReturn(Optional.of("s2"));
        final ColumnMappingEntity sensitive = new ColumnMappingEntity();
        sensitive.setTableName("usage_pii");
        sensitive.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal("usage_pii")).thenReturn(List.of(sensitive));

        // s1 names `analysts` under read and sensitive, so the parent edge is satisfied; the declared read source s2 does not.
        assertThatThrownBy(() -> service.replaceAccess(
                "usage_pii", Map.of(Privilege.TABLE_SENSITIVE, List.of("analysts"))))
                .isInstanceOf(com.epam.aidial.analytics.exception.SensitiveGrantWidensException.class)
                .hasMessageContaining("s2");
        verify(definitions, never()).save(any());
    }

    @Test
    @DisplayName("an enrichment is never treated as a source, so no needless query is issued")
    void anEnrichmentIsNotQueriedForItsOwnEnrichments() {
        final TableDefinitionEntity enrichment = table("usage_insights");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("dial_usage_log");
        existing(enrichment);
        when(definitions.findById("dial_usage_log")).thenReturn(Optional.of(table("dial_usage_log")));

        service.replaceAccess("usage_insights", Map.of(Privilege.TABLE_WRITE, List.of("bot")));

        verify(definitions, never()).findBySourceTable(anyString());
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
