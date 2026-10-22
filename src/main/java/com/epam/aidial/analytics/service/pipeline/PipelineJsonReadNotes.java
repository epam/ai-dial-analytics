package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.service.enrichment.EnrichResolver;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TransformVar;
import com.epam.aidial.analytics.service.enrichment.support.EntityFieldResolver;
import com.epam.aidial.analytics.service.enrichment.support.EntityFields;
import com.epam.aidial.analytics.service.enrichment.validation.ExprSqlValidator;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.query.JsonExtractFunctions;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a declaration's author should know about how it reads a native {@code JSON} ({@code object}) column:
 * the one extractor that cannot be served from a JSON subcolumn, and what choosing it costs.
 *
 * <p>{@code json_extract_string_ci} is rendered as {@code toJSONString(<column>)} — the whole document as
 * serialized text — because it has no statically known subcolumn to address: not knowing the key's spelling
 * is the entire point of it. ClickHouse stores a {@code JSON} column as <b>one stream per path per
 * part</b>, so reading it whole opens paths x parts read buffers. That cost is independent of the window and
 * of the data volume: a column such as {@code dial_usage_log_payload.request_tags} can hold hundreds of
 * paths across hundreds of parts, and a window matching <em>zero</em> rows is then refused by the same 2 GiB
 * ceiling as one matching every row. Every other {@code json_extract_*} over the same column addresses one
 * literal path and reads one subcolumn — {@code json_extract_array} included, which was reported here until
 * it was found to be path-addressable after all (see {@code JsonExtractFunctions}).
 *
 * <p><b>Reported, never refused</b> — see the {@code structured-query} spec, "The cost of reading a native
 * JSON column scales with paths times parts". Affordability is a property of the table's physical layout
 * rather than of the declaration: the same expression is cheap on a compacted table and ruinous on a
 * fragmented one, and the layout moves under the declaration without the declaration changing. A static
 * refusal would reject affordable uses, still admit unaffordable ones, and strand shipped declarations —
 * including a system pipeline — behind a gate they could not pass.
 * The resource budget is the enforcement point; this is here so nobody picks the expensive form unaware.
 *
 * <p><b>Served on a write, not on a read.</b> {@code POST /v1/pipelines} and
 * {@code PATCH /v1/pipelines/&#123;name&#125;} carry these notes; {@code GET} does not. The notes answer
 * "what did I just declare", which is a question about an act rather than about stored state — and deriving
 * them costs one SQL front-end translation per governed fragment, which a listing would pay per entry for an
 * answer that has not changed since it was reported.
 *
 * <p><b>Best-effort, exactly as {@link PipelineFreshnessDiagnostics} is.</b> Working these out means
 * re-resolving the declaration against its source; a failure degrades to no notes and is logged, because a
 * declaration the gate has just accepted must not be answered with an error raised by advice about it.
 * Deliberately not {@code @Transactional}: the resolvers reached from here <em>do</em> throw, and a throw
 * inside a surrounding read-only transaction would mark it rollback-only, turning a swallowed failure into
 * an {@code UnexpectedRollbackException} at commit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineJsonReadNotes {

    private final EnrichResolver enrichResolver;
    private final EntityFieldResolver entityFields;
    private final FilterSqlValidator filterValidator;
    private final ExprSqlValidator exprValidator;
    private final PipelineSpecCodec specCodec;
    private final AggregateSqlReader sqlReader;

    /**
     * The notes for one just-registered or just-patched pipeline, in declaration order — the {@code filter}
     * first, then each Class-1 output expression. Empty for a declaration that reads no {@code object}
     * column through either extractor, which is the ordinary case.
     */
    public List<String> of(PipelineEntity pipeline) {
        try {
            return notesOf(pipeline);
        } catch (RuntimeException ex) {
            log.debug("Could not analyse how pipeline '{}' reads its JSON columns, so its registration notes"
                    + " are omitted: {}", pipeline.getName(), ex.getMessage());
            return List.of();
        }
    }

    private List<String> notesOf(PipelineEntity pipeline) {
        return switch (pipeline.getKind()) {
            case AGGREGATE -> aggregateNotes(pipeline);
            case ENRICH -> enrichNotes(pipeline);
            // An external pipeline declares no governed fragment ADAS renders, so it reads nothing here.
            case EXTERNAL -> List.of();
        };
    }

    /**
     * An aggregate declaration's one governed membership predicate: its {@code filter}, or for an SQL declaration
     * (which may not declare one) its statement's {@code WHERE}.
     */
    private List<String> aggregateNotes(PipelineEntity pipeline) {
        final String source = pipeline.getInputTable();
        if (StringUtils.isBlank(source)) {
            return List.of();
        }
        if (StringUtils.isNotBlank(pipeline.getFilterSql())) {
            return filterNotes(source, entityFields.entityOf(source), pipeline.getFilterSql());
        }
        final String sql = specCodec.readAggregate(pipeline).getSql();
        if (StringUtils.isBlank(sql)) {
            return List.of();
        }
        final FilterNode where = sqlReader.require(sql).translation().query().filter();
        if (where == null) {
            return List.of();
        }
        final EntityFields entity = entityFields.entityOf(source);
        final List<String> notes = new ArrayList<>();
        appendNotes(notes, "the WHERE of sql", entity, collect(where, entity));
        return notes;
    }

    /**
     * An enrich declaration's {@code filter} plus, for a Class-1 ({@code sql}) transform, every output
     * expression — the two positions the spec names, and the two that are rendered into the
     * statements the read budget then has to bound.
     */
    private List<String> enrichNotes(PipelineEntity pipeline) {
        final ResolvedEnrichPipeline resolved = enrichResolver.resolve(pipeline);
        final String source = resolved.source();
        if (StringUtils.isBlank(source)) {
            return List.of();
        }
        final EntityFields entity = entityFields.enrichEntityOf(source);
        final List<String> notes = new ArrayList<>(filterNotes(source, entity, pipeline.getFilterSql()));
        final EnrichSpec.TransformSpec transform = resolved.transform();
        if (transform == null || transform.getType() != TransformType.SQL) {
            return List.copyOf(notes);
        }
        for (final TransformVar var : transform.effectiveOutputVars()) {
            if (StringUtils.isBlank(var.getSql())) {
                continue;
            }
            final String what = "output variable '" + var.getName() + "'";
            final Expr expr = exprValidator.analyze(source, var.getSql(), what, true).expr();
            appendNotes(notes, what, entity, collect(expr, entity));
        }
        return List.copyOf(notes);
    }

    private List<String> filterNotes(String source, EntityFields entity, String filterSql) {
        if (StringUtils.isBlank(filterSql)) {
            return List.of();
        }
        final FilterNode filter = filterValidator.analyze(source, filterSql, "filter", true).filter();
        final List<String> notes = new ArrayList<>();
        appendNotes(notes, "filter", entity, collect(filter, entity));
        return notes;
    }

    /** One serialized-text read of one {@code object} column, by the function that made it one. */
    private record SerializedRead(String column, String function) {}

    private void appendNotes(
            List<String> into, String what, EntityFields entity, Set<SerializedRead> reads) {
        for (final SerializedRead read : reads) {
            into.add(noteFor(what, entity, read));
        }
    }

    /**
     * The note itself: what the expression does, what it costs, and — where the function has one — the
     * single-path reader that does not.
     *
     * <p>{@link JsonExtractFunctions#STRING_CI} is the only member that reaches here today, and it has an
     * alternative. The remedy still branches on {@link JsonExtractFunctions#singlePathAlternative}
     * returning one, because that method answers {@code null} for every other name: a member joining
     * {@code SERIALIZED_TEXT} later with no cheaper spelling would otherwise get a note confidently
     * naming {@code null} as its alternative.
     */
    private String noteFor(String what, EntityFields entity, SerializedRead read) {
        final String owner = entity.ownerOf(read.column());
        final String column = owner == null || read.column().contains(".")
                ? read.column()
                : owner + "." + read.column();
        final String alternative = JsonExtractFunctions.singlePathAlternative(read.function());
        final String remedy = alternative == null
                ? " This function has no single-path alternative, and the read is bounded at execution by"
                        + " the pipeline read budget."
                : " Where the key's case is known, " + alternative
                        + " addresses one literal path and reads one subcolumn instead.";
        return what + " reads the object column '" + column + "' through " + read.function()
                + ", which reads the whole JSON document as serialized text: one read stream per JSON path"
                + " per part, so the cost scales with the table's part count rather than with the rows the"
                + " window matches." + remedy;
    }

    /**
     * Walks a filter tree for serialized-text reads of an {@code object} column.
     *
     * <p><b>A deliberate second copy of {@code ExprTranslator.readsSerializedText}.</b> That predicate is
     * private to the renderer, and the renderer's copy answers a question about a
     * {@code QueryFieldBinding} resolved from a query model; this one answers it about a catalog
     * {@link EntityFields} entry, which is the namespace a declaration is governed in. Both resolve the
     * membership from {@link JsonExtractFunctions} rather than restating it, which is what kept them in
     * step when {@code json_extract_array} left the set.
     */
    private static Set<SerializedRead> collect(FilterNode node, EntityFields entity) {
        final Set<SerializedRead> reads = new LinkedHashSet<>();
        collect(node, entity, reads);
        return reads;
    }

    private static Set<SerializedRead> collect(Expr expr, EntityFields entity) {
        final Set<SerializedRead> reads = new LinkedHashSet<>();
        collect(expr, entity, reads);
        return reads;
    }

    private static void collect(FilterNode node, EntityFields entity, Set<SerializedRead> into) {
        if (node instanceof LogicalNode logical) {
            nullSafe(logical.args()).forEach(arg -> collect(arg, entity, into));
        } else if (node instanceof ComparisonNode comparison) {
            nullSafe(comparison.args()).forEach(arg -> collect(arg, entity, into));
        }
    }

    private static void collect(Expr expr, EntityFields entity, Set<SerializedRead> into) {
        switch (expr) {
            case FnExpr fn -> {
                final String column = serializedObjectRead(fn, entity);
                if (column != null) {
                    into.add(new SerializedRead(column, fn.name()));
                }
                nullSafe(fn.args()).forEach(arg -> collect(arg, entity, into));
            }
            case ArrayExpr array -> nullSafe(array.items()).forEach(item -> collect(item, entity, into));
            case CaseExpr caseExpr -> {
                for (final CaseBranch branch : nullSafe(caseExpr.when())) {
                    collect(branch.when(), entity, into);
                    collect(branch.then(), entity, into);
                }
                collect(caseExpr.otherwise(), entity, into);
            }
            case WindowExpr window -> {
                collect(window.fn(), entity, into);
                nullSafe(window.partitionBy()).forEach(key -> collect(key, entity, into));
                nullSafe(window.orderBy()).stream().map(WindowOrder::expr)
                        .forEach(order -> collect(order, entity, into));
            }
            case null, default -> {
                // A field, a literal, a bound parameter and a scalar subquery carry no such read: a bare
                // object reference is a projection, which this note is not about.
            }
        }
    }

    /**
     * The {@code object} column {@code fn} reads whole, or null. Mirrors
     * {@code ExprTranslator.objectFieldArgument}: only a direct field reference in the JSON position
     * resolves to a column, and only an {@code object}-typed one is read through its serialized text.
     */
    private static String serializedObjectRead(FnExpr fn, EntityFields entity) {
        // Lower-cased to match the renderer, which lower-cases before dispatch: an author who wrote
        // JSON_EXTRACT_STRING_CI gets the expensive rendering either way, so they must get the note too.
        if (!JsonExtractFunctions.readsSerializedText(fn.name().toLowerCase(Locale.ROOT))) {
            return null;
        }
        final List<Expr> args = nullSafe(fn.args());
        if (args.isEmpty() || !(args.getFirst() instanceof FieldExpr(String name))) {
            return null;
        }
        return entity.typeOf(name) == QueryFieldType.OBJECT ? name : null;
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
