## 1. Shared entity field view

- [x] 1.1 Move `AggregateSpecValidator`'s `Columns`/`Columns.Field` records and its `entityColumns(inputTable)` builder into `service/enrichment/support/` as a Spring `@Component` (e.g. `EntityFieldView`), preserving: catalog-row sourcing (not the role-filtered query model), `putIfAbsent` on qualified names, the `enrichmentSourced`/`ownerOf`/`enrichmentOwners` accessors, and membership from `CatalogQueryService#activeEnrichmentsOf`
- [x] 1.2 Repoint `AggregateSpecValidator` at the extracted component with no behaviour change; `AggregateSpecValidatorTest` must pass unchanged
- [x] 1.3 Carry over the `unknownField` message shape so a qualified reference names the enrichment it was resolved against rather than reporting it as an unknown column of the input

## 2. Registration: resolve a rule's declaration against the entity

- [x] 2.1 `RuleGovernanceValidator#requireGrainKeyOnReadSource`: resolve the target's grain key against the entity field view — a source column under either spelling, or a qualified enrichment field whose last segment is the grain key — keeping the type-compatibility check against the type the target is keyed at
- [x] 2.2 `validateGroupTrigger`: accept a `group_by` that either equals the grain key or is a qualified enrichment field whose last segment is it; reject a bare name that only an enrichment declares, with a message naming the qualified spelling
- [x] 2.3 Pass `allowEnrichmentColumns = true` from every rule-path call into `FilterSqlValidator` and `SourceScopeGuard` — `filter_sql`, `ready_when.signal`, `member_select.prefer_sql`
- [x] 2.4 `validateMemberSelect`: resolve `order_by` columns against the entity field view instead of `columns.findByTableNameOrderByOrdinal(source)`
- [x] 2.5 `checkBoundBindings`: resolve `input_bindings[].column` against the entity field view, admitting a qualified enrichment field
- [x] 2.6 `checkEntitlement`: resolve a referenced field's `sensitive` flag through the entity field view so an enrichment column is gated exactly as a source column, and route the rule paths' `SensitiveProjectionGuard#reattribute` calls with `includeEnrichments = true` so a hidden enrichment column yields 403 `sensitive_column_not_entitled` rather than 422
- [x] 2.7 Enforce the nullability rule: an enrichment field in a value-producing position (`group_by`, a Class-1 output expression) must be declared `nullable`; exempt `array` and `map`
- [x] 2.8 Reject a `type=sql` rule whose target grain resolves to an enrichment field rather than the read source's own `identity_column`

## 3. Registration: the enrichment dependency graph

- [x] 3.1 Derive a rule's referenced enrichments once, from the same field set the statements join, via `EnrichmentJoins#referencedEnrichments` — never by re-parsing the declaration
- [x] 3.2 Refuse an enrichment read classified `GROUP_TRIGGER` or `NOT_CLASS1` by `EnrichmentFreshness#of`, with 422 `rule_validation_failed` naming the enrichment and the reason; admit `CLAMPABLE` and `NO_RULE`
- [x] 3.3 `FOREIGN_AXIS` (design Q-1) — **decided: admit and report**, following the pipeline precedent. `Clampability.refused()` covers `GROUP_TRIGGER`/`NOT_CLASS1` only, so a foreign-axis read registers and surfaces in the rule's `unclamped_reads`
- [x] 3.4 Refuse a rule whose references would close a cycle over the edges `target_enrichment → referencedEnrichments`, including a rule referencing its own target, with 422 naming the enrichments in the cycle
- [x] 3.5 Re-run 3.2–3.4 on enable, not only on register, so an enabled rule cannot bypass them

## 4. Runtime: scan and group assembly

- [x] 4.1 `ScanService`: switch to `scanSources.entityModel(source)` and emit `EnrichmentJoins.addReferenced` over the statement's own referenced field set
- [x] 4.2 `ScanService`: project an enrichment-sourced `group_by` under the target's plain grain key alias, leaving every other enrichment field under its qualified flat name
- [x] 4.3 `ScanService`: emit `<groupKey> IS NOT NULL AND <groupKey> <> ''` for a group rule whose `group_by` is enrichment-sourced, and only then (design Q-2 keeps this scoped)
- [x] 4.4 `GroupFetchService`: switch to `entityModel`, emit the referenced joins in the selection phase, and build the group-key lookup predicate from the joined field rather than a raw qualified name. Q-3 **answered**: phase two derives its own referenced set (`bound` + the group key) rather than reusing the declaration's, so it joins exactly what it reads and a policy ranking by an enrichment column drags no join into it
- [x] 4.5 `GroupFetchService`: apply the same non-empty group-key predicate as 4.3
- [x] 4.6 `SensitiveProjectionGuard#enforce`: audit the rule-path call sites with `includeEnrichments = true` as its own step — confirm it does not relax the posture that closes `buildScan`'s raw-name fallback
- [x] 4.7 `ScanSourceMetadata#projected`: refuse a qualified name absent from `bindings` instead of rendering `"<source>"."<enrichment>.<column>"`

