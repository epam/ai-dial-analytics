## MODIFIED Requirements

### Requirement: Import order and no grants

Import SHALL apply objects in dependency order: source tables, then enrichment tables, then `enrich` pipelines, then `aggregate`
pipelines. Import SHALL write no access: every table it creates has no access entry, so until an operator grants access with
`PUT /v1/tables/{name}/access?role=<role>` only callers holding the global privileges can see or read a created source. A created
enrichment has no read-side access of its own: it is readable at once by every caller that may read its source, which may be a table
the target already had and had granted, subject to that source's column policies (see
[table-access-control](../table-access-control/spec.md)).

#### Scenario: Imported table has no grants

- **WHEN** a table that has access entries on the source is exported and imported
- **THEN** `GET /v1/tables/{name}/access` on the target returns an empty `roles` object

#### Scenario: An imported enrichment of an existing source follows its grants

- **GIVEN** a target on which `analytics-viewers`' entry on `dial_usage_log` carries `read` with no column policy
- **WHEN** a bundle creating an enrichment of `dial_usage_log` is imported
- **THEN** a caller carrying only `analytics-viewers` reads the new enrichment's non-sensitive columns at once, and
  `GET /v1/tables/{name}/access` on the enrichment returns an empty `roles` object
