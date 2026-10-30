package com.epam.aidial.analytics.service.enrichment.validation;

import com.dashjoin.jsonata.Jsonata;
import com.epam.aidial.analytics.configuration.GroupFetchProperties;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.SensitiveColumnNotEntitledException;
import com.epam.aidial.analytics.exception.SensitiveGrantWidensException;
import com.epam.aidial.analytics.exception.TableRelayoutInProgressException;
import com.epam.aidial.analytics.service.enrichment.model.EnrichDeclaration;
import com.epam.aidial.analytics.service.enrichment.model.InputBinding;
import com.epam.aidial.analytics.service.enrichment.model.MemberOrder;
import com.epam.aidial.analytics.service.enrichment.model.MemberSelect;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.enrichment.model.ReadyWhen;
import com.epam.aidial.analytics.service.enrichment.model.TransformOutput;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TransformVar;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.CursorColumns;
import com.epam.aidial.analytics.service.enrichment.support.EnrichmentTypeMapping;
import com.epam.aidial.analytics.service.enrichment.support.EntityFieldResolver;
import com.epam.aidial.analytics.service.enrichment.support.EntityFields;
import com.epam.aidial.analytics.service.enrichment.support.ScanSourceMetadata;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.support.TargetOutputColumns;
import com.epam.aidial.analytics.service.pipeline.EnrichmentFreshness;
import com.epam.aidial.analytics.service.pipeline.PipelineDeclaration;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.QualifiedColumnTokens;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.query.ComputedFields;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.EnrichmentAlignment;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant;
import com.epam.aidial.analytics.service.table.relayout.RelayoutStateStore;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The static governance gate run when an ENRICH pipeline is declared or enabled. Each failure is surfaced as a caller-
 * safe exception that maps to the documented status/code:
 * {@link PipelineValidationException} (422 {@code pipeline_validation_failed}),
 * {@link SensitiveColumnNotEntitledException} (403 {@code sensitive_column_not_entitled}).
 *
 * <p>Checks common to every declaration: (1) the target enrichment exists / is ACTIVE / is an enrichment, and
 * every output binding names an existing column of it whose type is compatible with the output
 * variable's type; (1a) the declared read source resolves — see {@link #requireReadSource}, which is what
 * every later check validates against; (2) {@code filter_sql} parses under the SQL front end (bounded
 * grammar, boolean, known columns); (3) referenced filter columns are entitled — a {@code sensitive} column is admitted
 * only for a caller that {@link SensitiveColumnAccess#mayReadSensitive(String)} on the owning table (or
 * any caller in {@code none} mode), mirroring the query surface's own rule; other callers are rejected;
 * (4) the {@code transform} block is shaped for its own {@code type}.
 *
 * <h2>Storability and executability are separate questions</h2>
 *
 * <p>The gate runs only where the pipeline is <b>armed</b> — on a registration that enables it, on a patch
 * that enables it, on a declaration patch of an already-enabled pipeline — and on the dry-run preview of a
 * disabled one, which answers what arming would. A disabled pipeline is a draft and is stored with any
 * subset of its declaration; a write that leaves it disabled runs only the derivation half,
 * {@link #deriveOutputBindingsBestEffort}. Nothing new is <em>runnable</em> as a result — {@code enabled:
 * false} already meant "not running", and the data plane refuses a draft missing a member it compiles
 * from ({@link EnrichDeclarationCompleteness}).
 *
 * <p>The cron-iff-schedule guard is deliberately <b>not</b> here: {@code PipelineService} applies it to
 * every kind before this gate runs, and its copy also parses the expression, so a second weaker check
 * could only report the same refusal with a different message.
 *
 * <p>The rest is <b>dispatched on {@code transform.type}</b>:
 *
 * <ul>
 *   <li>{@code LLM} — the transform carries a non-blank model; bound input-binding columns exist and
 *       are entitled; input/output JSONata expressions parse (static parse only).
 *   <li>{@code SQL} (Class-1) — no model is required and there is no JSONata; instead every output
 *       variable's {@code sql} expression passes {@link ExprSqlValidator} (bounded grammar, scalar not
 *       aggregate, single non-array value), its referenced columns run the same entitlement check, and
 *       its inferred type must be compatible with the variable's declared type. A Class-1 pipeline declares
 *       no input bindings, binds at least one output column, and targets an enrichment keyed on the
 *       source's identity column — its derivation is per source row.
 * </ul>
 *
 * <p>The gate also <b>derives</b> the output mapping — from the transform's outputs matched to the
 * target's columns by name, each stored under the target's physical {@code source_name} — and writes it
 * into the declaration's spec. It is not declared by the caller; see {@link #deriveOutputBindings}.
 *
 * <p>The transform is authored on the pipeline, so the target and the read source are always in scope at
 * the moment the declaration is written: an {@code outputs} key matching no column and a {@code sql}
 * expression naming no source column are both checked here, once.
 *
 * <p><b>Known limitation:</b> the model check does not verify the model is a live DIAL deployment —
 * ADAS has no deployment-listing client to reuse (only caller-key introspection). Live verification is
 * deferred; only a non-blank model is enforced here.
 */
@Component
@RequiredArgsConstructor
public class EnrichSpecValidator {


    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final ScanSourceMetadata scanSources;
    private final FilterSqlValidator filterValidator;
    private final ExprSqlValidator exprValidator;
    private final EnrichmentTypeMapping typeMapping;
    private final SensitiveColumnAccess sensitiveAccess;
    private final SensitiveProjectionGuard sensitiveGuard;
    private final GroupFetchProperties groupFetchProperties;
    private final EntityFieldResolver entityFields;
    private final EnrichmentFreshness freshness;
    private final PipelineRepository pipelines;
    private final PipelineSpecCodec specCodec;
    private final SensitiveGrantInvariant sensitiveGrants;
    private final RelayoutStateStore relayouts;

    /**
     * Runs the gate for a populated (not yet persisted) ENRICH declaration. Throws on the first failure.
     *
     * <p>Called only where the pipeline is armed — a write that leaves it enabled, and the preview of a
     * disabled one — so the template-placeholder check always runs; see the class javadoc on storability
     * versus executability.
     */
    public void validate(EnrichDeclaration declaration) {
        TableDefinitionEntity target = requireActiveEnrichment(declaration.target());
        String grainKey = grainKeyOf(target);
        EnrichSpec.TransformSpec transform = requireValidTransform(declaration);
        Map<String, ColumnMappingEntity> outputColumns = deriveOutputBindings(declaration, target, transform);

        String source = requireReadSource(declaration, target, grainKey);
        requireAlignableWriter(declaration, target, transform, source);
        // Every reference below resolves against the read source's ENTITY — its own columns under either
        // spelling, plus each ACTIVE enrichment's under the qualified flat name. Resolved once and threaded,
        // so no two checks can disagree about what a name means, and so the enrichments a declaration
        // actually reaches can be collected in one place for the dependency rules at the end.
        EntityFields entity = entityFields.enrichEntityOf(source);
        Set<String> referenced = new LinkedHashSet<>();

        Set<String> filterColumns = filterValidator.validateAndCollectColumns(source, declaration.filterSql(), true);
        referenced.addAll(filterColumns);
        checkEntitlement(filterColumns, entity, source, "filter");
        validateGroupTrigger(declaration, transform, source, entity, grainKey, referenced);
        if (transform.getType() == TransformType.SQL) {
            validateSqlExpressions(declaration, transform, source, entity, grainKey, referenced, outputColumns);
        } else {
            checkBoundBindings(declaration, entity, source, referenced);
            parseJsonata(declaration, transform);
            validateTemplatePlaceholders(declaration, transform);
        }
        final Set<String> readEnrichments = requireSoundEnrichmentReads(declaration, source, entity, referenced);
        // The grants half of the laundering rule, stated once in SensitiveGrantInvariant so registration
        // and PUT .../access cannot drift: a pipeline may not be declared against grants that already
        // expose a sensitive column of its target more widely than the source does.
        //
        // Both calls, and the second is the one that matters here. requireNotWidened walks the edges the
        // catalog and the pipeline registry already record, which for a registration in progress do not yet
        // include this declaration's own read source — and `source` above may name any ACTIVE source, not
        // just the target's parent. Checking only the stored edges compared the target's grants against the
        // wrong table, so a pipeline could copy a sensitive column of an unrelated source into an
        // enrichment whose sensitive list its own parent happens to cover.
        //
        // Re-thrown as a pipeline validation failure, because this capability's error contract says a
        // governance violation on register or enable is 422 `pipeline_validation_failed` and that the
        // registry SHALL NOT report one condition under two codes. `sensitive_grant_widens` stays the code
        // for PUT /v1/tables/{name}/access, where the grant edit is what the caller asked for; here the
        // grant state is context for a declaration the caller is trying to register.
        try {
            sensitiveGrants.requireNotWidened(target, null);
            sensitiveGrants.requireNotWidenedAgainst(target, source);
            // And every enrichment reached through the source's grain join. A declaration addresses such a
            // column as "<enrichment>.<column>", so the table whose values are copied is in neither the
            // target's parentage nor the pipeline's input_table — it exists only here, in the resolved set.
            sensitiveGrants.requireNotWidenedAgainst(target, readEnrichments);
        } catch (SensitiveGrantWidensException widens) {
            throw new PipelineValidationException(widens.getMessage());
        }
        // The grain key is read by every derivation and stored as the target's key, so a sensitive one is
        // copied into the target on every row, and its author must be entitled to it like any reference.
        checkEntitlement(Set.of(grainKey), entity, source, "grain key");
    }

