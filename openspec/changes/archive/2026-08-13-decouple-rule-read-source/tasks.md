## 1. Catalog schema

- [x] 1.1 Add `db/migration/postgres/V26__rule_read_source.sql`: nullable `rule.source_table TEXT
  REFERENCES table_definition (name)` (RESTRICT — deliberately not `ON DELETE CASCADE`, see design
  decision 7) plus `rule_source_table_idx` on it. No backfill, no `NOT NULL`.
- [x] 1.2 Add the `sourceTable` field to `RuleEntity` and correct its class javadoc, which currently
  states the scan source is derived and never stored.

## 2. Read-source resolution

- [x] 2.1 Add `source` to `RuleRegistration` and set it (trimmed to null) in `RuleService#apply`, so an
  omitted `source` on `PUT` re-defaults against the (possibly changed) target enrichment.
- [x] 2.2 Change `RuleService#sourceOf` to resolve `rule.sourceTable ?? target.sourceTable`, and update
  `#resolve`, `#resolveForScan`, and the class javadoc accordingly.
- [x] 2.3 Change `RuleService#sync` / `#versionColumnsOf` to batch over the returned rules' **read**
  sources (declared or derived) rather than over the target definitions' `source_table`, keeping the
  constant-query-count guarantee.
- [x] 2.4 Confirm `ScanService`, `GroupFetchService`, and `MaterializeService` need no change — all three
  consume `ResolvedRule#source()`. Verify by reading, not by assuming; note anything that resolves a
  source independently.

## 3. Governance gate

- [x] 3.1 In `RuleGovernanceValidator#validate`, resolve the rule's read source instead of reading
  `target.getSourceTable()`, and reject a declared source that is unknown or is not `TableType.SOURCE`
  with `RuleValidationException` (422).
- [x] 3.2 Add the grain-key invariant **for a declared source**: the target enrichment's grain key must be
  a declared column of the read source (matched on `name` or `source_name`) with a type compatible with
  the type the target is keyed at, reusing `EnrichmentTypeMapping#isCompatible`. Error names the grain key.
- [x] 3.3 Add the scannability invariant **for a declared source** by calling
  `ScanSourceMetadata#cursorColumns` on it at registration, so registration and scan agree on what
  "scannable" means. Re-attribute its `QueryValidationException` to a 422 `rule_validation_failed`.
  Deliberately not applied to a derived source: that would invalidate rules valid today.
- [x] 3.4 Point `requireIdentityGrain` (Class-1) at the read source, and update its javadoc: the target's
  grain key must equal the **read source's** `identity_column`.
- [x] 3.5 Update the javadoc on `SourceScopeGuard`, `FilterSqlValidator`, and `ExprSqlValidator` to say
  "the rule's read source" — no logic change; they are already parameterized by source.

## 4. Generation bump on a version-column repoint

- [x] 4.1 Add a `RuleRepository` query for rules whose `source_table` equals a given table.
- [x] 4.2 Rewrite `RuleGenerationService#onVersionColumnRepointed` to bump the union of (rules declaring
  that read source) and (rules with a null `source_table` whose target enrichment hangs off it), and to
  **exclude** rules whose target hangs off the repointed table but which declare another read source.
  Update the class javadoc, which asserts a rule's source is its target's `source_table`.

## 5. Delete dependency

- [x] 5.1 In `TableCatalogService#delete`, reject deleting a table a rule declares as its read source
  with the same HTTP 409 used for enrichments and pipeline inputs, naming the rule.

## 6. API surface

- [x] 6.1 Add the optional `source` field to `CreateRuleRequest` with a `@Schema` description, and carry
  it through the rule mapper into `RuleRegistration`.
- [x] 6.2 Update `RuleDto`'s `source` and `version_column` descriptions: the read source (declared or
  derived from the target enrichment) and *its* version column. No field is added or removed.

## 7. Tests

- [x] 7.1 `RuleServiceTest`: read-source resolution — declared wins, omitted derives, `PUT` without
  `source` re-defaults.
- [x] 7.2 `RuleRegistryIntegrationTest`: create a rule with `source` naming another source table
  (`rate_analytics` into a `conversations` enrichment keyed on `chat_id`); assert 201, the returned
  `source`/`version_column`, and that `GET /v1/rules` reports the declared source. Assert the
  target enrichment's own `source_table` is unchanged.
- [x] 7.3 `RuleRegistryIntegrationTest`: each new rejection — unknown source, enrichment-kind source,
  grain key absent from the read source, grain key present at an incompatible type, read source without
  scan metadata — is 422 `rule_validation_failed`.
- [x] 7.4 `RuleRegistryIntegrationTest`: source-scoping still bites against the *read* source — a
  `filter_sql` over a column of the target's `source_table` that the declared read source lacks is 422.
- [x] 7.5 `RuleRegistryIntegrationTest`: repointing `version_column` bumps a rule that declares that read
  source, and does **not** bump a rule whose target hangs off it but which declares another.
- [x] 7.6 `EnrichmentScanIntegrationTest` / `GroupEnrichmentIntegrationTest`: scan and group-assembly of a
  rule with a declared read source page that table, and a cursor issued before a `PUT` that changes the
  read source is rejected with 400 `invalid_cursor` rather than mis-seeking.
- [x] 7.7 `RuleDataAuthorizationIntegrationTest`: a caller holding only the target enrichment's access
  may scan a rule whose declared read source is another table, and the sensitive-column re-check is
  applied against that read source's columns.
- [x] 7.8 `Class1MaterializeIntegrationTest`: a `sql` rule declaring a read source whose
  `identity_column` is the target's grain key materializes from it; one whose grain is coarser is
  rejected at registration.
- [x] 7.9 `TableManagementIntegrationTest`: deleting a source a rule declares as its read source is 409
  and drops nothing.

## 8. Docs and verification

- [x] 8.1 Docs: confirmed `docs/configuration.md` needs no change (no config property added — verified
  `application.yml` and `configuration.md` are untouched). There is no rule/enrichment document under
  `docs/` to amend: that capability is documented by `openspec/specs/enrichment-*` (updated by this
  change's delta specs) and by the OpenAPI `@Schema` text on `CreateRuleRequest.source` / `RuleDto`
  (updated in 6.1/6.2). README has no rule-registry section; adding one is out of this change's scope.
- [x] 8.2 Run `./gradlew clean build` (Docker daemon required for the integration tests) and confirm it
  passes — compile, checkstyle, and the full test suite. Green.
