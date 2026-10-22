package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.service.table.ColumnInput;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

import java.util.List;
import java.util.Set;

/**
 * The provenance columns stamped on an enrichment row — facts about what produced the row (the pipeline
 * generation and the model), when, and what input it saw (a group pipeline's input version and whether
 * that input was trimmed). Nothing in the enrichment pipeline reads them back, so they are
 * <em>optional</em> on a target enrichment: a write permits them (this set) but silently skips any that
 * the target does not physically carry, so a target without them still enriches successfully.
 *
 * <p>Both write paths use them: the runner supplies them as ordinary columns on
 * {@code POST /v1/pipelines/{name}/rows}, and the Class-1 materialize path stamps them itself inside its
 * {@code INSERT … SELECT} — skipping {@link #MODEL}, {@link #GROUP_VERSION}, and {@link #TRUNCATED}
 * there, since a {@code sql} transform makes no model call and may not use the group trigger.
 *
 * <p><b>{@link #EVALUATOR_VERSION} is a reserved name, never written again.</b> A row's definition is its
 * pipeline's {@code transform}, and {@link #PIPELINE_GENERATION} is what identifies it. The old column stays
 * in this set because targets created before the fold still hold it: keeping the name reserved stops it
 * being renamed or reused with another meaning, and a caller that supplies it on a write is refused rather
 * than tolerated — see {@code EnrichWriteValidator}.
 *
 * <p>Every name here is deliberately <b>un-prefixed</b>. The {@code _} prefix is reserved for ADAS's own
 * system columns, which are not caller-settable at all ({@code _updated_at} is stamped by ClickHouse and
 * a caller naming it is rejected), and a user-declared enrichment column may not start with {@code _}.
 * Provenance is declared on the target like any other column, so it lives in the user namespace.
 */
public final class ProvenanceColumns {

    /**
     * Which evaluator version produced the row — <b>stamped only by rows written before the fold</b>.
     * Reserved because existing targets may still hold rows carrying one; nothing writes it, and it is no
     * longer injected into new targets.
     */
    public static final String EVALUATOR_VERSION = "evaluator_version";

    /**
     * Which pipeline {@code generation} produced the row — the stamp that replaced
     * {@link #EVALUATOR_VERSION}.
     *
     * <p><b>A new column rather than a reuse, deliberately.</b> Existing rows carry small version integers
     * and new rows carry generations, which are typically larger; two number spaces in one column is unreadable,
     * and provenance columns are optional and best-effort, so adding one is cheap and removing none is
     * free.
     *
     * <p>The generation is also the <em>better</em> stamp, not merely a different one: an evaluator version
     * was blind to a {@code filter}, {@code trigger} or {@code member_select} change, so two rows stamped
     * v6 could have been selected from entirely different inputs. The cost is that it moves more often
     * than the prompt does, so "which prompt produced this row" becomes a {@code pipeline_aud} lookup
     * rather than a human-legible version number.
     */
    public static final String PIPELINE_GENERATION = "pipeline_generation";

    /** Which model produced the row; not applicable to a Class-1 ({@code sql}) transform. */
    public static final String MODEL = "model";

    /** When the row was produced. */
    public static final String ENRICHED_AT = "enriched_at";

    /**
     * The version of the group input the row was computed from — for a group pipeline, the maximum version
     * column value across the members it saw. It makes a label's input identifiable after the fact: a
     * downstream reader can tell a label computed from a stale group from one computed from the current
     * one, which the label alone cannot express.
     */
    public static final String GROUP_VERSION = "group_version";

    /**
     * Whether the input the row was computed from had been trimmed — a group whose members exceeded the
     * assembly row or byte cap, or the caller's own budget. Without it a truncated group's label is
     * indistinguishable from a complete group's, so a partial judgement would read as a full one.
     */
    public static final String TRUNCATED = "truncated";

    /** Canonical provenance column names, single-sourced for the write-back allow-list and skip logic. */
    public static final Set<String> NAMES = Set.of(
            EVALUATOR_VERSION, PIPELINE_GENERATION, MODEL, ENRICHED_AT, GROUP_VERSION, TRUNCATED);

    /**
     * The tag the injected columns carry. {@code system} rather than {@code provenance}: the two live
     * {@code llm} targets disagree (one uses each), and one of them has to be canonical — {@code system} is
     * the spelling that matches what these columns are everywhere else in the catalog, and the schema view
     * groups by tag, so an analyst sees them together and last.
     */
    public static final String SYSTEM_TAG = "system";

