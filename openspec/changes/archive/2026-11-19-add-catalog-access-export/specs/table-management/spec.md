## MODIFIED Requirements

### Requirement: Table definitions are promoted between environments through catalog export and import

A user table's identity and schema SHALL be exportable and importable as defined by
[catalog-export-import](../catalog-export-import/spec.md), and an import SHALL create a table only through the same create and
define-schema steps as `POST /v1/tables` and `PUT /v1/tables/{name}/schema`. Every rule in this capability therefore applies to an
imported table: identifier and reserved-name checks, `max-tables` and `max-columns-per-table`, the write-mode rules and provenance
injection. That capability owns which tables travel (system, OTLP landing, PENDING and FAILED tables do not), how columns travel ("Columns
travel as stored") and the known provenance asymmetry. An import never patches or deletes an existing table's definition or columns; it only creates tables and
deletes the ones it created during compensation. Called with `include_access=true`, it also writes access entries onto
existing tables, as that capability's "Access import" defines.

#### Scenario: An imported table obeys the create rules

- **WHEN** a bundle table would bring the target over `max-tables`, or over `max-columns-per-table` once provenance is injected
- **THEN** the import is refused with `422` `catalog_import_invalid` and nothing is written, as a direct create would be refused

#### Scenario: An access import changes no existing table's columns or settings

- **WHEN** a bundle carrying access for an existing table `t` is imported with `resolution_policy=SKIP_IF_EXISTS` and
  `include_access=true`
- **THEN** `t`'s columns, description and settings are unchanged, and only its access entries change
