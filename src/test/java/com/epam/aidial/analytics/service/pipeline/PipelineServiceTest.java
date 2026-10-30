package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.PipelineConflictException;
import com.epam.aidial.analytics.exception.PipelineNotFoundException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.RowPolicyConfinedException;
import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.observability.TransitionLog;
import com.epam.aidial.analytics.service.enrichment.EnrichResolver;
import com.epam.aidial.analytics.service.enrichment.model.MemberSelect;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.enrichment.model.TransformOutput;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.RunnerContactMetrics;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichSpecValidator;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.pipeline.model.GroupKey;
import com.epam.aidial.analytics.service.pipeline.model.Measure;
import com.epam.aidial.analytics.service.pipeline.model.PipelineEnabledFilter;
import com.epam.aidial.analytics.service.pipeline.model.PipelineRegistration;
import com.epam.aidial.analytics.service.security.RowPolicyFilter;
import com.epam.aidial.analytics.service.security.RowPolicyFilterStubs;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import org.apache.logging.log4j.Level;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit coverage for {@link PipelineService} — the registry's lifecycle rules, across both kinds.
 *
 * <p>The two that carry most weight are the <b>cursor reset on re-declaration</b> and the <b>refusal to
 * edit an enabled aggregate pipeline</b>. Both guard the same failure: a target holding rows computed by a
 * declaration that no longer exists, with nothing recording which row came from which. Neither shows up as
 * an error — the pipeline keeps running and the numbers are simply wrong for the keys nobody recomputed.
 *
 * <p>Since the fold, four per-kind divergences carry the same weight and are covered here too: the enrich
 * exemption from that refusal, the cursor reset skipping an enrich row's rebuild anchor, registration-time
 * {@code enabled}, and the {@code SCHEDULE} default that keeps every pre-existing aggregate registration
 * body valid.
 *
 * <p>The spec codec is the <b>real</b> one over the application's configured mapper, not a mock: the point
 * of the untyped column is that a declaration round-trips through it unchanged, and a stubbed codec would
 * assert nothing about that.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineServiceTest {

    private static final String NAME = "usage_daily_rollup";
    private static final String ENRICH_NAME = "usage-client-identity-live";
    private static final String CRON = "0 0 * * * *";
    private static final String COMPUTED = "dial_usage_log_payload.request_body_full";

    @Mock
    private PipelineRepository pipelines;
    @Mock
    private TableDefinitionRepository definitions;
    @Mock
    private AggregateSpecValidator aggregateValidator;
    @Mock
    private EnrichSpecValidator enrichValidator;
    @Mock
    private EnrichResolver enrichResolver;
    @Mock
    private AggregateMetrics metrics;
    @Mock
    private RunnerContactMetrics runnerContactMetrics;
    @Mock
    private SecurityClaimsExtractor claims;
    @Mock
    private AggregateSqlReader sqlReader;
    @Mock
    private LookupIndexService lookupIndexes;

    private final PipelineSpecCodec specCodec =
            new PipelineSpecCodec(new JsonMapperConfiguration().jsonMapper());

    private PipelineService service;

    @BeforeEach
    void setUp() {
        service = new PipelineService(pipelines, definitions, aggregateValidator, enrichValidator, specCodec,
                enrichResolver, metrics, runnerContactMetrics, new TransitionLog(), claims, com.epam.aidial.analytics.service.security.RowPolicyFilterStubs.inert(),
                sqlReader, lookupIndexes);
        given(pipelines.saveAndFlush(any())).willAnswer(inv -> inv.getArgument(0));
        given(definitions.existsById(any())).willReturn(true);
    }

    private static PipelineRegistration registration() {
        return aggregate(NAME, CRON);
    }

    private static PipelineRegistration aggregate(String name, String cron) {
        return new PipelineRegistration(name, PipelineKind.AGGREGATE, "usage_daily", "dial_usage_log", null,
                new AggregateSpec(), TriggerKind.SCHEDULE, cron, null, null, null, false);
    }

    /**
     * The minimum stored enrich declaration: a transform with a type, which is what the kind-dispatched
     * paths branch on. The gate itself is mocked here, so nothing else has to be populated.
     */
    private static EnrichSpec enrichSpec() {
        final EnrichSpec spec = new EnrichSpec();
        spec.mutableTransform().setType(TransformType.SQL);
        return spec;
    }

    private static PipelineRegistration enrich(TriggerKind trigger, boolean enabled) {
        return new PipelineRegistration(ENRICH_NAME, PipelineKind.ENRICH, "usage_client_identity", null, null,
                enrichSpec(), trigger, null, null, null, null, enabled);
    }

    /** A pipeline that has already run once: enabled, armed, with a stored scan position. */
    private static PipelineEntity ran() {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName(NAME);
        pipeline.setKind(PipelineKind.AGGREGATE);
        pipeline.setTarget("usage_daily");
        pipeline.setInputTable("dial_usage_log");
        pipeline.setTriggerKind(TriggerKind.SCHEDULE);
        pipeline.setTriggerCron(CRON);
        pipeline.setEnabled(true);
        pipeline.setNextRunAt(Instant.now());
        pipeline.setCursorVersion(1_700_000_000_000L);
        pipeline.setCursorIdentity("evt-1");
        pipeline.setHasMore(true);
        pipeline.setLastError("previous failure");
        pipeline.setGeneration(4);
        return pipeline;
    }

    /** An enabled ENRICH pipeline with a rebuild anchor that must survive a re-declaration. */
    private static PipelineEntity enrichRan(Instant rebuiltAt) {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName(ENRICH_NAME);
        pipeline.setKind(PipelineKind.ENRICH);
        pipeline.setTarget("usage_client_identity");
        pipeline.setTriggerKind(TriggerKind.SCHEDULE);
        pipeline.setTriggerCron("0 * * * * *");
        pipeline.setEnabled(true);
        pipeline.setRebuiltAt(rebuiltAt);
        pipeline.setGeneration(7);
        return pipeline;
    }

    @Test
    void registrationLandsDisabledAndDisarmed() {
        given(pipelines.existsById(NAME)).willReturn(false);

        final PipelineEntity created = service.create(registration());

        // The preview gate only means something if nothing runs before it.
        assertThat(created.isEnabled()).isFalse();
        assertThat(created.getNextRunAt()).isNull();
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
        assertThat(created.getGeneration()).isEqualTo(1);
    }

    @Test
    void duplicateNameIsConflict() {
        given(pipelines.existsById(NAME)).willReturn(true);

        assertThatThrownBy(() -> service.create(registration()))
                .isInstanceOf(PipelineConflictException.class);
        verify(pipelines, never()).saveAndFlush(any());
    }

    @Test
    void targetAlreadyWrittenByAnotherKindIsConflict() {
        given(pipelines.existsById(anyString())).willReturn(false);
        final PipelineEntity existing = ran();
        given(pipelines.findByTarget("usage_client_identity")).willReturn(Optional.of(existing));

        // One writer per target, across kinds. Before the fold the two registries could not contend because
        // their target shapes were disjoint; now it is a constraint rather than an argument.
        assertThatThrownBy(() -> service.create(enrich(TriggerKind.SCHEDULE, false)))
                .isInstanceOf(PipelineConflictException.class)
                .hasMessageContaining("already written by pipeline '" + NAME + "'");
        verify(pipelines, never()).saveAndFlush(any());
    }

    @Test
    void anInvalidCronIsRejectedUpFront() {
        given(pipelines.existsById(NAME)).willReturn(false);

        // A cron that does not parse would leave the pipeline armed but silently never firing.
        assertThatThrownBy(() -> service.create(aggregate(NAME, "not a cron")))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("not a valid cron");
    }

    private static PipelineRegistration aggregateDraft(AggregateSpec spec, String filter) {
        return new PipelineRegistration(NAME, PipelineKind.AGGREGATE, "usage_daily", "dial_usage_log", filter,
                spec, TriggerKind.SCHEDULE, CRON, null, null, null, false);
    }

    /**
     * An aggregate registration is always a draft, so the arming gate never sees it: a computed field anywhere in
     * the declaration is refused by name on the write itself, with the gate's 422, form and SQL mode alike.
     */
    @Test
    void aggregateDraftNamingComputedFieldIsRefusedOnTheWrite() {
        given(pipelines.existsById(NAME)).willReturn(false);
        final AggregateSpec sql = new AggregateSpec();
        sql.setSql("INSERT INTO usage_daily (n) SELECT max(length(\"" + COMPUTED + "\")) AS n FROM dial_usage_log");
        final GroupKey key = new GroupKey();
        key.setColumn(COMPUTED);
        final AggregateSpec grouped = new AggregateSpec();
        grouped.setGroupBy(List.of(key));
        final Measure scoped = new Measure();
        scoped.setName("n");
        scoped.setFn("count");
        scoped.setWhere(COMPUTED + " IS NOT NULL");
        final AggregateSpec measured = new AggregateSpec();
        measured.setMeasures(List.of(scoped));

        assertThatThrownBy(() -> service.create(aggregateDraft(sql, null)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("the SQL declaration references '" + COMPUTED + "'")
                .hasMessageContaining("bound as a transform input");
        assertThatThrownBy(() -> service.create(aggregateDraft(grouped, null)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("group_by references '" + COMPUTED + "'");
        assertThatThrownBy(() -> service.create(aggregateDraft(measured, null)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("measure 'n' where references '" + COMPUTED + "'");
        assertThatThrownBy(() -> service.create(aggregateDraft(new AggregateSpec(), "\"" + COMPUTED + "\" <> ''")))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("filter references '" + COMPUTED + "'");
        verify(pipelines, never()).saveAndFlush(any());
        // Named only in a literal or a comment, it is no reference.
        assertThatCode(() -> service.create(aggregateDraft(new AggregateSpec(),
                "deployment <> '" + COMPUTED + "' /* not \"" + COMPUTED + "\" */"))).doesNotThrowAnyException();
    }

    /** A {@code sql} transform's output expression on an enrich draft, refused on the write as at arming. */
    @Test
    void enrichDraftWhoseSqlOutputReadsComputedFieldIsRefusedOnTheWrite() {
        given(pipelines.existsById(ENRICH_NAME)).willReturn(false);
        final EnrichSpec spec = enrichSpec();
        final TransformOutput output = new TransformOutput();
        output.setName("size");
        output.setSql("length(\"" + COMPUTED + "\")");
        spec.mutableTransform().setOutputs(List.of(output));

        assertThatThrownBy(() -> service.create(new PipelineRegistration(ENRICH_NAME, PipelineKind.ENRICH,
                "usage_client_identity", null, null, spec, TriggerKind.SCHEDULE, null, null, null, null, false)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("output variable 'size' references '" + COMPUTED + "'");
        verify(pipelines, never()).saveAndFlush(any());
    }

    @Test
    void anAggregateRegistrationOmittingTheTriggerKindDefaultsToSchedule() {
        given(pipelines.existsById(NAME)).willReturn(false);
        final PipelineRegistration noKind = new PipelineRegistration(NAME, PipelineKind.AGGREGATE,
                "usage_daily", "dial_usage_log", null, new AggregateSpec(), null, CRON, null, null, null,
                false);

        // `trigger` has always been optional on the aggregate request and nothing read its `kind`, so
        // without this default every registration body written before the merge would become a 422.
        final PipelineEntity created = service.create(noKind);

        assertThat(created.getTriggerKind()).isEqualTo(TriggerKind.SCHEDULE);
        assertThat(created.getTriggerCron()).isEqualTo(CRON);
    }

    @Test
    void anEnrichScheduleRegistrationWithNoCronIsStoredWithTheDefault() {
        given(pipelines.existsById(ENRICH_NAME)).willReturn(false);

        final PipelineEntity created = service.create(enrich(TriggerKind.SCHEDULE, false));

        // An enrich schedule trigger that declares no cron is stored with every minute at a name-derived
        // second, so the stored declaration always says when the pipeline runs.
        assertThat(created.getTriggerKind()).isEqualTo(TriggerKind.SCHEDULE);
        assertThat(created.getTriggerCron()).isEqualTo(DefaultSchedule.cronFor(ENRICH_NAME));
    }

    @Test
    void anEnrichGroupRegistrationIsStoredWithNoCron() {
        given(pipelines.existsById(ENRICH_NAME)).willReturn(false);

        final PipelineEntity created = service.create(enrich(TriggerKind.GROUP, false));

        // The default belongs to the schedule trigger only: a group pipeline's clock is the readiness sweep.
        assertThat(created.getTriggerCron()).isNull();
    }

    @Test
    void anEnrichRegistrationMayLandEnabledWhileAnAggregateOneMayNot() {
        given(pipelines.existsById(anyString())).willReturn(false);

        // The forced disable belongs to the preview gate, not to registration: an aggregate pipeline has
        // one, an enrich pipeline does not, so requiring a second call there would gate nothing.
        assertThat(service.create(enrich(TriggerKind.SCHEDULE, true)).isEnabled()).isTrue();
        assertThat(service.create(new PipelineRegistration(NAME, PipelineKind.AGGREGATE, "usage_daily",
                "dial_usage_log", null, new AggregateSpec(), TriggerKind.SCHEDULE, CRON, null, null, null,
                true)).isEnabled()).isFalse();
    }

    @Test
    void anEnrichPipelineIsNeverArmedForTheAggregateTick() {
        given(pipelines.existsById(ENRICH_NAME)).willReturn(false);
        given(pipelines.findById(ENRICH_NAME)).willReturn(Optional.of(enrichRan(Instant.now())));

        // The first of three independent guards against the in-process aggregate tick picking up an enrich
        // row — the others being the kind filter in findDue and the assertion in the refresh service. One
        // is not enough: an aggregate refresh against an enrichment target writes wrong rows rather than
        // failing.
        assertThat(service.create(enrich(TriggerKind.SCHEDULE, true)).getNextRunAt()).isNull();
        assertThat(service.patch(ENRICH_NAME, null, true).getNextRunAt()).isNull();
    }

    @Test
    void reDeclaringDisabledPipelineResetsItsScanPosition() {
        final PipelineEntity stored = ran();
        stored.setEnabled(false);
        stored.setNextRunAt(null);
        given(pipelines.findById(NAME)).willReturn(Optional.of(stored));

        final PipelineEntity patched = service.patch(NAME, registration(), null);

        // Keeping the position would confine the new declaration to input not yet read, leaving every key
        // materialized before the edit holding values the previous declaration produced.
        assertThat(patched.getCursorVersion()).isNull();
        assertThat(patched.getCursorIdentity()).isNull();
        assertThat(patched.isHasMore()).isFalse();
        // The recorded error belonged to the declaration being replaced.
        assertThat(patched.getLastError()).isNull();
    }

    @Test
    void reDeclaringAnEnabledAggregatePipelineIsRefused() {
        given(pipelines.findById(NAME)).willReturn(Optional.of(ran()));

        // A state conflict, not a bad declaration — the spec sent may be perfectly good and only the timing
        // wrong — so it answers 409, the same code refreshing a disabled pipeline uses.
        assertThatThrownBy(() -> service.patch(NAME, registration(), null))
                .isInstanceOf(PipelineConflictException.class)
                .hasMessageContaining("disable it before");
        verify(pipelines, never()).saveAndFlush(any());
    }

    @Test
    void reDeclaringAnEnabledEnrichPipelineIsPermittedAndLeavesTheRebuildAnchorAlone() {
        final Instant anchor = Instant.parse("2026-08-01T00:00:00Z");
        final PipelineEntity stored = enrichRan(anchor);
        given(pipelines.findById(ENRICH_NAME)).willReturn(Optional.of(stored));

        final PipelineEntity patched =
                service.patch(ENRICH_NAME, enrich(TriggerKind.SCHEDULE, true), null);

        // Correctness rather than convenience: its target is a grain-keyed replace-by-grain table, so new
        // rows computing the new way while old rows keep their values is already how it behaves — the
        // aggregate invariant was never this kind's.
        assertThat(patched.getGeneration()).isEqualTo(8);
        // `rebuilt_at` is the ROLLUP rebuild anchor. Re-stamping it here would write a fact that does not
        // apply to a row that is not a rollup, and the enrich path's own re-derivation marker is separate.
        assertThat(patched.getRebuiltAt()).isEqualTo(anchor);
    }

    @Test
    void anUnimplementedKindIsRefusedNamingTheOnesThatWork() {
        given(pipelines.existsById(NAME)).willReturn(false);
        final PipelineRegistration external = new PipelineRegistration(NAME, PipelineKind.EXTERNAL,
                "usage_daily", "dial_usage_log", null, new AggregateSpec(), TriggerKind.SCHEDULE, CRON,
                null, null, null, false);

        // The discriminator carries the kinds this object will grow into, so a caller naming one is told it
        // is not implemented yet rather than getting the generic binding failure an omitted member produces.
        assertThatThrownBy(() -> service.create(external))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("'external' is not implemented")
                .hasMessageContaining("aggregate")
                .hasMessageContaining("enrich");
        verify(pipelines, never()).saveAndFlush(any());
    }

    @Test
    void patchThatReDeclaresAndEnablesRunsTheGateOnce() {
        final PipelineEntity stored = ran();
        stored.setEnabled(false);
        stored.setNextRunAt(null);
        given(pipelines.findById(NAME)).willReturn(Optional.of(stored));

        final PipelineEntity patched = service.patch(NAME, registration(), true);

        // Both branches validate, but over identical state — running the full gate twice in one call would
        // just pay for it twice.
        assertThat(patched.isEnabled()).isTrue();
        assertThat(patched.getNextRunAt()).isNotNull();
        verify(aggregateValidator, times(1))
                .validate(eq(NAME), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void theGateIsDispatchedOnKindAndRunsOnlyWhenTheWriteArms() {
        given(pipelines.existsById(anyString())).willReturn(false);

        // Two DISABLED registrations: drafts, so neither gate refuses anything — but each kind's derivation
        // half still runs, dispatched on the kind, so a draft is never stored with its derived members erased.
        service.create(registration());
        service.create(enrich(TriggerKind.SCHEDULE, false));
        verify(aggregateValidator, never()).validate(any(), any(), any(), any(), any(), any());
        verify(enrichValidator, never()).validate(any());
        verify(aggregateValidator, times(1)).deriveGroupKeysBestEffort(eq("dial_usage_log"), eq("usage_daily"), any());
        verify(enrichValidator, times(1)).deriveOutputBindingsBestEffort(any());

        // An enrich registration that arms the pipeline is gated — by the enrich gate, not the aggregate one.
        // A declaration validated by the wrong one would be accepted on rules that do not apply to it.
        service.create(new PipelineRegistration("armed-live", PipelineKind.ENRICH, "usage_armed", null, null,
                enrichSpec(), TriggerKind.SCHEDULE, null, null, null, null, true));
        verify(enrichValidator, times(1)).validate(any());
        verify(aggregateValidator, never()).validate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void draftRegistrationOfIdentityAndTargetAloneIsStored() {
        given(pipelines.existsById(anyString())).willReturn(false);

        final PipelineEntity enrich = service.create(new PipelineRegistration(ENRICH_NAME, PipelineKind.ENRICH,
                "usage_client_identity", null, null, null, null, null, null, null, null, false));
        final PipelineEntity aggregate = service.create(new PipelineRegistration(NAME, PipelineKind.AGGREGATE,
                "usage_daily", null, null, null, null, null, null, null, null, false));

        assertThat(enrich.isEnabled()).isFalse();
        // Not picked yet, and not defaulted for enrich: a draft is stored with no trigger kind.
        assertThat(enrich.getTriggerKind()).isNull();
        // An aggregate has only the one trigger it could mean, so the wire default still applies.
        assertThat(aggregate.getTriggerKind()).isEqualTo(TriggerKind.SCHEDULE);
        assertThat(aggregate.getInputTable()).isNull();
        // Derived members are stored empty, never null — one representation in memory and in storage.
        assertThat(((AggregateSpec) specCodec.read(aggregate)).getGroupBy()).isEmpty();
        assertThat(specCodec.readEnrich(enrich).getOutputBindings()).isEmpty();
    }

    @Test
    void draftSaveThatCannotReDeriveKeepsTheStoredOutputMapping() {
        final PipelineEntity stored = new PipelineEntity();
        stored.setName(ENRICH_NAME);
        stored.setKind(PipelineKind.ENRICH);
        stored.setTarget("usage_client_identity");
        stored.setTriggerKind(TriggerKind.SCHEDULE);
        stored.setTriggerCron("0 * * * * *");
        final EnrichSpec previous = enrichSpec();
        final OutputBinding binding = new OutputBinding();
        binding.setVar("client");
        binding.setColumn("client");
        previous.setOutputBindings(List.of(binding));
        stored.setSpec(specCodec.write(previous));
        given(pipelines.findById(ENRICH_NAME)).willReturn(Optional.of(stored));
        given(enrichValidator.deriveOutputBindingsBestEffort(any())).willReturn(false);

        final PipelineEntity patched = service.patch(ENRICH_NAME, new PipelineRegistration(ENRICH_NAME, null,
                null, null, null, new EnrichSpec(), null, null, null, null, null, false), null);

        // The write could not re-derive the mapping, so it kept the stored one rather than erasing it: an
        // erased mapping would let a column this pipeline writes be dropped by the catalog's guard.
        assertThat(specCodec.readEnrich(patched).getOutputBindings())
                .extracting(OutputBinding::getColumn).containsExactly("client");
        // And the omitted target is kept rather than refused: target is sticky on a patch.
        assertThat(patched.getTarget()).isEqualTo("usage_client_identity");
        // Replace semantics for everything else: the omitted trigger is cleared.
        assertThat(patched.getTriggerKind()).isNull();
        verify(enrichValidator, never()).validate(any());
    }

    @Test
    void draftPatchNamingAnotherTargetRepointsIt() {
        final PipelineEntity stored = ran();
        stored.setEnabled(false);
        given(pipelines.findById(NAME)).willReturn(Optional.of(stored));

        final PipelineEntity patched = service.patch(NAME, new PipelineRegistration(NAME, null, "usage_weekly",
                null, null, new AggregateSpec(), null, null, null, null, null, false), null);

        assertThat(patched.getTarget()).isEqualTo("usage_weekly");
    }

    /** A disabled aggregate row whose stored declaration carries a derived {@code group_by}. */
    private PipelineEntity aggregateDraftWithStoredGroupBy() {
        final PipelineEntity stored = ran();
        stored.setEnabled(false);
        final AggregateSpec previous = new AggregateSpec();
        final GroupKey key = new GroupKey();
        key.setColumn("deployment");
        previous.setGroupBy(List.of(key));
        stored.setSpec(specCodec.write(previous));
        given(pipelines.findById(NAME)).willReturn(Optional.of(stored));
        given(aggregateValidator.deriveGroupKeysBestEffort(any(), any(), any())).willReturn(false);
        return stored;
    }

    @Test
    void draftSaveOverTheSameTablesThatCannotReDeriveKeepsTheStoredGroupBy() {
        aggregateDraftWithStoredGroupBy();

        final PipelineEntity patched = service.patch(NAME, aggregate(NAME, CRON), null);

        assertThat(((AggregateSpec) specCodec.read(patched)).getGroupBy())
                .extracting(GroupKey::getColumn).containsExactly("deployment");
    }

    @Test
    void draftSaveRepointingTheTargetOrInputDoesNotCarryTheStoredGroupBy() {
        aggregateDraftWithStoredGroupBy();

        // The stored keys name the old target's ordering key, so kept across a repoint they would describe a
        // table this pipeline no longer writes.
        final PipelineEntity repointed = service.patch(NAME, new PipelineRegistration(NAME, null, "usage_weekly",
                "dial_usage_log", null, new AggregateSpec(), null, CRON, null, null, null, false), null);
        assertThat(((AggregateSpec) specCodec.read(repointed)).getGroupBy()).isEmpty();

        aggregateDraftWithStoredGroupBy();
        final PipelineEntity reInput = service.patch(NAME, new PipelineRegistration(NAME, null, "usage_daily",
                "other_log", null, new AggregateSpec(), null, CRON, null, null, null, false), null);
        assertThat(((AggregateSpec) specCodec.read(reInput)).getGroupBy()).isEmpty();
    }

    @Test
    void unreadableStoredSpecIsRepairedByReDeclarationRatherThanFailingIt() {
        // A re-declaration that arms reads nothing stored, so a row whose stored spec no longer parses
        // stays repairable by one.
        final PipelineEntity damaged = enrichRan(Instant.now());
        damaged.setSpec("{not json");
        given(pipelines.findById(ENRICH_NAME)).willReturn(Optional.of(damaged));

        final PipelineEntity repaired = service.patch(ENRICH_NAME, enrich(TriggerKind.SCHEDULE, true), null);
        assertThat(specCodec.readEnrich(repaired).getTransform().getType()).isEqualTo(TransformType.SQL);

        // A draft save that cannot derive treats it as nothing stored rather than answering 500.
        damaged.setSpec("{not json");
        damaged.setEnabled(false);
        given(enrichValidator.deriveOutputBindingsBestEffort(any())).willReturn(false);
        final PipelineEntity drafted = service.patch(ENRICH_NAME, enrich(TriggerKind.SCHEDULE, false), null);
        assertThat(specCodec.readEnrich(drafted).getOutputBindings()).isEmpty();
    }

    /** An enrich spec naming every member an author writes: a typed transform with one output. */
    private static EnrichSpec completeEnrichSpec() {
        final EnrichSpec spec = enrichSpec();
        final TransformOutput output = new TransformOutput();
        output.setName("client");
        spec.mutableTransform().setOutputs(List.of(output));
        return spec;
    }

    /** A paused enrich pipeline: it ran, so its declaration is complete, and it is now disabled. */
    private PipelineEntity pausedEnrich() {
        final PipelineEntity paused = enrichRan(Instant.now());
        paused.setEnabled(false);
        paused.setSpec(specCodec.write(completeEnrichSpec()));
        given(pipelines.findById(ENRICH_NAME)).willReturn(Optional.of(paused));
        return paused;
    }

    @Test
    void patchLeavingPausedPipelineIncompleteIsRefusedNamingWhatItWouldDrop() {
        final PipelineEntity paused = pausedEnrich();

        // A runtime knob alone maps to an untyped transform: under replace semantics this clears the
        // transform's type and outputs and the trigger, none of which the patch restated.
        assertThatThrownBy(() -> service.patch(ENRICH_NAME, new PipelineRegistration(ENRICH_NAME, null, null,
                null, null, new EnrichSpec(), null, null, null, null, null, false), null))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("would leave its complete declaration incomplete")
                .hasMessageContaining("missing transform.type, transform.outputs, trigger.kind");
        verify(pipelines, never()).saveAndFlush(any());
        assertThat(paused.getGeneration()).isEqualTo(7);
    }

    @Test
    void pausedPipelineMayBeReDeclaredWholeWhileDisabled() {
        pausedEnrich();

        final PipelineEntity patched = service.patch(ENRICH_NAME, new PipelineRegistration(ENRICH_NAME, null,
                null, null, null, completeEnrichSpec(), TriggerKind.SCHEDULE, CRON, null, null, null, false), null);

        assertThat(patched.getTriggerKind()).isEqualTo(TriggerKind.SCHEDULE);
        assertThat(patched.isEnabled()).isFalse();
    }

    @Test
    void pausedPipelineMayHaveItsOutputsEmptiedByRestatingTheTransform() {
        final PipelineEntity paused = pausedEnrich();
        final EnrichSpec stored = completeEnrichSpec();
        final OutputBinding binding = new OutputBinding();
        binding.setVar("client");
        binding.setColumn("client");
        stored.setOutputBindings(List.of(binding));
        paused.setSpec(specCodec.write(stored));
        given(enrichValidator.deriveOutputBindingsBestEffort(any())).willReturn(false);

        // A typed transform with no outputs is a deliberate edit, not an omission: it is how the last output
        // comes off so its target column can be dropped.
        final PipelineEntity patched = service.patch(ENRICH_NAME, new PipelineRegistration(ENRICH_NAME, null,
                null, null, null, enrichSpec(), TriggerKind.SCHEDULE, null, null, null, null, false), null);

        // The mapping for the removed output is not carried over, or the column would stay guarded.
        assertThat(specCodec.readEnrich(patched).getOutputBindings()).isEmpty();
        assertThat(patched.isEnabled()).isFalse();
    }

    @Test
    void incompleteDraftMayStillBeSavedWithAnySubset() {
        final PipelineEntity draft = pausedEnrich();
        draft.setTriggerKind(null);

        assertThatCode(() -> service.patch(ENRICH_NAME, new PipelineRegistration(ENRICH_NAME, null, null, null,
                null, enrichSpec(), null, null, null, null, null, false), null)).doesNotThrowAnyException();
    }

    @Test
    void patchLeavingPausedAggregateWithoutItsMeasuresIsRefused() {
        final PipelineEntity paused = ran();
        paused.setEnabled(false);
        final AggregateSpec stored = new AggregateSpec();
        final Measure measure = new Measure();
        measure.setName("call_count");
        measure.setFn("count");
        stored.setMeasures(List.of(measure));
        paused.setSpec(specCodec.write(stored));
        given(pipelines.findById(NAME)).willReturn(Optional.of(paused));

        assertThatThrownBy(() -> service.patch(NAME, aggregate(NAME, CRON), null))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("missing measures");
    }

    @Test
    void enablingAnEnrichDraftWithNoTriggerKindIsRefusedNamingIt() {
        final PipelineEntity draft = new PipelineEntity();
        draft.setName(ENRICH_NAME);
        draft.setKind(PipelineKind.ENRICH);
        draft.setTarget("usage_client_identity");
        draft.setSpec(specCodec.write(enrichSpec()));
        given(pipelines.findById(ENRICH_NAME)).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.patch(ENRICH_NAME, null, true))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("trigger.kind is required");
        assertThat(draft.isEnabled()).isFalse();
        verify(pipelines, never()).saveAndFlush(any());
    }

    @Test
    void enablingAnAggregateDraftWithNoCronIsA422NotA500() {
        // `trigger: {kind: schedule}` with no cron is exactly what a draft may hold. The enable-only path
        // never ran apply(), so without its own trigger check it reached AggregateScheduler.nextRun(null).
        final PipelineEntity draft = ran();
        draft.setEnabled(false);
        draft.setNextRunAt(null);
        draft.setTriggerCron(null);
        given(pipelines.findById(NAME)).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.patch(NAME, null, true))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("trigger.cron is required");
        assertThat(draft.isEnabled()).isFalse();
    }

    @Test
    void draftWithMalformedCronIsRefusedAtOnce() {
        // Unfinished is deferred; malformed is not.
        given(pipelines.existsById(anyString())).willReturn(false);

        assertThatThrownBy(() -> service.create(aggregate(NAME, "not a cron")))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("not a valid cron expression");
    }

    @Test
    void memberSelectOnNonGroupTriggerIsRefusedEvenOnDraft() {
        // The database refuses this on every row, so deferring it would only turn a named 422 into an
        // integrity violation at flush, reported as a name collision.
        given(pipelines.existsById(anyString())).willReturn(false);
        final MemberSelect select = new MemberSelect();
        select.setLimit(1);

        assertThatThrownBy(() -> service.create(new PipelineRegistration(ENRICH_NAME, PipelineKind.ENRICH,
                "usage_client_identity", null, null, enrichSpec(), TriggerKind.SCHEDULE, null, null, null, select,
                false)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("member_select is only valid for a group pipeline");
    }

    @Test
    void nonScheduleTriggerOnAnAggregateIsRefusedEvenOnDraft() {
        given(pipelines.existsById(anyString())).willReturn(false);

        assertThatThrownBy(() -> service.create(new PipelineRegistration(NAME, PipelineKind.AGGREGATE,
                "usage_daily", null, null, null, TriggerKind.GROUP, null, null, null, null, false)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("'aggregate'");
    }

    @Test
    void draftNamingTableThatDoesNotExistIsA422NotCollision() {
        given(pipelines.existsById(anyString())).willReturn(false);
        given(definitions.existsById("ghost")).willReturn(false);

        assertThatThrownBy(() -> service.create(new PipelineRegistration(NAME, PipelineKind.AGGREGATE,
                "ghost", null, null, null, null, null, null, null, null, false)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("target table 'ghost' does not exist");
    }

    @Test
    void checkViolationAtFlushIsReportedByTheConstraintItBroke() {
        // A CHECK is a declaration the service should have refused first, not a name collision.
        given(pipelines.existsById(anyString())).willReturn(false);
        given(pipelines.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("check",
                new ConstraintViolationException("violates", null, "pipeline_armed_is_complete")));

        assertThatThrownBy(() -> service.create(registration()))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("an armed pipeline must name its trigger kind");
    }

    @Test
    void uniqueViolationAtFlushIsStillConflict() {
        given(pipelines.existsById(anyString())).willReturn(false);
        given(pipelines.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("dup",
                new ConstraintViolationException("duplicate", null, "pipeline_target_key")));

        assertThatThrownBy(() -> service.create(registration()))
                .isInstanceOf(PipelineConflictException.class);
    }

    @Test
    void enablingReValidatesAndArmsTheSchedule() {
        final PipelineEntity stored = ran();
        stored.setEnabled(false);
        stored.setNextRunAt(null);
        given(pipelines.findById(NAME)).willReturn(Optional.of(stored));

        final PipelineEntity patched = service.patch(NAME, null, true);

        assertThat(patched.isEnabled()).isTrue();
        assertThat(patched.getNextRunAt()).isNotNull();
        // Re-validated at the moment of arming: the input's schema may have moved since registration.
        verify(aggregateValidator)
                .validate(eq(NAME), eq("dial_usage_log"), eq("usage_daily"), eq(null), any(), eq("aggregate"));
    }

    @Test
    void enablingLeavesTheCursorAloneSoNoWindowIsRepeated() {
        final PipelineEntity stored = ran();
        stored.setEnabled(false);
        given(pipelines.findById(NAME)).willReturn(Optional.of(stored));

        final PipelineEntity patched = service.patch(NAME, null, true);

        // Only a *declaration* change invalidates the position. A pause and resume must pick up where it
        // left off, or every resume would re-read the whole input.
        assertThat(patched.getCursorVersion()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void disablingDisarmsTheSchedule() {
        given(pipelines.findById(NAME)).willReturn(Optional.of(ran()));

        final PipelineEntity patched = service.patch(NAME, null, false);

        assertThat(patched.isEnabled()).isFalse();
        assertThat(patched.getNextRunAt()).isNull();
    }

    @Test
    void noOpPatchDoesNotMoveTheGeneration() {
        final PipelineEntity stored = ran();
        given(pipelines.findById(NAME)).willReturn(Optional.of(stored));

        // `generation` is an ETag: bumping it when nothing changed would tell a consumer to re-read.
        assertThat(service.patch(NAME, null, true).getGeneration()).isEqualTo(4);
    }

    @Test
    void patchThatReDeclaresAndEnablesBumpsGenerationOnce() {
        final PipelineEntity stored = ran();
        stored.setEnabled(false);
        given(pipelines.findById(NAME)).willReturn(Optional.of(stored));

        assertThat(service.patch(NAME, registration(), true).getGeneration()).isEqualTo(5);
    }

    /** The bump lines {@code PipelineService} logged while {@code action} ran. */
    private static List<String> generationLines(Runnable action) {
        try (LogCapture capture = LogCapture.of(PipelineService.class)) {
            action.run();
            return capture.messagesAt(Level.INFO).stream()
                    .filter(line -> line.startsWith("pipeline ") && line.contains(" generation "))
                    .toList();
        }
    }

    @Test
    void patchThatReDeclaresAndEnablesLogsOneBumpNamingBothAndTheActor() {
        final PipelineEntity stored = ran();
        stored.setEnabled(false);
        given(pipelines.findById(NAME)).willReturn(Optional.of(stored));
        given(claims.isAuthenticated()).willReturn(true);
        given(claims.getAuthor()).willReturn("operator@example.com");

        final List<String> lines = generationLines(() -> service.patch(NAME, registration(), true));

        assertThat(lines).containsExactly(
                "pipeline " + NAME + " generation 4 -> 5 (cause=patch(re-declare,enable), actor=operator@example.com)");
    }

    @Test
    void registrationLogsItsBumpWithoutInventingAnActor() {
        given(pipelines.existsById(NAME)).willReturn(false);
        given(claims.isAuthenticated()).willReturn(false);
        given(claims.getAuthor()).willReturn("anonymousUser");

        final List<String> lines = generationLines(() -> service.create(registration()));

        assertThat(lines).containsExactly("pipeline " + NAME + " generation 0 -> 1 (cause=register, actor=-)");
    }

    @Test
    void disablingPatchNamesItsCauseAndNoOpPatchLogsNoBump() {
        given(pipelines.findById(NAME)).willReturn(Optional.of(ran()));

        assertThat(generationLines(() -> service.patch(NAME, null, false)))
                .containsExactly("pipeline " + NAME + " generation 4 -> 5 (cause=patch(disable), actor=-)");

        given(pipelines.findById(NAME)).willReturn(Optional.of(ran()));
        assertThat(generationLines(() -> service.patch(NAME, null, true))).isEmpty();
    }

    /** The line reports a bump that was accepted: a write refused at flush rolls it back, so it logs none. */
    @Test
    void refusedWritesLogNoBump() {
        given(pipelines.existsById(NAME)).willReturn(false);
        given(pipelines.findById(NAME)).willReturn(Optional.of(ran()));
        given(pipelines.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("dup",
                new ConstraintViolationException("duplicate", null, "pipeline_target_key")));

        assertThat(generationLines(() -> assertThatThrownBy(() -> service.create(registration()))
                .isInstanceOf(PipelineConflictException.class))).isEmpty();
        assertThat(generationLines(() -> assertThatThrownBy(() -> service.patch(NAME, null, false))
                .isInstanceOf(PipelineConflictException.class))).isEmpty();
    }

    @Test
    void storedDeclarationRoundTripsThroughTheUntypedColumn() {
        given(pipelines.existsById(anyString())).willReturn(false);

        final PipelineEntity aggregatePipeline = service.create(registration());
        final PipelineEntity enrichPipeline = service.create(enrich(TriggerKind.SCHEDULE, false));

        // The whole reason the column is untyped and the codec dispatches on `kind`: a declaration of either
        // shape is readable back as itself, with no type stamp inside the JSON that a migration would have
        // had to write into every row already stored.
        assertThat(specCodec.readAggregate(aggregatePipeline)).isInstanceOf(AggregateSpec.class);
        assertThat(specCodec.readEnrich(enrichPipeline)).isInstanceOf(EnrichSpec.class);
        assertThat(specCodec.read(aggregatePipeline)).isInstanceOf(AggregateSpec.class);
        assertThat(specCodec.read(enrichPipeline)).isInstanceOf(EnrichSpec.class);
    }

    @Test
    void readingStoredDeclarationAsTheOtherKindFails() {
        given(pipelines.existsById(anyString())).willReturn(false);
        final PipelineEntity enrichPipeline = service.create(enrich(TriggerKind.SCHEDULE, false));

        // Asserted rather than cast at each call site, so a kind mix-up is one failure here instead of a
        // ClassCastException several frames away.
        assertThatThrownBy(() -> specCodec.readAggregate(enrichPipeline))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is enrich, not aggregate");
    }

    @Test
    void everyListingGoesThroughTheOneOrderedQuery() {
        service.list("usage_daily", PipelineKind.AGGREGATE, PipelineEnabledFilter.ENABLED, Instant.EPOCH);
        service.list(null, null, PipelineEnabledFilter.ANY, null);

        // Four optional predicates in one query, so the kind filter is applied by the database rather than
        // in memory and every listing shares one ordering — `ANY` disables the predicate with a null rather
        // than widening it to both values.
        verify(pipelines).list("usage_daily", PipelineKind.AGGREGATE, Boolean.TRUE, Instant.EPOCH);
        verify(pipelines).list(null, null, null, null);
    }

    @Test
    void anUnknownPipelineIsNotFound() {
        given(pipelines.findById(NAME)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(NAME)).isInstanceOf(PipelineNotFoundException.class);
        assertThatThrownBy(() -> service.delete(NAME)).isInstanceOf(PipelineNotFoundException.class);
        assertThatThrownBy(() -> service.patch(NAME, null, true)).isInstanceOf(PipelineNotFoundException.class);
    }

    @Test
    void dataPlaneCallOnAnAggregatePipelineIsRefusedRatherThanNotFound() {
        given(pipelines.findById(NAME)).willReturn(Optional.of(ran()));

        // The pipeline exists, and saying so is more useful than pretending it does not.
        assertThatThrownBy(() -> service.resolveForScan(NAME))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("is of kind 'aggregate'");
    }

    @Test
    void deletingLeavesTheTargetRowsAlone() {
        final PipelineEntity stored = ran();
        given(pipelines.findById(NAME)).willReturn(Optional.of(stored));

        // A target that stops being fed is stale, not invalid — dropping its data as a side effect of
        // removing the process would be a surprise.
        assertThatCode(() -> service.delete(NAME)).doesNotThrowAnyException();
        verify(pipelines).delete(stored);
        // The rows stay; the series does not. The staleness gauge is derived at scrape time from a watermark
        // nothing will move again, so one left behind climbs forever on a pipeline that no longer exists —
        // a permanent alert on exactly the signal a stall raises.
        verify(metrics).forget(NAME);
    }

    @Test
    void deletingAnUnknownPipelineDropsNoSeries() {
        given(pipelines.findById(NAME)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(NAME)).isInstanceOf(PipelineNotFoundException.class);
        verify(metrics, never()).forget(any());
        verify(runnerContactMetrics, never()).forget(any());
    }

    @Test
    void deletingDropsTheRunnerContactSeriesToo() {
        given(pipelines.findById(NAME)).willReturn(Optional.of(ran()));

        service.delete(NAME);

        verify(runnerContactMetrics).forget(NAME);
    }

    /**
     * The second deletion path, which removes a pipeline because its target table was dropped. It did not
     * deregister anything — a live instance of the leak {@code forget}'s own javadoc says it exists to
     * prevent, and one the runner-contact gauge would have inherited verbatim.
     */
    @Test
    void deletingBecauseTheTargetTableWasDroppedDropsBothSeries() {
        final PipelineEntity stored = ran();
        given(pipelines.findByTarget("usage_daily")).willReturn(Optional.of(stored));

        service.deletePipelineTargetingTable("usage_daily");

        verify(pipelines).delete(stored);
        verify(metrics).forget(NAME);
        verify(runnerContactMetrics).forget(NAME);
    }

    @Test
    void droppingAnUntargetedTableDropsNoSeries() {
        given(pipelines.findByTarget("usage_daily")).willReturn(Optional.empty());

        service.deletePipelineTargetingTable("usage_daily");

        verify(metrics, never()).forget(any());
        verify(runnerContactMetrics, never()).forget(any());
    }

    /**
     * Disabling drops the runner-contact series and nothing else. The runner's feed is
     * {@code enabled=true}, so a disabled pipeline leaves it and the runner correctly stops calling — a
     * climbing "the runner has stopped calling" series would then be the intended behaviour raising the
     * loudest alarm on the dashboard. The aggregate watermark stays: a switched-off rollup still has a
     * last-successful-run position worth reading.
     */
    @Test
    void disablingDropsTheRunnerContactSeriesAndNotTheAggregateOne() {
        given(pipelines.findById(NAME)).willReturn(Optional.of(ran()));

        service.patch(NAME, null, false);

        verify(runnerContactMetrics).forget(NAME);
        verify(metrics, never()).forget(any());
    }

    @Test
    void enablingDropsNothing() {
        final PipelineEntity stored = ran();
        stored.setEnabled(false);
        given(pipelines.findById(NAME)).willReturn(Optional.of(stored));

        service.patch(NAME, null, true);

        verify(runnerContactMetrics, never()).forget(any());
    }

    /** A no-op patch is not a state change, so it must not drop a series either. */
    @Test
    void patchThatChangesNothingDropsNothing() {
        given(pipelines.findById(NAME)).willReturn(Optional.of(ran()));

        service.patch(NAME, null, true);

        verify(runnerContactMetrics, never()).forget(any());
    }

    // --- SQL mode ------------------------------------------------------------------------------------

    private static final String SQL = "INSERT INTO usage_daily (deployment, call_count)\n"
            + "  SELECT deployment, count(*) FROM dial_usage_log GROUP BY 1 -- kept verbatim";
    private static final String UNPARSEABLE = "INSERT INTO usage_daily (deployment, call_count) SELEC";

    private static AggregateSpec sqlSpec(String sql) {
        final AggregateSpec spec = new AggregateSpec();
        spec.setSql(sql);
        return spec;
    }

    /** A registration declaring {@code sql}, as the mapper builds it: no input, no filter, no form members. */
    private static PipelineRegistration sqlDeclaration(String sql, String cron) {
        return new PipelineRegistration(NAME, PipelineKind.AGGREGATE, "usage_daily", null, null, sqlSpec(sql),
                cron == null ? null : TriggerKind.SCHEDULE, cron, null, null, null, false);
    }

    /** A paused SQL-mode pipeline that has refreshed: complete, disabled, with a stored scan position. */
    private PipelineEntity pausedSql() {
        final PipelineEntity paused = ran();
        paused.setEnabled(false);
        paused.setNextRunAt(null);
        paused.setSpec(specCodec.write(sqlSpec(SQL)));
        given(pipelines.findById(NAME)).willReturn(Optional.of(paused));
        given(sqlReader.inputTableOf(SQL)).willReturn(Optional.of("dial_usage_log"));
        return paused;
    }

    @Test
    void sqlDraftDerivesItsInputFromTheStatementAndNoGroupKeys() {
        given(pipelines.existsById(NAME)).willReturn(false);
        given(sqlReader.inputTableOf(SQL)).willReturn(Optional.of("dial_usage_log"));

        final PipelineEntity created = service.create(sqlDeclaration(SQL, CRON));

        assertThat(created.getInputTable()).isEqualTo("dial_usage_log");
        final AggregateSpec stored = specCodec.readAggregate(created);
        assertThat(stored.getSql()).isEqualTo(SQL);
        assertThat(stored.getGroupBy()).isEmpty();
        verify(aggregateValidator, never()).deriveGroupKeysBestEffort(any(), any(), any());
    }

    @Test
    void unparseableSqlDraftIsStoredVerbatimWithNoInputTable() {
        given(pipelines.existsById(NAME)).willReturn(false);
        given(sqlReader.inputTableOf(UNPARSEABLE)).willReturn(Optional.empty());

        final PipelineEntity created = service.create(sqlDeclaration(UNPARSEABLE, null));

        assertThat(created.getInputTable()).isNull();
        assertThat(specCodec.readAggregate(created).getSql()).isEqualTo(UNPARSEABLE);
    }

    @Test
    void triggerOnlyPatchOfPausedSqlPipelineKeepsItsStatement() {
        pausedSql();

        // The mapper carries the stored statement onto a patch carrying no declaration-mode member.
        final PipelineEntity patched = service.patch(NAME, sqlDeclaration(SQL, "0 */5 * * * *"), null);

        assertThat(patched.getTriggerCron()).isEqualTo("0 */5 * * * *");
        assertThat(specCodec.readAggregate(patched).getSql()).isEqualTo(SQL);
        assertThat(patched.getInputTable()).isEqualTo("dial_usage_log");
    }

    @Test
    void modeSwitchMayNotLeavePausedSqlDeclarationIncomplete() {
        final PipelineEntity paused = pausedSql();
        final long generation = paused.getGeneration();
        final GroupKey key = new GroupKey();
        key.setColumn("deployment");
        final AggregateSpec formOnlyKeys = new AggregateSpec();
        formOnlyKeys.setGroupBy(List.of(key));

        assertThatThrownBy(() -> service.patch(NAME, new PipelineRegistration(NAME, null, null, null, null,
                formOnlyKeys, null, null, null, null, null, false), null))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("missing inputs, measures, trigger.cron");
        assertThat(paused.getGeneration()).isEqualTo(generation);
        verify(pipelines, never()).saveAndFlush(any());
    }

    @Test
    void rewritingPausedStatementIsTakenAsSentEvenWhenUnreadable() {
        pausedSql();
        given(sqlReader.inputTableOf(UNPARSEABLE)).willReturn(Optional.empty());

        final PipelineEntity patched = service.patch(NAME, sqlDeclaration(UNPARSEABLE, CRON), null);

        assertThat(specCodec.readAggregate(patched).getSql()).isEqualTo(UNPARSEABLE);
        assertThat(patched.getInputTable()).isNull();
    }

    @Test
    void sqlDeclarationMissingItsTriggerIsIncompleteForTheGuard() {
        pausedSql();

        // A paused complete SQL declaration patched with only a new statement would drop its cron.
        assertThatThrownBy(() -> service.patch(NAME, sqlDeclaration(SQL, null), null))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("missing trigger.cron");
    }

    @Test
    void patchingTheStatementBumpsTheGenerationAndResetsTheCursor() {
        final PipelineEntity paused = pausedSql();
        final long generation = paused.getGeneration();

        final PipelineEntity patched = service.patch(NAME, sqlDeclaration(SQL + " ", CRON), null);

        assertThat(patched.getGeneration()).isEqualTo(generation + 1);
        assertThat(patched.getCursorVersion()).isNull();
        assertThat(patched.getCursorIdentity()).isNull();
        assertThat(patched.isHasMore()).isFalse();
    }

    @Test
    void patchingTheStatementOfAnEnabledPipelineIsConflict() {
        final PipelineEntity enabled = ran();
        enabled.setSpec(specCodec.write(sqlSpec(SQL)));
        given(pipelines.findById(NAME)).willReturn(Optional.of(enabled));

        assertThatThrownBy(() -> service.patch(NAME, sqlDeclaration(SQL, CRON), null))
                .isInstanceOf(PipelineConflictException.class);
        assertThat(enabled.getGeneration()).isEqualTo(4);
        assertThat(enabled.getCursorVersion()).isNotNull();
    }

    @Test
    void sendingSqlClearsFormDeclarationsMembersAndFilter() {
        final PipelineEntity form = ran();
        form.setEnabled(false);
        form.setFilterSql("deployment = 'gpt-4o'");
        final GroupKey key = new GroupKey();
        key.setColumn("deployment");
        final Measure measure = new Measure();
        measure.setName("call_count");
        measure.setFn("count");
        final AggregateSpec formSpec = new AggregateSpec();
        formSpec.setGroupBy(List.of(key));
        formSpec.setMeasures(List.of(measure));
        form.setSpec(specCodec.write(formSpec));
        given(pipelines.findById(NAME)).willReturn(Optional.of(form));
        given(sqlReader.inputTableOf(SQL)).willReturn(Optional.of("dial_usage_log"));

        final PipelineEntity patched = service.patch(NAME, sqlDeclaration(SQL, CRON), null);

        final AggregateSpec stored = specCodec.readAggregate(patched);
        assertThat(stored.getSql()).isEqualTo(SQL);
        assertThat(stored.getGroupBy()).isEmpty();
        assertThat(stored.getMeasures()).isEmpty();
        assertThat(patched.getFilterSql()).isNull();
    }

    @Test
    void sendingMeasuresClearsTheStoredStatement() {
        pausedSql();
        final Measure measure = new Measure();
        measure.setName("call_count");
        measure.setFn("count");
        final AggregateSpec formSpec = new AggregateSpec();
        formSpec.setMeasures(List.of(measure));

        final PipelineEntity patched = service.patch(NAME, new PipelineRegistration(NAME, null, null,
                "dial_usage_log", null, formSpec, TriggerKind.SCHEDULE, CRON, null, null, null, false), null);

        assertThat(specCodec.readAggregate(patched).getSql()).isNull();
        assertThat(specCodec.readAggregate(patched).getMeasures()).hasSize(1);
    }

    @Test
    void enablingAnSqlDraftGivesItTheInputItsStatementReads() {
        final PipelineEntity draft = pausedSql();
        draft.setInputTable(null);

        service.patch(NAME, null, true);

        assertThat(draft.getInputTable()).isEqualTo("dial_usage_log");
        verify(aggregateValidator).validate(eq(NAME), eq("dial_usage_log"), eq("usage_daily"), any(), any(), any());
    }

    @Test
    void armingSqlDeclarationAsksRowPoliciesOfTheInputItsStatementReads() {
        // The SQL-mode declaration names no `inputs`: the input is derived from the statement, and that is the table
        // the refresh reads as the system identity, so a caller confined on it may not arm the pipeline.
        final RowPolicyFilter rowPolicies = RowPolicyFilterStubs.inert();
        willThrow(new RowPolicyConfinedException("confined on 'dial_usage_log'"))
                .given(rowPolicies).requireUnconfinedPipeline(eq("dial_usage_log"), any(), any());
        service = new PipelineService(pipelines, definitions, aggregateValidator, enrichValidator, specCodec,
                enrichResolver, metrics, runnerContactMetrics, new TransitionLog(), claims, rowPolicies, sqlReader,
                lookupIndexes);
        final PipelineEntity paused = pausedSql();
        final long generation = paused.getGeneration();

        // A new statement armed in the same call, and a trigger-only patch (the mapper carries the stored statement).
        given(sqlReader.inputTableOf(SQL + " ")).willReturn(Optional.of("dial_usage_log"));
        assertThatThrownBy(() -> service.patch(NAME, sqlDeclaration(SQL + " ", CRON), true))
                .isInstanceOf(RowPolicyConfinedException.class);
        assertThatThrownBy(() -> service.patch(NAME, sqlDeclaration(SQL, "0 */5 * * * *"), true))
                .isInstanceOf(RowPolicyConfinedException.class);
        assertThat(paused.isEnabled()).isFalse();
        assertThat(paused.getGeneration()).isEqualTo(generation);
        verify(pipelines, never()).saveAndFlush(any());
    }

    @Test
    void storedSqlDeclarationIsCompleteWithoutReparsingAsTheCaller() {
        // A caller who may not see a column the statement reads cannot plan it, but the stored declaration is still
        // complete, so a form-only patch may not erase its statement.
        pausedSql();
        given(sqlReader.inputTableOf(SQL)).willReturn(Optional.empty());
        final GroupKey key = new GroupKey();
        key.setColumn("deployment");
        final AggregateSpec formOnlyKeys = new AggregateSpec();
        formOnlyKeys.setGroupBy(List.of(key));

        assertThatThrownBy(() -> service.patch(NAME, new PipelineRegistration(NAME, null, null, null, null,
                formOnlyKeys, null, null, null, null, null, false), null))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("missing inputs, measures, trigger.cron");
    }
}
