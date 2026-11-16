package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.AccessConflictException;
import com.epam.aidial.analytics.exception.EnrichmentFollowsSourceException;
import com.epam.aidial.analytics.exception.SystemTableException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.table.TableAccessService.AccessEntry;
import com.epam.aidial.analytics.service.table.TableAccessService.EntryReplacement;
import com.epam.aidial.analytics.service.table.TableAccessService.EntryRequest;
import com.epam.aidial.analytics.service.table.TableAccessService.IneffectiveGrant;
import com.epam.aidial.analytics.service.table.TableAccessService.IneffectiveReason;
import com.epam.aidial.analytics.service.table.TableAccessService.RowsRequest;
import com.epam.aidial.analytics.service.table.grant.ColumnPolicyCodec;
import com.epam.aidial.analytics.service.table.grant.RoleGenerationCodec;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicy;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyCodec;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyConditions;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyService;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.ProviderRolePrivileges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.StringNode;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit behaviour of {@link TableAccessService}: the role-keyed entry over the three stored documents, the {@code PUT}
 * check order, whole-entry replace, empty-entry removal, canonical ordering, the generation with its per-role conflict
 * detection, and the ineffective-grant report. Validation here is mode-independent, so it holds in {@code none} mode too.
 */
class TableAccessServiceTest {

    private final JsonMapper jsonMapper = new JsonMapperConfiguration().jsonMapper();
    private final TableDefinitionRepository definitions = mock(TableDefinitionRepository.class);
    private final ColumnMappingRepository columns = mock(ColumnMappingRepository.class);
    private final ProviderRolePrivileges providerRolePrivileges = mock(ProviderRolePrivileges.class);
    private final RowPolicyCodec rowPolicyCodec = new RowPolicyCodec(jsonMapper);
    private final ColumnPolicyCodec columnPolicyCodec = new ColumnPolicyCodec(jsonMapper);
    private final RowPolicyService rowPolicies = mock(RowPolicyService.class);
    private final RowPolicyConditions conditions = mock(RowPolicyConditions.class);
    private final TableAccessService service = new TableAccessService(definitions, columns, providerRolePrivileges,
            rowPolicyCodec, columnPolicyCodec, rowPolicies, conditions, new RoleGenerationCodec(jsonMapper));

    private TableDefinitionEntity table;

    @BeforeEach
    void setUp() {
        table = table("t", TableType.SOURCE);
        table.setAccessGeneration(4);
        stored(table);
        when(columns.findByTableNameOrderByOrdinal("t")).thenReturn(List.of(column("t", "a"), column("t", "b"), column("t", "c")));
        when(rowPolicies.validate(anyString(), anyString(), any())).thenAnswer(invocation -> invocation.getArgument(2));
    }

    @Test
    void putStoresTheEntryInTheThreeDocumentsAndBumpsTheGeneration() {
        final EntryReplacement result = service.put("t", "X", request(4, List.of("read"), filterRows(), List.of("b", "a")));

        assertThat(result.generation()).isEqualTo(5);
        assertThat(result.role()).isEqualTo("X");
        assertThat(result.entry().privileges()).containsExactly(Privilege.TABLE_READ);
        assertThat(result.entry().columns()).containsExactly("a", "b");
        assertThat(table.getAccess().getRead()).containsExactly("X");
        assertThat(rowPolicyCodec.read(table.getRowPolicies())).containsKey("X");
        assertThat(columnPolicyCodec.read(table.getColumnPolicies())).containsEntry("X", List.of("a", "b"));
        assertThat(table.getAccessGeneration()).isEqualTo(5);
    }

    @Test
    void putReplacesTheWholeEntryAndLeavesOtherRolesAlone() {
        service.put("t", "X", request(4, List.of("read", "write"), filterRows(), List.of("a", "b")));
        service.put("t", "Y", request(5, List.of("get"), null, null));

        final EntryReplacement result = service.put("t", "X", request(6, List.of("read"), null, null));

        assertThat(result.entry()).isEqualTo(new AccessEntry(List.of(Privilege.TABLE_READ), null, null));
        assertThat(service.list("t").roles()).containsOnlyKeys("X", "Y");
        assertThat(service.list("t").roles().get("Y").privileges()).containsExactly(Privilege.TABLE_GET);
        assertThat(rowPolicyCodec.read(table.getRowPolicies())).doesNotContainKey("X");
        assertThat(columnPolicyCodec.read(table.getColumnPolicies())).doesNotContainKey("X");
    }

