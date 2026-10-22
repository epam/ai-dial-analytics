-- Group-grain enrichment (capability enrichment-registry/enrichment-runtime): a third trigger kind
-- that fires once per *group key* rather than once per row, for labels whose unit of judgement is a set
-- of rows sharing a key (a conversation, a turn) rather than a single row.
--
-- Group-ness lives on the rule, not on the enrichment: enrichment_grain still carries only
-- (table_name, grain_key, cardinality), and a group enrichment is structurally identical to a row one
-- whose grain happens to be a coarser column — which is why the auto-join needs no change at all.
--
-- group_by is the group key and must equal the target enrichment's grain (the service layer enforces
-- that; the DB cannot see across tables here). ready_when is the readiness declaration
-- {signal?, idle?, max_staleness?, cost_ceiling?}: ADAS validates and serves it, the caller evaluates it,
-- which is what keeps dirty-marking proportional to new rows instead of to the number of groups.
ALTER TABLE rule
    ADD COLUMN group_by   TEXT,
    ADD COLUMN ready_when JSONB;

ALTER TABLE rule
    DROP CONSTRAINT rule_trigger_kind_check,
    ADD CONSTRAINT rule_trigger_kind_check CHECK (trigger_kind IN ('ON_INGEST', 'SCHEDULE', 'GROUP'));

-- A group rule declares its group key; no other trigger kind may. rule_cron_iff_schedule needs no
-- rework — it already forces trigger_cron IS NULL for GROUP, whose cadence is the caller's readiness
-- sweep rather than a cron expression.
ALTER TABLE rule
    ADD CONSTRAINT rule_group_by_iff_group CHECK ((trigger_kind = 'GROUP') = (group_by IS NOT NULL));
