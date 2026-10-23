-- Persist the route that served a request on dial_usage_log.
--
-- DIAL Core reports two flat fields on a record that a route served: route_name, the route's
-- configuration key, and route_type, the kind of route ('global' for one from the routes section,
-- 'application' for one configured under an application). It omits both on every other record. Until
-- now the shipped mapping dropped them, so the only route signal left was event_kind, which a URI regex
-- derives and which cannot see global routes or say which route ran.
--
-- This complements the CREATE TABLE in V1 rather than editing it (an applied migration's checksum is
-- fixed): a fresh install runs V1 and every later migration, and an operator applying the DDL out of
-- band does the same in that order.
--
-- Both columns are LowCardinality(Nullable(String)), the same declaration as parent_deployment and
-- response_upstream_uri: values come from a bounded configuration, so dictionary encoding fits. Nullable
-- is load-bearing, because NULL is the "no route served this request" signal and most rows carry it.
-- route_type is deliberately a string rather than an Enum8: the producer owns the domain, and a value
-- outside a closed enum would fail the insert and with it the whole batch, so a new route kind must
-- ingest as reported instead.
--
-- Cost: ADD COLUMN is metadata-only. It schedules no mutation and rewrites no part, so existing rows
-- read back NULL until they are re-ingested, and the sort key is untouched, so dedup behavior is
-- unchanged. The columns are appended after response_body_bytes, route_name then route_type, and the
-- mapping and catalog ordinals follow that physical order.
--
-- Ordering invariant: SchemaValidator refuses at startup a mapping declaring a column the physical
-- table lacks, so this file and the mapping entries in mapping/dial-usage-log.yaml ship together. An
-- operator running with clickhouse.migrations.enabled=false applies this DDL BEFORE the new build.
ALTER TABLE dial_usage_log
    ADD COLUMN IF NOT EXISTS route_name LowCardinality(Nullable(String)),
    ADD COLUMN IF NOT EXISTS route_type LowCardinality(Nullable(String));
