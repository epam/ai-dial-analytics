## 1. One home for the pair's rules

- [x] 1.1 New `service/table/ScanMetadataRules`: `requireDeclarable` (the kind rule — an enrichment declares
      neither member) and `validateSource` (membership, `timestamp` version, non-nullable, non-`sensitive`,
      the `_updated_at` allowance for `upsert_by_key`), each message naming the property and why it matters
- [x] 1.2 `TableSchemaCreationService.requireComplete`: the pair is no longer required for a source; delegate
      the kind rule, which now rejects a `version_column` on an enrichment as well as an `identity_column`
- [x] 1.3 `CatalogSchemaWriter.persistSourceSchema` delegates the physical rules;
      `persistEnrichmentSchema` no longer accepts or persists a `version_column`
- [x] 1.4 Name the write discipline when `_updated_at` is declared on an `append` source, instead of
      reporting it as an undeclared column

## 2. Documentation that had drifted

- [x] 2.1 `ScanSourceMetadata` javadoc: the pair is caller-declared and optional, what the table API verifies
      versus what stays the caller's promise, and why forcing a declaration would be worse (it still
      described the pair as operator-only)
- [x] 2.2 `TableSchemaCreationService`, `DefineSchemaInput`, `DefineSchemaRequest`, `TableDto`: optionality,
      the physical rules, the non-unique-identity trap, and an example value a caller can actually declare
- [x] 2.3 `AggregateSpecValidator.validateInput`: say what the scannability guard covers now that a source may
      legitimately declare no pair
- [x] 2.4 `docs/heavy-columns.md`: its `POST .../schema` example, and a note that the pair is unrelated to
      heaviness

## 3. Tests

- [x] 3.1 `CatalogSchemaWriterTest`: a non-`timestamp` version, a nullable member, a `sensitive` member, the
      engine version accepted for `upsert_by_key` and rejected for `append`
- [x] 3.2 `TableSchemaCreationServiceTest`: either member on an enrichment is a 422 and nothing is persisted;
      a source declaring nothing is not an error
- [x] 3.3 `TableManagementIntegrationTest`: the three physical rules answer 422 at define time and materialize
      nothing; a source declaring nothing (or one member) materializes and reports what it declared; an
      enrichment is refused either member; the enrichment `version_column` tests are gone
- [x] 3.4 `IntegrationTables`: no defaulted pair on either source helper — the identity half is a claim about
      the data, so a fixture makes it explicitly or not at all
- [x] 3.5 `EnrichmentScanIntegrationTest`: the unconfigured-source test needs no fabricated state again, and
      the counterpart still declares the pair through the endpoints

## 4. Specs

- [x] 4.1 `table-management`: the pair is optional with the physical rules on a declared member, an enrichment
      refuses both, and the reason optionality is load-bearing (a multi-column grain has no single identity)
- [x] 4.2 `enrichment-runtime`: what the table API verifies, and why a false pair is worse than none
- [x] 4.3 `aggregate-pipeline`: the input's scannability requirement (previously enforced in code only), and
      when a rollup target may declare the pair — with the silent-skip mechanism spelled out
- [x] 4.4 `openspec/project.md`: optionality, the physical rules, the freeze, an enrichment declaring neither
- [x] 4.5 Correct the archived `expose-scan-metadata` artifacts, which still described the pair as optional
      and unfrozen, and record that its required-ness was withdrawn here
- [x] 4.6 `./gradlew clean build` and `openspec validate` both pass