    @Test
    void privilegesAreDeduplicatedInCanonicalOrderAndNullMeansNone() {
        assertThat(service.put("t", "X", request(4, List.of("modify", "get", "modify"), null, null)).entry().privileges())
                .containsExactly(Privilege.TABLE_GET, Privilege.TABLE_MODIFY);
        final EntryReplacement nullPrivileges = service.put("t", "Z", request(5, null, null, List.of("a")));
        assertThat(nullPrivileges.entry().privileges()).isEmpty();
        assertThat(nullPrivileges.entry().columns()).containsExactly("a");
    }

    @Test
    void anEmptyEntryRemovesTheRoleAndStillBumps() {
        service.put("t", "X", request(4, List.of("write"), null, null));

        final EntryReplacement removed = service.put("t", "X", request(5, List.of(), null, null));

        assertThat(removed.entry().isEmpty()).isTrue();
        assertThat(removed.generation()).isEqualTo(6);
        assertThatThrownBy(() -> service.get("t", "X")).isInstanceOf(TableNotFoundException.class);
        assertThat(service.list("t").roles()).isEmpty();
    }

    @Test
    void emptyPutForRoleWithNoEntryAnswersWithoutBumpingOrTouchingDocument() {
        final String accessBefore = jsonMapper.writeValueAsString(table.getAccess());
        final TableAccess accessObject = table.getAccess();

        final EntryReplacement result = service.put("t", "Z", request(4, List.of(), null, null));

        assertThat(result.generation()).isEqualTo(4);
        assertThat(table.getAccessGeneration()).isEqualTo(4);
        assertThat(table.getAccess()).isSameAs(accessObject);
        assertThat(jsonMapper.writeValueAsString(table.getAccess())).isEqualTo(accessBefore);
        assertThat(table.getRowPolicies()).isEqualTo("{}");
        assertThat(table.getColumnPolicies()).isEqualTo("{}");
    }

    @Test
    void anUnchangedPutWritesNothingEvenOverReserialisedText() {
        service.put("t", "X", request(4, List.of("read"), filterRows(), List.of("a")));
        // Postgres re-serialises JSONB: the stored text read back differs from the codec's output.
        table.setRowPolicies(table.getRowPolicies().replace(":", ": ").replace(",", ", "));
        table.setColumnPolicies("{\"X\": [\"a\"]}");
        final String rows = table.getRowPolicies();
        final String cols = table.getColumnPolicies();
        final TableAccess access = table.getAccess();

        final EntryReplacement result = service.put("t", "X", request(5, List.of("read"), filterRows(), List.of("a")));

        assertThat(result.generation()).isEqualTo(5);
        assertThat(table.getAccessGeneration()).isEqualTo(5);
        assertThat(table.getRowPolicies()).isSameAs(rows);
        assertThat(table.getColumnPolicies()).isSameAs(cols);
        assertThat(table.getAccess()).isSameAs(access);
    }

    @Test
    void theRoleIsTrimmedOnEveryMethod() {
        service.put("t", " X ", request(4, List.of("read"), null, null));

        assertThat(service.get("t", "  X").role()).isEqualTo("X");
        service.delete("t", "X  ", null);
        assertThat(service.list("t").roles()).isEmpty();
        assertThat(table.getAccessGeneration()).isEqualTo(6);
    }

    @Test
    void deleteOfRoleWithNoEntryIsNotFoundAndChangesNothing() {
        assertThatThrownBy(() -> service.delete("t", "nobody", null)).isInstanceOf(TableNotFoundException.class);
        assertThatThrownBy(() -> service.delete("t", "nobody", "4")).isInstanceOf(TableNotFoundException.class);
        // No entry is 404 before the generation is compared, even with one above the table's.
        assertThatThrownBy(() -> service.delete("t", "nobody", "9")).isInstanceOf(TableNotFoundException.class);
        assertThat(table.getAccessGeneration()).isEqualTo(4);
    }

    @Test
    void anUnknownTableIsNotFoundOnEveryMethodBeforeTheBody() {
        when(definitions.findById("nope")).thenReturn(Optional.empty());
        when(definitions.findForUpdate("nope")).thenReturn(Optional.empty());
        final EntryRequest invalid = new EntryRequest(null, List.of("read"), new RowsRequest(filter(), "a = 1"), null);

        assertThatThrownBy(() -> service.list("nope")).isInstanceOf(TableNotFoundException.class);
        assertThatThrownBy(() -> service.get("nope", "X")).isInstanceOf(TableNotFoundException.class);
        assertThatThrownBy(() -> service.delete("nope", "X", "x")).isInstanceOf(TableNotFoundException.class);
        assertThatThrownBy(() -> service.put("nope", "X", invalid)).isInstanceOf(TableNotFoundException.class);
    }