## 5. Runtime: the freshness clamp

- [x] 5.1 `ScanService`: bound the window with `EnrichmentFreshness#bound(referencedEnrichments, source)`, taking the lowest position when several enrichments are joined; leave an unreferencing rule's window untouched
- [x] 5.2 `MaterializeService`: apply the same bound to the probe-then-write window, and emit the referenced joins per statement so a probe naming no enrichment stays unjoined
- [x] 5.3 Report a window emptied by the clamp as `rowsWritten: 0`, `hasMore: false`, echoing the input cursor — never as a failure; same for an empty clamped scan page
- [x] 5.4 Confirm `ready_when.idle` is measured from the activity the clamped scan reported, not from unclamped source time

## 6. Class-1 expressions

- [x] 6.1 `ExprSqlValidator`: validate output expressions against the entity field view and pass `allowEnrichmentColumns = true` into `SourceScopeGuard`
- [x] 6.2 `MaterializeService`: switch to `entityModel` and emit `EnrichmentJoins.addReferenced` on the `INSERT … SELECT`
- [x] 6.3 ~~Refuse a Class-1 output expression reading a `sensitive` enrichment column bound to a non-`sensitive` target column~~ — **dropped, see design D-9.** `enrichment-registry`'s "Runner identity and derived-column sensitivity posture" already decides, deliberately, that derived columns do not inherit sensitivity; the entitlement check on *referencing* the column is kept, the inheritance rule is not

## 7. Rule state: freshness diagnostics

- [x] 7.1 Add a `RuleFreshnessDiagnostics` component modelled on `PipelineFreshnessDiagnostics`: `unclampedReads` (enrichment + reason as a wire code) and `rebuild` (enrichment + `rederivedAt`), derived on read, degrading to none on a resolution failure and logging it
- [x] 7.2 Surface both on `RuleDto` (snake_case, `@JsonProperty`) and attach them in `RuleController` on get, list, create and update — mirroring `PipelineController`
- [x] 7.3 Confirm no cascade is triggered: a re-derived upstream is reported only

## 8. Catalog safety

- [x] 8.1 Extend the rule-dependency check so an enrichment a rule references cannot be dropped — 409 `conflict` naming the reading rules — alongside the existing declared-read-source check
- [x] 8.2 Refuse a schema patch that drops or renames a column of an enrichment a registered rule references; leave an unreferenced column of that enrichment patchable

## 9. Cleanup

- [x] 9.1 Delete `ScanSourceMetadata#sourceModel` and its javadoc once `ScanService`, `GroupFetchService` and `MaterializeService` no longer call it; confirm no remaining callers

## 10. Documentation

- [x] 10.1 `docs/configuration.md`: extend the enrichment section with the rule-side entity read — the qualified flat name, the `nullable` rule for a group key and a Class-1 output, the ADAS-emitted non-empty group-key predicate, and the refusal of an unclampable enrichment read. No new config property is introduced, so no new property table row
- [x] 10.2 `docs/configuration.md`: state the Class-1 join cost — a ClickHouse hash join reads the enrichment in full and the window bound does not prune it, so a small frequent materialize window pays a full enrichment read; size such a rule against the enrichment's whole table
- [x] 10.3 Document the writer grant: `SELECT` on any **enrichment** table a rule references, alongside the existing aggregate-pipeline grant, and note it must be provisioned before enabling such a rule
- [x] 10.4 `README.md`: no env-var table; add an inline mention only if a link is warranted

## 11. Tests

- [x] 11.1 `RuleGovernanceValidatorTest`: grain key from an enrichment accepted; bare enrichment name rejected with the qualified spelling named; enrichment of another source rejected; incompatible grain-key type rejected; non-`nullable` enrichment column refused as a group key and in a Class-1 output but accepted in a predicate; `array` exempt; bound input binding over an enrichment column accepted
- [x] 11.2 `RuleGovernanceValidatorTest`: `GROUP_TRIGGER` and `NOT_CLASS1` enrichment reads refused; `NO_RULE` admitted unbounded; cycle and self-reference refused; all re-checked on enable
- [x] 11.3 `SourceScopeGuardTest` / `FilterSqlValidatorTest`: a qualified enrichment column of the read source admitted; a substituted `FROM` still refused; an enrichment of another source refused
- [x] 11.4 `GroupFetchServiceTest`: selection phase emits the referenced join; the lookup predicate uses the joined field; NULL and empty group keys excluded; phase two joins everything it references
- [x] 11.5 `ScanServiceTest`: enrichment-sourced `group_by` projected under the plain grain key; other enrichment fields under the qualified name; no join emitted for an unreferenced enrichment; SQL byte-identical for a rule naming no enrichment
- [x] 11.6 Clamp tests on both windows: window held to the lowest referenced position; unreferencing rule unclamped; clamped-empty reported as an empty page / zero-row write echoing the cursor
- [x] 11.7 Sensitive-column tests: enrichment column gated as a source column; a non-entitled caller gets 403 `sensitive_column_not_entitled` (not 422) on register, scan, group assembly and materialize
- [x] 11.8 Catalog-safety tests: dropping a referenced enrichment and dropping/renaming a referenced enrichment column both 409 `conflict`; an unreferenced column stays patchable
- [x] 11.9 Integration test under `.../integration/`: register a group rule on `dial_usage_log` grouped by `usage_client_identity.client_session_id` targeting `session_insights`, scan it, assemble a group, write back — each test owning its data (truncate + insert)
- [x] 11.10 Regression: every rule fixture referencing only source columns validates and renders exactly as before

