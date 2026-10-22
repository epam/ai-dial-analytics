-- Retire the `on_ingest` trigger kind and the `advanced.scan_every` knob (capability enrichment-registry,
-- "Trigger kinds and the default schedule"). `on_ingest` was a row-grain trigger that differed from
-- `schedule` only in its clock: a fixed delay of `scan_every` instead of a cron, over the same scan.
--
-- What this migration does:
--   1. every ON_INGEST row becomes SCHEDULE, with a cron derived from its stored `scan_every` (or the
--      pre-rename `cadence`), rounded UP to the next cadence a cron expresses evenly;
--   2. every ENRICH SCHEDULE row still without a cron (a draft) gets the default the write path now applies;
--   3. `scan_every` and `cadence` are removed from every ENRICH spec, GROUP pipelines included;
--   4. `pipeline_trigger_kind_check` narrows to SCHEDULE and GROUP.
--
-- `generation` and `updated_at` are deliberately NOT touched. A generation bump discards every Class-1
-- pipeline's materialized position and re-syncs every runner lane, for a change no operator made. The runner
-- still picks up the new schedule: it reschedules every pipeline on its first poll after a restart, and it
-- restarts with the paired deploy that raises the runner contract to 3.
--
-- No `pipeline_aud` revision is written. Envers is not running here and a synthetic revision would claim an
-- actor; the next real write records the new trigger. The historical ON_INGEST revisions stay as written and
-- are read as SCHEDULE by `TriggerKindConverter`.
--
-- The cron's second is the first four bytes of md5(name), unsigned, mod 60: the same value
-- `DefaultSchedule.cronFor` computes, so a migrated pipeline and a re-registered one agree.

CREATE FUNCTION pg_temp.retired_cadence_seconds(raw TEXT) RETURNS NUMERIC
    LANGUAGE plpgsql AS
$$
DECLARE
    v TEXT := btrim(raw);
    parts TEXT[];
BEGIN
    IF v IS NULL OR v = '' THEN
        RETURN NULL;
    END IF;
    -- Spring's DurationStyle.SIMPLE: an integer and an optional unit, milliseconds when it has none.
    parts := regexp_match(v, '^\+?([0-9]+)(ns|us|ms|s|m|h|d)?$', 'i');
    IF parts IS NOT NULL THEN
        RETURN parts[1]::NUMERIC * CASE lower(coalesce(parts[2], 'ms'))
            WHEN 'ns' THEN 0.000000001 WHEN 'us' THEN 0.000001 WHEN 'ms' THEN 0.001
            WHEN 's' THEN 1 WHEN 'm' THEN 60 WHEN 'h' THEN 3600 ELSE 86400 END;
    END IF;
    -- DurationStyle.ISO8601, e.g. PT1M30S, which PostgreSQL's interval input accepts as written.
    IF v ~* '^P' THEN
        BEGIN
            RETURN extract(EPOCH FROM v::INTERVAL);
        EXCEPTION WHEN OTHERS THEN
            RETURN NULL;
        END;
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION pg_temp.retired_cadence_cron(name TEXT, seconds NUMERIC) RETURNS TEXT
    LANGUAGE plpgsql AS
$$
DECLARE
    s TEXT := (('x' || substr(md5(name), 1, 8))::BIT(32)::BIGINT % 60)::TEXT;
    step INT;
BEGIN
    -- Absent, unparseable and sub-minute cadences all become every minute.
    IF seconds IS NULL OR seconds <= 60 THEN
        RETURN s || ' * * * * *';
    END IF;
    IF seconds <= 3600 THEN
        SELECT min(d) INTO step FROM unnest(ARRAY[1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30, 60]) AS d
            WHERE d >= ceil(seconds / 60);
        RETURN CASE WHEN step = 60 THEN s || ' 0 * * * *' ELSE s || ' */' || step || ' * * * *' END;
    END IF;
    -- Above a day there is no larger even step, so a longer cadence is capped at daily.
    SELECT coalesce(min(d), 24) INTO step FROM unnest(ARRAY[1, 2, 3, 4, 6, 8, 12, 24]) AS d
        WHERE d >= ceil(seconds / 3600);
    RETURN CASE WHEN step = 24 THEN s || ' 0 0 * * *' ELSE s || ' 0 */' || step || ' * * *' END;
END;
$$;

DO
$$
DECLARE
    r RECORD;
    cron TEXT;
BEGIN
    FOR r IN SELECT name, coalesce(spec ->> 'scan_every', spec ->> 'cadence') AS cadence
             FROM pipeline WHERE trigger_kind = 'ON_INGEST' ORDER BY name
    LOOP
        cron := pg_temp.retired_cadence_cron(r.name, pg_temp.retired_cadence_seconds(r.cadence));
        UPDATE pipeline SET trigger_kind = 'SCHEDULE', trigger_cron = cron WHERE name = r.name;
        RAISE NOTICE 'pipeline %: on_ingest (scan_every %) -> schedule (%)', r.name, coalesce(r.cadence, 'unset'), cron;
    END LOOP;

    FOR r IN SELECT name FROM pipeline
             WHERE kind = 'ENRICH' AND trigger_kind = 'SCHEDULE' AND trigger_cron IS NULL ORDER BY name
    LOOP
        cron := pg_temp.retired_cadence_cron(r.name, NULL);
        UPDATE pipeline SET trigger_cron = cron WHERE name = r.name;
        RAISE NOTICE 'pipeline %: schedule draft without a cron -> %', r.name, cron;
    END LOOP;
END;
$$;

UPDATE pipeline SET spec = spec - 'scan_every' - 'cadence'
WHERE kind = 'ENRICH' AND (spec ? 'scan_every' OR spec ? 'cadence');

DROP FUNCTION pg_temp.retired_cadence_cron(TEXT, NUMERIC);
DROP FUNCTION pg_temp.retired_cadence_seconds(TEXT);

ALTER TABLE pipeline DROP CONSTRAINT pipeline_trigger_kind_check;
ALTER TABLE pipeline ADD CONSTRAINT pipeline_trigger_kind_check
    CHECK (trigger_kind IN ('SCHEDULE', 'GROUP'));