    @Test
    void theEnrichmentRefusalPrecedesTheGenerationAndTheBody() {
        final TableDefinitionEntity enrichment = table("e", TableType.ENRICHMENT);
        enrichment.setSourceTable("t");
        stored(enrichment);

        assertThatThrownBy(() -> service.put("e", "X", new EntryRequest(null, List.of("read"), null, null)))
                .isInstanceOf(EnrichmentFollowsSourceException.class).hasMessageContaining("'t'");
        assertThatThrownBy(() -> service.put("e", "X", request(9, List.of(), new RowsRequest(filter(), "a = 1"), null)))
                .isInstanceOf(EnrichmentFollowsSourceException.class);
        assertThatThrownBy(() -> service.put("e", "READ_ONLY_ANALYTIC", request(0, List.of(), new RowsRequest(null, null), null)))
                .isInstanceOf(EnrichmentFollowsSourceException.class);
        assertThatThrownBy(() -> service.put("e", "X", request(0, List.of(), null, List.of())))
                .isInstanceOf(EnrichmentFollowsSourceException.class);
        assertThat(service.put("e", "labeler", request(0, List.of("write"), null, null)).entry().privileges())
                .containsExactly(Privilege.TABLE_WRITE);
    }

    @Test
    void anUnusableGenerationIs422BeforeStalenessAndTheBody() {
        for (final JsonNode generation : new JsonNode[] {null, JsonNodeFactory.instance.nullNode(), StringNode.valueOf("4"),
                DoubleNode.valueOf(4.5), DoubleNode.valueOf(4.0), JsonNodeFactory.instance.numberNode(new BigInteger("9223372036854775808"))}) {
            assertThatThrownBy(() -> service.put("t", "X", new EntryRequest(generation, List.of(), null, List.of())))
                    .isInstanceOf(TableValidationException.class).hasMessageContaining("generation");
        }
        assertThat(table.getAccessGeneration()).isEqualTo(4);
    }

    @Test
    void staleGenerationIs409WithoutTheCurrentValueAndBeforeInvalidRoleOrBody() {
        table.setAccessGeneration(42);
        table.setAccessRoleGenerations("{\"READ_ONLY_ANALYTIC\": 42}");

        assertThatThrownBy(() -> service.put("t", "READ_ONLY_ANALYTIC", request(41, List.of(), null, List.of())))
                .isInstanceOf(AccessConflictException.class)
                .hasMessageNotContaining("42")
                .hasMessageNotContaining("41");
        assertThat(table.getAccessGeneration()).isEqualTo(42);
        assertThat(service.list("t").roles()).isEmpty();
    }

    @Test
    void writesToDifferentRolesFromOneReadBothSucceed() {
        assertThat(service.put("t", "X", request(4, List.of("read"), null, null)).generation()).isEqualTo(5);
        assertThat(service.put("t", "Y", request(4, List.of("read"), null, null)).generation()).isEqualTo(6);
        service.delete("t", "X", "5");
        assertThat(table.getAccessGeneration()).isEqualTo(7);
    }

    @Test
    void writesToOneRoleFromOneReadConflict() {
        service.put("t", "X", request(4, List.of("read"), null, null));

        assertThatThrownBy(() -> service.put("t", "X", request(4, List.of("get"), null, null))).isInstanceOf(AccessConflictException.class);
        assertThatThrownBy(() -> service.delete("t", "X", "4")).isInstanceOf(AccessConflictException.class);
        assertThat(table.getAccessGeneration()).isEqualTo(5);
        assertThat(service.get("t", "X").entry().privileges()).containsExactly(Privilege.TABLE_READ);
    }

    @Test
    void retriedPutAfterSuccessAnswersTheEntryAndWritesNothing() {
        service.put("t", "X", request(4, List.of("read"), filterRows(), List.of("b", "a")));
        service.put("t", "Y", request(5, List.of("get"), null, null));

        final EntryReplacement retried = service.put("t", "X", request(4, List.of("read"), filterRows(), List.of("a", "b")));

        assertThat(retried.generation()).isEqualTo(6);
        assertThat(retried.entry().columns()).containsExactly("a", "b");
        assertThat(table.getAccessGeneration()).isEqualTo(6);
    }