    /**
     * The one check the dry-run preview of an <b>enabled</b> pipeline adds on top of its stored declaration:
     * the template placeholders. (A disabled one is previewed through the whole of {@link #validate}.)
     *
     * <p>Exposed rather than folded into a "validate for preview" entry point because the preview is
     * previewing what is <em>stored</em>, not re-validating it: everything else the gate asks was asked
     * when the pipeline was armed, and asking it again would report a pipeline as unpreviewable for a
     * drift the operator did not cause and cannot see from here.
     */
    public void validateForPreview(EnrichDeclaration declaration) {
        final EnrichSpec.TransformSpec transform = declaration.spec().getTransform();
        if (transform != null && transform.getType() != TransformType.SQL) {
            validateTemplatePlaceholders(declaration, transform);
        }
    }

    /**
     * The <b>derivation</b> half of {@link #validate}, run alone on a declaration write that does not arm
     * the pipeline: the output mapping is derived from the transform's outputs against the target's exposed
     * columns exactly as the gate derives it, and nothing is refused.
     *
     * <p>Best-effort and non-destructive by contract. A draft may declare no transform yet, or an output
     * naming a column the target does not have; in every such case this reports {@code false} and leaves
     * the spec's mapping as it was, so the caller can keep the <em>stored</em> mapping rather than write an
     * empty one. That is what keeps the catalog's column drop/rename guard seeing this pipeline as a writer
     * — it resolves writes from the stored {@code output_bindings}, and an erased mapping would let a
     * column the pipeline writes be dropped from ClickHouse ahead of a catalog transaction that then rolls
     * back. It never throws; the refusal for the same condition belongs to the arming gate and to preview.
     *
     * @return whether the mapping was derived from the declaration's current transform
     */
    public boolean deriveOutputBindingsBestEffort(EnrichDeclaration declaration) {
        final EnrichSpec.TransformSpec transform = declaration.spec().getTransform();
        if (StringUtils.isBlank(declaration.target()) || transform == null || transform.getType() == null) {
            return false;
        }
        final TableDefinitionEntity target = definitions.findById(declaration.target()).orElse(null);
        if (target == null || target.getType() != TableType.ENRICHMENT) {
            return false;
        }
        try {
            deriveOutputBindings(declaration, target, transform);
            return true;
        } catch (PipelineValidationException ex) {
            return false;
        }
    }

    /**
     * Whether a stored mapping still describes the stored transform: every output the transform declares
     * is bound, and nothing else is. A declaration write keeps a mapping it could not re-derive (see
     * {@link #deriveOutputBindingsBestEffort}), so a stored mapping can outlive the transform it came from;
     * a caller about to write through it asks this first rather than write a newly authored expression into
     * a column the previous transform bound.
     *
     * <p>Compared by output name, without resolving the target: the question is whether the two members
     * of one stored declaration agree with each other, not whether either still agrees with the catalog.
     */
    public static boolean outputBindingsMatchTransform(EnrichSpec spec) {
        final EnrichSpec.TransformSpec transform = spec == null ? null : spec.getTransform();
        if (transform == null || spec.getOutputBindings() == null || spec.getOutputBindings().isEmpty()) {
            return false;
        }
        final Set<String> declared = new LinkedHashSet<>();
        for (TransformVar var : transform.effectiveOutputVars()) {
            declared.add(var.getName());
        }
        final Set<String> bound = new LinkedHashSet<>();
        for (OutputBinding binding : spec.getOutputBindings()) {
            bound.add(binding.getVar());
        }
        return declared.equals(bound);
    }

    /**
     * Re-derives one stored declaration's output mapping in place, for the catalog's
     * {@code onTargetColumnsChanged} notification.
     *
     * <p>Only the mapping. This is deliberately <b>not</b> the full gate: the caller is inside a catalog
     * write transaction reacting to a column change, and a stored declaration that has drifted for some
     * unrelated reason must not turn an operator's schema patch into a validation failure about a pipeline
     * they were not touching. The mapping is what the column change actually invalidated, so the mapping is
     * what is refreshed; everything else is re-checked on the next register, patch or materialize as before.
     *
     * @return the transform's type, so the caller can scope its bump — or empty when the target no longer
     *     resolves or the declaration carries no transform, in which case nothing is rewritten and nothing
     *     is bumped
     */
    public Optional<TransformType> rederiveOutputBindings(EnrichDeclaration declaration) {
        final TableDefinitionEntity target =
                definitions.findById(declaration.target()).orElse(null);
        if (target == null) {
            return Optional.empty();
        }
        final EnrichSpec.TransformSpec transform = declaration.spec().getTransform();
        if (transform == null || transform.getType() == null) {
            return Optional.empty();
        }
        deriveOutputBindings(declaration, target, transform);
        return Optional.of(transform.getType());
    }

    /**
     * Keeps every pipeline off an aligned target whose rows could not carry the target's alignment value, on both
     * lanes (see {@link EnrichmentAlignment}; the write paths share the rule through {@code PartitionEcho}):
     *
     * <ul>
     *   <li>A pipeline — {@code sql} included — reading a table other than the target's own source. A row of another
     *       table holds no value of the target's source rows: a Class-1 write would stamp the epoch default, or another
     *       table's same-named column, and an {@code llm} scan has nothing to echo. Either way every row matches
     *       nothing under the tuple join.
     *   <li>A group-trigger pipeline. Its rows are written from an assembled group, never from a scanned source row, so
     *       neither the scan page nor the group-rows assembly carries a partition value for the write-back to require.
     * </ul>
     *
     * <p>A target ADAS is relaying out is judged as the aligned table it is about to become. An {@code llm} pipeline
     * onto it is refused outright until the relayout finishes: its scans would carry no partition value until the
     * catalog flips, and every write-back after the flip would then be refused (503, transient — retry once the
     * table's {@code layout} is no longer {@code migrating}).
     */
    private void requireAlignableWriter(EnrichDeclaration declaration, TableDefinitionEntity target,
            EnrichSpec.TransformSpec transform, String source) {
        TableDefinitionEntity targetSource = definitions.findById(target.getSourceTable()).orElse(null);
        String alignment = EnrichmentAlignment.alignedColumn(target, targetSource);
        boolean relayingOut = false;
        if (alignment == null) {
            relayingOut = relayouts.find(target.getName()).map(state -> state.step().inProgress()).orElse(false);
            alignment = relayingOut ? EnrichmentAlignment.eligibleColumn(target.getGrainKey(), targetSource) : null;
        }
        if (alignment == null) {
            return;
        }
        if (!source.equals(target.getSourceTable())) {
            throw new PipelineValidationException(otherReadSourceMessage(target.getName(), target.getSourceTable(), alignment, source));
        }
        if (declaration.triggerKind() == TriggerKind.GROUP) {
            throw new PipelineValidationException("target_enrichment '" + target.getName() + "' is partitioned like '"
                    + target.getSourceTable() + "' on '" + alignment + "', so a group-trigger pipeline cannot write it: each"
                    + " row of the target carries its source row's '" + alignment + "', and a row written from an assembled"
                    + " group has no single source row to take it from; use a row trigger");
        }
        if (relayingOut && transform.getType() != TransformType.SQL) {
            throw new TableRelayoutInProgressException("target_enrichment '" + target.getName() + "' is being relaid out"
                    + " by ADAS into the layout aligned on '" + alignment + "', and an llm pipeline writing it can be armed"
                    + " only once that finishes; retry when the table's layout is no longer 'migrating'");
        }
    }

    /**
     * The refusal of a pipeline reading {@code readSource} into {@code target}, aligned on {@code alignment} with its
     * own source {@code targetSource}; shared with the materialize-time guard for a declaration that drifted.
     */
    public static String otherReadSourceMessage(String target, String targetSource, String alignment, String readSource) {
        return "target_enrichment '" + target + "' is partitioned like '" + targetSource + "' on '" + alignment
                + "', so a pipeline writing it must read '" + targetSource + "' rather than '" + readSource + "': each row"
                + " it writes carries its source row's '" + alignment + "', which a row of another table does not hold";
    }

    private TableDefinitionEntity requireActiveEnrichment(String name) {
        if (StringUtils.isBlank(name)) {
            throw new PipelineValidationException("target_enrichment must not be blank");
        }
        TableDefinitionEntity target = definitions.findById(name)
                .orElseThrow(() -> new PipelineValidationException("target_enrichment '" + name + "' does not exist"));
        if (target.getType() != TableType.ENRICHMENT) {
            throw new PipelineValidationException("target_enrichment '" + name + "' is not an enrichment table");
        }
        if (target.getStatus() != TableStatus.ACTIVE) {
            throw new PipelineValidationException("target_enrichment '" + name + "' is not ACTIVE");
        }
        return target;
    }

    /**
     * The pipeline's <b>read source</b>: the table every governed fragment is validated against and every
     * pipeline-scoped read pages. A pipeline that declares none follows its target enrichment's
     * {@code source_table}, exactly as every pipeline did before the two became separable.
     *
     * <p>The checks below run for a <b>declared</b> source only, and deliberately so. A derived source
     * already satisfies the grain-key one by construction — an enrichment's grain key is validated to be a
     * column of its {@code source_table} when its schema is written — while the other two are new
     * requirements a derived source need not meet: a source may legitimately declare no scan metadata, and
     * a pipeline over such an enrichment registers today and is refused on the scan path instead. Applying them
     * to derived sources would invalidate pipelines that are valid now, which declaring a read source must not
     * do. Declaring a table is a fresh statement of intent, and the gate answers it immediately.
     *
     * @throws PipelineValidationException (422) if a declared source is unknown, is not an ACTIVE source table,
     *     does not carry the target's grain key at a compatible type, or is not scannable
     */
    private String requireReadSource(EnrichDeclaration declaration, TableDefinitionEntity target, String grainKey) {
        String declared = StringUtils.trimToNull(declaration.declaredSource());
        if (declared == null) {
            return target.getSourceTable();
        }
        TableDefinitionEntity source = definitions.findById(declared)
                .orElseThrow(() -> new PipelineValidationException("source '" + declared + "' does not exist"));
        if (source.getType() != TableType.SOURCE) {
            throw new PipelineValidationException("source '" + declared + "' is not a source table;"
                    + " a pipeline reads a source, and an enrichment is reached through its source's grain join");
        }
        // Only an ACTIVE definition has a confirmed ClickHouse table behind it. A FAILED one keeps the
        // columns its failed schema definition persisted, so it can satisfy both checks below and still be
        // a table no scan can read — the same reason the target enrichment must be ACTIVE.
        if (source.getStatus() != TableStatus.ACTIVE) {
            throw new PipelineValidationException("source '" + declared + "' is not ACTIVE ("
                    + source.getStatus().code() + "); it has no materialized table for a pipeline to read");
        }
        requireGrainKeyOnReadSource(declaration, target, grainKey, declared);
        requireScannable(declared);
        return declared;
    }

