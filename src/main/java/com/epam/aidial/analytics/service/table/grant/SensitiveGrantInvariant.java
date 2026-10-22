package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.exception.SensitiveGrantWidensException;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.PipelineInputDependents;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.ProviderRolePrivileges;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place the laundering invariant is stated: <b>a sensitive column of a derived table is never
 * granted more widely than the same values on the table it derives from</b>. A table that carries a
 * sensitive column may list a role under {@code sensitive} only if every table it reads lists that role
 * under both {@code read} and {@code sensitive}.
 *
 * <p><b>Why the two sides are read differently.</b> The clearance confers nothing alone, so on the source
 * side only a role holding it <em>and</em> a read could see the values a derived column carries. On the
 * derived side every role in the {@code sensitive} list counts, whatever its {@code read} grant there,
 * because the role may hold {@code TABLE_READ} globally. The {@code modify} list is never consulted:
 * without the clearance it reaches no sensitive column, and with it the role is on the list checked here.
 * One consequence is accepted rather than closed: a holder of {@code modify} and {@code sensitive} on a
 * derived table may clear a derived column's flag — through the grant lists that holder is, by this very
 * rule, a sensitive reader of every table it reads.
 *
 * <p><b>A globally cleared role is bounded through its {@code read} grant.</b> The clearance can be held
 * globally without any read — a state the old {@code TABLE_READ_SENSITIVE}, which carried its read, could
 * not express. Such a role needs only a {@code read} grant on the derived table to read its sensitive
 * values, and appears on no {@code sensitive} list for the check above to see. So a role in the derived
 * {@code read} list that {@code roles-mapping} maps to global {@code TABLE_SENSITIVE} is held to the same
 * bar: every table read must list it under {@code read}. This is the one place the mapping is consulted,
 * and only to <em>refuse</em> — never to exempt, for the reason below. It is checked when grants or
 * schema change, not when the mapping does: remapping a role to global {@code TABLE_SENSITIVE} after its
 * derived {@code read} grant was stored is not re-examined, and is an operator's decision to make knowingly.
 *
 * <p>While sensitive-column entitlement was a single global role, declaring the derived column
 * {@code sensitive} was a uniform barrier and nothing like this was needed. Per-table entitlement is what
 * separates the two grantee sets and opens the gap.
 *
 * <p><b>The relation is "reads from", not "is a child of".</b> That distinction is the whole correctness
 * argument, because catalog parentage and the read edge are three different things:
 *
 * <ul>
 *   <li>an {@code ENRICHMENT}'s {@code source_table} — the catalog edge, and the only one the first
 *       version of this rule followed;</li>
 *   <li>an {@code ENRICH} pipeline's declared {@code input_table}, which may name <b>any</b> ACTIVE
 *       source (see {@code EnrichSpecValidator.requireReadSource} — it requires the grain key and
 *       scannability, never that the source equal the target's parent). So a pipeline may copy a
 *       sensitive column of {@code s2} into an enrichment of {@code s1}, and comparing against
 *       {@code s1} checks the wrong table;</li>
 *   <li>an {@code AGGREGATE} pipeline's {@code input_table}, whose rollup target is a {@code SOURCE}
 *       with no catalog parent at all — a first-class queryable entity carrying its own
 *       {@code sensitive} list, reachable without any grant on the table it rolls up;</li>
 *   <li>and an <b>enrichment reached through the read source's grain join</b>. A declaration addresses
 *       such a column as {@code "<enrichment>.<column>"}, so the table whose values are copied appears
 *       nowhere in the pipeline row: its {@code input_table} is the source, and the enrichment is only
 *       in the parsed declaration. This edge is why {@link PipelineInputDependents} exists at all, and
 *       missing it left the same disclosure the {@code input_table} edge closed, reached one join
 *       further out — a rollup of {@code usage_client_identity.user_email} into a target granted more
 *       widely than that enrichment, readable by a caller with no grant on it.</li>
 * </ul>
 *
 * <p>So the source side is the union of the catalog edge, the pipeline edge
 * ({@code PipelineRepository.findEffectiveInputTableOfTarget}) and the grain-join edge
 * ({@code PipelineInputDependents.enrichmentsReadByPipelineTargeting}); the derived side is the union of
 * {@code findBySourceTable}, {@code findTargetsByInputTable} and
 * {@code PipelineInputDependents.targetsReadingEnrichment}. The pipeline edge is read as a <b>name</b>
 * through a scalar projection rather than by loading {@code PipelineEntity}, for the dirty-check reason
 * those queries document: a pipeline loaded inside the transaction editing a grant would flush a
 * full-row {@code UPDATE} and open an Envers revision on a pipeline nobody touched.
 *
 * <p>{@link PipelineInputDependents} is injected {@code @Lazy}, and that is structural rather than
 * stylistic: it is implemented by a registry component that depends on both pipeline validators, each of
 * which depends on this class, so an eager injection closes a bean cycle. The interface itself exists to
 * invert that dependency; a lazy proxy completes the inversion.
 *
 * <p>The relation is deliberately <b>not</b> narrowed to "a source that itself carries a sensitive
 * column". An author may declare a derived column {@code sensitive} whose origin column is not flagged —
 * that is the ordinary shape on the aggregate path, where a rollup of a protected value is what the flag
 * is for — so a source with no flagged column of its own can still be where protected values come from.
 * The cost is that a target carrying a sensitive column must hold its {@code sensitive} grants within
 * <em>every</em> table it reads, enrichments included, and the remedy is the same grant on that table.
 *
 * <p><b>Every way a pair can drift is checked, from here.</b> There are four, and checking only the
 * obvious one is how the first version of this rule stayed broken:
 *
 * <ul>
 *   <li>the derived table's list is <b>widened</b> — {@link #requireNotWidened(TableDefinitionEntity,
 *       TableAccess)} on a grant edit to it;</li>
 *   <li>a source's list is <b>narrowed</b>, leaving the derived table's untouched and now wider — the
 *       same call, which also walks everything derived from an edited table;</li>
 *   <li>the derived table <b>gains</b> a sensitive column, so a pair that had nothing to protect suddenly
 *       does — {@link #requireNotWidenedWithNewSensitiveColumn} from both schema paths, for a column newly
 *       marked sensitive, one added already flagged, and one declared in the initial
 *       {@code POST .../schema};</li>
 *   <li>a <b>new or patched pipeline</b> introduces a read edge that did not exist when the grants were
 *       written — {@link #requireNotWidenedAgainst} from the enrich and aggregate validators, which have
 *       the declared source in hand before it is persisted and so cannot be found by the queries
 *       above.</li>
 * </ul>
 *
 * <p>A fifth path existed until the evaluator fold: registering a new evaluator version changed what a
 * stored pipeline resolving {@code latest} read, writing neither a grant list nor a {@code sensitive}
 * flag. The fold moved the transform onto the pipeline row, so that edit is now the fourth bullet — a
 * pipeline patch — and reaches the validators like any other. The author-entitlement check those
 * validators run matters more there than this rule does, because this rule is <em>vacuous</em> whenever
 * the target carries no sensitive column.
 *
 * <p><b>The condition is "the derived table carries a sensitive column"</b>, not "some pipeline derives one
 * from a sensitive source column". The narrower reading states the risk more precisely, but it is not
 * answerable at the moment a grant is edited without enumerating every pipeline targeting the table and
 * resolving what each reads — and a predicate that holds at registration and not at grant time is exactly
 * the split that let the narrowing direction go unchecked. One condition evaluated identically at every
 * entry point is worth being conservative for: the cost is refusing a {@code sensitive} grant on a
 * derived table whose sensitive column happens not to be derived from a protected one, and the remedy is
 * the same grant on the table it reads.
 *
 * <p><b>A role is exempt only if the source's own lists name it.</b> Not if some configured
 * {@code roles-mapping} maps that role name to global {@code TABLE_READ} or {@code TABLE_SENSITIVE}: that lookup is a union
 * across providers and is documented as advice, so it would exempt a role name on the strength of a
 * mapping that a caller arriving through a different provider does not have. Granting a globally-entitled
 * role is reported as an ineffective grant anyway, so the operator's remedy is to leave it out of the list
 * rather than to have the rule guess.
 *
 * <p>This constrains <b>grants</b>, not the {@code sensitive} flag. Derived enrichment columns
 * deliberately do not inherit sensitivity (see the {@code enrichment-registry} capability), because
 * registration never mutates catalog metadata. So the invariant bites exactly where an author
 * <em>did</em> declare a column of the derived table sensitive — which, on the aggregate path,
 * {@code AggregateSpecValidator.requireSensitivityPropagated} makes mandatory whenever the column derives
 * from a sensitive input.
 *
 * <p>It runs in {@code none} mode too, unlike the privilege checks around it. Those decide <em>who may
 * act</em> and must fail open where nothing is enforced; this decides <em>what stored state may exist</em>,
 * and a grant document written while security was off goes live the moment a deployment switches to
 * {@code oidc}, when refusing it would be too late. The carve-out is stated in the
 * {@code table-access-control} capability.
 */
@Component
public class SensitiveGrantInvariant {

    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final PipelineRepository pipelines;
    private final PipelineInputDependents pipelineReads;
    private final ProviderRolePrivileges providerRolePrivileges;

    public SensitiveGrantInvariant(TableDefinitionRepository definitions, ColumnMappingRepository columns,
            PipelineRepository pipelines, @Lazy PipelineInputDependents pipelineReads,
            ProviderRolePrivileges providerRolePrivileges) {
        this.definitions = definitions;
        this.columns = columns;
        this.pipelines = pipelines;
        this.pipelineReads = pipelineReads;
        this.providerRolePrivileges = providerRolePrivileges;
    }

    /**
     * Asserts the invariant for every pair {@code definition} belongs to — as the derived side against
     * everything it reads, and as the source side for everything derived from it — with {@code pending}
     * standing in for its grant lists.
     *
     * <p>{@code pending} rather than the entity's own {@code access} so the caller can validate
     * <b>before</b> mutating: applying the merge first dirties a managed, {@code @Audited} entity, and the
     * queries below force-flush that {@code UPDATE} — opening an Envers revision — before the rule has
     * decided.
     *
     * @param definition the table whose grants are being edited
     * @param pending    the grant lists it would have
     * @throws SensitiveGrantWidensException when some derived table's {@code sensitive} list would
     *                                       exceed that of a table it reads
     */
    public void requireNotWidened(TableDefinitionEntity definition, TableAccess pending) {
        // Guarded rather than left to requirePair's own precondition: sourcesOf parses every stored
        // declaration to find the grain-join edge, and a table with no sensitive column can never be the
        // derived side of a non-vacuous pair, so the scan would be issued to reach a decision already made.
        if (hasSensitiveColumn(definition.getName())) {
            for (final String source : sourcesOf(definition)) {
                requireSubset(definition, grantsOf(definition, pending), source, null);
            }
        }
        for (final TableDefinitionEntity derived : derivedFrom(definition)) {
            requirePair(derived, null, definition.getName(), pending);
        }
    }

    /**
     * The same invariant for a table that is <b>about to</b> carry a sensitive column, before the column
     * exists. The entry point above skips a pair with no sensitive column, which is right there and wrong
     * here: a schema change that first marks a column {@code sensitive} — by patching an existing one, by
     * adding one already flagged, or by declaring one in the initial schema — is the moment the pair first
     * has something to protect, and checking afterwards would be checking a state already written.
     *
     * @throws SensitiveGrantWidensException when the table's {@code sensitive} list already exceeds
     *                                       that of a table it reads
     */
    public void requireNotWidenedWithNewSensitiveColumn(TableDefinitionEntity table) {
        for (final String source : sourcesOf(table)) {
            requireSubset(table, grantsOf(table, null), source, null);
        }
    }

    /**
     * The same invariant against a read source that is <b>being declared</b> rather than stored — the
     * pipeline validators' entry point.
     *
     * <p>Needed because a registration is the one moment the read edge cannot be queried: the pipeline row
     * does not exist yet, so {@link #sourcesOf} cannot see the source the declaration names. The validator
     * has already resolved it, and passes it here.
     *
     * @param derived    the pipeline's target table
     * @param sourceName the table the declaration reads
     * @throws SensitiveGrantWidensException when the target's stored {@code sensitive} list exceeds
     *                                       that source's
     */
    public void requireNotWidenedAgainst(TableDefinitionEntity derived, String sourceName) {
        if (StringUtils.isBlank(sourceName) || sourceName.equals(derived.getName())) {
            return;
        }
        requirePair(derived, null, sourceName, null);
    }

    /**
     * {@link #requireNotWidenedAgainst(TableDefinitionEntity, String)} for every table a declaration
     * reads — its read source and each enrichment it reaches through that source's grain join.
     *
     * @param derived     the pipeline's target table
     * @param sourceNames the tables the declaration reads; blanks and self-references are ignored
     */
    public void requireNotWidenedAgainst(TableDefinitionEntity derived, Collection<String> sourceNames) {
        for (final String sourceName : sourceNames) {
            requireNotWidenedAgainst(derived, sourceName);
        }
    }

    /**
     * Every table {@code definition} reads: its catalog parent when it is an enrichment, the effective read
     * source of the pipeline writing it, and each enrichment that pipeline reaches through the grain join.
     * Usually one table, and for an aggregate rollup target never the first.
     */
    private Set<String> sourcesOf(TableDefinitionEntity definition) {
        final Set<String> sources = new LinkedHashSet<>();
        if (definition.getType() == TableType.ENRICHMENT && StringUtils.isNotBlank(definition.getSourceTable())) {
            sources.add(definition.getSourceTable());
        }
        pipelines.findEffectiveInputTableOfTarget(definition.getName())
                .filter(StringUtils::isNotBlank)
                .ifPresent(sources::add);
        sources.addAll(pipelineReads.enrichmentsReadByPipelineTargeting(definition.getName()));
        sources.remove(definition.getName());
        return sources;
    }

    /**
     * Every table derived from {@code definition}: its catalog children, and the target of every pipeline
     * declaring it as their read source.
     *
     * <p>The catalog query is skipped for an {@code ENRICHMENT}, which can never be a catalog parent — its
     * own {@code source_table} must name a {@code SOURCE} — so the query would be issued for a provably
     * empty result on every grant edit to one. The pipeline query is <b>not</b> skipped: an enrichment is
     * not a parent, but that says nothing about what a pipeline declares as its read source.
     */
    private List<TableDefinitionEntity> derivedFrom(TableDefinitionEntity definition) {
        final String name = definition.getName();
        final Map<String, TableDefinitionEntity> derived = new LinkedHashMap<>();
        if (definition.getType() != TableType.ENRICHMENT) {
            for (final TableDefinitionEntity child : definitions.findBySourceTable(name)) {
                derived.put(child.getName(), child);
            }
        }
        final Set<String> byPipeline = new LinkedHashSet<>(pipelines.findTargetsByInputTable(name));
        if (definition.getType() == TableType.ENRICHMENT) {
            // Only an enrichment is reachable through a grain join, and only this direction needs the
            // parsed declarations — a source's readers are its own input_table rows, found above.
            byPipeline.addAll(pipelineReads.targetsReadingEnrichment(name));
        }
        for (final String target : byPipeline) {
            if (!derived.containsKey(target)) {
                definitions.findById(target).ifPresent(table -> derived.put(target, table));
            }
        }
        derived.remove(name);
        return List.copyOf(derived.values());
    }

    /**
     * One pair. A derived table with no sensitive column has nothing to protect, so the pair is vacuously
     * fine.
     */
    private void requirePair(TableDefinitionEntity derived, TableAccess derivedGrants,
            String sourceName, TableAccess sourceGrants) {
        if (!hasSensitiveColumn(derived.getName())) {
            return;
        }
        requireSubset(derived, grantsOf(derived, derivedGrants), sourceName, sourceGrants);
    }

    /** The comparison itself, with no precondition on whether a sensitive column exists yet. */
    private void requireSubset(TableDefinitionEntity derived, TableAccess derivedGrants,
            String sourceName, TableAccess sourceGrants) {
        final TableDefinitionEntity source =
                StringUtils.isBlank(sourceName) ? null : definitions.findById(sourceName).orElse(null);
        // A missing source grants nothing, which is the conservative reading.
        final TableAccess sourceAccess = source == null ? new TableAccess() : grantsOf(source, sourceGrants);
        final List<String> sourceCleared = sourceAccess.rolesFor(Privilege.TABLE_SENSITIVE);
        for (final String role : derivedGrants.rolesFor(Privilege.TABLE_SENSITIVE)) {
            // The source side needs the clearance AND a read, because the clearance alone reads nothing: a
            // role cleared on the source but not granted its read never could see the values derived here.
            if (sourceCleared.contains(role)
                    && TableCallerPermissions.isGranted(sourceAccess, Privilege.TABLE_READ, Set.of(role))) {
                continue;
            }
            throw new SensitiveGrantWidensException("table '" + derived.getName()
                    + "' grants 'sensitive' to role '" + role + "', which the table it reads, '"
                    + sourceName + "', does not grant both 'read' and 'sensitive'; '" + derived.getName()
                    + "' carries a sensitive column, so this would expose it more widely than the column it"
                    + " derives from. Grant 'read' and 'sensitive' on '" + sourceName + "' as well, or remove"
                    + " the role from '" + derived.getName() + "'");
        }
        for (final String role : derivedGrants.rolesFor(Privilege.TABLE_READ)) {
            if (!providerRolePrivileges.holdsGlobally(role, Privilege.TABLE_SENSITIVE)
                    || TableCallerPermissions.isGranted(sourceAccess, Privilege.TABLE_READ, Set.of(role))) {
                continue;
            }
            throw new SensitiveGrantWidensException("table '" + derived.getName()
                    + "' grants 'read' to role '" + role + "', which holds the sensitive clearance globally, so"
                    + " it would read '" + derived.getName() + "''s sensitive column; the table it reads, '"
                    + sourceName + "', does not grant it 'read'. Grant 'read' on '" + sourceName + "' as well,"
                    + " or remove the role from '" + derived.getName() + "'");
        }
    }

    /** {@code pending} when the caller supplied one, else the stored lists; never null. */
    private static TableAccess grantsOf(TableDefinitionEntity definition, TableAccess pending) {
        if (pending != null) {
            return pending;
        }
        return definition.getAccess() == null ? new TableAccess() : definition.getAccess();
    }

    private boolean hasSensitiveColumn(String tableName) {
        return columns.findByTableNameOrderByOrdinal(tableName).stream()
                .anyMatch(ColumnMappingEntity::isSensitive);
    }
}