    @Test
    void generationAboveTheTablesConflicts() {
        assertThatThrownBy(() -> service.put("t", "X", request(5, List.of("read"), null, null))).isInstanceOf(AccessConflictException.class);
        assertThatThrownBy(() -> service.put("t", "X", request(5, List.of(), null, List.of())))
                .as("before the body checks").isInstanceOf(AccessConflictException.class);
        service.put("t", "X", request(4, List.of("read"), null, null));
        assertThatThrownBy(() -> service.delete("t", "X", "6")).isInstanceOf(AccessConflictException.class);
        assertThat(table.getAccessGeneration()).isEqualTo(5);
    }

    @Test
    void deletedRoleKeepsItsMarkSoAnOlderPutConflicts() {
        service.put("t", "X", request(4, List.of("read"), null, null));
        service.delete("t", "X", "5");

        assertThat(table.getAccessGeneration()).isEqualTo(6);
        assertThatThrownBy(() -> service.put("t", "X", request(5, List.of("read"), null, null))).isInstanceOf(AccessConflictException.class);
        assertThat(service.put("t", "X", request(6, List.of("read"), null, null)).generation()).isEqualTo(7);
    }

    @Test
    void deleteGenerationMustBeAnInteger() {
        service.put("t", "X", request(4, List.of("read"), null, null));
        for (final String generation : List.of("", "5.0", "5.5", "five", "9223372036854775808")) {
            assertThatThrownBy(() -> service.delete("t", "X", generation)).isInstanceOf(TableValidationException.class)
                    .hasMessageContaining("generation");
        }
        assertThat(table.getAccessGeneration()).isEqualTo(5);
    }