## 12. Verification

- [x] 12.1 Confirm the delta specs in `openspec/changes/rule-reads-entity/specs/` match the shipped behaviour, and record any decision taken during implementation (design Q-1, Q-2, Q-3) in `design.md`
- [x] 12.2 `./gradlew clean build` passes — compile, checkstyle and tests

## 13. Review fixes

- [x] 13.1 Refuse a **row-grain** rule whose target's grain key is reachable only through an enrichment. The widening admitted it at registration, and the scan then projected the bare grain key — a column the read source does not declare — so the statement named a column absent from its `FROM` and failed at execution. Only a group rule, which declares the qualified field as `group_by` and projects it under the plain grain key, may key its rows on an enrichment column
- [x] 13.2 Resolve a `type=sql` rule's reads from its **pinned evaluator's output expressions**, not from the rule row alone. `declaredFields` scanned only the rule's own fragments, so a Class-1 rule deriving from a sibling enrichment — the shape this change exists to allow — contributed no edge to the dependency-cycle walk, no dependency for the catalog's drop/rename checks, and no entry in its freshness diagnostics. `RuleDeclaration` carries the evaluator ref for this
- [x] 13.3 Do not record the **drained horizon** when the freshness clamp is what emptied a materialize window. The horizon claims "nothing matching below this instant", and a downstream clamp reads it as licence to advance — so recording it under a clamp propagated the exact unbounded read the clamp had just prevented, one level down. Whether the clamp withheld anything is established by re-probing the same window with the clamp lifted, not inferred from where the bound sits: the inference version was wrong in the common direction — a source whose newest row is hours old puts every bound below the safety-lag horizon, so a clamped rule would never record a drained horizon again, silently withdrawing the mechanism that releases a filtered rule's dependents. Caught by the live positive control, where a genuinely caught-up clamped rule failed to record
- [x] 13.4 Derive each statement's **join** set from its own clauses, separately from the clamp's set: a group rule's scan no longer joins an enrichment only its `input_bindings` reach (the clamp still covers it, because assembly reads those values for the rows that page marks), and the group assembly's selection phase no longer joins one only the bound body columns reach
- [x] 13.5 Answer the multi-enrichment grain-key ambiguity with the field `group_by` names, rather than refusing a declaration that already says which one it means
- [x] 13.6 Correct the javadoc on `SourceScopeGuard#requireSourceScoped` and `SensitiveProjectionGuard#reattribute` that still described the rule paths as refusing enrichment references; widen `RuleDto.unclamped_reads.reason`'s documented values to include `group_trigger`/`not_class1`, which registration refuses but an upstream that changed shape afterwards can still produce
- [x] 13.7 `Class1MaterializeIntegrationTest.expressionReferencingAnEnrichmentColumnIsRejected` asserted the pre-change rationale ("a rule-scoped read drops the enrichment joins") for a rule reading its **own target**; retitled and re-pinned on the refusal that actually fires, the length-zero dependency cycle
- [x] 13.8 Tests for each: row-grain enrichment grain key refused; a non-nullable enrichment column refused as a group key and in a Class-1 expression; a Class-1-expression-only read blocking the enrichment's drop and closing a cycle; a clamped-empty materialize leaving `drained_at` unset

## 14. Live verification (local ADAS + Enrichment Runner)

- [x] 14.1 Project an enrichment-sourced `group_by` under its **qualified** name as well as the plain grain key. Design D-3 was right that the runner's `GroupMarkBuilder` decides the whole thing and wrong about which name it uses: it reads `row.get(rule.groupBy())`, and `group_by` on the wire is the qualified name, so aliasing to the bare grain key made the lookup miss on every row. Proven against the live runner — with only the plain name it scanned, advanced its cursor and marked **zero** groups, dead-lettering nothing; with the qualified name restored it marked every group and carried them through assembly, input mapping and prompt rendering
- [x] 14.2 End-to-end on local ADAS + runner against 75k real `dial_usage_log` rows: a Class-1 rule fills `rre_session.session_id`, a group rule reads `dial_usage_log` grouped by `rre_session.session_id` into `rre_label`. Verified live: registration of all four surfaces, the qualified name in `filter_sql`, an `input_bindings` column over the enrichment, the freshness clamp reporting `unclamped_reads: []` against a CLAMPABLE upstream, `rebuild_required` firing after the upstream re-derived, the emitted non-empty group-key guard excluding the 85% of rows whose `chat_id` is empty, and the group assembly returning members by the plain key
- [x] 14.3 Fixtures removed afterwards (`rre_label`, `rre_spine`, `rre_session`, their rules and evaluators)
