## ADDED Requirements

### Requirement: rate_analytics retention expiry drops whole daily partitions

The `rate_analytics` table's `TTL` expiry SHALL drop whole parts rather than rewrite them
(`ttl_only_drop_parts = 1`), for the same reason and on the same terms as the `dial_usage_log` target it
is ingested alongside (see [data-sync](../data-sync/spec.md)): a row-level TTL rewrite needs free disk
equal to the part it rewrites, so on a full volume retention cannot run at all. The table is partitioned
by `toYYYYMMDD(request_time)` with its `TTL` expressed on `request_time`, so a part holds one day's rows
and expiry is quantized to that part — a row MAY outlive its `TTL` by up to one day, which is immaterial
against a one-year retention.

The setting SHALL be applied by the same versioned ClickHouse migration that applies it to the other
ingest target, so it is present whether Flyway applies the migrations or an operator applies them out of
band in version order.

#### Scenario: Provisioned table declares whole-part expiry

- **WHEN** `rate_analytics` is provisioned by applying the ClickHouse migrations in version order
- **THEN** its declaration carries `ttl_only_drop_parts = 1`, so a later `TTL` expiry drops expired
  daily parts instead of rewriting them