    /**
     * A declared read source SHALL carry the target enrichment's grain key, at a type compatible with the
     * one the target is keyed at. The pipeline's derived rows are keyed by that column and the target is read
     * back through a grain join on it, so a read source without it could produce no addressable row, and
     * one carrying a same-named column of another type would key the enrichment with values its join
     * cannot match — a silently empty enrichment rather than an error.
     *
     * <p>The type the target is keyed at is read from the target's own {@code source_table}, because that
     * is where {@code CatalogSchemaWriter} resolves the grain column's type from when the enrichment's
     * schema is materialized. A target whose source no longer declares the column is left to the catalog
     * to answer for: the check confines itself to the pair it can compare.
     */
    private void requireGrainKeyOnReadSource(
            EnrichDeclaration declaration, TableDefinitionEntity target, String grainKey, String readSource) {
        EntityFields entity = entityFields.enrichEntityOf(readSource);
        String field = grainKeyField(entity, grainKey, declaration.triggerGroupBy())
                .orElseThrow(() -> new PipelineValidationException("source '" + readSource
                        + "' does not reach the target enrichment's grain key '" + grainKey
                        + "'; a pipeline's derived rows are keyed by that column, and neither '" + readSource
                        + "' nor any of its ACTIVE enrichments declares it"));
        requireEnrichmentGrainKeyIsGrouped(declaration, grainKey, readSource, field, entity);
        QueryFieldType onRead = entity.typeOf(field);
        findColumn(columns.findByTableNameOrderByOrdinal(target.getSourceTable()), grainKey)
                .filter(keyed -> !typeMapping.isCompatible(onRead, keyed.getType()))
                .ifPresent(keyed -> {
                    throw new PipelineValidationException("source '" + readSource + "' reaches grain key '"
                            + grainKey + "' as " + onRead.code() + " (through '" + field + "'), but '"
                            + target.getName() + "' is keyed on it as " + keyed.getType().code());
                });
    }

    /**
     * Only a <b>group</b> pipeline may take its grain key from an enrichment.
     *
     * <p>A group pipeline declares the qualified field as its {@code group_by}, and every read projects it
     * under the plain grain key. A row-grain pipeline declares no {@code group_by} at all: its reads project
     * the grain key by its own bare name, which nothing on the read source carries, so the statement would
     * name a column absent from its {@code FROM} clause and fail at execution rather than here. The shape
     * is also unsound on its own terms — many source rows share one enrichment value, so a row-grain pipeline
     * would write many derived rows onto one target key and the target's {@code ReplacingMergeTree} would
     * collapse them to an arbitrary survivor, which is the same reason {@link #requireIdentityGrain}
     * refuses it for a Class-1 pipeline.
     */
    private static void requireEnrichmentGrainKeyIsGrouped(
            EnrichDeclaration declaration, String grainKey, String readSource, String field, EntityFields entity) {
        if (!entity.isEnrichmentSourced(field) || declaration.triggerKind() == TriggerKind.GROUP) {
            return;
        }
        throw new PipelineValidationException("source '" + readSource + "' reaches the target enrichment's"
                + " grain key '" + grainKey + "' only through enrichment '" + entity.ownerOf(field)
                + "' (as '" + field + "'), and only a group pipeline may key its derived rows on an enrichment"
                + " column — it names the qualified field as group_by and the read projects it under the"
                + " plain grain key. A row-grain pipeline reads '" + grainKey + "' under its own name, which '"
                + readSource + "' does not declare, and many source rows sharing one enrichment value would"
                + " collapse onto a single target row. Declare trigger_kind=group with group_by '" + field
                + "', or key '" + declaration.target() + "' on a column of '" + readSource + "'");
    }

    /**
     * The entity field carrying {@code grainKey}: the read source's own column when it declares one,
     * otherwise the single qualified enrichment field whose last segment is the grain key.
     *
     * <p>The base table wins outright, which is what keeps this widening monotonic — a pipeline that resolved
     * before enrichments were admitted resolves to the same column now, and an enrichment can never shadow
     * one. Ambiguity is only possible among enrichments, and is refused rather than resolved arbitrarily:
     * two enrichments of one source both declaring the grain key give the author no way to say which they
     * meant except by naming it — which is exactly what {@code declaredGroupBy} is, so a declaration that
     * already names one of the candidates is not ambiguous and is answered with the field it named.
     */
    private Optional<String> grainKeyField(EntityFields entity, String grainKey, String declaredGroupBy) {
        if (entity.contains(grainKey) && !entity.isEnrichmentSourced(grainKey)) {
            return Optional.of(grainKey);
        }
        List<String> qualified = entity.names().stream()
                .filter(entity::isEnrichmentSourced)
                .filter(name -> EntityFields.lastSegment(name).equals(grainKey))
                .toList();
        if (qualified.size() > 1) {
            if (qualified.contains(StringUtils.trimToNull(declaredGroupBy))) {
                return Optional.of(declaredGroupBy.trim());
            }
            throw new PipelineValidationException("grain key '" + grainKey + "' is declared by more than one"
                    + " enrichment of the read source (" + String.join(", ", qualified)
                    + "); name the one to group by explicitly as group_by");
        }
        return qualified.stream().findFirst();
    }

    /**
     * A declared read source SHALL be scannable — every pipeline-scoped read resolves its cursor columns, so
     * an unscannable one is a pipeline that can never run. Resolving through {@link ScanSourceMetadata} rather
     * than reading the definition directly is what keeps registration and the scan path from disagreeing
     * about what "scannable" means.
     */
    private void requireScannable(String readSource) {
        try {
            scanSources.cursorColumns(readSource);
        } catch (QueryValidationException ex) {
            throw new PipelineValidationException("source '" + readSource + "' cannot be read by a pipeline: "
                    + ex.getMessage());
        }
    }

    /**
     * The {@code transform} block's own shape, dispatched on its {@code type} — the checks that used to
     * run when an evaluator was registered, now run where the declaration is written.
     *
     * <p><b>This is not the evaluator check moved verbatim, and the difference is the single most likely
     * way to ship this change broken.</b> That check required at least one {@code outputs} entry, which
     * every declaration folded from a pre-{@code outputs} definition has none of — so moving it as-is
     * would refuse every such pipeline at its first patch, enable or read-time validation. What is
     * required here is at least one output <b>resolved through the accessor</b>
     * ({@code outputs}, else {@code output_vars}), exactly as every other consumer resolves them.
     *
     * <p>The per-output <em>shape</em> rules below likewise apply to the {@code outputs} shape only. A
     * folded legacy declaration's {@code output_vars} were shaped when the version they came from was
     * registered, under the rules of the day, and a stored row is never rewritten — so re-judging them
     * against today's rules could only change a verdict on a declaration nobody edited.
     *
     * @return the transform, so the caller threads it rather than re-reading the spec
     */
    private static EnrichSpec.TransformSpec requireValidTransform(EnrichDeclaration declaration) {
        final EnrichSpec.TransformSpec transform = declaration.spec().getTransform();
        if (transform == null) {
            // Ordinarily a draft being armed before its transform was declared, which is now the common
            // case: a disabled pipeline may be stored without one. The rarer cause is a pre-fold write
            // landing on a folded pipeline during a rolling deploy, which also empties the transform's input
            // bindings. This gate cannot tell the two apart, so it names the fix both share and mentions the
            // second as a possibility rather than asserting it.
            throw new PipelineValidationException("transform is required for an 'enrich' pipeline: it is"
                    + " what the pipeline runs, and a declaration without one describes no work. Declare it"
                    + " by PATCH before arming the pipeline. If the pipeline had a transform before, a write"
                    + " served by a pre-fold ADAS pod may have dropped it along with the input bindings;"
                    + " re-declare it in full from the audit trail rather than re-enabling it");
        }
        if (transform.getType() == null) {
            throw new PipelineValidationException("transform.type is required (allowed: llm, sql); it"
                    + " selects the grammar of an outputs value, and both grammars are a string on the"
                    + " wire, so it cannot be inferred");
        }
        requireOneOutputShape(transform);
        final List<TransformVar> resolved = transform.effectiveOutputVars();
        if (resolved.isEmpty()) {
            throw new PipelineValidationException("a transform requires at least one output");
        }
        final Set<String> names = new LinkedHashSet<>();
        for (TransformVar var : resolved) {
            if (StringUtils.isBlank(var.getName())) {
                throw new PipelineValidationException("a transform output requires a name");
            }
            if (!names.add(var.getName())) {
                throw new PipelineValidationException(
                        "output '" + var.getName() + "' is declared twice");
            }
        }
        if (transform.getType() == TransformType.SQL) {
            requireValidSqlShape(transform);
        } else {
            requireValidLlmShape(transform);
        }
        return transform;
    }

    /**
     * A declaration carries <b>one</b> output shape, never both.
     *
     * <p>The two coexist in storage indefinitely and the accessor prefers {@code outputs}, so a
     * declaration carrying both would silently run the one and ignore the other — and a
     * read-modify-write client that added {@code outputs} to a folded legacy declaration without clearing
     * {@code output_vars} would get exactly that. Refused on either verb.
     */
    private static void requireOneOutputShape(EnrichSpec.TransformSpec transform) {
        final boolean hasOutputs = transform.getOutputs() != null && !transform.getOutputs().isEmpty();
        final boolean hasOutputVars =
                transform.getOutputVars() != null && !transform.getOutputVars().isEmpty();
        if (hasOutputs && hasOutputVars) {
            throw new PipelineValidationException("transform declares both 'outputs' and the legacy"
                    + " 'output_vars'; a declaration carries one output shape, and the two would resolve"
                    + " to different sets of outputs. Clear 'output_vars' to migrate to 'outputs'");
        }
    }

