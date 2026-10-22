package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.configuration.GroupFetchProperties;
import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.configuration.ScanProperties;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.observability.trace.LogExecution;
import com.epam.aidial.analytics.service.enrichment.model.EnrichDeclaration;
import com.epam.aidial.analytics.service.enrichment.model.GroupPage;
import com.epam.aidial.analytics.service.enrichment.model.InputBinding;
import com.epam.aidial.analytics.service.enrichment.model.MemberOrder;
import com.epam.aidial.analytics.service.enrichment.model.MemberSelect;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.CursorColumns;
import com.epam.aidial.analytics.service.enrichment.support.CursorPredicates;
import com.epam.aidial.analytics.service.enrichment.support.PipelineAuthorizer;
import com.epam.aidial.analytics.service.enrichment.support.ScanSourceMetadata;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichDeclarationCompleteness;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;
import com.epam.aidial.analytics.service.pipeline.PipelineReadBudget;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumn;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumnRole;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.EnrichmentJoins;
import com.epam.aidial.analytics.service.query.PartitionPreselection;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Group-input assembly: given a group key, returns that group's members so a caller can render one prompt
 * for the whole group. Everything else comes from the stored pipeline — the source, the membership filter, the
 * projection, the member-selection policy, and the ordering — so the caller supplies <em>only</em> the key
 * and never learns which table the group lives in. That is the same trust boundary as the scan and the
 * write-back.
 *
 * <p>The assembly applies the <b>same safety-lag as the scan</b>. A group's contents and the group version
 * the caller dirty-marked from a scan page must agree; assembling from rows the scan has not yet surfaced
 * would produce a label whose version understates its input.
 *
 * <p><b>Read in two phases, because the response being bounded is not the same as the read being bounded.</b>
 * A single query that projects the pipeline's bound body columns and then caps rows and bytes has already paid
 * for every body by the time either cap can act: the row cap is a {@code LIMIT} applied after the scan and
 * the byte cap is applied here, in Java, over rows already in hand. For a group whose members carry
 * request/response bodies that is the difference between reading megabytes and reading tens of gigabytes —
 * enough to exhaust a ClickHouse memory budget outright, which is a shared-instance failure rather than a
 * failed request. So {@link #selectMembers} reads only inexpensive columns and decides which members
 * qualify, and {@link #projectMembers} reads the bound columns for those members alone — as one statement
 * per heavy column, because several heavy columns in one statement cost the sum of all of them and a pair
 * can exceed a ceiling either half clears alone.
 *
 * <p>The projection phase addresses each member by its value for <b>every</b> column of the source's
 * ordering key, which is the source's primary index and therefore prunes the read to the granules holding
 * those members. Addressing them by the identity column <em>alone</em> would prune nothing and would leave
 * the read proportional to the whole group; the identity column is nonetheless carried <em>alongside</em>
 * the ordering key, because an ordering key need not be unique — see {@link #lookupKey}. A source declaring
 * no ordering key cannot be point-looked-up at all and falls back to the single-phase read, keeping its
 * previous cost rather than silently returning less.
 *
 * <p><b>Which members qualify is a policy, and the pipeline owns it.</b> Absent a {@link MemberSelect} the
 * policy is the group's most recent members — a group-grain label describes the group's current state, so
 * keeping the head would answer a question about a conversation's outcome from its opening. A pipeline that
 * declares one replaces that ranking, because "newest" is wrong wherever the last row of a group is not the
 * interesting one. Either way this is a statement about <em>which members qualify</em> and is deliberately
 * independent of the <b>order they are returned in</b>: the response is always ascending in the source's
 * total order, which the scan's cursor semantics and the caller's own trimming both depend on.
 *
 * <p>Sensitive columns are gated by entitlement, never stripped: a projection quietly missing the column a
 * pipeline was written to read would yield a quietly wrong label, so a non-entitled caller is refused outright.
 * The check binds to what the <b>pipeline declares</b> — its bindings, its filter and its selection policy — and
 * not to what a given phase happens to project, so moving a column out of a phase's projection cannot
 * quietly remove the entitlement it requires.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class GroupFetchService {

    /**
     * Internal alias under which the selection phase projects the pipeline's {@code prefer_sql} verdict, so the
     * preference can be ranked in SQL and its fallback applied here. Stripped from the response, exactly as
     * the scan's {@code __signal} is a read-side helper rather than a source column.
     */
    private static final String PREFER_ALIAS = "__prefer";

    /**
     * Alias prefix under which both phases project the member's {@link #lookupKey} columns <b>as text</b>, so
     * the two reads can be matched up. Text for the same reason {@link CursorPredicates} encodes the cursor
     * tuple as text and epoch milliseconds: the ClickHouse client's Java mapping for a column type is not
     * something this code should depend on, and a {@code DateTime64(3)} value round-tripped through a
     * temporal Java type and back into a literal is exactly where a millisecond goes missing — which would
     * not fail loudly, it would silently match no row.
     *
     * <p>The <em>predicate</em> still compares the raw key column against a string constant
     * ({@code request_time = '2026-08-17 07:52:54.534'}), which the database folds to the column's own type
     * before index analysis. Wrapping the column in a function instead would cost the granule pruning that
     * is the whole point of addressing members this way.
     */
    private static final String ORDERING_KEY_ALIAS = "__ok";

    /**
     * Aliases under which the selection phase projects the source's total order in its canonical encoding —
     * epoch milliseconds and text, exactly as {@link CursorPredicates} encodes the scan cursor. The response
     * contract is ascending in that order, and with a declared {@code order_by} the selection order is
     * <em>not</em> that order, so restoring it has to be a sort on these values rather than a reversal of
     * whatever the ranking produced. Stripped from the response like every other helper column.
     */
    private static final String CURSOR_VERSION = "__cursor_v";

    private static final String CURSOR_IDENTITY = "__cursor_id";

    private final PipelineService pipelines;
    private final PipelineAuthorizer authorizer;
    private final ScanSourceMetadata scanSources;
    private final SensitiveProjectionGuard sensitiveGuard;
    private final FilterSqlValidator filterValidator;
    private final FilterTranslator filterTranslator;
    private final DSLContext renderContext;
    private final StructuredQueryExecutor executor;
    private final JsonMapper jsonMapper;
    private final ScanProperties scanProperties;
    private final GroupFetchProperties groupFetchProperties;
    private final PipelineBudgetProperties readBudget;

    public GroupPage fetch(String pipelineName, String groupKey, Integer limit) {
        return fetch(pipelines.resolveForScan(pipelineName), groupKey, limit);   // 404 unknown_pipeline if absent
    }

    /**
     * The runner's group assembly over the pipeline {@link #fetch(String, String, Integer)} resolved. The dry-run
     * preview does not come through here: it samples through {@link #previewMembers}, which shares this method's
     * member selection and governance (see {@link #prepare}) but not its response contract.
     */
    private GroupPage fetch(ResolvedEnrichPipeline resolved, String groupKey, Integer limit) {
        Assembly a = prepare(resolved, groupKey);
        StatementTag selectTag = new StatementTag(a.pipelineName(), "group-select");
        int effectiveLimit = resolveLimit(limit, a.select());

        // Nothing to defer: with no bound columns the read is already cheap, and with no ordering key the
        // members cannot be addressed for a second read at all.
        if (a.bound().isEmpty() || a.orderingKey().isEmpty()) {
            if (!a.bound().isEmpty()) {
                log.debug("Source '{}' declares no ordering key; assembling group in one phase", a.source());
            }
            Candidates single = selectMembers(a.model(), a.filter(), a.prefer(), a.orderBy(), a.response(),
                    referenced(a.response(), a.policy()), a.groupKeyField(), a.groupKeyAlias(), groupKey,
                    a.cursorColumns(), effectiveLimit, List.of(), selectTag);
            return trim(single.kept(), responseKeys(a.response(), a.groupKeyAlias()), single.narrowed());
        }

        // Phase one: the response's columns except the bound ones, plus the lookup key it addresses members
        // by. The policy's own columns are deliberately NOT projected — they are referenced by the preference
        // predicate and the ordering, both of which the database evaluates without them reaching the result
        // set, and the response contract does not carry them. Projecting them would read and discard a value
        // per member, and would drag a body column back into this phase for a policy that ranks by one.
        Set<String> selection = new LinkedHashSet<>(a.response());
        selection.removeAll(a.bound());

        List<String> lookupKey = lookupKey(a.orderingKey(), a.cursorColumns());
        Candidates candidates = selectMembers(a.model(), a.filter(), a.prefer(), a.orderBy(), selection,
                referenced(selection, a.policy()), a.groupKeyField(), a.groupKeyAlias(), groupKey,
                a.cursorColumns(), effectiveLimit, lookupKey, selectTag);
        if (candidates.kept().isEmpty()) {
            return new GroupPage(List.of(), false);
        }

        // Phase two: the bound columns for the selected members only. A member phase two cannot find is
        // dropped, which trims the group exactly as a cap does and is reported the same way.
        List<Map<String, Object>> assembled = projectMembers(a.model(), a.bound(), lookupKey, a.groupKeyField(),
                groupKey, a.cursorColumns(), candidates.kept(), new StatementTag(a.pipelineName(), "group-project"));
        boolean shortfall = assembled.size() < candidates.kept().size();
        return trim(assembled, responseKeys(a.response(), a.groupKeyAlias()),
                candidates.narrowed() || shortfall);
    }

    /**
     * Everything a group read is planned from, resolved and authorized once: the source and its model, the
     * membership filter and the selection policy, the bound columns, the response's columns and the group key's
     * two spellings. Shared by the runner's {@link #fetch} and the preview's {@link #previewMembers}, so the two
     * select and govern members identically.
     */
    private record Assembly(
            String pipelineName,
            String source,
            CursorColumns cursorColumns,
            QueryEntityModel model,
            MemberSelect select,
            List<MemberOrder> orderBy,
            FilterSqlValidator.FilterAnalysis filter,
            FilterSqlValidator.FilterAnalysis prefer,
            Set<String> bound,
            Set<String> response,
            Set<String> policy,
            String groupKeyField,
            String groupKeyAlias,
            List<String> orderingKey) {}

    private Assembly prepare(ResolvedEnrichPipeline resolved, String groupKey) {
        final String pipelineName = resolved.pipeline().getName();
        EnrichDeclaration declaration = resolved.declaration();
        authorizer.authorize(resolved.source(), declaration.target());   // 403 pipeline_access_denied (none-mode bypass)
        // Before the group-trigger check, so a draft that has not picked its trigger is told that rather
        // than that it is "not a group pipeline" — and so a group pipeline with no group key is a named 422
        // instead of the null dereference below answering 500.
        EnrichDeclarationCompleteness.requireRunnable(resolved, "assemble a group");

        if (declaration.triggerKind() != TriggerKind.GROUP) {
            throw new PipelineValidationException("pipeline '" + pipelineName + "' is not a group pipeline; group assembly "
                    + "applies only to a pipeline with trigger_kind=group");
        }
        if (StringUtils.isBlank(groupKey)) {
            throw new QueryValidationException("groupKey must not be blank");
        }
        String source = resolved.source();
        if (StringUtils.isBlank(source)) {
            throw new QueryValidationException("pipeline '" + pipelineName + "' target has no resolvable source");
        }
        CursorColumns cursorColumns = scanSources.cursorColumns(source);
        QueryEntityModel model = scanSources.entityModel(source);
        MemberSelect select = declaration.memberSelect();
        List<MemberOrder> orderBy = select == null || select.getOrderBy() == null
                ? List.of() : select.getOrderBy();

        // As on the scan and materialize paths: a non-entitled caller's parse of a pipeline referencing a
        // sensitive column fails on the hidden column, which is a 403, not a malformed pipeline.
        FilterSqlValidator.FilterAnalysis filter = sensitiveGuard.reattributing(source, "group assembly",
                () -> filterValidator.analyze(source, declaration.filterSql(), "filter", true), true);
        FilterSqlValidator.FilterAnalysis prefer = select == null || StringUtils.isBlank(select.getPreferSql())
                ? null
                : sensitiveGuard.reattributing(source, "group assembly", () -> filterValidator
                        .analyze(source, select.getPreferSql(), "member_select.prefer_sql", true), true);

        Set<String> bound = new LinkedHashSet<>();
        for (InputBinding binding : declaration.inputBindings()) {
            if (StringUtils.isNotBlank(binding.getColumn())) {
                bound.add(binding.getColumn());
            }
        }

        // What the response carries, unchanged by the two-phase split: the pipeline's referenced columns. The
        // reads' own helper columns — the lookup key, the cursor encoding, the preference verdict — decide
        // which members qualify and how to address them, are not part of the contract, and are dropped
        // before returning.
        // As on the scan: an enrichment-sourced group key is read qualified and returned under the plain
        // grain key, so the same spelling addresses the group everywhere the caller sees it.
        String groupKeyField = declaration.triggerGroupBy();
        String groupKeyAlias = groupKeyField.equals(resolved.grainKey()) ? null : resolved.grainKey();
        Set<String> response = new LinkedHashSet<>(filter.columns());
        response.addAll(bound);
        if (groupKeyAlias == null) {
            response.add(groupKeyField);
        }
        response.add(cursorColumns.version());
        response.add(cursorColumns.identity());

        // Entitlement binds to what the pipeline DECLARES, before either phase reads anything.
        Set<String> declared = new LinkedHashSet<>(response);
        declared.add(groupKeyField);
        if (prefer != null) {
            declared.addAll(prefer.columns());
        }
        for (MemberOrder order : orderBy) {
            declared.add(order.getColumn());
        }
        sensitiveGuard.enforceEntity(source, declared, "group assembly");

        // What the selection phase names beyond its projection: the preference, the ordering, and the group
        // key. Kept apart from `declared` — which also carries the bound columns, so it is the right set to
        // gate entitlement on and the wrong one to emit joins from — because in the two-phase read the bound
        // columns belong to phase two alone, and joining their enrichment here would make every assembly pay
        // a full hash read of a table this phase never selects from.
        Set<String> policy = new LinkedHashSet<>();
        if (prefer != null) {
            policy.addAll(prefer.columns());
        }
        for (MemberOrder order : orderBy) {
            policy.add(order.getColumn());
        }
        policy.add(groupKeyField);

        return new Assembly(pipelineName, source, cursorColumns, model, select, orderBy, filter, prefer, bound,
                response, policy, groupKeyField, groupKeyAlias, scanSources.orderingKey(source));
    }

    /**
     * A group's dry-run sample: up to the requested number of members, carrying only their bound input columns,
     * in the order the assembly delivers them; how many members the runner's fetch would choose from; and the row
     * cap it would cut them at.
     *
     * @param counted      the group's members after any {@code member_select} preference, counted up to one past
     *                     {@code cap}
     * @param cap          the runner's row cap on this group: the configured default, lowered by a declared
     *                     {@code member_select.limit}
     * @param capIsDefault whether {@code cap} is the configured default rather than a limit the declaration chose
     * @param selected     how many members were chosen to sample; more than {@code rows} holds when the bound
     *                     columns of some could not be read back by their lookup key
     */
    public record MemberSample(List<Map<String, Object>> rows, List<PreviewColumn> columns, int counted, int cap,
            boolean capIsDefault, int selected) {}

    /**
     * Samples one group for the dry-run preview, through the runner's own member selection but never its
     * {@link #fetch}, whose contract is the runner's.
     *
     * <p>Phase one counts the group's members after the preference, reading up to one past the runner's cap and
     * projecting no bound or filter column — only the identity and order columns the selection needs — so a
     * filter on a body column is evaluated without that body being returned for every member. Phase two reads
     * the bound columns for the top {@code sampleRows} members by rank alone. A source with no ordering key cannot
     * be point-looked-up, so there the sample is a second single-phase read limited to {@code sampleRows}, rather
     * than the count reading a cap's worth of heavy rows.
     */
    public MemberSample previewMembers(ResolvedEnrichPipeline resolved, String groupKey, int sampleRows) {
        Assembly assembly = prepare(resolved, groupKey);
        QueryEntityModel model = assembly.model();
        Map<String, QueryFieldBinding> bindings = model.bindings();
        Set<String> bound = assembly.bound();
        List<PreviewColumn> columns = bound.stream()
                .map(name -> PreviewColumn.read(name, PreviewColumnRole.INPUT, bindings.get(name)))
                .toList();

        // The runner's cap, as resolveLimit takes it for a request naming no limit; computed here rather than
        // through it, because the count reads cap + 1 rows, which resolveLimit would refuse at max-rows.
        Integer declared = assembly.select() == null ? null : assembly.select().getLimit();
        int defaultRows = groupFetchProperties.defaultRows();
        int cap = declared == null ? defaultRows : Math.min(defaultRows, declared);
        boolean capIsDefault = declared == null || declared > defaultRows;

        boolean twoPhase = !bound.isEmpty() && !assembly.orderingKey().isEmpty();
        List<String> lookupKey = twoPhase ? lookupKey(assembly.orderingKey(), assembly.cursorColumns()) : List.of();
        Set<String> filterColumns = new LinkedHashSet<>(assembly.filter().columns());
        Candidates pool = selectMembers(model, assembly.filter(), assembly.prefer(), assembly.orderBy(), Set.of(),
                referenced(filterColumns, assembly.policy()), assembly.groupKeyField(), assembly.groupKeyAlias(),
                groupKey, assembly.cursorColumns(), cap, lookupKey,
                new StatementTag(assembly.pipelineName(), "preview-group-count"));
        List<Map<String, Object>> top = pool.kept().subList(0, Math.min(sampleRows, pool.kept().size()));

        List<Map<String, Object>> sampled;
        if (top.isEmpty() || bound.isEmpty()) {
            // With no bound column — reachable: the gate admits a group pipeline whose inputs are all JSONata, or
            // none, since a group template need name only {{members}} — each member is returned as an empty row
            // under no columns. Deliberately not an empty sample: that would read as a group with no member, while
            // these rows say how many members the runner would assemble, each carrying the nothing it binds.
            sampled = top;
        } else if (twoPhase) {
            sampled = projectMembers(model, bound, lookupKey, assembly.groupKeyField(), groupKey,
                    assembly.cursorColumns(), top, new StatementTag(assembly.pipelineName(), "preview-group-project"));
        } else {
            Set<String> projection = new LinkedHashSet<>(bound);
            Set<String> referenced = new LinkedHashSet<>(filterColumns);
            referenced.addAll(projection);
            sampled = selectMembers(model, assembly.filter(), assembly.prefer(), assembly.orderBy(), projection,
                    referenced(referenced, assembly.policy()), assembly.groupKeyField(), assembly.groupKeyAlias(),
                    groupKey, assembly.cursorColumns(), sampleRows, List.of(),
                    new StatementTag(assembly.pipelineName(), "preview-group-select")).kept();
        }
        List<Map<String, Object>> ordered = new ArrayList<>(sampled);
        ordered.sort(Comparator
                .comparingLong((Map<String, Object> row) -> ((Number) row.get(CURSOR_VERSION)).longValue())
                .thenComparing(row -> String.valueOf(row.get(CURSOR_IDENTITY))));
        List<Map<String, Object>> rows = ordered.stream().map(row -> project(row, bound)).toList();
        return new MemberSample(rows, columns, pool.pooled(), cap, capIsDefault, top.size());
    }

    /** What one phase's clauses name: the columns it projects, plus the selection policy's own. */
    private static Set<String> referenced(Set<String> projection, Set<String> policy) {
        Set<String> referenced = new LinkedHashSet<>(projection);
        referenced.addAll(policy);
        return referenced;
    }

    /**
     * The response's column names, with an enrichment-sourced group key represented by the plain grain key
     * it is projected under. {@code trim} strips every helper column not in this set, so the alias has to
     * appear here or the group key would be stripped from the very rows it identifies.
     */
    private static Set<String> responseKeys(Set<String> response, String groupKeyAlias) {
        if (groupKeyAlias == null) {
            return response;
        }
        Set<String> keys = new LinkedHashSet<>(response);
        keys.add(groupKeyAlias);
        return keys;
    }

    /**
     * The selection phase's outcome: the members that qualify, highest-ranked first, whether qualifying
     * dropped any member the group actually contains, and how many qualified before the cap applied — counted up
     * to one past it, which is all the read fetches. {@code narrowed} is what makes {@code truncated} honest
     * for a policy that returns two of three hundred members without any configured cap being reached.
     */
    private record Candidates(List<Map<String, Object>> kept, boolean narrowed, int pooled) {
    }

    /**
     * Reads the group's members under {@code projection} and applies the selection policy, returning at most
     * {@code effectiveLimit} of them in the policy's ranking — which is the source's total order descending
     * only while the pipeline declares no {@code order_by}.
     *
     * <p>The preference and its fallback are resolved in one read rather than two. {@code prefer_sql} is
     * projected as a verdict column and ranked first, so members satisfying it sort ahead of those that do
     * not; if the top member satisfies it the non-satisfying ones are then dropped here, and if <b>no</b>
     * member satisfies it every member remains a candidate. A preference no member can meet must not turn
     * into an empty group — an empty group is indistinguishable from one that has aged out of the source,
     * which would strand the caller.
     */
    private Candidates selectMembers(
            QueryEntityModel model,
            FilterSqlValidator.FilterAnalysis filter,
            FilterSqlValidator.FilterAnalysis prefer,
            List<MemberOrder> orderBy,
            Set<String> projection,
            Set<String> referenced,
            String groupBy,
            String groupKeyAlias,
            String groupKey,
            CursorColumns cursorColumns,
            int effectiveLimit,
            List<String> lookupKey,
            StatementTag tag) {
        Map<String, QueryFieldBinding> bindings = model.bindings();
        SelectQuery<Record> select = renderContext.selectQuery();
        select.addFrom(model.table());
        Field<?> groupField = ScanSourceMetadata.projected(model, bindings, groupBy);
        GroupKeyLookup lookup = groupKeyLookup(model, filter, groupBy, groupField, groupKey);
        // A LEFT JOIN per enrichment this phase's own clauses name — the membership filter, the preference,
        // the ordering and the group key all resolve here, so all four are covered by one referenced set.
        // Unkeyed unless an aligned group key pre-selects partitions: then the enrichment supplying the key is
        // restricted to the key's rows, every other aligned join is pruned to the selected partitions through its
        // tuple join, and every join without an aligned pair is keyed on the source-only conjuncts the selection
        // holds — the partition set among them. Without a partition set those conjuncts are the filter and the
        // safety lag, which select nearly the whole source, and keying on them would only add a set build.
        if (lookup.preselected() == null) {
            EnrichmentJoins.addReferenced(select, model, referenced);
        } else {
            List<Condition> keys = new ArrayList<>();
            keys.add(lookup.preselected());
            if (filter.filter() != null) {
                StructuredQueryBuilder.sourceOnlyConjuncts(model, filter.filter())
                        .forEach(conjunct -> keys.add(filterTranslator.toCondition(conjunct, bindings)));
            }
            keys.add(CursorPredicates.safetyLag(cursorColumns, scanProperties.safetyLag()));
            EnrichmentJoins.addReferenced(select, model, referenced, List.of(), keys, Map.of(lookup.owner(), lookup.key()));
            select.addConditions(lookup.preselected());
        }
        for (String name : projection) {
            select.addSelect(ScanSourceMetadata.projected(model, bindings, name).as(name));
        }
        if (groupKeyAlias != null) {
            select.addSelect(ScanSourceMetadata.projected(model, bindings, groupBy).as(groupKeyAlias));
        }
        addLookupKeyAliases(select, model, bindings, lookupKey);
        Field<Long> version = CursorPredicates.versionExpr(cursorColumns);
        Field<String> identity = CursorPredicates.identityExpr(cursorColumns);
        select.addSelect(version.as(CURSOR_VERSION));
        select.addSelect(identity.as(CURSOR_IDENTITY));
        Field<Boolean> preferField = null;
        if (prefer != null) {
            preferField = DSL.field(filterTranslator.toCondition(prefer.filter(), bindings)).as(PREFER_ALIAS);
            select.addSelect(preferField);
        }
        // Membership: the group key, plus the pipeline's own filter — the caller supplies neither. The key is
        // the joined field when an enrichment supplies it, so the predicate resolves against the same
        // expression the projection aliases rather than against a name the FROM clause does not carry.
        select.addConditions(lookup.key());
        if (groupKeyAlias != null) {
            // Same exclusion the scan emits: a NULL or empty key addresses no row of the target, so it can
            // never name a group worth assembling. Redundant against a non-empty groupKey, and kept for the
            // case it is not: an empty groupKey must assemble nothing rather than every keyless member.
            select.addConditions(groupField.isNotNull(), DSL.condition("toString({0}) != ''", groupField));
        }
        if (filter.filter() != null) {
            select.addConditions(filterTranslator.toCondition(filter.filter(), bindings));
        }
        select.addConditions(CursorPredicates.safetyLag(cursorColumns, scanProperties.safetyLag()));

        List<SortField<?>> ordering = new ArrayList<>();
        if (preferField != null) {
            // Preferred members first; with no preference declared this rank is absent entirely.
            ordering.add(preferField.desc());
        }
        for (MemberOrder order : orderBy) {
            Field<?> field = ScanSourceMetadata.projected(model, bindings, order.getColumn());
            ordering.add(order.directionOrDefault() == SortDir.ASC ? field.asc() : field.desc());
        }
        // The source's total order descending, always last: it makes the selection a total order, so
        // re-assembling an unchanged group selects the same members. Absent a policy it IS the policy.
        ordering.add(version.desc());
        ordering.add(identity.desc());
        select.addOrderBy(ordering);
        // One extra row so a full group stays distinguishable from a trimmed one.
        select.addLimit(effectiveLimit + 1);

        List<Map<String, Object>> rows = PipelineReadBudget.run(executor, select, model, "group member selection", readBudget, tag);
        List<Map<String, Object>> pool = rows;
        if (prefer != null && !rows.isEmpty() && satisfiesPreference(rows.getFirst())) {
            pool = rows.stream().filter(GroupFetchService::satisfiesPreference).toList();
        }
        boolean narrowed = pool.size() < rows.size() || pool.size() > effectiveLimit;
        List<Map<String, Object>> kept = pool.size() > effectiveLimit
                ? new ArrayList<>(pool.subList(0, effectiveLimit))
                : new ArrayList<>(pool);
        kept.forEach(row -> row.remove(PREFER_ALIAS));
        return new Candidates(kept, narrowed, pool.size());
    }

    /**
     * The membership predicate on the group key, and what it adds when an aligned enrichment supplies the key.
     *
     * @param key         the predicate itself
     * @param owner       the table name of the aligned enrichment supplying the key; null unless pre-selected
     * @param preselected the partition restriction the source takes; null unless pre-selected
     */
    private record GroupKeyLookup(Condition key, String owner, Condition preselected) {
    }

    /**
     * The group-key predicate. A {@code string} key is compared on the bare column — {@code toString(col)} would
     * hide the column from its lookup index — and every other type through {@code toString}, since the caller's
     * key arrives as text and parsing it would change which values match ({@code '01'} names no {@code long}).
     * When an aligned enrichment supplies a {@code string} key, the predicate is a lookup on it and pre-selects the
     * partitions the group's members live in (see {@link PartitionPreselection}), bounded by the filter's own bounds
     * on the source's partition column: the enrichment is restricted to the key's rows, and the source to those
     * partitions, so the selection reads the group's active days instead of every row since the source's floor.
     * The members selected are the same: every member matched an enrichment row carrying the key and its own
     * partition. The key is never the type default here: {@code prepare} refuses a blank one.
     */
    private GroupKeyLookup groupKeyLookup(QueryEntityModel model, FilterSqlValidator.FilterAnalysis filter, String groupBy,
            Field<?> groupField, String groupKey) {
        QueryFieldBinding binding = model.bindings().get(groupBy);
        if (binding == null || binding.type() != QueryFieldType.STRING) {
            return new GroupKeyLookup(DSL.condition("toString({0}) = {1}", groupField, DSL.inline(groupKey)), null, null);
        }
        Condition key = DSL.condition("{0} = {1}", groupField, DSL.inline(groupKey));
        EnrichmentJoin owner = model.ownerOf(groupBy);
        if (owner == null) {
            return new GroupKeyLookup(key, null, null);
        }
        // The field the model binds is the enrichment's own column, two-part qualified, so the same predicate reads
        // correctly inside the pre-selection's subquery and the enrichment's derived table.
        List<Condition> bounds = StructuredQueryBuilder.partitionBounds(filterTranslator, model, owner, filter.filter());
        return PartitionPreselection.of(owner, key, bounds)
                .map(preselected -> new GroupKeyLookup(key, owner.table().getName(), preselected))
                .orElseGet(() -> new GroupKeyLookup(key, null, null));
    }

    /**
     * Reads {@code bound} for the selected members and merges them onto those members, keeping the ranking
     * they arrived in.
     *
     * <p><b>One read per heavy column, because their costs add up inside one statement.</b> A statement
     * projecting several heavy columns pays for each of them concurrently — the read buffers of every
     * candidate granule of every column are held at once — so a declaration binding two heavy columns costs
     * twice a declaration binding one, and the pair can exceed a ceiling that either half clears on its own.
     * On a poorly pruned source that is on the order of a gigabyte per heavy column to return a single
     * member, whatever that member's values weigh (a few-KB column and a few-hundred-KB one cost the same, because the cost is the
     * granules read, not the bytes returned). Splitting the projection makes the peak the largest single
     * column rather than the sum of all of them, which is what keeps a declaration's heavy-column count
     * from deciding whether its group fetch runs at all.
     *
     * <p>The split is per <em>heavy</em> column only; every inexpensive bound column rides along with the
     * first read, since their cost is not what any of this is about. A declaration binding no heavy column
     * is therefore read by exactly one statement, as before.
     *
     * <p>Every read addresses the same members by the same {@link #lookupKey}, so the slices are gathered
     * back under that key. A member is kept only when <em>every</em> read found it: a member that one read
     * saw and another did not is treated as excluded rather than as an error — under a re-ingesting source
     * that race is benign, and failing the whole group would dead-letter it. The shortfall still marks the
     * response {@code truncated}, because the label then saw part of the group. It is logged, because the
     * other way to lose a member here is a mis-rendered point lookup, and that must not pass as a benign
     * race.
     */
    private List<Map<String, Object>> projectMembers(
            QueryEntityModel model,
            Set<String> bound,
            List<String> lookupKey,
            String groupBy,
            String groupKey,
            CursorColumns cursorColumns,
            List<Map<String, Object>> members,
            StatementTag tag) {
        List<Set<String>> batches = projectionBatches(model.bindings(), bound);

        // One map per member, filled from every read. The tally is what says a member was found by ALL of
        // them, counted once per read rather than once per row: a heavy column is legitimately NULL, so
        // presence cannot be read off the value, and a lookup key that repeats must not let one read's two
        // rows stand in for another read's none.
        Map<List<Object>, Map<String, Object>> byKey = new HashMap<>();
        Map<List<Object>, Integer> matched = new HashMap<>();
        for (Set<String> batch : batches) {
            Set<List<Object>> seen = new HashSet<>();
            for (Map<String, Object> row : readMembers(
                    model, batch, lookupKey, groupBy, groupKey, cursorColumns, members, tag)) {
                List<Object> key = keyOf(row, lookupKey.size());
                byKey.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).putAll(row);
                if (seen.add(key)) {
                    matched.merge(key, 1, Integer::sum);
                }
            }
        }

        List<Map<String, Object>> assembled = new ArrayList<>(members.size());
        for (Map<String, Object> member : members) {
            List<Object> key = keyOf(member, lookupKey.size());
            if (matched.getOrDefault(key, 0) < batches.size()) {
                continue;
            }
            Map<String, Object> bodies = byKey.get(key);
            Map<String, Object> merged = new LinkedHashMap<>(member);
            for (String name : bound) {
                merged.put(name, bodies.get(name));
            }
            assembled.add(merged);
        }
        if (assembled.size() < members.size()) {
            log.warn("Group assembly projected {} of {} selected members over {} read(s); the missing members "
                    + "were not found by their lookup key", assembled.size(), members.size(), batches.size());
        }
        return assembled;
    }

    /**
     * The bound columns grouped into the reads that project them: one read per heavy column, and one shared
     * read for everything inexpensive, folded into the first heavy read when there is one so the common
     * declaration costs no extra round trip.
     *
     * <p>Heaviness is the catalog's own column flag, read off the query model's bindings. A column with no
     * binding is treated as heavy: it cannot occur on this path — {@code fetch} enforces entitlement over
     * the whole declaration before either phase runs, so a sensitive column is either present in the
     * role-filtered model or has already been refused — and of the two ways to be wrong about a column's
     * cost, one statement too many is the one that cannot exhaust a budget.
     */
    private static List<Set<String>> projectionBatches(
            Map<String, QueryFieldBinding> bindings, Set<String> bound) {
        List<Set<String>> heavy = new ArrayList<>();
        Set<String> light = new LinkedHashSet<>();
        for (String name : bound) {
            QueryFieldBinding binding = bindings.get(name);
            if (binding == null || binding.heavy()) {
                heavy.add(new LinkedHashSet<>(List.of(name)));
            } else {
                light.add(name);
            }
        }
        if (heavy.isEmpty()) {
            return List.of(light);
        }
        if (!light.isEmpty()) {
            heavy.getFirst().addAll(light);
        }
        return heavy;
    }

    /**
     * Reads {@code projection} for the selected members, as one statement.
     *
     * <p>Each member is addressed by its {@link #lookupKey} values, as a disjunction of conjunctions rather
     * than a row-value {@code IN}: the union of point ranges is what the database's key analysis prunes on,
     * and it renders identically on every dialect instead of relying on row-value expression support. The
     * group key is repeated as a predicate because it is the read's most selective index term and pruning by
     * it costs nothing here. The lookup is bounded to one row per member, which is what keeps the read's
     * cost proportional to the members it was asked for even on a source whose ordering key repeats.
     *
     * <p><b>The member lookup keys the joins as well as filtering the source.</b> Those predicates name the
     * source alone, so they are handed to {@link EnrichmentJoins} rather than only added to the
     * {@code WHERE} — which is what lets an enrichment holding the projected columns be read as a derived
     * table restricted to the grain keys, and the partitions, this one member occupies. That matters here
     * more than anywhere else in the service: this is the phase that projects the declaration's heavy
     * columns, and it projects them for a handful of rows. Unkeyed, a payload enrichment whose grain key is
     * uncorrelated with its partitioning is read across every one of its parts to return them, which is how
     * one group fetch exhausted a ClickHouse node's whole memory budget.
     */
    private List<Map<String, Object>> readMembers(
            QueryEntityModel model,
            Set<String> projection,
            List<String> lookupKey,
            String groupBy,
            String groupKey,
            CursorColumns cursorColumns,
            List<Map<String, Object>> members,
            StatementTag tag) {
        Map<String, QueryFieldBinding> bindings = model.bindings();
        SelectQuery<Record> select = renderContext.selectQuery();
        select.addFrom(model.table());
        // This read's own referenced set, not the whole declaration's: it projects its own columns and
        // predicates on the group key, and nothing else. Deriving it here is what keeps a policy that ranks
        // by an enrichment column from dragging that enrichment's join into a read that never reads it —
        // and, now that the projection is split, keeps each read's joins to the enrichments it names.
        Set<String> referenced = new LinkedHashSet<>(projection);
        referenced.add(groupBy);

        // The member lookup is built before the joins, because it is also what keys them. These are the
        // predicates that confine the SOURCE rows this statement reads — the per-member point lookups and
        // the safety lag — so EnrichmentJoins can restrict each joined enrichment to the grain keys, and to
        // the partitions, those predicates select. Without them the statement asks for the bound columns of
        // one member and pays to read a candidate granule from every part of the enrichment holding them.
        List<Condition> lookups = new ArrayList<>();
        for (Map<String, Object> member : members) {
            List<Condition> tuple = new ArrayList<>();
            for (int i = 0; i < lookupKey.size(); i++) {
                Field<?> column = ScanSourceMetadata.projected(model, bindings, lookupKey.get(i));
                Object value = member.get(ORDERING_KEY_ALIAS + i);
                // Compared as a raw literal rather than through eq(): jOOQ would coerce the bind value to
                // the field's own declared type, and a text form of a DateTime64 does not survive that
                // conversion — it renders as NULL and silently matches nothing. Leaving the constant as
                // text lets the database fold it to the key column's type, which is also what keeps the
                // predicate index-usable.
                tuple.add(value == null
                        ? DSL.condition("{0} IS NULL", column)
                        : DSL.condition("{0} = {1}", column, DSL.inline(value.toString())));
            }
            lookups.add(DSL.and(tuple));
        }
        Condition safetyLag = CursorPredicates.safetyLag(cursorColumns, scanProperties.safetyLag());
        List<Condition> memberPredicates = List.of(safetyLag, DSL.or(lookups));

        // The group-key predicate is deliberately NOT among them. It resolves against the joined field when
        // an enrichment supplies the key — which is the whole point of a group trigger keyed on an
        // enrichment column — and a predicate naming an enrichment column cannot travel into the subquery
        // that decides whether to emit that enrichment's join. The lookup key and the safety lag carry no
        // such risk by construction (the source's ordering key plus its identity and version columns), and
        // the guard below is what keeps that a checked fact rather than a remembered one.
        List<String> keyingColumns = new ArrayList<>(lookupKey);
        keyingColumns.add(cursorColumns.version());
        EnrichmentJoins.addReferenced(select, model, referenced,
                EnrichmentJoins.referencesSourceOnly(model, keyingColumns) ? memberPredicates : List.of());

        for (String name : projection) {
            select.addSelect(ScanSourceMetadata.projected(model, bindings, name).as(name));
        }
        addLookupKeyAliases(select, model, bindings, lookupKey);
        Field<?> groupField = ScanSourceMetadata.projected(model, bindings, groupBy);
        select.addConditions(DSL.condition("toString({0}) = {1}", groupField, DSL.inline(groupKey)));
        select.addConditions(memberPredicates);
        // A backstop, not a policy: one row per member is what a unique lookup key yields anyway, and a
        // source whose identity column turns out not to be unique must not be able to reintroduce the
        // unbounded body read this phase exists to avoid.
        select.addLimit(members.size());

        return PipelineReadBudget.run(executor, select, model, label(projection), readBudget, tag);
    }

    /**
     * How one read of the projection phase names itself in a budget refusal. The columns are named because
     * the projection is split by column: a refusal that said only "group payload projection" would leave the
     * operator to work out which of a declaration's bound columns is the one that does not fit, which is the
     * single most useful thing the message can carry.
     */
    private static String label(Set<String> projection) {
        return "group payload projection of " + String.join(", ", projection);
    }

    /**
     * The tuple a member is addressed by on the second read: the source's ordering key, plus its identity
     * column when the ordering key does not already carry it.
     *
     * <p>The ordering key is what makes the lookup <em>cheap</em> — it is the source's primary index, so a
     * conjunction over all of it prunes the read to the granules holding those members. It is not, however,
     * what makes the lookup <em>exact</em>: only a deduplicating engine keyed on that tuple guarantees it
     * identifies at most one row, and a source is free to be a plain {@code MergeTree} whose ordering key
     * repeats. Two members sharing a tuple would then be indistinguishable on the merge, and each would be
     * handed whichever of their bodies came back last — a quietly wrong group input rather than a failure.
     * The identity column is declared unique per row, so appending it makes the match exact while leaving
     * every pruning term in place.
     */
    private static List<String> lookupKey(List<String> orderingKey, CursorColumns cursorColumns) {
        if (orderingKey.contains(cursorColumns.identity())) {
            return orderingKey;
        }
        List<String> extended = new ArrayList<>(orderingKey);
        extended.add(cursorColumns.identity());
        return List.copyOf(extended);
    }

    /**
     * Applies the byte cap to a list in the policy's ranking and returns the survivors in the source's total
     * order, restricted to the columns the response contract names.
     *
     * <p>The cap cuts from the <b>bottom of the ranking</b>, which is what keeps the members the policy chose:
     * it accumulates each member's serialized size — the same serialization the response will use — and stops
     * before exceeding the ceiling. The row cap and the policy have already been applied by
     * {@link #selectMembers}, which reports them as {@code narrowed}, so {@code truncated} ends up covering
     * all three: a label computed from part of a group must not report itself complete, whichever decided it.
     *
     * <p>Cutting by rank and returning by total order are two different orders, so the survivors are sorted
     * only after the cap has chosen them. A first member that alone exceeds the byte cap is still returned,
     * because returning an empty group would be indistinguishable from "this group has no members" and would
     * strand the caller.
     */
    private GroupPage trim(List<Map<String, Object>> ranked, Set<String> response, boolean narrowed) {
        long maxBytes = groupFetchProperties.maxBytes().toBytes();
        long accumulated = 0;
        boolean byteTrimmed = false;
        List<Member> survivors = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Map<String, Object> raw = ranked.get(i);
            Map<String, Object> projected = project(raw, response);
            accumulated += jsonMapper.writeValueAsBytes(projected).length;
            if (accumulated > maxBytes && i > 0) {
                log.debug("Group assembly trimmed to the {} highest-ranked of {} members by the {} byte cap",
                        i, ranked.size(), maxBytes);
                byteTrimmed = true;
                break;
            }
            survivors.add(new Member(raw, projected));
        }
        survivors.sort(TOTAL_ORDER);
        return new GroupPage(survivors.stream().map(Member::projected).toList(), narrowed || byteTrimmed);
    }

    /**
     * A surviving member, kept alongside the projected form the response will carry so the byte cap measures
     * exactly what it bounds and no member's body is serialized twice.
     */
    private record Member(Map<String, Object> raw, Map<String, Object> projected) {
    }

    /**
     * The source's total order, ascending — the response contract, which the assembly departs from only so its
     * {@code LIMIT} selects the window the policy ranks highest.
     *
     * <p>A <b>sort</b>, not a reversal of the ranked list. Reversing is equivalent only while the selection
     * order is the total order descending, which stops being true the moment a pipeline declares an
     * {@code order_by}: a member ranked first by message count can be the group's oldest, and reversing would
     * then report the ranking backwards and call it ingest order. The comparison runs on the canonical
     * encodings, so it does not depend on the client's Java mapping for the version column's type.
     */
    private static final Comparator<Member> TOTAL_ORDER = Comparator
            .comparingLong((Member member) -> ((Number) member.raw().get(CURSOR_VERSION)).longValue())
            .thenComparing(member -> String.valueOf(member.raw().get(CURSOR_IDENTITY)));

    /** Keeps only the columns the response contract names, in their declared order. */
    private static Map<String, Object> project(Map<String, Object> row, Set<String> response) {
        Map<String, Object> projected = new LinkedHashMap<>();
        for (String name : response) {
            if (row.containsKey(name)) {
                projected.put(name, row.get(name));
            }
        }
        return projected;
    }

    /**
     * Projects every {@link #lookupKey} column as text under {@link #ORDERING_KEY_ALIAS}{@code <i>}, which is
     * how the two phases address the same row without depending on the client's Java mapping for the column's
     * type. A no-op for the single-phase path, which has no second read to match up.
     */
    private static void addLookupKeyAliases(SelectQuery<Record> select, QueryEntityModel model,
            Map<String, QueryFieldBinding> bindings, List<String> lookupKey) {
        for (int i = 0; i < lookupKey.size(); i++) {
            Field<?> column = ScanSourceMetadata.projected(model, bindings, lookupKey.get(i));
            select.addSelect(DSL.field("toString({0})", String.class, column).as(ORDERING_KEY_ALIAS + i));
        }
    }

    /** The row's lookup-key tuple as read back under the text aliases; {@code null}-tolerant. */
    private static List<Object> keyOf(Map<String, Object> row, int degree) {
        Object[] values = new Object[degree];
        for (int i = 0; i < degree; i++) {
            Object value = row.get(ORDERING_KEY_ALIAS + i);
            values[i] = value == null ? null : value.toString();
        }
        return Arrays.asList(values);
    }

    /**
     * The projected {@code prefer_sql} verdict for one member. ClickHouse answers a boolean expression as
     * {@code UInt8}, so the value arrives as a number rather than a {@link Boolean} depending on the column
     * type it was built from; both forms mean the same thing here.
     */
    private static boolean satisfiesPreference(Map<String, Object> row) {
        Object verdict = row.get(PREFER_ALIAS);
        if (verdict instanceof Boolean bool) {
            return bool;
        }
        return verdict instanceof Number number && number.intValue() != 0;
    }

    /**
     * The effective row cap: the smallest of the caller's request, the applicable configured bound, and the
     * pipeline's declared {@code member_select.limit}. The declared limit lowers the cap rather than sitting
     * beside it, so a caller asking for more members than the pipeline allows cannot defeat the policy — which
     * is what lets a pipeline bound its own read without any change to its caller.
     */
    private int resolveLimit(Integer limit, MemberSelect select) {
        int requested;
        if (limit == null || limit <= 0) {
            requested = groupFetchProperties.defaultRows();
        } else if (limit > groupFetchProperties.maxRows()) {
            throw new QueryValidationException("limit exceeds the maximum of " + groupFetchProperties.maxRows());
        } else {
            requested = limit;
        }
        Integer declared = select == null ? null : select.getLimit();
        return declared == null ? requested : Math.min(requested, declared);
    }
}
