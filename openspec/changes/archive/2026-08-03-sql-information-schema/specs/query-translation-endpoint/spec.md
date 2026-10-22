## ADDED Requirements

### Requirement: Catalog metadata statements have no DSL translation

`POST /v1/queries/translate-sql` SHALL reject a statement against `INFORMATION_SCHEMA` with `400` and
the stable `bad_request` code, because a catalog query has no `StructuredQuery` equivalent: the DSL
addresses queryable entities, and the catalog relations are not entities.

The message SHALL say that catalog metadata statements cannot be translated, rather than reporting the
relation as an unknown entity — the relation does exist and is executable elsewhere, so "not found"
would send a caller looking for a typo.

This SHALL NOT weaken the endpoint's contract that a successful translation is executable: a statement
that translates continues to be one `POST /v1/queries/execute` accepts.

#### Scenario: Metadata statement is not translatable

- **WHEN** a client posts `SELECT TABLE_NAME FROM INFORMATION_SCHEMA."TABLES"` to `translate-sql`
- **THEN** the response is `400` with `error` `bad_request` and a message stating that catalog metadata
  statements have no structured-query equivalent

#### Scenario: Data statements are unaffected

- **WHEN** a client posts a statement against a queryable entity, qualified or bare
- **THEN** it translates as before
