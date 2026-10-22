package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database's own guards on the merged `pipeline` table, exercised by inserting <b>past the service
 * layer</b>.
 *
 * <p>That is the whole point of the test. Every state below is already refused by the API — the
 * kind-dispatched validation, the cross-kind field rejection, the trigger-kind defaulting — so a test that
 * went through `POST /v1/pipelines` would pass whether the constraints existed or not. What these
 * constraints protect is the path that does <em>not</em> go through the API: the fold migration itself,
 * a future migration, and an operator with `psql`.
 *
 * <p>`pipeline_aggregate_is_scheduled` matters more than the rest, because it has no counterpart on the
 * pre-fold `rule` table to have been carried over: without it an AGGREGATE row can claim a trigger kind
 * the in-process scheduler never fires and `findDue` never selects, so it would simply never run.
 *
 * <p><b>The evaluator leftovers are gone, and their absence is asserted rather than assumed.</b> The
 * definition lives in the pipeline's own `spec`, so V48 drops the `evaluator` / `evaluator_version` tables,
 * both pointer columns on `pipeline` and their `pipeline_aud` mirrors, and `rule_legacy` /
 * `rule_legacy_aud`. It keeps `legacy_rule_id`: the previous release still maps it, so dropping it would
 * fail every pipeline read on a pod still running that build during a rolling deploy. The next release
 * drops it, and the retention test below is what keeps it until then.
 */
class PipelineRegistryConstraintIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String TARGET = "pc_target";
    private static final String INPUT = "pc_input";

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Inserts a pipeline row directly. Columns the guards do not concern are given the values the fold
     * migration writes, so a failure is unambiguously the constraint under test.
     */
    private void insert(String name, String kind, String triggerKind, String cron,
            String groupBy, String memberSelect, String inputTable) {
        insert(name, kind, triggerKind, cron, groupBy, memberSelect, inputTable, false);
    }

    /**
     * As above, choosing {@code enabled}. Three guards bind only an <b>armed</b> row since V42 — a disabled
     * pipeline is a draft that may not have named its input, or typed its cron, yet — so the refusals those
     * three make are asserted on an armed row, and their absence on a draft beside it.
     */
    private void insert(String name, String kind, String triggerKind, String cron,
            String groupBy, String memberSelect, String inputTable, boolean enabled) {
        jdbc.update("""
                INSERT INTO pipeline (name, kind, target, input_table, spec, trigger_kind, trigger_cron,
                                      trigger_group_by, member_select, enabled, generation,
                                      has_more, rebuilt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, ?::jsonb, ?, 0, false, now(), now(), now())
                """,
                name, kind, TARGET + "_" + name, inputTable, triggerKind, cron, groupBy, memberSelect,
                enabled);
    }

    /**
     * Removes everything {@link #seedTables} added. Required rather than tidy: the PostgreSQL container is
     * a singleton shared by every integration test, and a `table_definition` row left behind is a third
     * SOURCE entity that `/v1/queries/entities` discovers — which fails a schema-discovery test asserting
     * exactly the two seeded ones, in a different class, for a reason nothing points at.
     */
    @AfterEach
    void removeSeededTables() {
        jdbc.update("DELETE FROM pipeline WHERE name LIKE 'pc%' OR target LIKE ?", TARGET + "%");
        jdbc.update("DELETE FROM table_definition WHERE name = ? OR name LIKE ?", INPUT, TARGET + "%");
    }

    private void seedTables(String suffix) {
        jdbc.update("INSERT INTO table_definition (name, type, status) VALUES (?, 'SOURCE', 'ACTIVE') "
                + "ON CONFLICT (name) DO NOTHING", INPUT);
        jdbc.update("INSERT INTO table_definition (name, type, status, source_table, grain_key) "
                + "VALUES (?, 'ENRICHMENT', 'ACTIVE', ?, 'event_id') ON CONFLICT (name) DO NOTHING",
                TARGET + "_" + suffix, INPUT);
    }

    @Test
    void theEvaluatorArchiveAndItsPointersAreDropped() {
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_name IN"
                        + " ('evaluator', 'evaluator_version', 'rule_legacy', 'rule_legacy_aud')", String.class))
                .as("the archive tables and the rule fold's leftovers are dropped")
                .isEmpty();
        assertThat(jdbc.queryForList(
                "SELECT table_name || '.' || column_name FROM information_schema.columns"
                        + " WHERE table_name IN ('pipeline', 'pipeline_aud')"
                        + " AND column_name IN ('evaluator_name', 'evaluator_version')", String.class))
                .as("both pointer columns and their audit mirrors are dropped")
                .isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'pipeline_evaluator_name_idx'",
                Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conrelid = 'pipeline'::regclass"
                        + " AND conname LIKE '%evaluator%'", Integer.class))
                .as("neither the V41-dropped CHECK nor the foreign key to evaluator remains")
                .isZero();
    }

    /**
     * {@code legacy_rule_id} is <b>retained</b> for one release, and this is the test that keeps it so: the
     * previous release's {@code PipelineEntity} still maps it, so dropping it would fail every pipeline read
     * on a pod still running that build for the length of a rolling deploy. The next release drops it.
     */
    @Test
    void theLegacyRuleIdColumnIsRetainedForOneMoreRelease() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'pipeline'"
                        + " AND column_name = 'legacy_rule_id'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void anAggregatePipelineClaimingTheGroupTriggerIsRefused() {
        seedTables("agggroup");

        // `trigger_kind` widened to admit the enrich triggers; without this guard an AGGREGATE row could
        // claim one and then never run, with nothing reporting why.
        assertThatThrownBy(() ->
                insert("agggroup", "AGGREGATE", "GROUP", null, "chat_id", null, INPUT))
                .hasMessageContaining("pipeline_aggregate_is_scheduled");
    }

    @Test
    void anAggregatePipelineWithNoInputIsRefused() {
        seedTables("agginput");

        // One-way rather than a biconditional: an ENRICH row may follow its target's parent.
        assertThatThrownBy(() ->
                insert("agginput", "AGGREGATE", "SCHEDULE", "0 0 * * * *", null, null, null, true))
                // Two guards now refuse this row — the re-scoped one and pipeline_armed_is_complete, which
                // states the same rule positively — and which Postgres names first is not part of the contract.
                // Each is judged on its own in DeferPipelineValidationMigrationTest.
                .hasMessageFindingMatch("pipeline_input_required_unless_enrich|pipeline_armed_is_complete");
        // A draft may not have named its input yet.
        assertThatCode(() ->
                insert("agginput", "AGGREGATE", "SCHEDULE", "0 0 * * * *", null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void scheduledPipelineWithoutCronIsRefusedAndTheReverse() {
        seedTables("cron");
        seedTables("cron2");

        assertThatThrownBy(() ->
                insert("cron", "AGGREGATE", "SCHEDULE", null, null, null, INPUT, true))
                .hasMessageContaining("pipeline_cron_iff_schedule");
        assertThatThrownBy(() ->
                insert("cron2", "ENRICH", "GROUP", "0 0 * * * *", "chat_id", null, INPUT, true))
                .hasMessageContaining("pipeline_cron_iff_schedule");
        // "Picked schedule, has not typed the cron yet" is exactly the state a draft is in.
        assertThatCode(() -> insert("cron", "AGGREGATE", "SCHEDULE", null, null, null, INPUT))
                .doesNotThrowAnyException();
    }

    @Test
    void theRetiredOnIngestTriggerKindIsRefused() {
        seedTables("retired");

        // V44 narrows the closed set once it has rewritten every row: a row-grain pipeline is SCHEDULE.
        assertThatThrownBy(() -> insert("retired", "ENRICH", "ON_INGEST", null, null, null, INPUT))
                .hasMessageContaining("pipeline_trigger_kind_check");
    }

    @Test
    void memberSelectionOffTheGroupTriggerIsRefused() {
        seedTables("member");

        // One-way, as it was on `rule`: a group pipeline need not narrow its members.
        assertThatThrownBy(() -> insert("member", "ENRICH", "SCHEDULE", null, null,
                "{\"limit\":1}", INPUT))
                .hasMessageContaining("pipeline_member_select_only_group");
    }

    @Test
    void theFoldedRuleColumnsAreAllPresent() {
        // The migration is the only writer of these, and a column silently missing would surface as a
        // Hibernate mapping failure at first read rather than here.
        assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'pipeline'",
                String.class))
                .contains("trigger_kind", "trigger_group_by", "ready_when", "member_select",
                        "materialized_through_version", "materialized_through_identity", "drained_at",
                        "history_rederived_at", "materialized_generation");
    }
}