    /**
     * The {@code llm} shape: a model, a request template, and each authored output prose with at most one
     * of a domain or a transform beside it.
     *
     * <p><b>The template is required here, and that is a shape check rather than the referential one that
     * was relaxed.</b> The two are easy to confuse and the distinction decides where each runs. "Does this
     * declaration have a prompt at all" is answerable from the block alone and is asked on <em>every</em>
     * write; "do its {@code {{placeholders}}} resolve against {@code transform.inputs}" needs two members
     * an author fills in at different times and is asked only when the pipeline is armed. Without this
     * check the second one covers nothing, because {@link TemplatePlaceholderRule#validate} returns
     * immediately on a blank template — so a {@code PATCH} that restated {@code transform} and dropped
     * {@code request_template} was stored, armed and left enabled, and the two corollaries went with it:
     * the every-declared-input-appears direction, and the mandatory {@code {{members}}} of a group
     * pipeline. What the runner would then send a model is a prompt with no instructions and, at group
     * grain, no data.
     *
     * <p><b>{@code prose} is not required</b>, and its absence is not a degraded case to warn about here:
     * composition defaults the property's {@code description} from the target column, which is where the
     * output's {@code type}, {@code enum} domain and enum order already come from. Where both are blank the
     * composed property simply carries no {@code description}, which is valid JSON Schema and is exactly
     * what a folded legacy declaration already produces — so refusing it at write would contradict the
     * storability line, and refusing it at arm would buy nothing the preview's note does not.
     */
    private static void requireValidLlmShape(EnrichSpec.TransformSpec transform) {
        if (StringUtils.isBlank(transform.getModel())) {
            throw new PipelineValidationException("an llm transform requires a model");
        }
        if (StringUtils.isBlank(transform.getRequestTemplate())) {
            throw new PipelineValidationException("an llm transform requires a request_template: it is the"
                    + " prompt the model is sent, and a blank one is not a draft but a pipeline that would"
                    + " spend money asking nothing. The placeholder check that relates the template to"
                    + " transform.inputs is the part deferred to enable and preview; having a template at"
                    + " all is not");
        }
        if (transform.getOutputs() == null) {
            return;
        }
        for (TransformOutput output : transform.getOutputs()) {
            final String what = "output '" + output.getName() + "'";
            if (StringUtils.isNotBlank(output.getSql())) {
                throw new PipelineValidationException(what + " carries a sql expression on an llm"
                        + " transform; an llm output is prose, optionally with values or jsonata");
            }
            final boolean hasValues = output.getValues() != null && !output.getValues().isEmpty();
            if (hasValues && StringUtils.isNotBlank(output.getJsonata())) {
                throw new PipelineValidationException(what + " declares both values and jsonata; an output"
                        + " carries at most one of them");
            }
            // Refused because an ABSENT jsonata already means exactly this - a direct field lookup by name
            // (see the runner's OutputMapper.resolve). Accepting the identity expression would store a
            // second spelling of the default, which is the redundancy this shape removes.
            if (output.getName().equals(StringUtils.trimToNull(output.getJsonata()))) {
                throw new PipelineValidationException(what + " declares the identity jsonata '"
                        + output.getName() + "'; omit jsonata, which already means a direct lookup by name");
            }
        }
    }

    /**
     * The {@code sql} shape: none of the five {@code llm}-only members, and one non-blank scalar
     * expression per authored output.
     *
     * <p>The non-blank expression check is kept exactly as it was, and deliberately does <b>not</b> follow
     * prose into being optional: a {@code sql} output's string is an expression, not prose, so there is
     * nothing on a column it could be defaulted from. The dispatch is type-split, so the relaxation cannot
     * leak across.
     */
    private static void requireValidSqlShape(EnrichSpec.TransformSpec transform) {
        rejectLlmOnlyField("preset", transform.getPreset() != null);
        rejectLlmOnlyField("model", StringUtils.isNotBlank(transform.getModel()));
        // params shape the preset's request, so they are as LLM-only as the preset itself.
        rejectLlmOnlyField("params",
                transform.getParams() != null && !transform.getParams().isEmpty());
        rejectLlmOnlyField("request_template",
                StringUtils.isNotBlank(transform.getRequestTemplate()));
        // The fifth llm-only member, and the one whose omission here was visible on the wire: a stored
        // response_schema is served as the compiled projection's derived `response_schema`, which the
        // pipeline read documents as absent for a sql transform "which constrains no model response". A
        // sql transform asks no model anything, so a schema on one holds nothing to a shape and would
        // tell a consumer the opposite.
        rejectLlmOnlyField("response_schema", transform.getResponseSchema() != null);
        if (transform.getOutputs() == null) {
            return;
        }
        for (TransformOutput output : transform.getOutputs()) {
            final String what = "output '" + output.getName() + "'";
            // The object form reaches here with its own members set rather than dropped, precisely so that
            // "you sent an object" and "your expression is blank" are two messages instead of one.
            if (StringUtils.isNotBlank(output.getProse())
                    || StringUtils.isNotBlank(output.getJsonata())
                    || (output.getValues() != null && !output.getValues().isEmpty())) {
                throw new PipelineValidationException(what + " is an object on a sql transform; a sql"
                        + " output is the expression itself, as a bare string");
            }
            if (StringUtils.isBlank(output.getSql())) {
                throw new PipelineValidationException(
                        what + " requires a sql expression for a sql transform");
            }
        }
    }

    private static void rejectLlmOnlyField(String field, boolean present) {
        if (present) {
            throw new PipelineValidationException(
                    "a sql transform must not carry 'transform." + field + "' (llm-only)");
        }
    }

    /**
     * Group-trigger governance. The gate constrains the pipeline's <em>declaration</em> rather than trying to
     * infer whether a grain is "a group": {@code table_definition.grain_key} carries only a column name, so
     * there is no row-vs-group marker to check. Enforced here:
     *
     * <ol>
     *   <li>{@code group_by} and {@code ready_when} are valid <b>only</b> on a {@code GROUP} trigger — the
     *       enforceable form of "{@code schedule} stays row-grain";
     *   <li>a {@code GROUP} pipeline's {@code group_by} equals the target enrichment's grain key, because the
     *       enrichment is physically keyed on that column and collapses by it, so grouping by anything else
     *       would write many groups' labels onto one key and lose all but the last;
     *   <li>a {@code GROUP} pipeline declares {@code ready_when} with at least one of {@code signal},
     *       {@code idle}, or {@code max_staleness} — a declaration with none marks a group dirty forever and
     *       never ready, so the pipeline would silently do nothing;
     *   <li>{@code ready_when.signal} parses under the same bounded grammar as {@code filter_sql}, and its
     *       referenced columns run the same sensitive-column entitlement check;
     *   <li>the durations parse and {@code cost_ceiling} is positive;
     *   <li>a {@code GROUP} trigger is rejected for a {@code transform.type: sql} declaration — a
     *       group-grain deterministic pipeline is an aggregation, which this capability deliberately does
     *       not provide.
     * </ol>
     */
    private void validateGroupTrigger(EnrichDeclaration declaration, EnrichSpec.TransformSpec transform,
            String source, EntityFields entity, String grainKey, Set<String> referenced) {
        boolean group = declaration.triggerKind() == TriggerKind.GROUP;
        if (!group) {
            if (StringUtils.isNotBlank(declaration.triggerGroupBy())) {
                throw new PipelineValidationException("trigger.group_by is only valid for a group pipeline");
            }
            if (declaration.readyWhen() != null) {
                throw new PipelineValidationException("trigger.ready_when is only valid for a group pipeline");
            }
            if (declaration.memberSelect() != null) {
                throw new PipelineValidationException("trigger.member_select is only valid for a group pipeline");
            }
            return;
        }
        if (transform.getType() == TransformType.SQL) {
            throw new PipelineValidationException("a sql transform cannot use the group trigger: a group-grain "
                    + "deterministic pipeline is an aggregation, not a Class-1 enrichment");
        }
        requireNotComputed("trigger.group_by", declaration.triggerGroupBy(), entity);
        requireGroupByResolvesToGrain(declaration, entity, grainKey, source);
        referenced.add(declaration.triggerGroupBy());
        // Entitlement-checked like every other reference: the group key is copied into the target as its
        // grain, so an author who may not read a sensitive one may not declare it.
        checkEntitlement(Set.of(declaration.triggerGroupBy()), entity, source, "trigger.group_by");
        validateMemberSelect(declaration, source, entity, referenced);
        ReadyWhen readyWhen = declaration.readyWhen();
        if (readyWhen == null) {
            throw new PipelineValidationException("a group pipeline requires ready_when");
        }
        boolean hasCondition = StringUtils.isNotBlank(readyWhen.getSignal())
                || StringUtils.isNotBlank(readyWhen.getIdle())
                || StringUtils.isNotBlank(readyWhen.getMaxStaleness());
        if (!hasCondition) {
            throw new PipelineValidationException(
                    "ready_when must declare at least one of signal, idle, or max_staleness;"
                            + " a group with no readiness condition would never be evaluated");
        }
        if (StringUtils.isNotBlank(readyWhen.getSignal())) {
            Set<String> signalColumns = filterValidator
                    .analyze(source, readyWhen.getSignal(), "ready_when.signal", true)
                    .columns();
            referenced.addAll(signalColumns);
            checkEntitlement(signalColumns, entity, source, "ready_when.signal");
        }
        requireDuration(readyWhen.getIdle(), "ready_when.idle");
        requireDuration(readyWhen.getMaxStaleness(), "ready_when.max_staleness");
        if (readyWhen.getCostCeiling() != null && readyWhen.getCostCeiling() <= 0) {
            throw new PipelineValidationException("ready_when.cost_ceiling must be a positive integer");
        }
    }

