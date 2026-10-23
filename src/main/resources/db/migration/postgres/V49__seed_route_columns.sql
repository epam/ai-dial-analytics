-- Register dial_usage_log.route_name and dial_usage_log.route_type in the catalog.
--
-- The physical columns are added by db/migration/clickhouse/V15__dial_usage_log_route.sql; this
-- migration only registers them, so it issues no ClickHouse ALTER — `sensitive`, `heavy`, `tag`,
-- `display_name` and `description` are catalog-only metadata and never appear in any DDL. dial_usage_log
-- is a seeded SOURCE in status ACTIVE, so its catalog rows can only be edited by migration
-- (PATCH /v1/tables/{name}/schema rejects a system-owned table).
--
-- Ordinals 39 and 40 put them last, matching the physical table: ADD COLUMN appends, so they follow
-- response_body_bytes (ordinal 37) and the gap V38 left at 38, rather than sitting beside deployment.
-- Catalog order is what a wildcard projection renders in, so it has to track the DDL; tag-ordered
-- discovery still groups them with deployment.
--
-- tag 'deployment' answers "what did they call?" alongside deployment, parent_deployment and
-- execution_path, and a route is the same kind of answer. 'deployment' is already present in
-- dial_usage_log's tag_order (V10__add_table_tag_order.sql), so no ordering change is needed.
--
-- nullable TRUE matches the physical LowCardinality(Nullable(String)): NULL is how "no route served
-- this request" reads. Neither column is sensitive (the values are operator configuration keys, not
-- user content) nor heavy (they are short).
INSERT INTO column_mapping (table_name, source_name, name, type, element_type, nullable, description,
                            display_name, tag, sensitive, heavy, ordinal)
VALUES ('dial_usage_log', 'route_name', 'route_name', 'string', NULL, TRUE,
        'Name of the route that served the request, as configured in DIAL Core. NULL when no route '
            || 'served it, and on rows ingested before the column existed until they are re-ingested. '
            || 'Unique only together with route_type and, for an application route, deployment.',
        'Route name', 'deployment', FALSE, FALSE, 39),
       ('dial_usage_log', 'route_type', 'route_type', 'string', NULL, TRUE,
        'Kind of route that served the request: global (a route from the routes section) or '
            || 'application (a route configured under the application in deployment); any other value '
            || 'the producer reports is stored as reported. NULL when no route served the request, and on '
            || 'rows ingested before the column existed until they are re-ingested.',
        'Route type', 'deployment', FALSE, FALSE, 40);
