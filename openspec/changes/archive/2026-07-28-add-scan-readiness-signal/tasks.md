## 1. Project the readiness verdict

- [x] 1.1 `ScanService`: public `SIGNAL_COLUMN = "__signal"`, documented as the payload counterpart of the
      internal `__cursor_*` aliases (double-underscored so it cannot collide with a source column, but not
      stripped from the response)
- [x] 1.2 `ScanService.scan`: keep the group rule's signal analysis as a `FilterAnalysis` instead of
      reducing it to its columns, and stop adding those columns to the projection
- [x] 1.3 `buildScan` takes the signal's `FilterNode` and, when non-null, projects
      `DSL.field(filterTranslator.toCondition(signal, bindings)).as(SIGNAL_COLUMN)`; a null node drops the
      column entirely, so a non-signal rule's SQL is unchanged
- [x] 1.4 `sensitiveGuard.enforce` runs over `projection ∪ signal columns` — the guard rejects a sensitive
      column being read *or derived*, so evaluating the predicate server-side must not relax it
- [x] 1.5 `toPage` normalizes the verdict to a JSON boolean (`UInt8` → boolean, null → false: an unknown
      verdict fails closed rather than firing an evaluation)

## 2. Tests

- [x] 2.1 `GroupEnrichmentIntegrationTest`: a signal rule's scan returns `__signal` false for a
      non-signalling row and true for a signalling one, and the raw column the predicate reads is absent
- [x] 2.2 `GroupEnrichmentIntegrationTest`: a group rule declaring no signal carries no `__signal` column
      at all (rather than a constant one)

## 3. Specs and verification

- [x] 3.1 Delta spec `enrichment-runtime`: "Scan projection and version isolation" — the verdict replaces
      the raw signal columns, with the reason; the sensitive re-check covers columns a governed predicate
      derives from
- [x] 3.2 `./gradlew clean build` green (checkstyle + unit + integration, Docker required)
