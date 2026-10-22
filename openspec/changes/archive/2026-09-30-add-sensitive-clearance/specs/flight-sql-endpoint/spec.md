## MODIFIED Requirements

### Requirement: Catalog metadata honors the caller's column visibility

Catalog RPCs SHALL resolve the catalog under the Flight caller's identity, exactly as the statement
path does, so a column hidden from the caller by sensitive-column filtering is absent from the
metadata as well as from query results. A caller without `TABLE_SENSITIVE` on the table that owns the column SHALL NOT be able to learn
from
any catalog RPC that a `sensitive` column exists. When `security.mode=none` the filtering fails open,
matching the query path.

#### Scenario: Sensitive columns absent from a read-only caller's table schema

- **WHEN** `security.mode=oidc` and a caller holding `TABLE_READ` but not `TABLE_SENSITIVE` calls
  `CommandGetTables` with
  `include_schema=true` for `dial_usage_log`, whose `jwt_claims` and `request_tags` columns are
  marked `sensitive`
- **THEN** the returned Arrow schema carries neither `jwt_claims` nor `request_tags`, while a
  caller entitled to that table's sensitive columns receives a schema for the same entity carrying both
