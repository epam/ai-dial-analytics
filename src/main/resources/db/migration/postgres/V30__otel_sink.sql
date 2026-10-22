-- OTLP sinks (capability otel-sink): a named landing zone for OpenTelemetry logs/traces/metrics.
--
-- A sink owns one catalog source table per signal it enabled, named `otel_<sink>_<signal>`. The
-- mapping is recorded explicitly rather than derived by pattern-matching `table_definition` for
-- `otel_%_logs`: a user-created table that happens to match the pattern would otherwise appear as a
-- phantom sink, the sink's own description and creation time would have nowhere to live, and DELETE
-- would have no authoritative answer to "which tables does this sink own".
--
-- There is deliberately NO foreign key from `otel_sink_signal.table_name` to `table_definition.name`.
-- The sink's tables are ordinary, non-`system` catalog tables (see design D3), so a FULL_ADMIN can
-- delete one through /v1/tables. A cascade would silently erase the signal and leave the sink
-- claiming fewer signals than it was created with; a restrict would make the table undeletable
-- through an endpoint that is specified to allow it. Instead the row survives, the sink view reports
-- the signal's table as absent, and ingest for it answers 409.
--
-- Not @Audited: like rules, pipelines and saved queries, a sink is not catalog schema — the audited
-- half is the `table_definition`/`column_mapping` rows the sink creates, which are audited already.
CREATE TABLE otel_sink (
    -- The sink name, and the path segment in /v1/otel/sink/<name>. Constrained to at most 51
    -- characters so the longest derived table name (`otel_` + name + `_metrics`) still fits the
    -- 64-character catalog identifier limit. The snake_case grammar itself is enforced in the
    -- service layer by the same Identifiers rule every table name goes through.
    name        TEXT PRIMARY KEY CHECK (length(name) BETWEEN 1 AND 51),
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE otel_sink_signal (
    sink_name  TEXT NOT NULL REFERENCES otel_sink (name) ON DELETE CASCADE,
    -- Uppercase enum-like TEXT with a CHECK, the @Enumerated(EnumType.STRING) convention used across
    -- this catalog.
    signal     TEXT NOT NULL CHECK (signal IN ('LOGS', 'TRACES', 'METRICS')),
    -- The catalog table backing this signal. UNIQUE so two sinks can never claim the same table,
    -- which the `otel_<sink>_<signal>` derivation already makes impossible but which is cheap to
    -- assert here rather than trust.
    table_name TEXT NOT NULL UNIQUE,

    PRIMARY KEY (sink_name, signal)
);