    @Test
    void theRoleIsCheckedBeforeTheSystemRefusalWhichPrecedesTheOtherBodyChecks() {
        table.setSystem(true);

        assertThatThrownBy(() -> service.put("t", "READ_ONLY_ADMIN", request(4, List.of("write"), null, null)))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> service.put("t", " ", request(4, List.of("read"), null, null)))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> service.put("t", "*", request(4, List.of("write"), null, null)))
                .isInstanceOf(SystemTableException.class);
        assertThat(service.put("t", "viewers", request(4, List.of("read"), null, List.of("a"))).generation()).isEqualTo(5);
    }

    @Test
    void theRemainingBodyChecksAre422() {
        assertThatThrownBy(() -> service.put("t", "X", request(4, List.of("list"), null, null)))
                .isInstanceOf(TableValidationException.class).hasMessageContaining("list");
        assertThatThrownBy(() -> service.put("t", "X", request(4, List.of("read_sensitive"), null, null)))
                .isInstanceOf(TableValidationException.class).hasMessageContaining("read_sensitive");
        assertThatThrownBy(() -> service.put("t", "*", request(4, List.of("modify"), null, null)))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> service.put("t", "X", request(4, List.of(), new RowsRequest(null, null), null)))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> service.put("t", "X", request(4, List.of(), new RowsRequest(filter(), "a = 1"), null)))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> service.put("t", "X", request(4, List.of(), null, List.of())))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> service.put("t", "X", request(4, List.of(), null, List.of("A"))))
                .isInstanceOf(TableValidationException.class).hasMessageContaining("'A'");
        assertThatThrownBy(() -> service.put("t", "X", request(4, List.of(), null, List.of(""))))
                .isInstanceOf(TableValidationException.class);
        assertThat(table.getAccessGeneration()).isEqualTo(4);
    }

    @Test
    void whereIsTranslatedAndStoredAsItsFilter() {
        final FilterNode translated = filter();
        when(conditions.toFilter("t", "project = 'P-1'")).thenReturn(translated);

        final EntryReplacement result = service.put("t", "X", request(4, List.of("read"), new RowsRequest(null, "project = 'P-1'"), null));

        assertThat(result.entry().rows()).isEqualTo(new RowPolicy(translated));
        verify(rowPolicies).validate(eq("t"), eq("X"), eq(new RowPolicy(translated)));
    }

    @Test
    void columnPoliciesNameExposedColumnsAndQualifiedEnrichmentColumns() {
        final TableDefinitionEntity enrichment = table("deployment_ref", TableType.ENRICHMENT);
        when(definitions.findBySourceTable("t")).thenReturn(List.of(enrichment));
        when(columns.findByTableNameOrderByOrdinal("deployment_ref")).thenReturn(List.of(column("deployment_ref", "display_name")));

        assertThat(service.put("t", "X", request(4, List.of("read"), null, List.of("deployment_ref.display_name", "a"))).entry().columns())
                .containsExactly("a", "deployment_ref.display_name");
        assertThatThrownBy(() -> service.put("t", "X", request(5, List.of("read"), null, List.of("display_name"))))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> service.put("t", "X", request(5, List.of("read"), null, List.of("other.display_name"))))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void physicalNameIsNotColumnPolicyName() {
        final ColumnMappingEntity renamed = column("t", "ingest_time");
        renamed.setSourceName("ingested");
        when(columns.findByTableNameOrderByOrdinal("t")).thenReturn(List.of(renamed));

        assertThat(service.put("t", "X", request(4, List.of("read"), null, List.of("ingest_time"))).entry().columns())
                .containsExactly("ingest_time");
        assertThatThrownBy(() -> service.put("t", "X", request(5, List.of("read"), null, List.of("ingested"))))
                .isInstanceOf(TableValidationException.class).hasMessageContaining("'ingested'");
    }

    @Test
    void eachIneffectiveReasonIsReported() {
        when(providerRolePrivileges.holdsGlobally("global-reader", Privilege.TABLE_READ)).thenReturn(true);
        assertThat(reasons(service.put("t", "global-reader", request(4, List.of("read"), null, null))))
                .containsExactly(IneffectiveReason.ALREADY_GLOBAL);
        assertThat(reasons(service.put("t", "pii", request(5, List.of("sensitive"), null, null))))
                .containsExactly(IneffectiveReason.SENSITIVE_WITHOUT_VERB);

        service.put("t", "X", request(6, List.of("read"), null, List.of("a")));
        assertThat(reasons(service.put("t", "*", request(7, List.of("read"), null, null))))
                .containsExactly(IneffectiveReason.LIFTS_COLUMN_POLICIES);
        assertThat(reasons(service.put("t", "*", request(8, List.of("read"), null, List.of("a"))))).isEmpty();
        assertThat(reasons(service.put("t", "*", request(9, List.of("read"), filterRows(), null)))).isEmpty();

        service.put("t", "*", request(10, List.of(), null, null));
        service.put("t", "Y", request(11, List.of("read"), filterRows(), null));
        assertThat(reasons(service.put("t", "*", request(12, List.of("read"), null, List.of("a")))))
                .containsExactly(IneffectiveReason.LIFTS_ROW_POLICIES);
    }

    @Test
    void sourceClearanceUsedByEnrichmentMaintainerIsNotReported() {
        final TableDefinitionEntity enrichment = table("e", TableType.ENRICHMENT);
        enrichment.getAccess().setModify(List.of("maintainers"));
        when(definitions.findBySourceTable("t")).thenReturn(List.of(enrichment));

        assertThat(reasons(service.put("t", "maintainers", request(4, List.of("sensitive"), null, null)))).isEmpty();
    }

    @Test
    void storedEveryoneWriteIsDroppedByTheNextPutOfThatEntryAndLeftByOthers() {
        table.getAccess().setWrite(List.of("*"));

        service.put("t", "other", request(4, List.of("read"), null, null));
        assertThat(table.getAccess().getWrite()).containsExactly("*");
        service.put("t", "*", request(5, List.of("read"), null, null));
        assertThat(table.getAccess().getWrite()).isEmpty();
        assertThat(table.getAccess().getRead()).containsExactly("other", "*");
    }

    @Test
    void anInvalidPutWritesNothing() {
        assertThatThrownBy(() -> service.put("t", "X", request(4, List.of("read"), null, List.of("zzz"))))
                .isInstanceOf(TableValidationException.class);

        verify(definitions, never()).save(any());
        assertThat(table.getAccess().getRead()).isEmpty();
    }

    private static List<IneffectiveReason> reasons(EntryReplacement replacement) {
        return replacement.ineffectiveGrants().stream().map(IneffectiveGrant::reason).toList();
    }

    private static EntryRequest request(long generation, List<String> privileges, RowsRequest rows, List<String> columns) {
        return new EntryRequest(LongNode.valueOf(generation), privileges, rows, columns);
    }

    private static RowsRequest filterRows() {
        return new RowsRequest(filter(), null);
    }

    private static FilterNode filter() {
        return new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("a"), new ValueExpr(ValueType.STRING, "P-1")));
    }

    private void stored(TableDefinitionEntity definition) {
        when(definitions.findById(definition.getName())).thenReturn(Optional.of(definition));
        when(definitions.findForUpdate(definition.getName())).thenReturn(Optional.of(definition));
    }

    private static TableDefinitionEntity table(String name, TableType type) {
        final TableDefinitionEntity definition = new TableDefinitionEntity();
        definition.setName(name);
        definition.setType(type);
        return definition;
    }

    private static ColumnMappingEntity column(String table, String name) {
        final ColumnMappingEntity column = new ColumnMappingEntity();
        column.setTableName(table);
        column.setName(name);
        column.setSourceName(name);
        return column;
    }
}
