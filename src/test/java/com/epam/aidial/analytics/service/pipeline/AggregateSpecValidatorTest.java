package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.SensitiveColumnNotEntitledException;
import com.epam.aidial.analytics.exception.SensitiveGrantWidensException;
import com.epam.aidial.analytics.service.enrichment.support.EntityFieldResolver;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.GroupKey;
import com.epam.aidial.analytics.service.pipeline.model.Measure;
import com.epam.aidial.analytics.service.pipeline.model.TruncUnit;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumn;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.sql.ResultColumn;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.PipelineInputDependents;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionArgDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionArgKind;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionGroup;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionReturnType;
import com.epam.aidial.analytics.web.security.Privilege;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit coverage for the {@link AggregateSpecValidator} branches that the integration test does not reach.
 *
 * <p>Every rejection here guards against a declaration that would <em>run</em> and produce numbers rather
 * than fail — a duplicate measure silently dropping a binding, a target column nothing writes reading as a
 * real zero, a second time bucket partitioning the same axis twice. None of these would surface as an
 * error at execution, which is why they are rejected at registration and pinned here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AggregateSpecValidatorTest {

    private static final String INPUT = "dial_usage_log";
    private static final String TARGET = "usage_daily";

    @Mock
    private TableDefinitionRepository definitions;
    @Mock
    private ColumnMappingRepository columns;
    @Mock
    private PipelineRepository pipelines;
    @Mock
    private FilterSqlValidator filterValidator;
    @Mock
    private SensitiveProjectionGuard sensitiveGuard;
    @Mock
    private CatalogQueryService catalog;
    @Mock
    private EnrichmentFreshness freshness;

    /**
     * The real codec over the application's configured mapper: the validator decodes a stored declaration
     * when answering the catalog's dependency questions, and a stub would assert nothing about that.
     */
    private static final PipelineSpecCodec SPEC_CODEC =
            new PipelineSpecCodec(new JsonMapperConfiguration().jsonMapper());

    private final PipelineInputDependents pipelineReads = mock(PipelineInputDependents.class);
    private final AggregateSqlReader sqlReader = mock(AggregateSqlReader.class);

    private AggregateSpecValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AggregateSpecValidator(definitions, pipelines, new QueryFunctionCatalog(),
                filterValidator, sensitiveGuard, new EntityFieldResolver(columns, catalog), freshness, SPEC_CODEC,
                new com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant(
                        definitions, columns, pipelines, pipelineReads, org.mockito.Mockito.mock(com.epam.aidial.analytics.web.security.ProviderRolePrivileges.class)),
                sqlReader);

        given(definitions.findById(INPUT)).willReturn(Optional.of(input()));
        given(definitions.findById(TARGET)).willReturn(Optional.of(target(List.of("deployment", "day"))));
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                column(INPUT, "total_tokens", QueryFieldType.LONG)));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "call_count", QueryFieldType.LONG)));
        given(pipelines.findByTarget(anyString())).willReturn(Optional.empty());
        given(filterValidator.analyze(anyString(), any(), anyString(), anyBoolean()))
                .willReturn(new FilterSqlValidator.FilterAnalysis(null, Set.of()));
        SensitiveGuardStubs.givenEntitledCaller(sensitiveGuard);
    }

    private static TableDefinitionEntity input() {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(INPUT);
        entity.setType(TableType.SOURCE);
        entity.setStatus(TableStatus.ACTIVE);
        entity.setVersionColumn("_ingested_at");
        entity.setIdentityColumn("event_id");
        return entity;
    }

    private static TableDefinitionEntity target(List<String> orderingKey) {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(TARGET);
        entity.setType(TableType.SOURCE);
        entity.setStatus(TableStatus.ACTIVE);
        entity.setWriteMode(TableWriteMode.UPSERT_BY_KEY);
        entity.setOrderingKey(new ArrayList<>(orderingKey));
        return entity;
    }

    private static ColumnMappingEntity column(String table, String name, QueryFieldType type) {
        final ColumnMappingEntity col = new ColumnMappingEntity();
        col.setTableName(table);
        col.setSourceName(name);
        col.setName(name);
        col.setType(type);
        return col;
    }

    private static GroupKey column(String name) {
        final GroupKey key = new GroupKey();
        key.setColumn(name);
        return key;
    }

    private static GroupKey bucket(String col, TruncUnit unit, String as) {
        final GroupKey key = new GroupKey();
        final GroupKey.Trunc trunc = new GroupKey.Trunc();
        trunc.setColumn(col);
        trunc.setUnit(unit);
        key.setTrunc(trunc);
        key.setAs(as);
        return key;
    }

    private static Measure measure(String name, String fn, String col) {
        final Measure m = new Measure();
        m.setName(name);
        m.setFn(fn);
        m.setColumn(col);
        return m;
    }

    /** A measure with its own row-membership predicate — a fourth place a column reference can appear. */
    private static Measure measureWhere(String name, String fn, String col, String where) {
        final Measure m = measure(name, fn, col);
        m.setWhere(where);
        return m;
    }

    private static Measure distinct(Measure measure) {
        measure.setDistinct(true);
        return measure;
    }

    private static AggregateSpec spec(List<GroupKey> groupBy, List<Measure> measures) {
        final AggregateSpec s = new AggregateSpec();
        s.setGroupBy(groupBy);
        s.setMeasures(measures);
        return s;
    }

    /** The declaration the fixture's target accepts, used as the baseline every case perturbs. */
    private static AggregateSpec valid() {
        return spec(List.of(column("deployment"), bucket("request_time", TruncUnit.DAY, "day")),
                List.of(measure("call_count", "count", null)));
    }

    // ------------------------------------------- the catalog's dependency questions, over a stored row

    /**
     * A stored declaration whose input no longer resolves contributes no references rather than throwing.
     *
     * <p>The isolation half of the guard's design, and the part a future refactor is most likely to
     * break: these two methods are called over <b>every</b> stored declaration from inside a table delete
     * or a schema patch, so one drifted declaration that threw would fail an operation about an unrelated
     * table. The other half — not re-running the SQL front end — is why they cannot simply delegate to the
     * validating overload, which does throw.
     */
    @Test
    void driftedDeclarationContributesNoReferencesRatherThanFailingTheGuard() {
        given(definitions.findById("gone")).willReturn(Optional.empty());
        final PipelineDeclaration drifted = declarationOf("gone", "length(\"e.c\") > 0", valid());

        assertThat(validator.referencedEnrichments(drifted)).isEmpty();
    }

    /**
     * The other way this resolver is asked about a broken stored row, and why it swallows around the whole
     * resolution rather than only around the input lookup: an undecodable {@code spec} makes the codec
     * throw from inside {@code declaredFieldsOf}. The method is {@code @Transactional}, so an escaping
     * throw would mark a caller's transaction rollback-only and turn a pipeline read's documented
     * "degrade to no diagnostics" into an {@code UnexpectedRollbackException} — HTTP 500.
     */
    @Test
    void undecodableStoredSpecContributesNoReferencesRatherThanThrowing() {
        final PipelineDeclaration corrupt = new PipelineDeclaration("p", PipelineKind.AGGREGATE, INPUT,
                TARGET, null, null, null, null, "{not json");

        assertThat(validator.referencedEnrichments(corrupt)).isEmpty();
    }

    @Test
    void declarationOfAnotherKindContributesNoFields() {
        // The guard dispatches on kind, so this is the belt to that braces: asked about an enrich row, the
        // aggregate gate answers nothing rather than decoding the wrong spec type.
        final PipelineDeclaration enrich = new PipelineDeclaration("e", PipelineKind.ENRICH, INPUT, TARGET,
                null, null, null, null, "{}");

        assertThat(validator.declaredFieldsOf(enrich)).isEmpty();
    }

    @Test
    void everyPositionOneColumnCanAppearInIsScanned() {
        final AggregateSpec spec = spec(
                List.of(column("deployment"), bucket("request_time", TruncUnit.DAY, "day")),
                List.of(measure("total", "sum", "total_price"),
                        measureWhere("chain", "count", null, "length(\"m.w\") > 0")));

        // Four positions: a plain group key, a bucketed one, a measure column, and a measure predicate —
        // plus the pipeline filter. An extraction covering the grain but not the predicates would pass
        // every table-level test and still let a referenced column be dropped.
        assertThat(validator.declaredFieldsOf(declarationOf(INPUT, "length(\"f.c\") > 0", spec)))
                .contains("deployment", "request_time", "total_price", "m.w", "f.c");
    }

    @Test
    void sqlDeclarationFindsQuotedEnrichmentReferenceBehindAnAlias() {
        givenEnrichment();
        final AggregateSpec spec = storedSqlSpec("INSERT INTO " + TARGET + " (client_session_id, total_tokens) SELECT u.\""
                + SESSION_KEY + "\", sum(total_tokens) FROM " + INPUT + " u GROUP BY 1");

        assertThat(validator.declaredFieldsOf(declarationOf(INPUT, null, spec))).contains(SESSION_KEY, "total_tokens");
        assertThat(validator.referencedEnrichments(declarationOf(INPUT, null, spec))).containsExactly(ENRICHMENT);
        verifyNoInteractions(sqlReader);
    }

    @Test
    void sqlDeclarationTargetColumnListDoesNotCountAsRead() {
        final AggregateSpec spec = storedSqlSpec("""
                -- rollup
                INSERT INTO "%s" ("deployment", call_count)
                SELECT deployment, count(*) FROM %s GROUP BY deployment""".formatted(TARGET, INPUT));

        assertThat(validator.declaredFieldsOf(declarationOf(INPUT, null, spec)))
                .contains("deployment")
                .doesNotContain("call_count");
    }

    @Test
    void sqlDeclarationWithUnrecognisedHeadIsScannedWhole() {
        final AggregateSpec spec = storedSqlSpec("INSERT INTO " + TARGET + " SELECT deployment FROM " + INPUT);

        assertThat(validator.declaredFieldsOf(declarationOf(INPUT, null, spec))).contains("deployment", TARGET);
    }

    @Test
    void unparseableSqlDraftWithoutInputStillNamesItsEnrichments() {
        final TableDefinitionEntity enrichment = new TableDefinitionEntity();
        enrichment.setName(ENRICHMENT);
        enrichment.setType(TableType.ENRICHMENT);
        given(definitions.findById(ENRICHMENT)).willReturn(Optional.of(enrichment));
        final AggregateSpec spec = storedSqlSpec("INSERT INTO " + TARGET + " (x) SELECT \"" + SESSION_KEY + "\", count( FROM");

        assertThat(validator.referencedEnrichmentsOrThrow(declarationOf(null, null, spec))).containsExactly(ENRICHMENT);
        verifyNoInteractions(sqlReader);
    }

    private static AggregateSpec storedSqlSpec(String sql) {
        final AggregateSpec s = new AggregateSpec();
        s.setGroupBy(null);
        s.setMeasures(null);
        s.setSql(sql);
        return s;
    }

    private static PipelineDeclaration declarationOf(String input, String filter, AggregateSpec spec) {
        return new PipelineDeclaration("p", PipelineKind.AGGREGATE, input, TARGET, null, filter,
                null, null, SPEC_CODEC.write(spec));
    }

    private void validate(AggregateSpec spec) {
        validator.validate("p", INPUT, TARGET, null, spec, "aggregate");
    }

    // The determinism gate is NOT asserted here: this test stubs FilterSqlValidator, so a clock in a
    // declaration filter or a measure `where` would be refused by the real collaborator and pass the stub.
    // The rule itself lives in FilterSqlValidatorTest, and the aggregate path's end-to-end 422 is pinned by
    // AggregatePipelineIntegrationTest against the real validator chain.

    // --- entity resolution: an enrichment reached through the input's grain join ------------------

    private static final String ENRICHMENT = "usage_client_identity";
    private static final String SESSION_KEY = ENRICHMENT + ".client_session_id";

    /**
     * Registers an ACTIVE enrichment on the input exposing {@code client_session_id} (plain) and
     * {@code user_email} (sensitive), both <b>nullable</b> — which is what
     * {@code TableColumnRules.resolveNullable} makes an enrichment column by default, and the only shape a
     * declaration may derive a value from.
     *
     * <p>These were once declared non-nullable on the reasoning that "the grain join makes them nullable
     * regardless". It does not: ClickHouse's {@code join_use_nulls} defaults to 0, so an unmatched outer
     * join renders a non-nullable column as its type default rather than as NULL. That shape is now refused
     * outright — see {@code nonNullableEnrichmentColumnIsRefusedAsGroupKey} and its measure sibling.
     */
    private void givenEnrichment() {
        final TableDefinitionEntity enrichment = new TableDefinitionEntity();
        enrichment.setName(ENRICHMENT);
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setStatus(TableStatus.ACTIVE);
        enrichment.setSourceTable(INPUT);
        enrichment.setGrainKey("event_id");
        given(catalog.activeEnrichmentsOf(INPUT)).willReturn(List.of(enrichment));
        final ColumnMappingEntity email = column(ENRICHMENT, "user_email", QueryFieldType.STRING);
        email.setSensitive(true);
        email.setNullable(true);
        final ColumnMappingEntity session = column(ENRICHMENT, "client_session_id", QueryFieldType.STRING);
        session.setNullable(true);
        given(columns.findByTableNameOrderByOrdinal(ENRICHMENT)).willReturn(List.of(session, email));
    }

    /** As {@link #givenEnrichment}, but with {@code client_session_id} declared non-nullable. */
    private void givenNonNullableEnrichmentColumn() {
        givenEnrichment();
        final ColumnMappingEntity session = column(ENRICHMENT, "client_session_id", QueryFieldType.STRING);
        session.setNullable(false);
        given(columns.findByTableNameOrderByOrdinal(ENRICHMENT)).willReturn(List.of(session));
    }

    /** A target keyed on the enrichment-sourced session id, with one nullable measure column. */
    private void givenSessionTarget(boolean nullableMeasure) {
        given(definitions.findById(TARGET)).willReturn(Optional.of(target(List.of("client_session_id"))));
        final ColumnMappingEntity total = column(TARGET, "total_tokens", QueryFieldType.LONG);
        total.setNullable(nullableMeasure);
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "client_session_id", QueryFieldType.STRING), total));
    }

    private static GroupKey named(String column, String as) {
        final GroupKey key = column(column);
        key.setAs(as);
        return key;
    }

    @Test
    void groupKeyNamingAnEnrichmentColumnIsAccepted() {
        givenEnrichment();
        givenSessionTarget(true);
        final AggregateSpec spec = spec(List.of(named(SESSION_KEY, "client_session_id")),
                List.of(measure("total_tokens", "sum", "total_tokens")));

        assertThatCode(() -> validator.validate("p", INPUT, TARGET, "length(\"" + SESSION_KEY + "\") > 0",
                spec, "aggregate")).doesNotThrowAnyException();
    }

    /** How the referenced enrichment's owning rule classifies for freshness purposes. */
    private void givenClampability(EnrichmentFreshness.Clampability clampability) {
        given(freshness.classify(any(), eq(INPUT))).willReturn(Map.of(ENRICHMENT, clampability));
    }

    /** A declaration reading the enrichment's session id as a group key, with the filter it requires. */
    private void validateSessionRollup() {
        givenEnrichment();
        givenSessionTarget(true);
        final AggregateSpec spec = spec(List.of(named(SESSION_KEY, "client_session_id")),
                List.of(measure("total_tokens", "sum", "total_tokens")));
        validator.validate("p", INPUT, TARGET, "length(\"" + SESSION_KEY + "\") > 0", spec, "aggregate");
    }

    @Test
    void groupTriggerEnrichmentColumnIsRefusedNamingTheEnrichment() {
        // A group-derived value is recomputed whenever its group gains a member, so there is no position a
        // window could stop at: the rollup would be wrong both from reading provisional values and from
        // keeping superseded ones, permanently and in both directions.
        givenClampability(EnrichmentFreshness.Clampability.GROUP_TRIGGER);

        assertThatThrownBy(this::validateSessionRollup)
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining(ENRICHMENT)
                .hasMessageContaining("group trigger")
                .hasMessageContaining("no settled position");
    }

    @Test
    void enrichmentAdasDoesNotWriteIsRefusedNamingTheEnrichment() {
        givenClampability(EnrichmentFreshness.Clampability.NOT_CLASS1);

        assertThatThrownBy(this::validateSessionRollup)
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining(ENRICHMENT)
                // The message has to point somewhere: only an enrichment computed in the database has a
                // position ADAS observed, so that is the shape a declarer has to reach for.
                .hasMessageContaining("'sql'");
    }

    @Test
    void enrichmentWhosePipelineReadsAnotherTableIsAccepted() {
        // Not refused, deliberately: its values are settled rather than provisional, so it is admitted
        // without a clamp and reported in pipeline state. Refusing would remove the only means ADAS has of
        // relating two tables — a rollup at a shared grain plus a pass-through rule.
        givenClampability(EnrichmentFreshness.Clampability.FOREIGN_AXIS);

        assertThatCode(this::validateSessionRollup).doesNotThrowAnyException();
    }

    @Test
    void enrichmentWithNoPipelineIsAccepted() {
        // Its absences are final rather than late, so there is nothing for a clamp to wait for.
        givenClampability(EnrichmentFreshness.Clampability.NO_PIPELINE);

        assertThatCode(this::validateSessionRollup).doesNotThrowAnyException();
    }

    @Test
    void class1EnrichmentOnTheInputIsAccepted() {
        givenClampability(EnrichmentFreshness.Clampability.CLAMPABLE);

        assertThatCode(this::validateSessionRollup).doesNotThrowAnyException();
    }

    @Test
    void enrichmentGroupKeyWithNoFilterIsRejected() {
        // An enrichment column is nullable by declaration and nullable again by the outer join, and a
        // nullable group key with no membership predicate has nowhere to put the NULL: the group keys are
        // the target's ordering_key and ClickHouse refuses a nullable sorting key.
        givenEnrichment();
        givenSessionTarget(true);
        final AggregateSpec spec = spec(List.of(named(SESSION_KEY, "client_session_id")),
                List.of(measure("total_tokens", "sum", "total_tokens")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("reads nullable input column")
                .hasMessageContaining(SESSION_KEY)
                .hasMessageContaining("declares no filter");
    }

    @Test
    void measureOverEnrichmentColumnStillNeedsNullableTarget() {
        // The join is outer, so even a group with rows can measure nothing; the target must be able to say so.
        givenEnrichment();
        givenSessionTarget(false);
        final AggregateSpec spec = spec(List.of(named("deployment", "client_session_id")),
                List.of(measure("total_tokens", "max", SESSION_KEY)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("total_tokens")
                .hasMessageContaining(SESSION_KEY);
    }

    @Test
    void nonNullableEnrichmentColumnIsRefusedAsGroupKey() {
        // The rule the whole enrichment-nullability design rests on. join_use_nulls defaults to 0, so an
        // unmatched outer join renders a non-nullable column as '' rather than NULL — and '' is a group key
        // indistinguishable from measured data, which no filter spelled `IS NOT NULL` would exclude.
        givenNonNullableEnrichmentColumn();
        givenSessionTarget(true);
        final AggregateSpec spec = spec(List.of(named(SESSION_KEY, "client_session_id")),
                List.of(measure("total_tokens", "sum", "total_tokens")));

        assertThatThrownBy(() -> validator.validate(
                "p", INPUT, TARGET, "length(\"" + SESSION_KEY + "\") > 0", spec, "aggregate"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining(SESSION_KEY)
                .hasMessageContaining("declared non-nullable")
                .hasMessageContaining("join_use_nulls");
    }

    @Test
    void nonNullableEnrichmentColumnIsRefusedAsMeasureColumn() {
        givenNonNullableEnrichmentColumn();
        givenSessionTarget(true);
        final AggregateSpec spec = spec(List.of(named("deployment", "client_session_id")),
                List.of(measure("total_tokens", "max", SESSION_KEY)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("measure column")
                .hasMessageContaining(SESSION_KEY)
                .hasMessageContaining("declared non-nullable");
    }

    @Test
    void nonNullableEnrichmentColumnMayStillBeReadByFilter() {
        // The deliberate asymmetry: a predicate selects rows rather than producing a value, and
        // `"<enrichment>.<column>" != ''` is exactly how a declarer excludes the unreached rows of a column
        // that cannot be nullable. Refusing it would remove the one correct expression of the intent.
        givenNonNullableEnrichmentColumn();
        given(definitions.findById(TARGET)).willReturn(Optional.of(target(List.of("deployment"))));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "call_count", QueryFieldType.LONG)));
        final AggregateSpec spec = spec(List.of(column("deployment")),
                List.of(measure("call_count", "count", null)));

        assertThatCode(() -> validator.validate(
                "p", INPUT, TARGET, "\"" + SESSION_KEY + "\" != ''", spec, "aggregate"))
                .doesNotThrowAnyException();
    }

    @Test
    void enrichmentGroupKeyWithoutAnAliasIsRefusedNamingTheAlias() {
        // outputName() falls back to the column, and no target column name may carry the '.' a qualified
        // flat name does — so without `as` the declaration can never bind, and the downstream refusal would
        // blame the target the declarer wrote correctly.
        givenEnrichment();
        givenSessionTarget(true);
        final AggregateSpec spec = spec(List.of(column(SESSION_KEY)),
                List.of(measure("total_tokens", "sum", "total_tokens")));

        assertThatThrownBy(() -> validator.validate(
                "p", INPUT, TARGET, "length(\"" + SESSION_KEY + "\") > 0", spec, "aggregate"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining(SESSION_KEY)
                .hasMessageContaining("must declare 'as'")
                .hasMessageContaining("client_session_id");
    }

    @Test
    void unknownColumnOfKnownEnrichmentNamesTheEnrichment() {
        givenEnrichment();
        final AggregateSpec spec = spec(List.of(column(ENRICHMENT + ".nope"), valid().getGroupBy().get(1)),
                List.of(measure("call_count", "count", null)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining(ENRICHMENT)
                .hasMessageContaining("nope");
    }

    /** A target keyed on {@code deployment} whose one measure column is a plain, nullable string. */
    private void givenEmailTarget(boolean sensitiveTargetColumn) {
        given(definitions.findById(TARGET)).willReturn(Optional.of(target(List.of("deployment"))));
        final ColumnMappingEntity email = column(TARGET, "last_email", QueryFieldType.STRING);
        email.setNullable(true);
        email.setSensitive(sensitiveTargetColumn);
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING), email));
    }

    private static AggregateSpec emailSpec() {
        return spec(List.of(column("deployment")),
                List.of(measure("last_email", "max", ENRICHMENT + ".user_email")));
    }

    @Test
    void entitlementGateRunsUnderEachOwningTablesOwnName() {
        // SensitiveProjectionGuard resolves a projection against one table's catalog rows. Handed the
        // qualified name against the input, "usage_client_identity.user_email" matches no sensitive column
        // of dial_usage_log and the gate passes — so the split by owner is what closes the entitlement half.
        givenEnrichment();
        givenEmailTarget(true);

        validator.validate("p", INPUT, TARGET, null, emailSpec(), "aggregate");

        verify(sensitiveGuard).enforce(
                eq(ENRICHMENT), argThat(names -> names.contains("user_email")), eq("aggregate"), eq("enrichment"));
        verify(sensitiveGuard).enforce(
                eq(INPUT), argThat(names -> names.contains("deployment")), eq("aggregate"), eq("source"));
    }

    @Test
    void nonEntitledCallerReferencingSensitiveEnrichmentColumnIsRefused() {
        // The 403 half, which comes along for free once the gate is called per owning table. Nothing is
        // stored either: PipelineService runs this gate before it saves, on register and on patch alike.
        givenEnrichment();
        givenEmailTarget(true);
        willThrow(new SensitiveColumnNotEntitledException("nope"))
                .given(sensitiveGuard).enforce(eq(ENRICHMENT), any(), anyString(), anyString());

        assertThatThrownBy(() -> validator.validate("p", INPUT, TARGET, null, emailSpec(), "aggregate"))
                .isInstanceOf(SensitiveColumnNotEntitledException.class);
    }

    @Test
    void sensitiveEnrichmentColumnMayNotFeedPlainTargetColumn() {
        // The anti-laundering rule, across the grain join. SystemIdentity's FULL_ADMIN elevation is
        // justified in its own javadoc by this rule being total, so an enrichment column exempt from it
        // would turn the scheduler into the laundering path the elevation is documented as not being.
        givenEnrichment();
        givenEmailTarget(false);

        assertThatThrownBy(() -> validator.validate("p", INPUT, TARGET, null, emailSpec(), "aggregate"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("last_email")
                .hasMessageContaining(ENRICHMENT + ".user_email")
                .hasMessageContaining("sensitive");
    }

    @Test
    void sensitiveEnrichmentColumnIntoSensitiveTargetColumnIsAccepted() {
        givenEnrichment();
        givenEmailTarget(true);

        assertThatCode(() -> validator.validate("p", INPUT, TARGET, null, emailSpec(), "aggregate"))
                .doesNotThrowAnyException();
    }

    @Test
    void theTargetTypeCheckReadsTheEnrichmentColumnsOwnType() {
        // Resolution alone is not enough: every downstream check reads through the same view, so a group
        // key drawn from an enrichment must be type-checked against its own declared type, not answered
        // null. The target's key column is a timestamp here and the enrichment column a string.
        givenEnrichment();
        given(definitions.findById(TARGET)).willReturn(Optional.of(target(List.of("client_session_id"))));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "client_session_id", QueryFieldType.TIMESTAMP)));
        final AggregateSpec spec = spec(List.of(named(SESSION_KEY, "client_session_id")),
                List.of(measure("call_count", "count", null)));

        assertThatThrownBy(() -> validator.validate("p", INPUT, TARGET, "x", spec, "aggregate"))
                .isInstanceOf(PipelineValidationException.class)
                // The point of the assertion: the view answered the enrichment column's OWN declared type
                // rather than null, so the mismatch against the target's timestamp is the reported failure.
                .hasMessageContaining("declared 'string'")
                .hasMessageContaining("declared 'timestamp'");
    }

    @Test
    void theBaselineDeclarationIsAccepted() {
        assertThatCode(() -> validate(valid())).doesNotThrowAnyException();
    }

    // ------------------------------------------- the derivation half alone, as a draft save runs it

    @Test
    void bestEffortDerivationExpandsAnOmittedGroupByFromTheOrderingKey() {
        given(definitions.findById(TARGET)).willReturn(Optional.of(target(List.of("deployment"))));
        final AggregateSpec spec = spec(new ArrayList<>(), List.of());

        assertThat(validator.deriveGroupKeysBestEffort(INPUT, TARGET, spec)).isTrue();
        assertThat(spec.getGroupBy()).extracting(GroupKey::getColumn).containsExactly("deployment");
    }

    @Test
    void bestEffortDerivationKeepsAnAuthoredGroupByAndReportsIt() {
        final AggregateSpec spec = valid();

        assertThat(validator.deriveGroupKeysBestEffort(null, null, spec)).isTrue();
        assertThat(spec.getGroupBy()).hasSize(2);
    }

    @Test
    void bestEffortDerivationReportsFalseAndLeavesTheSpecWhereItCannotDerive() {
        // The fixture target orders by `day`, which is not a plain input column: the gate would refuse it.
        final AggregateSpec unDerivable = spec(new ArrayList<>(), List.of());
        assertThatCode(() -> assertThat(validator.deriveGroupKeysBestEffort(INPUT, TARGET, unDerivable)).isFalse())
                .doesNotThrowAnyException();
        assertThat(unDerivable.getGroupBy()).isEmpty();

        // A draft that names no input yet, or an input that is not ACTIVE, derives nothing either.
        final AggregateSpec noInput = spec(new ArrayList<>(), List.of());
        assertThat(validator.deriveGroupKeysBestEffort(null, TARGET, noInput)).isFalse();
        final TableDefinitionEntity pending = input();
        pending.setStatus(TableStatus.PENDING);
        given(definitions.findById(INPUT)).willReturn(Optional.of(pending));
        assertThat(validator.deriveGroupKeysBestEffort(INPUT, TARGET, noInput)).isFalse();
        assertThat(noInput.getGroupBy()).isEmpty();
    }

    @Test
    void duplicateMeasureNameIsRejected() {
        // Two measures writing one column: the second would silently win and the first would vanish.
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("call_count", "count", null), measure("call_count", "sum", "total_tokens")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("duplicate measure name");
    }

    @Test
    void duplicateGroupKeyOutputNameIsRejected() {
        final AggregateSpec spec = spec(
                List.of(column("deployment"), bucket("request_time", TruncUnit.DAY, "deployment")),
                List.of(measure("call_count", "count", null)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("duplicate group_by output name");
    }

    @Test
    void unwrittenTargetColumnIsRejected() {
        // The target declares call_count, which no measure writes: it could only hold a type default, and a
        // zero that means "never computed" is indistinguishable from a zero that means "no calls".
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("call_count", "count", null)));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "call_count", QueryFieldType.LONG),
                column(TARGET, "orphan", QueryFieldType.LONG)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("orphan");
    }

    @Test
    void unknownAggregateFunctionIsRejected() {
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("call_count", "median", "total_tokens")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("not a supported aggregate function");
    }

    @Test
    void scalarFunctionIsRejectedAsMeasure() {
        // `lower` is in the shared catalog but in the scalar group — a measure must aggregate.
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("call_count", "lower", "deployment")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("not a supported aggregate function");
    }

    @Test
    void arrayJoinIsRejectedAsMeasure() {
        // array_join is row-expanding, and an incrementally refreshed aggregate has no story for a measure
        // that changes the row count under it. Nothing was added to keep it out — a measure resolves only
        // against the catalog's aggregate group, and array_join is a scalar — so this pins the claim rather
        // than a guard, and would fail if the function were ever promoted to the aggregate group.
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("call_count", "array_join", "execution_path")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("not a supported aggregate function");
    }

    @Test
    void theTextAndArrayScalarsAreRejectedAsMeasures() {
        // Same claim as above for the four functions this change adds: all scalars, so a measure cannot
        // name one, and nothing was added to keep them out. Pinned rather than assumed, since promoting
        // any of them to the aggregate group later would silently make it a legal measure.
        for (final String name : List.of("concat", "to_string", "array_to_string", "array_slice")) {
            final AggregateSpec spec = spec(valid().getGroupBy(),
                    List.of(measure("call_count", name, "deployment")));
            assertThatThrownBy(() -> validate(spec))
                    .as("measure '%s'", name)
                    .isInstanceOf(PipelineValidationException.class)
                    .hasMessageContaining("not a supported aggregate function");
        }
    }

    @Test
    void distinctIsAcceptedForCount() {
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(distinct(measure("call_count", "count", "deployment"))));

        assertThatCode(() -> validate(spec)).doesNotThrowAnyException();
    }

    @Test
    void distinctIsAcceptedForSum() {
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(distinct(measure("call_count", "sum", "total_tokens"))));

        assertThatCode(() -> validate(spec)).doesNotThrowAnyException();
    }

    @Test
    void distinctIsAcceptedAlongsideMeasurePredicate() {
        // The pair success_count needs: the predicate chooses the rows, the quantifier deduplicates the
        // values on them. They are independent, so declaring both must not be treated as a conflict.
        final Measure scoped = distinct(measure("call_count", "count", "deployment"));
        scoped.setWhere("total_tokens > 0");
        final AggregateSpec spec = spec(valid().getGroupBy(), List.of(scoped));

        assertThatCode(() -> validate(spec)).doesNotThrowAnyException();
    }

    @Test
    void distinctIsRejectedForFunctionsTheCatalogSaysDoNotTakeIt() {
        // min over deduplicated values is the same min, so the quantifier does nothing. Harmless in a query
        // that is gone once answered; in a stored declaration it misdescribes a materialized column for as
        // long as the pipeline exists, which is why the pipeline surface is stricter than the query one.
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(distinct(measure("call_count", "min", "total_tokens"))));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("does not accept it")
                .hasMessageContaining("distinct_supported");
    }

    @Test
    void distinctIsRejectedForTheArrayAggregateAsRedundant() {
        // Not "unsupported" so much as redundant, and it gets the message the query surface already gives:
        // reporting a bare refusal would leave an operator wondering how else to ask a function that only
        // ever returns distinct values for distinct values.
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(distinct(measure("call_count", "group_uniq_array", "deployment"))));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("already returns the distinct values of the group");
    }

    @Test
    void distinctCountWithNoColumnIsRejected() {
        // A column-less count is a row count, and rows are not a value to deduplicate — the only honest
        // rendering would be the non-distinct one, so the declaration would misdescribe its own output.
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(distinct(measure("call_count", "count", null))));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("declares 'distinct' with no column");
    }

    @Test
    void secondTimeBucketIsRejected() {
        given(definitions.findById(TARGET))
                .willReturn(Optional.of(target(List.of("day", "hour"))));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "hour", QueryFieldType.TIMESTAMP),
                column(TARGET, "call_count", QueryFieldType.LONG)));
        final AggregateSpec spec = spec(
                List.of(bucket("request_time", TruncUnit.DAY, "day"),
                        bucket("request_time", TruncUnit.HOUR, "hour")),
                List.of(measure("call_count", "count", null)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("at most one time bucket");
    }

    @Test
    void nonScannableInputIsRejected() {
        // Without a version/identity pair there is no total order to window, so a refresh could not bound
        // itself — and an unbounded recompute of a whole source is not something to discover at runtime.
        final TableDefinitionEntity unscannable = input();
        unscannable.setVersionColumn(null);
        given(definitions.findById(INPUT)).willReturn(Optional.of(unscannable));

        assertThatThrownBy(() -> validate(valid()))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("not scannable");
    }

    @Test
    void bucketOverNonTemporalColumnIsRejected() {
        given(definitions.findById(TARGET)).willReturn(Optional.of(target(List.of("deployment", "day"))));
        final AggregateSpec spec = spec(
                List.of(column("deployment"), bucket("total_tokens", TruncUnit.DAY, "day")),
                List.of(measure("call_count", "count", null)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("date or timestamp");
    }

    @Test
    void groupKeyDeclaringBothColumnAndTruncIsRejected() {
        final GroupKey both = bucket("request_time", TruncUnit.DAY, "day");
        both.setColumn("deployment");
        final AggregateSpec spec =
                spec(List.of(column("deployment"), both), List.of(measure("call_count", "count", null)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("exactly one of");
    }

    @Test
    void measureOverAnUndeclaredInputColumnIsRejected() {
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("call_count", "sum", "ghost_column")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("ghost_column");
    }

    @Test
    void groupKeyBoundToMistypedTargetColumnIsRejected() {
        // A group key is the input value itself, so an incompatible target column is a bad declaration.
        // Left unchecked it reaches ClickHouse, which either rejects the statement (reported as if the
        // engine were at fault) or silently coerces the value.
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.LONG),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "call_count", QueryFieldType.LONG)));

        assertThatThrownBy(() -> validate(valid()))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("the types must match");
    }

    @Test
    void timeBucketBoundToNonTemporalTargetColumnIsRejected() {
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.STRING),
                column(TARGET, "call_count", QueryFieldType.LONG)));

        assertThatThrownBy(() -> validate(valid()))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("it must be declared date or timestamp");
    }

    @Test
    void hourBucketBoundToDateTargetColumnIsRejected() {
        // The bucket start is truncated to midnight on insert, and because the bucket is part of the
        // ordering_key the engine then collapses all 24 of a day's rows onto one arbitrary survivor. No
        // error, no duplicate — 23 buckets simply disappear, which is why a date column is refused here
        // even though it is temporal and holds a day bucket perfectly well.
        given(definitions.findById(TARGET)).willReturn(Optional.of(target(List.of("deployment", "hour"))));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "hour", QueryFieldType.DATE),
                column(TARGET, "call_count", QueryFieldType.LONG)));
        final AggregateSpec spec = spec(
                List.of(column("deployment"), bucket("request_time", TruncUnit.HOUR, "hour")),
                List.of(measure("call_count", "count", null)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("it must be declared timestamp");
    }

    @Test
    void dayBucketBoundToDateTargetColumnIsAccepted() {
        // A day bucket starts at midnight, so a date column holds it without losing anything.
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.DATE),
                column(TARGET, "call_count", QueryFieldType.LONG)));

        assertThatCode(() -> validate(valid())).doesNotThrowAnyException();
    }

    @Test
    void calendarMonthBucketIsAccepted() {
        // A month is not a fixed width, so it compiles through the catalog's calendar truncation rather
        // than its fixed-width binning — see TruncUnit. Bound to a timestamp target column here; the
        // date-column case is covered below.
        final AggregateSpec spec = spec(
                List.of(column("deployment"), bucket("request_time", TruncUnit.MONTH, "day")),
                List.of(measure("call_count", "count", null)));

        assertThatCode(() -> validate(spec)).doesNotThrowAnyException();
    }

    @Test
    void monthBucketBoundToDateTargetColumnIsAccepted() {
        // A month starts at midnight on the 1st, so a date column holds it without losing anything —
        // the same reasoning that admits a day bucket.
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.DATE),
                column(TARGET, "call_count", QueryFieldType.LONG)));
        final AggregateSpec spec = spec(
                List.of(column("deployment"), bucket("request_time", TruncUnit.MONTH, "day")),
                List.of(measure("call_count", "count", null)));

        assertThatCode(() -> validate(spec)).doesNotThrowAnyException();
    }

    @Test
    void averageBoundToIntegerTargetColumnIsRejected() {
        // The catalog types avg and sum alike (both `numeric`), but an average is fractional whatever it
        // reads: ClickHouse converts rather than refusing, so a long column would store a truncated mean —
        // a plausible number, wrong, with nothing marking it.
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "avg_tokens", QueryFieldType.LONG)));
        final AggregateSpec spec =
                spec(valid().getGroupBy(), List.of(measure("avg_tokens", "avg", "total_tokens")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("yields a fractional value");
    }

    @Test
    void averageBoundToDecimalTargetColumnIsAccepted() {
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "avg_tokens", QueryFieldType.DECIMAL)));

        assertThatCode(() -> validate(
                spec(valid().getGroupBy(), List.of(measure("avg_tokens", "avg", "total_tokens")))))
                .doesNotThrowAnyException();
    }

    @Test
    void sumOverDecimalColumnBoundToIntegerTargetColumnIsRejected() {
        // A sum into a long is the ordinary, correct case — but only while the input is integral. Over a
        // decimal column the total is a decimal, and the same truncation applies.
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                column(INPUT, "total_price", QueryFieldType.DECIMAL)));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "cost", QueryFieldType.LONG)));
        final AggregateSpec spec =
                spec(valid().getGroupBy(), List.of(measure("cost", "sum", "total_price")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("yields a fractional value");
    }

    @Test
    void sumOverIntegerColumnBoundToIntegerTargetColumnIsAccepted() {
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "tokens", QueryFieldType.LONG)));

        assertThatCode(() -> validate(
                spec(valid().getGroupBy(), List.of(measure("tokens", "sum", "total_tokens")))))
                .doesNotThrowAnyException();
    }

    @Test
    void nullableMeasureBoundToNonNullableTargetColumnIsRejected() {
        // The zeros case. sum over a nullable column returns NULL for a group holding no value, and
        // ClickHouse converts that to the type default rather than refusing — so "no token data for this
        // deployment-day" is stored as, and indistinguishable from, "this deployment-day used zero tokens".
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                nullable(column(INPUT, "total_tokens", QueryFieldType.LONG))));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "tokens", QueryFieldType.LONG)));
        final AggregateSpec spec =
                spec(valid().getGroupBy(), List.of(measure("tokens", "sum", "total_tokens")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("nullable input column 'total_tokens'")
                .hasMessageContaining("declared non-nullable")
                // The remedy is a table change, not a pipeline edit: an operator who reads it the other way
                // will edit the spec repeatedly and get the same refusal every time.
                .hasMessageContaining("change to the table, not to the pipeline");
    }

    @Test
    void nullableMeasureBoundToNullableTargetColumnIsAccepted() {
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                nullable(column(INPUT, "total_tokens", QueryFieldType.LONG))));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                nullable(column(TARGET, "tokens", QueryFieldType.LONG))));

        assertThatCode(() -> validate(
                spec(valid().getGroupBy(), List.of(measure("tokens", "sum", "total_tokens")))))
                .doesNotThrowAnyException();
    }

    @Test
    void measureOverNonNullableInputIsExempt() {
        // Keyed on the input column, not the function: sum over a non-nullable column cannot be NULL, so
        // refusing it would be over-refusal on the most ordinary declaration there is.
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "tokens", QueryFieldType.LONG)));

        assertThatCode(() -> validate(
                spec(valid().getGroupBy(), List.of(measure("tokens", "sum", "total_tokens")))))
                .doesNotThrowAnyException();
    }

    @Test
    void countIsExemptFromTheNullabilityRule() {
        // count never returns NULL, and count(*) names no input column at all — so it cannot be decided by
        // the input rule and is exempted outright.
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                nullable(column(INPUT, "total_tokens", QueryFieldType.LONG))));

        assertThatCode(() -> validate(
                spec(valid().getGroupBy(), List.of(measure("call_count", "count", "total_tokens")))))
                .doesNotThrowAnyException();
    }

    @Test
    void nullablePlainGroupKeyWithNoFilterIsRejected() {
        // The nullable-measure failure with the remedy removed. A NULL deployment is its own group, and the
        // group keys ARE the target's ordering_key — which ClickHouse refuses to make nullable — so no target
        // shape could hold it. The engine stores '' instead, and the rollup carries a deployment nobody ran.
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                nullable(column(INPUT, "deployment", QueryFieldType.STRING)),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                column(INPUT, "total_tokens", QueryFieldType.LONG)));

        assertThatThrownBy(() -> validate(valid()))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("nullable input column 'deployment'")
                .hasMessageContaining("declares no filter")
                // The message has to carry the remedy, or the reading left is "this column is unusable".
                .hasMessageContaining("deployment IS NOT NULL");
    }

    @Test
    void nullableBucketColumnWithNoFilterIsRejected() {
        // The other half of the same defect: a NULL cannot be bucketed, so the pruning bound (a comparison on
        // the raw column) drops those rows out of the recompute entirely and the rollup's totals quietly stop
        // reconciling with the input. Held to the same rule, because "drops a row" is not better than
        // "invents one" and the distinction has nothing to do with the NULL.
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                nullable(column(INPUT, "request_time", QueryFieldType.TIMESTAMP)),
                column(INPUT, "total_tokens", QueryFieldType.LONG)));

        assertThatThrownBy(() -> validate(valid()))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("group key 'day'")
                .hasMessageContaining("nullable input column 'request_time'")
                .hasMessageContaining("cannot be bucketed");
    }

    @Test
    void anyDeclaredFilterAdmitsNullableGroupKey() {
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                nullable(column(INPUT, "deployment", QueryFieldType.STRING)),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                column(INPUT, "total_tokens", QueryFieldType.LONG)));

        // The predicate is deliberately NOT parsed for null-handling: inferring that from an arbitrary
        // boolean would either refuse correct declarations or admit wrong ones. What the rule establishes is
        // that the declarer considered the question, which a pipeline with no predicate at all has not — so a
        // filter that happens not to mention the column is accepted too, and that is the contract.
        assertThatCode(() -> validator.validate(
                "p", INPUT, TARGET, "deployment IS NOT NULL", valid(), "aggregate"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("p", INPUT, TARGET, "success = true", valid(), "aggregate"))
                .doesNotThrowAnyException();
    }

    @Test
    void nonNullableGroupKeysNeedNoFilter() {
        // The baseline fixture is entirely non-nullable, so the ordinary declaration is untouched: no NULL can
        // reach a key, and demanding a filter for one would be pure over-refusal.
        assertThatCode(() -> validate(valid())).doesNotThrowAnyException();
    }

    @Test
    void sensitiveColumnIntoUnprotectedTargetColumnIsRejected() {
        // The laundering case. Scheduled refreshes run elevated, so nothing downstream would stop this: the
        // protected values would land in a target column the query surface serves to everyone. The flag has
        // to be carried across the derivation.
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                sensitive(column(INPUT, "total_tokens", QueryFieldType.LONG))));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "peak", QueryFieldType.LONG)));
        final AggregateSpec spec =
                spec(valid().getGroupBy(), List.of(measure("peak", "max", "total_tokens")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("must itself be declared sensitive");
    }

    @Test
    void sensitiveColumnIntoProtectedTargetColumnIsAccepted() {
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                sensitive(column(INPUT, "total_tokens", QueryFieldType.LONG))));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                sensitive(column(TARGET, "peak", QueryFieldType.LONG))));
        final AggregateSpec spec =
                spec(valid().getGroupBy(), List.of(measure("peak", "max", "total_tokens")));

        assertThatCode(() -> validate(spec)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a rollup target granted more widely than its input is refused as a validation failure")
    void widelyGrantedRollupTargetIsRefused() {
        // Two things at once, and the second is the point of the re-throw: the rule fires on the aggregate
        // path at all, and it surfaces as this capability's own 422 pipeline_validation_failed rather than
        // as the grant surface's sensitive_grant_widens. One condition, one code per surface.
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                sensitive(column(INPUT, "total_tokens", QueryFieldType.LONG))));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                sensitive(column(TARGET, "peak", QueryFieldType.LONG))));
        // The input protects its sensitive column; the target hands it to a role the input does not name.
        final TableDefinitionEntity target = target(List.of("deployment", "day"));
        target.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        given(definitions.findById(TARGET)).willReturn(Optional.of(target));
        final AggregateSpec spec =
                spec(valid().getGroupBy(), List.of(measure("peak", "max", "total_tokens")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .isNotInstanceOf(SensitiveGrantWidensException.class)
                .hasMessageContaining("analysts")
                .hasMessageContaining(INPUT);
    }

    @Test
    void inputColumnsAreAddressedByTheirExposedName() {
        // The declaration names input columns the way a query over the same table does — the exposed name,
        // which is what the compiled SELECT resolves against. Validating the physical name instead would
        // accept declarations that cannot compile, and reject ones that can, on any aliased column.
        final ColumnMappingEntity aliased = column(INPUT, "total_tokens", QueryFieldType.LONG);
        aliased.setSourceName("tokens_physical");
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                aliased));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "tokens", QueryFieldType.LONG)));

        assertThatCode(() -> validate(
                spec(valid().getGroupBy(), List.of(measure("tokens", "sum", "total_tokens")))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(
                spec(valid().getGroupBy(), List.of(measure("tokens", "sum", "tokens_physical")))))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("tokens_physical");
    }

    @Test
    void arrayMeasureBindsToAnArrayTargetColumn() {
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("deployments", "group_uniq_array", "deployment")));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "deployments", QueryFieldType.ARRAY)));

        assertThatCode(() -> validate(spec)).doesNotThrowAnyException();
    }

    @Test
    void arrayMeasureBoundToScalarTargetColumnIsRejected() {
        // The set would land in a String column, which ClickHouse fills by coercion rather than refusing —
        // the same class of failure as a fractional average in a long column.
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("deployments", "group_uniq_array", "deployment")));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "deployments", QueryFieldType.STRING)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("returns array")
                .hasMessageContaining("is declared 'string'");
    }

    @Test
    void arrayMeasureOverNullableInputIsAcceptedByNonNullableTarget() {
        // The nullability rule exempts group_uniq_array: it skips NULLs and answers an all-NULL group with
        // an empty array, and ClickHouse has no Nullable(Array) — so requiring a nullable target column, as
        // sum/avg/min/max do, would leave this measure with no declarable target at all.
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("deployments", "group_uniq_array", "parent_deployment")));
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                nullable(column(INPUT, "parent_deployment", QueryFieldType.STRING))));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "deployments", QueryFieldType.ARRAY)));

        assertThatCode(() -> validate(spec)).doesNotThrowAnyException();
    }

    @Test
    void arrayMeasureOverSensitiveInputColumnStillNeedsSensitiveTarget() {
        // An array is no laundering exemption: the set holds the protected values themselves.
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("deployments", "group_uniq_array", "deployment")));
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                sensitive(column(INPUT, "deployment", QueryFieldType.STRING)),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP)));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                sensitive(column(TARGET, "deployment", QueryFieldType.STRING)),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "deployments", QueryFieldType.ARRAY)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("must itself be declared sensitive");
    }

    @Test
    void arrayMeasureMatchingItsInputElementTypeIsAccepted() {
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("deployments", "group_uniq_array", "deployment")));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                arrayOf(TARGET, "deployments", QueryFieldType.STRING)));

        assertThatCode(() -> validate(spec)).doesNotThrowAnyException();
    }

    @Test
    void arrayMeasureWhoseElementTypeDiffersFromItsInputIsRejected() {
        // The shape matches (array into array), so only the element type is left to catch it — and it has to,
        // because ClickHouse converts positionally on INSERT … SELECT rather than refusing: the rollup would
        // hold stringified numbers that every reader treats as text.
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("tokens_seen", "group_uniq_array", "total_tokens")));
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                column(INPUT, "total_tokens", QueryFieldType.LONG)));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                arrayOf(TARGET, "tokens_seen", QueryFieldType.STRING)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("declared 'long'")
                .hasMessageContaining("array of 'string'");
    }

    @Test
    void arrayMeasureOverAnArrayInputColumnIsRejected() {
        // A set of arrays is a nested type no element_type can name; the SQL surface refuses the same
        // expression, so accepting it here would only defer the failure to the refresh.
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("paths", "group_uniq_array", "execution_path")));
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                arrayOf(INPUT, "execution_path", QueryFieldType.STRING)));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                arrayOf(TARGET, "paths", QueryFieldType.STRING)));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("must be scalars");
    }

    // --- per-measure `where` -----------------------------------------------------------------------

    @Test
    void scopedMeasureBoundToNonNullableTargetColumnIsRejected() {
        // The nullable-measure rule reached the other way. total_tokens is NOT nullable here, so the input
        // rule exempts it — but a group in which no row satisfies the predicate still has nothing to sum,
        // and a non-nullable target would store that as a measured zero. Keying the check on the input
        // column alone would let exactly this declaration through.
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "tokens", QueryFieldType.LONG)));
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(scoped(measure("tokens", "sum", "total_tokens"), "deployment = 'gpt-4'")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("is scoped by a 'where'")
                .hasMessageContaining("no row matches")
                .hasMessageContaining("declared non-nullable");
    }

    @Test
    void scopedMeasureBoundToNullableTargetColumnIsAccepted() {
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                nullable(column(TARGET, "tokens", QueryFieldType.LONG))));

        assertThatCode(() -> validate(spec(valid().getGroupBy(),
                List.of(scoped(measure("tokens", "sum", "total_tokens"), "deployment = 'gpt-4'")))))
                .doesNotThrowAnyException();
    }

    @Test
    void scopedCountAndArrayMeasuresStayExemptFromTheNullableRule() {
        // The NEVER_NULL exemption has to survive scoping, for the same reason it exists: a scoped count of
        // nothing is 0 and a scoped group_uniq_array of nothing is the empty set — both honest answers, not
        // fabricated ones. Refusing them would force a nullable target that can never hold a NULL, and for
        // the array column ClickHouse cannot even express one.
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "call_count", QueryFieldType.LONG),
                arrayOf(TARGET, "deployments", QueryFieldType.STRING)));
        final AggregateSpec spec = spec(valid().getGroupBy(), List.of(
                scoped(measure("call_count", "count", null), "deployment = 'gpt-4'"),
                scoped(measure("deployments", "group_uniq_array", "deployment"), "deployment = 'gpt-4'")));

        assertThatCode(() -> validate(spec)).doesNotThrowAnyException();
    }

    @Test
    void malformedMeasurePredicateIsRejectedAtDeclaration() {
        // The predicate goes through the shared filter grammar, so a broken one is a 422 here rather than a
        // statement that fails inside every scheduled refresh.
        given(filterValidator.analyze(anyString(), any(), anyString(), anyBoolean()))
                .willThrow(new PipelineValidationException("unknown column 'nope'"));
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(scoped(measure("call_count", "count", null), "nope = 1")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("unknown column 'nope'");
    }

    @Test
    void measurePredicateColumnsRunTheSensitiveEntitlementGate() {
        // A predicate reads its columns even though it derives nothing from them, and entitlement is about
        // reading: without this, `sum(x) where jwt_claims = '…'` would be a way to test a protected value
        // one comparison at a time from a caller not entitled to see it.
        given(filterValidator.analyze(anyString(), any(), anyString(), anyBoolean()))
                .willReturn(new FilterSqlValidator.FilterAnalysis(null, Set.of("jwt_claims")));
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(scoped(measure("call_count", "count", null), "jwt_claims = 'x'")));

        validate(spec);

        verify(sensitiveGuard).enforce(
                eq(INPUT), argThat(cols -> cols.contains("jwt_claims")), anyString(), anyString());
    }

    // --- measure arity -----------------------------------------------------------------------------

    /**
     * The guard against the REAL catalog, now that {@code arg_max} is in it. This path was unreachable
     * until that entry landed — the synthetic-catalog test below was the only coverage — so this is the
     * case that proves the guard fires on a function an operator can actually name, with a message that
     * tells them what to do instead.
     */
    @Test
    void measureNamingArgMaxIsRejectedByTheRealCatalog() {
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("latest_rate", "arg_max", "deployment")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("arg_max")
                .hasMessageContaining("takes 2 arguments")
                .hasMessageContaining("arg_max(value, order)")
                .hasMessageContaining("exactly one input column")
                // The message has to point somewhere, or the operator is stuck: arg_max is available at
                // query time, which is the whole reason refusing it here is acceptable.
                .hasMessageContaining("query time");
    }

    @Test
    void measureNamingFlatteningAggregateIsRejectedWithItsOwnReason() {
        // The measure vocabulary is derived from the catalog's aggregate group, so groupUniqArrayArray
        // arrived in it the moment the query surface gained the function. It is refused — a measure is
        // one function over one SCALAR column — and the reason matters: the array-measure rule would
        // have refused it too, saying "a set of arrays is not a declarable column type", which is true
        // of group_uniq_array and false of a function whose whole purpose is to flatten.
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("steps", "groupUniqArrayArray", "execution_path")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("groupUniqArrayArray")
                .hasMessageContaining("input column must be a scalar")
                .hasMessageContaining("query time")
                .hasMessageNotContaining("not a declarable column type");
    }

    @Test
    void measureNamingTwoOperandAggregateIsRejected() {
        // Kept alongside the real-catalog case above: this one is keyed on a function the catalog does not
        // have, so it still asserts what the guard is FOR — the day the next two-operand aggregate is
        // added, the measure vocabulary must not silently widen to include a call a measure cannot
        // express. Unchecked, such a declaration would store and enable, and the arity failure would
        // surface inside every scheduled refresh, visible only in state.last_error.
        validator = new AggregateSpecValidator(definitions, pipelines, catalogWithTwoOperandAggregate(),
                filterValidator, sensitiveGuard, new EntityFieldResolver(columns, catalog), freshness, SPEC_CODEC,
                new com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant(
                        definitions, columns, pipelines, pipelineReads, org.mockito.Mockito.mock(com.epam.aidial.analytics.web.security.ProviderRolePrivileges.class)),
                org.mockito.Mockito.mock(AggregateSqlReader.class));
        final AggregateSpec spec = spec(valid().getGroupBy(),
                List.of(measure("call_count", "pair_max", "deployment")));

        assertThatThrownBy(() -> validate(spec))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("pair_max")
                .hasMessageContaining("takes 2 arguments")
                .hasMessageContaining("pair_max(value, order)")
                .hasMessageContaining("exactly one input column");
    }

    /** The real catalog plus one hypothetical two-operand aggregate, to exercise the arity guard. */
    private static QueryFunctionCatalog catalogWithTwoOperandAggregate() {
        final QueryFunctionDto twoOperand = new QueryFunctionDto("pair_max", QueryFunctionGroup.AGGREGATE,
                "pair_max(value, order)", QueryFunctionReturnType.SAME_AS_ARGUMENT, false, true, "test-only",
                List.of(new QueryFunctionArgDto("value", QueryFunctionArgKind.EXPRESSION, null, null, null),
                        new QueryFunctionArgDto("order", QueryFunctionArgKind.EXPRESSION, null, null, null)));
        return new QueryFunctionCatalog() {
            @Override
            public List<QueryFunctionDto> listFunctions() {
                return Stream.concat(super.listFunctions().stream(), Stream.of(twoOperand)).toList();
            }
        };
    }

    private static Measure scoped(Measure measure, String where) {
        measure.setWhere(where);
        return measure;
    }

    private static ColumnMappingEntity sensitive(ColumnMappingEntity column) {
        column.setSensitive(true);
        return column;
    }

    private static ColumnMappingEntity nullable(ColumnMappingEntity column) {
        column.setNullable(true);
        return column;
    }

    /** An {@code array} column carrying a declared element type, as a materialized target column does. */
    private static ColumnMappingEntity arrayOf(String table, String name, QueryFieldType elementType) {
        final ColumnMappingEntity column = column(table, name, QueryFieldType.ARRAY);
        column.setElementType(elementType);
        return column;
    }

    // ------------------------------------------- the preview's column types

    /** An input carrying one column of each type the preview's inference distinguishes, and a matching target. */
    private void givenTypedColumns(QueryFieldType bucketTarget) {
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                column(INPUT, "total_tokens", QueryFieldType.LONG),
                column(INPUT, "total_price", QueryFieldType.DECIMAL)));
        given(columns.findByTableNameOrderByOrdinal(TARGET)).willReturn(List.of(
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", bucketTarget),
                column(TARGET, "hour", QueryFieldType.TIMESTAMP),
                column(TARGET, "cost", QueryFieldType.DECIMAL),
                column(TARGET, "avg_tokens", QueryFieldType.DECIMAL),
                column(TARGET, "tokens", QueryFieldType.LONG),
                column(TARGET, "first_seen", QueryFieldType.TIMESTAMP),
                column(TARGET, "last_seen", QueryFieldType.TIMESTAMP),
                column(TARGET, "min_deployment", QueryFieldType.STRING),
                column(TARGET, "max_deployment", QueryFieldType.STRING),
                column(TARGET, "deployments", QueryFieldType.ARRAY)));
    }

    private Map<String, QueryFieldType> previewTypes(AggregateSpec spec) {
        final Map<String, QueryFieldType> types = new java.util.LinkedHashMap<>();
        for (final PreviewColumn column : validator.previewColumns(INPUT, TARGET, spec)) {
            types.put(column.name(), column.type());
        }
        return types;
    }

    @Test
    void previewInfersEachMeasuresYieldedType() {
        givenTypedColumns(QueryFieldType.TIMESTAMP);
        final AggregateSpec spec = spec(List.of(column("deployment")), List.of(
                measure("cost", "sum", "total_price"),
                measure("tokens", "sum", "total_tokens"),
                measure("avg_tokens", "avg", "total_tokens"),
                measure("first_seen", "min", "request_time"),
                measure("last_seen", "max", "request_time"),
                measure("min_deployment", "min", "deployment"),
                measure("max_deployment", "max", "deployment"),
                measure("deployments", "group_uniq_array", "deployment")));

        assertThat(previewTypes(spec)).containsExactly(
                entry("deployment", QueryFieldType.STRING),
                entry("cost", QueryFieldType.DECIMAL),
                entry("tokens", QueryFieldType.LONG),
                entry("avg_tokens", QueryFieldType.DECIMAL),
                entry("first_seen", QueryFieldType.TIMESTAMP),
                entry("last_seen", QueryFieldType.TIMESTAMP),
                entry("min_deployment", QueryFieldType.STRING),
                entry("max_deployment", QueryFieldType.STRING),
                entry("deployments", QueryFieldType.ARRAY));
    }

    @Test
    void previewTypesAnHourBucketAsTimestamp() {
        givenTypedColumns(QueryFieldType.TIMESTAMP);
        final AggregateSpec spec = spec(List.of(bucket("request_time", TruncUnit.HOUR, "hour")),
                List.of(measure("tokens", "sum", "total_tokens")));

        assertThat(previewTypes(spec)).containsEntry("hour", QueryFieldType.TIMESTAMP);
    }

    @Test
    void previewTypesDayBucketByItsTargetColumn() {
        givenTypedColumns(QueryFieldType.TIMESTAMP);
        final AggregateSpec spec = spec(List.of(bucket("request_time", TruncUnit.DAY, "day")),
                List.of(measure("tokens", "sum", "total_tokens")));
        assertThat(previewTypes(spec)).containsEntry("day", QueryFieldType.TIMESTAMP);

        givenTypedColumns(QueryFieldType.DATE);
        assertThat(previewTypes(spec)).containsEntry("day", QueryFieldType.DATE);
    }

    @Test
    void onlyMinAndMaxAreValuePreserving() {
        final AggregateSpec spec = spec(List.of(column("deployment")), List.of(
                measure("first_seen", "min", "request_time"),
                measure("last_seen", "max", "request_time"),
                measure("tokens", "sum", "total_tokens"),
                measure("avg_tokens", "avg", "total_tokens"),
                measure("call_count", "count", null),
                measure("deployments", "group_uniq_array", "deployment")));

        assertThat(AggregateSpecValidator.valuePreservingMeasures(spec)).containsExactlyInAnyOrder("first_seen", "last_seen");
    }

    // --- SQL mode ------------------------------------------------------------------------------------

    private static final String SQL = "INSERT INTO usage_daily (deployment, day, call_count) "
            + "SELECT deployment, date_trunc('day', request_time), count(*) FROM dial_usage_log GROUP BY 1, 2";

    private static AggregateSpec sqlSpec() {
        final AggregateSpec spec = new AggregateSpec();
        spec.setSql(SQL);
        return spec;
    }

    /** A planned statement inserting into {@code target} and reading {@code input}. */
    private static com.epam.aidial.analytics.service.query.sql.SqlInsertPlan plan(String target, String input) {
        return new com.epam.aidial.analytics.service.query.sql.SqlInsertPlan(target,
                List.of("deployment", "day", "call_count"),
                new com.epam.aidial.analytics.service.query.sql.SqlTranslation(
                        new com.epam.aidial.analytics.service.query.model.StructuredQuery(input, null,
                                com.epam.aidial.analytics.service.query.model.QueryMode.AGGREGATE, false, List.of(
                                        new OutputColumn(new FieldExpr("deployment"), null),
                                        new OutputColumn(new FnExpr("date_trunc", false,
                                                List.of(new ValueExpr(ValueType.STRING, "day"), new FieldExpr("request_time"))), null),
                                        new OutputColumn(new FnExpr("count", false, List.of()), null)),
                                List.of("deployment", "date_trunc"), null, List.of(), null),
                        List.of(new ResultColumn("deployment", ResultColumnKind.STRING),
                                new ResultColumn("date_trunc", ResultColumnKind.TIMESTAMP),
                                new ResultColumn("count", ResultColumnKind.INT64)), false));
    }

    @Test
    void sqlModeIsNotAskedForMeasuresOrGroupKeys() {
        given(sqlReader.require(SQL)).willReturn(plan(TARGET, INPUT));
        final AggregateSpec spec = sqlSpec();

        assertThatCode(() -> validator.validate("p", null, TARGET, null, spec, "arm")).doesNotThrowAnyException();
        // The statement's GROUP BY declares the keys: none is derived from the target's ordering_key.
        assertThat(spec.getGroupBy()).isEmpty();
    }

    @Test
    void unreadableSqlIsRefusedNamingSqlBeforeAnythingElse() {
        given(sqlReader.require(SQL)).willThrow(new PipelineValidationException("sql cannot be read: parse error"));

        assertThatThrownBy(() -> validator.validate("p", null, "no_such_target", null, sqlSpec(), "arm"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageStartingWith("sql cannot be read");
    }

    @Test
    void sqlTargetMismatchIsRefusedNamingBothTables() {
        given(sqlReader.require(SQL)).willReturn(plan("usage_weekly", INPUT));

        assertThatThrownBy(() -> validator.validate("p", INPUT, TARGET, null, sqlSpec(), "arm"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("'usage_weekly'")
                .hasMessageContaining("'" + TARGET + "'");
    }

    @Test
    void declaredInputThatDisagreesWithTheStatementIsRefusedNamingBoth() {
        given(sqlReader.require(SQL)).willReturn(plan(TARGET, INPUT));

        assertThatThrownBy(() -> validator.validate("p", "other_source", TARGET, null, sqlSpec(), "arm"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("'other_source'")
                .hasMessageContaining("'" + INPUT + "'");
    }

    @Test
    void sqlModeStillHoldsTheTargetToTheFormPathsChecks() {
        given(sqlReader.require(SQL)).willReturn(plan(TARGET, INPUT));
        final TableDefinitionEntity appendOnly = target(List.of("deployment", "day"));
        appendOnly.setWriteMode(TableWriteMode.APPEND);
        given(definitions.findById(TARGET)).willReturn(Optional.of(appendOnly));

        assertThatThrownBy(() -> validator.validate("p", null, TARGET, null, sqlSpec(), "arm"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("upsert_by_key");
    }
}
