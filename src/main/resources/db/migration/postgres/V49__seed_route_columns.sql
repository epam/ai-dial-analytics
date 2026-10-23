-- Catalog rows for the dial_usage_log route columns added by clickhouse/V15.
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
