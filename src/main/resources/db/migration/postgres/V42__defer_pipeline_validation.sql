-- Defer a pipeline's declaration gate from "when the row is written" to "when the row is armed"
-- (capability pipeline-registry). A disabled pipeline is now a draft: it may be stored with any subset of
-- its declaration, and the kind gate runs when a write leaves it enabled. The storage guards follow the
-- same line: what they assert of an ARMED row is unchanged, and a disabled row is no longer held to it.
--
-- Every CHECK below is written so it evaluates to FALSE, never NULL, on an armed row whose `trigger_kind`
-- is missing. A CHECK passes on UNKNOWN, so a bare `(trigger_kind = 'SCHEDULE') = (...)` would admit exactly
-- the row these guards exist to refuse; the explicit `trigger_kind IS NOT NULL AND` is what closes that.
--
-- Left untouched, deliberately:
--   * `target NOT NULL`, its UNIQUE, its FK and ON DELETE CASCADE -- `target` stays required at registration,
--     so a draft can never outlive the table it writes;
--   * `pipeline_member_select_only_group` and `pipeline_aggregate_is_scheduled` -- a NULL `trigger_kind`
--     already satisfies both (UNKNOWN passes), and they still catch a genuine cross-field contradiction on
--     a draft, so relaxing them would delete two guards for nothing;
--   * `pipeline_trigger_kind_check` -- the closed set is unchanged and NULL passes it.
--
-- Data-safe: every existing row is either enabled and complete, or disabled and no longer constrained.
-- No column is added, so `pipeline_aud` needs no widening.

-- A draft may not have picked its trigger yet.
ALTER TABLE pipeline ALTER COLUMN trigger_kind DROP NOT NULL;

-- An armed AGGREGATE pipeline must still name its input; a draft may not know it yet.
ALTER TABLE pipeline DROP CONSTRAINT pipeline_input_required_unless_enrich;
ALTER TABLE pipeline ADD CONSTRAINT pipeline_input_required_unless_enrich
    CHECK (NOT enabled OR input_table IS NOT NULL OR kind = 'ENRICH');

-- "Picked schedule, has not typed the cron yet" is precisely the state a draft is in, so the biconditional
-- binds only once the pipeline is armed.
ALTER TABLE pipeline DROP CONSTRAINT pipeline_cron_iff_schedule;
ALTER TABLE pipeline ADD CONSTRAINT pipeline_cron_iff_schedule
    CHECK (NOT enabled
           OR (trigger_kind IS NOT NULL AND (trigger_kind = 'SCHEDULE') = (trigger_cron IS NOT NULL)));

ALTER TABLE pipeline DROP CONSTRAINT pipeline_group_by_iff_group;
ALTER TABLE pipeline ADD CONSTRAINT pipeline_group_by_iff_group
    CHECK (NOT enabled
           OR (trigger_kind IS NOT NULL AND (trigger_kind = 'GROUP') = (trigger_group_by IS NOT NULL)));

-- The positive statement the three re-scoped guards imply between them, said once: an armed row names a
-- trigger kind and, unless it is ENRICH, an input.
ALTER TABLE pipeline ADD CONSTRAINT pipeline_armed_is_complete
    CHECK (NOT enabled OR (trigger_kind IS NOT NULL AND (kind = 'ENRICH' OR input_table IS NOT NULL)));