    /**
     * Member-selection governance, for the optional {@code member_select} of a {@code GROUP} pipeline. The
     * policy decides which of a group's members are read at assembly time, so every part of it is
     * validated against the pipeline's <b>read source</b> here rather than being discovered at read time:
     *
     * <ol>
     *   <li>{@code prefer_sql} parses under the same bounded grammar as {@code filter_sql}, and its
     *       referenced columns run the same sensitive-column entitlement check — it is a predicate over the
     *       same columns, so it gets the same front end rather than a second one that could diverge;
     *   <li>every {@code order_by} entry names a column that exists on the read source. A direction is
     *       optional and defaults to descending;
     *   <li>{@code limit} is present and within {@code [1, enrichment.group-fetch.max-rows]}. It is
     *       required because the whole point of declaring a policy is to bound the read, and a policy that
     *       ranks members without saying how many to keep bounds nothing.
     * </ol>
     *
     * <p>An empty {@code order_by} is accepted: {@code prefer_sql} plus a limit is a complete policy on its
     * own, and the source's total order descending is appended as the tiebreak in every case.
     */
    private void validateMemberSelect(
            EnrichDeclaration declaration, String source, EntityFields entity, Set<String> referenced) {
        MemberSelect select = declaration.memberSelect();
        if (select == null) {
            return;
        }
        if (StringUtils.isNotBlank(select.getPreferSql())) {
            Set<String> preferColumns = filterValidator
                    .analyze(source, select.getPreferSql(), "member_select.prefer_sql", true)
                    .columns();
            referenced.addAll(preferColumns);
            checkEntitlement(preferColumns, entity, source, "member_select.prefer_sql");
        }
        List<MemberOrder> orderBy = select.getOrderBy() == null ? List.of() : select.getOrderBy();
        Set<String> orderColumns = new LinkedHashSet<>();
        for (MemberOrder order : orderBy) {
            if (StringUtils.isBlank(order.getColumn())) {
                throw new PipelineValidationException("every member_select.order_by entry must name a column");
            }
            if (!entity.contains(order.getColumn())) {
                throw new PipelineValidationException(unknownField(
                        "member_select.order_by column", order.getColumn(), source, entity));
            }
            requireNotComputed("member_select.order_by", order.getColumn(), entity);
            orderColumns.add(order.getColumn());
        }
        referenced.addAll(orderColumns);
        checkEntitlement(orderColumns, entity, source, "member_select.order_by");
        Integer limit = select.getLimit();
        if (limit == null) {
            throw new PipelineValidationException("member_select requires a limit; a selection policy that does "
                    + "not say how many members to keep would not bound the group's read");
        }
        if (limit < 1 || limit > groupFetchProperties.maxRows()) {
            throw new PipelineValidationException("member_select.limit must be between 1 and "
                    + groupFetchProperties.maxRows() + ", got " + limit);
        }
    }