    /**
     * The injected names in their <b>declared order</b>, which is the order schema-definition injects them
     * in. {@link #EVALUATOR_VERSION} is not among them: nothing writes it, so a new target would only ever
     * carry it empty. It stays in {@link #NAMES} for the targets that already hold stamped values.
     *
     * <p>A {@link List}, and separate from {@link #NAMES}, because {@code NAMES} is a {@code Set.of(...)}
     * whose iteration order is unspecified <em>and salted per JVM run</em> — so injecting from it would
     * give two tables built by two runs of the same service different column orders, for no reason a reader
     * could ever discover. {@code NAMES} stays the membership test it is; this is the sequence.
     */
    public static final List<String> DECLARED_ORDER = List.of(
            PIPELINE_GENERATION, MODEL, ENRICHED_AT, GROUP_VERSION, TRUNCATED);

    /**
     * The canonical declaration of each provenance column, keyed by name, for injection into an
     * enrichment's schema definition.
     *
     * <p><b>{@code pipeline_generation} is {@code long}, and the type is load-bearing rather than
     * cosmetic.</b> {@code MaterializeService#addProvenance} switches on the <em>declared</em> type, so a
     * {@code string} declaration would stamp the generation as text and an unsupported one would skip the
     * stamp with a debug log rather than failing the write. A {@code generation} is an unbounded counter,
     * so {@code long} is the only honest declaration.
     *
     * <p>{@code evaluator_version} is <b>not</b> injected: nothing has written it since the fold, so on a
     * new target it could only ever be empty. A caller who declares it still gets it exactly as declared.
     *
     * <p>All five are injected <b>unconditionally</b>, including the three a Class-1 target can never
     * populate ({@code MaterializeService.NOT_APPLICABLE_TO_SQL} holds exactly {@code model},
     * {@code group_version} and {@code truncated}). Injecting selectively would require the table to know
     * its pipeline's kind and trigger, which the catalog deliberately does not — so the cost is three
     * always-empty columns on a Class-1 target, and the descriptions say so outright rather than leaving an
     * analyst to wonder why the column is empty.
     */
    public static List<ColumnInput> canonicalColumns() {
        return List.of(
                column(PIPELINE_GENERATION, QueryFieldType.LONG, "Pipeline generation",
                        "System column stamped by the pipeline that wrote this row, not produced by the"
                                + " model: the pipeline generation that produced it. Read the pipeline's"
                                + " audit trail at that generation to see the declaration — including the"
                                + " prompt — the row was computed under."),
                column(MODEL, QueryFieldType.STRING, "Model",
                        "System column stamped by the pipeline that wrote this row, not produced by the"
                                + " model: the DIAL deployment that produced it. Always empty on an"
                                + " enrichment computed in-database by a sql transform, which makes no"
                                + " model call."),
                column(ENRICHED_AT, QueryFieldType.TIMESTAMP, "Enriched at",
                        "System column stamped by the pipeline that wrote this row, not produced by the"
                                + " model: when the row was computed."),
                column(GROUP_VERSION, QueryFieldType.LONG, "Group version",
                        "System column stamped by the pipeline that wrote this row, not produced by the"
                                + " model: for a group-triggered pipeline, epoch milliseconds of the highest"
                                + " input version across the members the label was computed from — which is"
                                + " what makes a label's input identifiable after the fact. Always empty on"
                                + " a row-grain or sql pipeline, which has no group."),
                column(TRUNCATED, QueryFieldType.BOOLEAN, "Input truncated",
                        "System column stamped by the pipeline that wrote this row, not produced by the"
                                + " model: true when the label saw only part of its input, for any reason."
                                + " That includes the pipeline's own member-selection policy narrowing the"
                                + " group, not only a cap being reached — so on a pipeline that deliberately"
                                + " selects one member per group this is true for every multi-member group"
                                + " by design, and is NOT a quality signal. Always empty on a row-grain or"
                                + " sql pipeline."));
    }

    private static ColumnInput column(
            String name, QueryFieldType type, String displayName, String description) {
        return new ColumnInput(name, name, type, null, null, true, description, displayName,
                SYSTEM_TAG, false, false);
    }

    private ProvenanceColumns() {
    }
}