    /** Accepts both the config-style short form ({@code 10m}) and ISO-8601 ({@code PT10M}). */
    private static void requireDuration(String value, String what) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        try {
            Duration parsed = DurationStyle.detectAndParse(value.trim());
            if (parsed.isNegative() || parsed.isZero()) {
                throw new PipelineValidationException(what + " must be a positive duration");
            }
        } catch (IllegalArgumentException ex) {
            throw new PipelineValidationException(
                    what + " '" + value + "' is not a duration (use 10m or PT10M)");
        }
    }

    /**
     * Class-1 governance: every output expression parses under the bounded grammar, is scalar (not
     * aggregating), is type-compatible with the target column that receives it, and references only columns
     * the author is entitled to read. A Class-1 pipeline additionally declares no {@code transform.inputs}
     * — there is no request to render — and must be row-grain, since a group-grain deterministic pipeline
     * is an aggregation this capability deliberately does not provide.
     *
     * <p><b>This runs at declaration time, because the read source is now always in hand.</b> An evaluator
     * named no source, so these expressions could only be validated when a pipeline bound one — the
     * "accepted at registration, refused at binding" deferral the fold removes.
     */
    private void validateSqlExpressions(EnrichDeclaration declaration, EnrichSpec.TransformSpec transform,
            String source, EntityFields entity, String grainKey, Set<String> referenced,
            Map<String, ColumnMappingEntity> outputColumns) {
        if (!declaration.inputBindings().isEmpty()) {
            throw new PipelineValidationException("a sql pipeline must not declare transform.inputs"
                    + " (there is no request to render)");
        }
        // Unreachable in practice — deriveOutputBindings already refuses a transform that resolves to no
        // column — but kept because the three write paths that read this list all fail closed on an empty
        // one, and a guard for "the list is empty" belongs next to the reason it must not be.
        if (declaration.outputBindings().isEmpty()) {
            throw new PipelineValidationException("the transform must declare at least one output that resolves"
                    + " to a column on the target; there would be nothing to materialize");
        }
        requireIdentityGrain(declaration, source, entity, grainKey, scanSources.cursorColumns(source).identity());
        for (TransformVar var : transform.effectiveOutputVars()) {
            String what = "output variable '" + var.getName() + "'";
            // Reattributed: the front end resolves at the caller's own bar, so a sensitive column it may
            // not see is absent from its model and Calcite answers "unknown column" — a 422 about a column
            // that exists and is protected, where `enrichment-registry` specifies a 403. checkEntitlement
            // below gives the honest answer but never runs, because the analysis fails first.
            ExprSqlValidator.ExprAnalysis analysis = sensitiveGuard.reattributing(
                    source, what, () -> exprValidator.analyze(source, var.getSql(), what, true), true);
            referenced.addAll(analysis.columns());
            checkEntitlement(analysis.columns(), entity, source, "expression for " + what);
            // A Class-1 expression writes its result into a target column, so every enrichment column it
            // reads is a value-producing reference and must be able to represent its own absence.
            analysis.columns().forEach(column -> requireAbsenceRepresentable(what, column, entity));
            requireInferredTypeFits(var, analysis, outputColumns.get(var.getName()), what);
        }
    }

    /**
     * The two Class-1 rules the materialize path re-checks over a stored declaration at execution — and
     * only these two, never the rest of the gate.
     *
     * <p>A declaration may now be stored before it passes the gate, so one that names every member its kind
     * requires can still be invalid. The materialize path already re-derives the expression parse, the
     * closed catalog, determinism, source scoping and the caller's entitlement; these are the two it did
     * not, and the two whose absence writes a <b>silently wrong row</b> rather than failing: an expression
     * whose result does not fit the column it is bound to, and a target keyed more coarsely than the read
     * source's rows, which the target's {@code ReplacingMergeTree} would collapse onto an arbitrary survivor.
     * Only an enabled pipeline reaches materialize, and it passed the whole gate when it was armed; what can
     * still be wrong is a stored row that drifted afterwards. Importing the rest would stop such a pipeline
     * for drift that writes no wrong value, which is drift the operator did not cause.
     *
     * <p>A bound column the target no longer declares is not refused here: that is drift, and the insert
     * reports it on its own.
     *
     * @param cursorColumns the source's cursor columns, which the caller already resolved to page it
     * @param expressions each bound output's analysed expression, keyed by the target column it is bound
     *     to — one per output binding, which is how the caller builds it
     */
    public void recheckClass1Soundness(EnrichDeclaration declaration, String source, String grainKey,
            CursorColumns cursorColumns, Map<String, ExprSqlValidator.ExprAnalysis> expressions) {
        requireIdentityGrain(declaration, source, entityFields.enrichEntityOf(source), grainKey, cursorColumns.identity());
        final List<ColumnMappingEntity> targetColumns = columns.findByTableNameOrderByOrdinal(declaration.target());
        for (OutputBinding binding : declaration.outputBindings()) {
            // Non-null by the caller's construction (one analysis per output binding); a null here means a
            // caller stopped building it that way, and must fail naming that rather than skip the check.
            final ExprSqlValidator.ExprAnalysis analysis = Objects.requireNonNull(expressions.get(binding.getColumn()),
                    () -> "no analysed expression for output binding '" + binding.getColumn() + "'; the caller must"
                            + " analyse one per binding");
            final ColumnMappingEntity column = targetColumns.stream()
                    .filter(candidate -> binding.getColumn().equals(candidate.getSourceName()))
                    .findFirst()
                    .orElse(null);
            if (column == null) {
                continue;
            }
            requireInferredTypeFits(null, analysis, column, "output variable '" + binding.getVar() + "'");
        }
    }

    /**
     * The expression's <b>inferred</b> type against the type of the target column that receives it.
     *
     * <p>One check where there used to be two. The declared per-output token was the redundant middle hop
     * of {@code inferred ~ declared} and {@code declared ~ column}, and dropping it is provably
     * one-directional rather than a change to test and hope: {@code isCompatible(a, b)} is {@code a == b},
     * or both in {@code NUMERIC}, or {@code a == STRING && b == ENUM}, and over those three rules it is
     * transitive. Take the two hops {@code a~y} and {@code y~c} — if {@code a == y} the second hop is the
     * conclusion; if {@code a~y} by numeric then {@code y} is numeric, so {@code y~c} holds only by
     * equality or numeric, either of which makes {@code c} numeric; if {@code a~y} by {@code STRING→ENUM}
     * then {@code y == ENUM}, so {@code y~c} holds only by equality, making {@code c == ENUM}. So
     * everything the two-hop check accepts today the collapsed check still accepts, and <b>no live
     * declaration can begin to fail</b>. It can only loosen: a declaration whose declared token disagreed
     * with its own expression was refused at the first hop and is now accepted — which is the point, since
     * the token was noise.
     *
     * <p>For a stored old-shape row {@code deriveOutputBindings} has <em>also</em> already checked
     * {@code declared ~ column}, so such a pipeline keeps both hops and its verdict is unchanged.
     *
     * <p>Element types are deliberately not compared for {@code array} — see
     * {@link EnrichmentTypeMapping#isCompatible}. An {@code array} expression against a column declared
     * {@code array} passes on shape alone, exactly as before, and an element mismatch still surfaces at
     * the ClickHouse insert rather than here.
     */
    private void requireInferredTypeFits(TransformVar var, ExprSqlValidator.ExprAnalysis analysis,
            ColumnMappingEntity column, String what) {
        if (column == null) {
            // deriveOutputBindings resolves a column for every output before this runs, so a miss here
            // would mean the two disagreed about what the transform declares.
            throw new PipelineValidationException(what + " has no resolved target column");
        }
        if (!typeMapping.isCompatible(analysis.type(), column.getType())) {
            throw new PipelineValidationException(what + " yields " + analysis.type().code()
                    + " but target column '" + column.getName() + "' is " + column.getType().code());
        }
    }

    /**
     * A Class-1 pipeline must write at the <em>row</em> grain of the table it reads: its target enrichment's
     * grain key has to be the <b>read source's</b> declared {@code identity_column}.
     *
     * <p>Materialize derives one value per read-source row and inserts it keyed by the target's grain.
     * Against a coarser grain (a conversation key, say) one window would insert many rows carrying the same
     * key and the target's {@code ReplacingMergeTree} would collapse them to an arbitrary survivor — a label
     * that is silently neither wrong nor reproducible. A per-group deterministic value is an aggregation,
     * which this capability deliberately does not provide, so the misdeclaration is rejected here rather
     * than materialized. Resolving against the read source is what keeps this meaningful once a pipeline may
     * read a table other than its target's: an enrichment whose grain is coarser than the rows its pipeline
     * reads stays a group pipeline. Resolving through {@link ScanSourceMetadata} also means a source with no
     * scan metadata is refused at registration instead of at the first materialize call.
     */
    private void requireIdentityGrain(
            EnrichDeclaration declaration, String source, EntityFields entity, String grainKey, String identity) {
        // A Class-1 pipeline's grain is the row grain of the table it pages, which no enrichment can supply:
        // the identity column has to be the source's own, so a grain key that only an enrichment reaches
        // is rejected here rather than producing one insert per unmatched join.
        if (entity.isEnrichmentSourced(grainKey)
                || (!entity.contains(grainKey) && grainKeyField(entity, grainKey, null).isPresent())) {
            throw new PipelineValidationException("a sql pipeline's target_enrichment must be keyed on the source's"
                    + " identity column '" + identity + "', but '" + declaration.target()
                    + "' is keyed on '" + grainKey + "', which only an enrichment of '" + source
                    + "' reaches; a Class-1 pipeline derives one value per source row");
        }
        if (!identity.equals(grainKey)) {
            throw new PipelineValidationException("a sql pipeline's target_enrichment must be keyed on the source's"
                    + " identity column '" + identity + "', but '" + declaration.target()
                    + "' is keyed on '" + grainKey + "'; a Class-1 pipeline derives one value per source row,"
                    + " and a coarser grain would collapse many rows onto one key");
        }
    }

    /**
     * The dependency rules for a pipeline that reads its source's enrichments: every enrichment it reaches must
     * have an <b>establishable materialized position</b>, and the reference must not close a cycle.
     *
     * <p>The position is what bounds the pipeline's read window. An enrichment row lands after the source row
     * it derives from, while the window advances on the source's cursor, so without a bound a row is read
     * while its enrichment cell is still absent — and because the source row does not move again, the
     * consequence is permanent: a group left unmarked, or a Class-1 value stored from an absence. This is
     * stricter than the aggregate path, which admits an unbounded read and reports it, because a rollup
     * recomputes every dirty key in full on its next refresh and a pipeline has no equivalent second chance.
     *
     * <p>A cycle is refused for a different reason with the same shape: two pipelines each reading the other's
     * target would bound each window on the other's position, freezing both with no error raised and no
     * operator action that releases them.
     *
     * <p>The referenced set comes from the fields the gate actually resolved, not from re-parsing the
     * declaration, so the enrichments checked here are exactly the ones the compiled statements join.
     */
    private Set<String> requireSoundEnrichmentReads(
            EnrichDeclaration declaration, String source, EntityFields entity, Set<String> referenced) {
        Set<String> read = referenced.stream()
                .filter(entity::isEnrichmentSourced)
                .map(entity::ownerOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (read.isEmpty()) {
            return read;
        }
        for (String enrichment : read) {
            EnrichmentFreshness.Clampability clampability = freshness.of(enrichment, source);
            if (clampability.refused()) {
                throw new PipelineValidationException("this pipeline reads enrichment '" + enrichment
                        + "', whose materialized position cannot be established (" + clampability.code()
                        + "): " + clampability.reason() + ". A pipeline's read window is bounded by that"
                        + " position, and its source cursor advances only once, so an unbounded read loses"
                        + " rows permanently rather than temporarily");
            }
        }
        requireAcyclic(declaration, read);
        return read;
    }

    /**
     * Refuses a reference that would close a cycle over the edges {@code target_enrichment → enrichments
     * read}. Walks forward from each enrichment this pipeline reads, looking for a path back to this pipeline's own
     * target; a pipeline reading its own target is the length-zero case and is caught by the same walk.
     */
    private void requireAcyclic(EnrichDeclaration declaration, Set<String> read) {
        String target = declaration.target();
        Deque<String> pending = new ArrayDeque<>(read);
        Set<String> seen = new LinkedHashSet<>(read);
        while (!pending.isEmpty()) {
            String enrichment = pending.poll();
            if (enrichment.equals(target)) {
                throw new PipelineValidationException("the pipeline targeting '" + target + "' reads enrichment '"
                        + String.join("', '", read) + "', which depends on '" + target
                        + "' — the reference closes a dependency cycle, and each pipeline's read window would"
                        + " then be bounded by the other's position, freezing both permanently");
            }
            for (String next : enrichmentsReadBy(enrichment)) {
                if (seen.add(next)) {
                    pending.add(next);
                }
            }
        }
    }

    /**
     * The enrichments the pipeline targeting {@code enrichment} reads, resolved the same way
     * {@link #validate} resolves them: a stored declaration's field references against its own read
     * source's entity. A pipeline whose declaration no longer resolves contributes no edges rather than
     * failing this gate — the graph walk must not turn an unrelated pipeline's drift into a refusal for the
     * declaration being registered.
     *
     * <p>Read as the detached projection rather than through the entity, so a spec of either kind is
     * readable through one query: an AGGREGATE writer contributes no bindings and so no edges, which is the
     * correct answer here rather than a special case.
     */
    private Set<String> enrichmentsReadBy(String enrichment) {
        return pipelines.findByTarget(enrichment)
                .map(PipelineDeclaration::of)
                .map(this::referencedEnrichments)
                .orElseGet(Set::of);
    }

    /**
     * The enrichments a <b>stored</b> pipeline's declaration reaches, resolved against its own read source's
     * entity. Public because two callers outside the gate need the same answer: the dependency-graph walk
     * above, and the freshness diagnostics a pipeline read reports.
     *
     * <p>Resolved from the declaration's field names rather than by re-running the SQL front end, which
     * would cost a translation per node of the walk. A qualified name is the only spelling an enrichment
     * column has, so scanning the stored fragments for {@code "<enrichment>.<column>"} tokens finds exactly
     * the enrichment references — and a token that resolves to nothing is dropped by the entity lookup.
     *
     * <p>A pipeline whose read source no longer resolves contributes nothing rather than throwing: neither
     * caller may turn one pipeline's drift into a failure for an unrelated read.
     */
    public Set<String> referencedEnrichments(EnrichDeclaration declaration) {
        return referencedEnrichments(declaration.projection());
    }

    /** As above, over a detached projection — see {@link PipelineDeclaration}. */
    public Set<String> referencedEnrichments(PipelineDeclaration declaration) {
        // Does not throw, whatever the stored declaration holds — the same contract as the aggregate twin,
        // and for the same reason: both callers (the catalog's dependency guards and a state read's freshness
        // report) sit inside a transaction whose owner assumes it cannot fail here, and a draft or a drifted
        // declaration must not fail an unrelated table delete, schema patch or pipeline read.
        try {
            String source = readSourceOf(declaration).orElse(null);
            if (source == null) {
                return Set.of();
            }
            return resolveReferencedEnrichments(declaration, source);
        } catch (RuntimeException ex) {
            return Set.of();
        }
    }

    /**
     * As {@link #referencedEnrichments(PipelineDeclaration)}, but <b>refusing</b> rather than degrading
     * when the declaration's read source no longer resolves.
     *
     * <p>Returning the empty set there is right for a diagnostic and for a delete guard, and wrong for the
     * laundering subset rule: "this declaration cannot be read" and "this declaration reads no enrichment"
     * are then the same answer, and the second accepts a grant the rule exists to refuse.
     */
    public Set<String> referencedEnrichmentsOrThrow(EnrichDeclaration declaration) {
        return referencedEnrichmentsOrThrow(declaration.projection());
    }

    /** As above, over a detached projection — see {@link PipelineDeclaration}. */
    public Set<String> referencedEnrichmentsOrThrow(PipelineDeclaration declaration) {
        String source = readSourceOf(declaration).orElseThrow(() -> new IllegalStateException(
                "pipeline '" + declaration.name() + "' declares no resolvable read source"));
        return resolveReferencedEnrichments(declaration, source);
    }

    private Set<String> resolveReferencedEnrichments(PipelineDeclaration declaration, String source) {
        EntityFields entity = entityFields.enrichEntityOf(source);
        return declaredFieldsOf(declaration).stream()
                .filter(entity::isEnrichmentSourced)
                .map(entity::ownerOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The table a stored pipeline reads: its declared source, else its target enrichment's. */
    public Optional<String> readSourceOf(EnrichDeclaration declaration) {
        return readSourceOf(declaration.projection());
    }

    /** As above, over a detached projection. */
    public Optional<String> readSourceOf(PipelineDeclaration declaration) {
        String declared = StringUtils.trimToNull(declaration.inputTable());
        return declared != null
                ? Optional.of(declared)
                : definitions.findById(declaration.target())
                        .map(TableDefinitionEntity::getSourceTable);
    }

    /**
     * Every field a stored declaration <b>may read</b>, source columns included — {@link #declaredFieldsOf}
     * plus every identifier in its SQL fragments.
     *
     * <p>{@link #declaredFieldsOf} answers the enrichment question and was mistaken for this one: it scans
     * SQL fragments for qualified {@code table.column} tokens only, so a bare source reference —
     * {@code length(deployment)}, {@code deployment = 'x'} — is invisible to it. One caller needs the
     * source half too: the schema guard refusing a rename of, or onto, a column a declaration reads. It wants
     * an over-approximation and not an under one — a spurious refusal on a column nobody reads rather than a
     * rename that misses a real read — so every identifier counts. See
     * {@link QualifiedColumnTokens#identifiers}.
     */
    public Set<String> readFieldsOf(PipelineDeclaration declaration) {
        final Set<String> fields = new LinkedHashSet<>(declaredFieldsOf(declaration));
        fields.addAll(QualifiedColumnTokens.identifiers(declaration.filterSql()));
        if (declaration.readyWhen() != null) {
            fields.addAll(QualifiedColumnTokens.identifiers(declaration.readyWhen().getSignal()));
        }
        if (declaration.memberSelect() != null) {
            fields.addAll(QualifiedColumnTokens.identifiers(declaration.memberSelect().getPreferSql()));
        }
        final EnrichSpec.TransformSpec transform = declaration.transform(specCodec);
        if (transform != null) {
            for (TransformVar var : transform.effectiveOutputVars()) {
                fields.addAll(QualifiedColumnTokens.identifiers(var.getSql()));
            }
        }
        return fields;
    }

    /**
     * Every field name a stored pipeline's declaration mentions — for the catalog's dependency checks —
     * gathered without re-running the SQL front end.
     * A qualified name is the only spelling an enrichment column has, so scanning the stored fragments for
     * {@code "<enrichment>.<column>"} tokens finds exactly the enrichment references the graph needs —
     * and a name that resolves to nothing is dropped by the caller's {@code isEnrichmentSourced} filter.
     *
     * <p>The <b>transform's</b> output expressions are scanned alongside the declaration's own
     * fragments, because that is where a {@code type=sql} declaration's column references live. Omitting
     * them would make a Class-1 pipeline that derives from a sibling enrichment — the case this capability
     * exists to allow — invisible to the dependency-cycle walk, to the catalog's drop and rename checks,
     * and to the freshness diagnostics, all three of which then report a pipeline as reading nothing while
     * its {@code INSERT … SELECT} joins the enrichment.
     */
    public Set<String> declaredFieldsOf(PipelineDeclaration declaration) {
        Set<String> fields = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(declaration.triggerGroupBy())) {
            fields.add(declaration.triggerGroupBy());
        }
        // Decoded once. Each call parses the stored `spec` JSON, and this runs over EVERY stored
        // declaration for each of the catalog's dependency questions.
        List<InputBinding> inputBindings = declaration.inputBindings(specCodec);
        if (inputBindings != null) {
            for (InputBinding binding : inputBindings) {
                if (StringUtils.isNotBlank(binding.getColumn())) {
                    fields.add(binding.getColumn());
                }
            }
        }
        MemberSelect select = declaration.memberSelect();
        if (select != null && select.getOrderBy() != null) {
            for (MemberOrder order : select.getOrderBy()) {
                if (StringUtils.isNotBlank(order.getColumn())) {
                    fields.add(order.getColumn());
                }
            }
        }
        fields.addAll(QualifiedColumnTokens.of(declaration.filterSql()));
        if (declaration.readyWhen() != null) {
            fields.addAll(QualifiedColumnTokens.of(declaration.readyWhen().getSignal()));
        }
        if (select != null) {
            fields.addAll(QualifiedColumnTokens.of(select.getPreferSql()));
        }
        fields.addAll(outputExpressionTokens(declaration));
        return fields;
    }

    /**
     * The {@code table.column} tokens this declaration's output expressions mention — a
     * {@code transform.type: sql} declaration's only column references, and empty for every other shape
     * (an {@code llm} transform's outputs carry JSONata, not SQL).
     *
     * <p><b>Read through the output-shape accessor, not through {@code outputs} directly.</b> This feeds
     * the catalog's drop-and-rename dependency guard, so a folded legacy declaration whose expressions
     * live in {@code output_vars} must contribute its edges too — a guard reading {@code outputs} alone
     * would contribute <b>zero</b> for such a pipeline and let an operator drop or rename a column its
     * {@code INSERT … SELECT} joins. That is silent data loss at the DDL, not a failed request.
     *
     * <p>A declaration that carries no transform contributes nothing rather than throwing, for the same
     * reason a pipeline whose read source has drifted does: neither the graph walk nor a catalog check may
     * turn one pipeline's drift into a failure for an unrelated operation.
     */
    private Set<String> outputExpressionTokens(PipelineDeclaration declaration) {
        final EnrichSpec.TransformSpec transform = declaration.transform(specCodec);
        if (transform == null) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (TransformVar var : transform.effectiveOutputVars()) {
            tokens.addAll(QualifiedColumnTokens.of(var.getSql()));
        }
        return tokens;
    }


    /**
     * The target enrichment's grain key, resolved once from the definition {@link #validate} already holds
     * and threaded to every check that needs it — the read-source, group-trigger and Class-1 grain rules all
     * key off the same value, and re-reading it per check invited them to disagree.
     *
     * <p>An ACTIVE enrichment always carries one (defining its schema is what makes it ACTIVE, and that is
     * where the grain key is required), so the guard is for a definition that somehow reached this gate
     * without one rather than an expected outcome.
     */
    private static String grainKeyOf(TableDefinitionEntity target) {
        if (StringUtils.isBlank(target.getGrainKey())) {
            throw new PipelineValidationException(
                    "target_enrichment '" + target.getName() + "' has no grain key");
        }
        return target.getGrainKey();
    }

    /**
     * <b>Derives</b> the output mapping from the transform's outputs and the target's columns, and writes
     * it into the declaration's spec. It is no longer declared by the caller: {@code output_bindings} is
     * not accepted on the wire, because it carried no information — all 54 live bindings were
     * {@code column == var}, with nothing cross-checking the two copies.
     *
     * <p><b>This is also where an output is validated against the target</b>, which is now possible at
     * every declaration write: {@code TargetOutputColumns.requireExposed} refuses an output resolving to no
     * exposed column of the target, naming the output and the target. There is nothing left to defer to a
     * later binding, because the target is in scope wherever the declaration is written.
     *
     * <p><b>Why the derived list is still stored.</b> Three ADAS paths read it and all three fail
     * <em>closed</em>: {@code EnrichWriteValidator} builds the runner's write allow-list as
     * {@code {bindings} ∪ {grain} ∪ {provenance}} (an empty list 422s every row write),
     * {@code MaterializeService#analyzeExpressions} throws on an empty map, and the Class-1 arm refuses a
     * pipeline that binds nothing. Deriving on read instead would have moved all three onto a resolution
     * that {@link com.epam.aidial.analytics.service.enrichment.EnrichResolver} deliberately does not
     * perform for a write (it omits the target definition to save two to three queries
     * per write). Computing the list here, once per register/patch, leaves every one of those paths
     * untouched — so the exact write allow-list stays exact — and keeps a code rollback unconditional,
     * because the stored bytes are the shape the previous build wrote.
     *
     * <p>The cost is that the stored copy can go stale against its target, which is why the catalog
     * notifies the registry when an enrichment target's columns change and re-derives it there.
     *
     * <p>A binding's {@code column} is the target's physical {@code source_name}: both write paths address
     * the target physically — the Class-1 materialize statement renders it into an {@code INSERT} column
     * list, and the pipeline-scoped write-back reconciles caller rows against the target's physical
     * columns — so resolving once here keeps a renamed column writable instead of failing at execution.
     *
     * <p>The <b>type check is kept only where a declared token exists</b>, which is a folded legacy
     * {@code output_vars} declaration. A new-shape output declares none, and the two checks the token used to sit
     * between collapse into one: the expression's inferred type against this column's, in the Class-1 arm.
     * That collapse can only loosen, never tighten — compatibility is equality, or both numeric, or
     * {@code string → enum}, and over those three rules it is transitive, so everything the two-hop check
     * accepts the single check still accepts.
     *
     * @return the resolved target column per output name, so the Class-1 arm can check each expression's
     *     inferred type against the column that receives it
     */
    private Map<String, ColumnMappingEntity> deriveOutputBindings(EnrichDeclaration declaration,
            TableDefinitionEntity target, EnrichSpec.TransformSpec transform) {
        List<ColumnMappingEntity> targetColumns = columns.findByTableNameOrderByOrdinal(target.getName());
        List<TransformVar> vars = transform.effectiveOutputVars();
        Map<String, ColumnMappingEntity> columnByVar = new LinkedHashMap<>();
        List<OutputBinding> derived = new ArrayList<>(vars.size());
        Set<String> boundColumns = new LinkedHashSet<>();
        for (TransformVar var : vars) {
            ColumnMappingEntity column =
                    TargetOutputColumns.requireExposed(var.getName(), targetColumns, target.getName());
            if (StringUtils.isNotBlank(var.getType())) {
                requireDeclaredTypeFits(var, column);
            }
            OutputBinding binding = new OutputBinding();
            binding.setColumn(column.getSourceName());
            binding.setVar(var.getName());
            if (!boundColumns.add(binding.getColumn())) {
                throw new PipelineValidationException("target column '" + binding.getColumn()
                        + "' receives more than one transform output; a column takes exactly one");
            }
            derived.add(binding);
            columnByVar.put(var.getName(), column);
        }
        if (derived.isEmpty()) {
            throw new PipelineValidationException("the transform must declare at least one output that"
                    + " resolves to a column on the target '" + target.getName() + "'; with none there is"
                    + " nothing to write, and the pipeline would silently do no work");
        }
        declaration.spec().setOutputBindings(derived);
        return columnByVar;
    }

    /**
     * The declared-token check, for a folded legacy output variable only. Kept rather than dropped so a
     * pipeline pinning such a version keeps exactly the verdict it has today — including the parameterized
     * {@code array<string>} refusal, whose message names the token to write instead.
     */
    private void requireDeclaredTypeFits(TransformVar var, ColumnMappingEntity column) {
        QueryFieldType varType;
        try {
            varType = typeMapping.resolve(var.getType());
        } catch (IllegalArgumentException ex) {
            throw new PipelineValidationException("output variable '" + var.getName() + "' has an unusable"
                    + " type '" + var.getType() + "': " + ex.getMessage());
        }
        if (!typeMapping.isCompatible(varType, column.getType())) {
            throw new PipelineValidationException("target column '" + column.getName()
                    + "' (type " + column.getType().code() + ") is incompatible with output variable '"
                    + var.getName() + "' (type " + varType.code() + ")");
        }
    }

    /**
     * The sensitive-column gate, resolved through the read source's entity so an enrichment column reached
     * through the grain join is governed exactly as one of the source's own. The refusal names the table
     * the column actually belongs to, which for an enrichment column is not the read source.
     */
    private void checkEntitlement(Set<String> referenced, EntityFields entity, String source, String what) {
        for (String column : referenced) {
            // Per owning table: within one flat entity the caller may be entitled to the source's sensitive
            // columns and not an enrichment's, so the question is asked of the table the column belongs to.
            // Read AND clearance: authoring is gated on REGISTRY_ADMIN, not on any read of the table, so the
            // clearance alone would admit an author who could not read the values it is declaring over.
            if (entity.isSensitive(column) && !sensitiveAccess.mayReadSensitive(entity.ownerOf(column))) {
                throw new SensitiveColumnNotEntitledException(what + " references sensitive column '"
                        + column + "' on '" + entity.ownerOf(column)
                        + "'; referencing it requires TABLE_READ and TABLE_SENSITIVE on that table");
            }
        }
    }

    private void checkBoundBindings(
            EnrichDeclaration declaration, EntityFields entity, String source, Set<String> referenced) {
        for (InputBinding binding : declaration.inputBindings()) {
            if (StringUtils.isBlank(binding.getColumn())) {
                continue;
            }
            String column = binding.getColumn();
            if (!entity.contains(column)) {
                throw new PipelineValidationException(
                        unknownField("vars column", column, source, entity));
            }
            referenced.add(column);
            if (entity.isSensitive(column) && !sensitiveAccess.mayReadSensitive(entity.ownerOf(column))) {
                throw new SensitiveColumnNotEntitledException("a vars entry references sensitive column '"
                        + column + "' on '" + entity.ownerOf(column)
                        + "'; referencing it requires TABLE_READ and TABLE_SENSITIVE on that table");
            }
        }
    }

    /**
     * A group key SHALL resolve to the target enrichment's grain key: equal to it, or the qualified flat
     * name of an enrichment column whose last segment is it. Whichever it names, the derived rows are keyed
     * by the plain grain key, because that is what the target is physically keyed on and collapses by —
     * which is why the reads project an enrichment-sourced key under the plain name.
     */
    private void requireGroupByResolvesToGrain(
            EnrichDeclaration declaration, EntityFields entity, String grainKey, String source) {
        String groupBy = declaration.triggerGroupBy();
        if (StringUtils.isBlank(groupBy)) {
            throw new PipelineValidationException("a group pipeline requires group_by");
        }
        // The bare grain key is only a valid group_by when the READ SOURCE itself declares it. When the key
        // is reachable only through an enrichment, the bare spelling resolves to nothing the statement can
        // project — it would render a column absent from the FROM clause — so it is refused here with the
        // qualified name to write instead, rather than accepted and failed at the first scan.
        boolean bareResolvesOnSource = entity.contains(groupBy) && !entity.isEnrichmentSourced(groupBy);
        if (!grainKey.equals(groupBy) || !bareResolvesOnSource) {
            if (!entity.contains(groupBy) || !entity.isEnrichmentSourced(groupBy)
                    || !EntityFields.lastSegment(groupBy).equals(grainKey)) {
                // A bare name an enrichment declares is the likely mistake, so say what to write instead.
                Optional<String> qualified = entity.names().stream()
                        .filter(entity::isEnrichmentSourced)
                        .filter(name -> EntityFields.lastSegment(name).equals(groupBy))
                        .findFirst();
                throw new PipelineValidationException("group_by '" + groupBy
                        + "' must resolve to the target enrichment's grain key '" + grainKey
                        + "'; the enrichment is keyed on and collapses by its grain"
                        + qualified.map(name -> ". An enrichment column has no unqualified spelling in the"
                                + " entity namespace — name it '" + name + "'").orElse(""));
            }
        }
        requireAbsenceRepresentable("group_by", groupBy, entity);
    }

    /**
     * A computed field may be bound as a transform input and named nowhere else: its value is completed after a read's
     * statement runs, so no group key, ranking or fragment can use it.
     */
    private static void requireNotComputed(String what, String name, EntityFields entity) {
        if (entity.isComputed(name)) {
            throw new PipelineValidationException(ComputedFields.declarationMessage(what, name));
        }
    }

    /**
     * A value-producing reference to an enrichment column requires that column to be able to hold the
     * <b>absence</b> of an enrichment row, which for every scalar type means being declared nullable. The
     * condition is {@link EntityFields#absenceIsUnrepresentable}, shared with the aggregate path; only the
     * refusal is the enrich path's own.
     */
    private void requireAbsenceRepresentable(String what, String name, EntityFields entity) {
        if (!entity.absenceIsUnrepresentable(name)) {
            return;
        }
        throw new PipelineValidationException(what + " '" + name + "' is an enrichment column declared"
                + " non-nullable, and a pipeline may not derive a value from one. It is read through an outer"
                + " grain join, and ClickHouse renders an unmatched non-nullable column as its type default"
                + " rather than as NULL (join_use_nulls defaults to 0), so every source row the enrichment"
                + " has not reached would contribute that default — an empty string, a zero, an epoch date"
                + " — indistinguishable from a derived value. Declare '" + EntityFields.lastSegment(name)
                + "' nullable on enrichment '" + entity.ownerOf(name) + "' so absence reads as NULL, or read"
                + " it in a filter (where it selects rows instead of producing a value)");
    }

    /**
     * The refusal for a field reference that resolves to nothing, naming the enrichment when the reference
     * was qualified with one. A declaration addresses an enrichment column as
     * {@code "<enrichment>.<column>"}, so a typo can be in either half — and "is not a column of source
     * 'dial_usage_log'" is a misleading answer for a name never meant to be a column of the source at all.
     */
    private static String unknownField(String what, String name, String source, EntityFields entity) {
        int dot = name == null ? -1 : name.indexOf('.');
        if (dot > 0) {
            String owner = name.substring(0, dot);
            if (entity.enrichmentOwners().contains(owner)) {
                return what + " '" + name + "' is not a declared column of enrichment '" + owner
                        + "', which is reached through source '" + source + "'";
            }
        }
        String qualified = entity.enrichmentOwners().isEmpty() ? ""
                : "; an enrichment column is named '<enrichment>.<column>', one of "
                        + String.join(", ", entity.enrichmentOwners());
        return what + " '" + name + "' is not a declared column of source '" + source + "'" + qualified;
    }

    /**
     * Holds the transform's {@code request_template} to its own {@code inputs}, under the rule for this
     * pipeline's grain — see {@link TemplatePlaceholderRule}, which owns the rule and explains why it
     * cannot be grain-blind.
     *
     * <p>{@code llm} only: a {@code sql} transform has no template and declares no inputs.
     *
     * <p><b>Run at arm and at preview, not on every declaration write.</b> This is the one referential
     * check between two members an author fills in at different times, so refusing it at write would make
     * the registry unusable as a place to compose a declaration — the "create == publish" trap the fold
     * exists to remove, reappearing one field lower down. See the class javadoc.
     */
    private static void validateTemplatePlaceholders(
            EnrichDeclaration declaration, EnrichSpec.TransformSpec transform) {
        final Set<String> varNames = declaration.inputBindings().stream()
                .map(InputBinding::getVar)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        TemplatePlaceholderRule.validate(
                transform.getRequestTemplate(), varNames, declaration.triggerKind());
    }

    private void parseJsonata(EnrichDeclaration declaration, EnrichSpec.TransformSpec transform) {
        for (InputBinding binding : declaration.inputBindings()) {
            parseExpression(binding.getJsonata(), "transform.inputs entry '" + binding.getVar() + "'");
        }
        for (TransformVar var : transform.effectiveOutputVars()) {
            parseExpression(var.getJsonata(), "output variable '" + var.getName() + "'");
        }
    }

    private void parseExpression(String expression, String where) {
        if (StringUtils.isBlank(expression)) {
            return;
        }
        try {
            Jsonata.jsonata(expression);
        } catch (RuntimeException ex) {
            throw new PipelineValidationException("JSONata for " + where + " does not parse: " + ex.getMessage());
        }
    }

    private static Optional<ColumnMappingEntity> findColumn(List<ColumnMappingEntity> columns, String name) {
        return columns.stream()
                .filter(c -> c.getName().equals(name) || c.getSourceName().equals(name))
                .findFirst();
    }

}
