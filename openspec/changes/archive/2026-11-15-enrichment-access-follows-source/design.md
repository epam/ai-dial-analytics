## Context

Every per-table check ends in one matcher: `TableAccessEvaluator.isAllowed(table, privilege)` returns true for a
global privilege, and otherwise asks `TableCallerPermissions.isGranted(definition.getAccess(), …)` about the **named
table's own** lists. The same matcher backs the reported `permissions` (`TableCallerPermissions.resolve`), the
sensitive-column checks (`SensitiveColumnAccess`), the query model (`CatalogQueryService.buildModel`, which drops an
enrichment's columns the caller cannot read), catalog listing and export, and the pipeline sensitive-projection guard.
None of them looks at the table type, which is why an enrichment is judged by its own lists everywhere today.

Row policies live in `table_definition.row_policies`; `RowPolicyFilter` reads every non-empty one, enrichments included,
into `additional_table_filters`. Grant lists live in `table_definition.access` (JSONB, keys `get`, `read`, `sensitive`,
`write`, `modify`). The only seeded enrichment is the system table `dial_usage_log_payload`; no migration writes grants
or policies on any table.

`SensitiveGrantInvariant` compares a target's `sensitive` list (and its `read` list's globally-cleared roles) with each
table it reads, over three "reads from" edges plus the enrichment's catalog parent, and is called from the access PUT,
both schema endpoints, enrich registration (`EnrichSpecValidator`) and aggregate registration and refresh
(`AggregateGovernance`, `AggregateSpecValidator`, `AggregateSqlValidator`). Finding some edges parses the stored
declaration, which is why a drifted one can turn a grant edit into `409 pipeline_declaration_unresolvable`.

## Goals / Non-Goals

**Goals:**

- One rule, enforced at one point, decides an enrichment's read-side privileges: the source's.
- No stored state that the new rule ignores: no enrichment read-side list or row policy survives the upgrade.
- A derived table's readers are decided by its own grants (an enrichment's by its source's), with no cross-table
  subset rule.

**Non-Goals:**

- Column-level grants. This change leaves the `sensitive` flag as the only per-column control.
- Changing `write` or `modify` on an enrichment, pipeline-scoped authorization (`TABLE_READ` on the read source,
  `TABLE_WRITE` on the target), the data-plane `row_policy_confined` check, the authoring-time sensitive-column
  entitlement, or the rule that a rollup column derived from a sensitive input column must be flagged `sensitive`.

## Decisions

### 1. Compose a governing access document instead of redirecting each lookup

The evaluator and the permission reporter resolve, for an enrichment, a `TableAccess` made of its **source's** `read`
and `sensitive` lists, a `get` list that is the union of the source's `get` list and its `modify` list **without `*`**,
and its **own** `write` and `modify` lists, and hand that to the unchanged `isGranted`. The `*` is dropped because
`isGranted` ignores a `*` only in a modifying list. Copied into `get` it would revive a legacy `*` stored in the
source's `modify`, which must confer nothing. An enrichment whose source row cannot be found (for example, a snapshot of
a deleted pair) gets empty read-side lists, so it fails closed. A system flag stays the enrichment's own, so
`dial_usage_log_payload` still refuses writes.

`isGranted(TABLE_GET)` walks the `get`, `read` and `modify` lists of the document it is given. The union carries the
source's `modify ⇒ get` across, so a caller that sees the source entity through `modify` also sees its enrichments'
columns. The enrichment's own `modify` list keeps the same implication on the enrichment: a role maintaining an
enrichment's schema can read its definition. Neither needs a special case in the matcher. `rows_restricted` for
an enrichment is `isConfined(source) || isConfined(enrichment)`; the second term is true only for a policy an earlier
build left there, and on a revision snapshot it reads the policies the enrichment had then.

*Alternatives.* Rewriting the table name to the source before every read-side check would lose the `modify ⇒ get`
implication on the enrichment, and would have to be repeated at each caller (`SensitiveColumnAccess`,
`CatalogQueryService`, export, pipeline guards) instead of once in the matcher's input. Skipping enrichments in
`CatalogQueryService.buildModel` alone would leave the catalog listing, `GET /v1/tables/{name}`, the reported
permissions and the pipeline guards on the old rule.

A small shared resolver (one method: definition → governing access) is used by `TableAccessEvaluator` and
`TableCallerPermissions`, so enforcement and reporting cannot read different lists.

### 2. Clear the stored lists and policies with a migration, and ignore any that reappear

Migration `V56` sets, for `type = 'ENRICHMENT'`, `access = access - 'get' - 'read' - 'sensitive'` and
`row_policies = '{}'`, so the stored catalog says what is in force.

The migration runs once, but an earlier build can write these again: an old replica during a rolling upgrade, or an operator restoring them
after a rollback (see "Rollback (manual)") before upgrading again. Such leftovers need no special handling, because neither kind can widen anything:

- The resolver never reads an enrichment's `get`, `read` or `sensitive` list, so a leftover list confers nothing.
- A leftover row policy is enforced as before. It only blanks the enrichment's values on rows the source already
  returns, so it can narrow but never widen.
- Both are returned as stored by `GET .../access`, `GET .../row-policies` and revision snapshots. They can be removed
  with the existing API: `PUT .../access` sending the lists empty (accepted), and `DELETE .../row-policies/{role}`.

The one reader that does skip them is the startup `*` report (`WildcardGranteeReport`): it exists to show which
tables `*` opens, and a leftover read-side list opens nothing. A leftover `*` row policy is still reported.

*Alternative considered and rejected.* Masking or skipping leftovers in every reader (`RowPolicyFilter`, both GETs,
the PUT response, the schema guards' `referencedColumns`, `requireUnconfined*`). That touches six readers for no
security gain, and it makes the leftovers impossible to see or remove.

`V43` deliberately avoided a bulk `UPDATE` because a raw `UPDATE` writes no Envers revision. This change does need to
clear data, so it accepts that gap and states it as an audit-trail limitation (see the `audit-trail` delta). The
enrichment's latest snapshot keeps showing the removed lists until its next change that rewrites its table
definition (description, grants, row policies). `table_definition_aud`
is left alone.

The migration carries a one-line header naming what it removes and linking
`openspec/specs/table-access-control/spec.md`; the reasons and the missing revision are stated in the specs, since an
applied migration's comment cannot be corrected.

### 3. Refuse a read-side grant or policy on an enrichment with `409 enrichment_follows_source`

`TableAccessService.replaceAccess` loads the definition (`404`), and then, **before** the per-key loop that holds the
`table_is_system`, blank-role and `*` checks, refuses a non-empty `get`, `read` or `sensitive` on an enrichment.

For row policies the check moves **into the controller**. `TableRowPolicyController.put` runs the both-forms check and
translates `where` (`RowPolicyConditions.toFilter`) before it calls the service. So the controller first resolves the
table (`404`) and refuses an enrichment (`409`), ahead of both. The existing enrichment-only `where` refusal in
`RowPolicyConditions` then becomes unreachable and is removed. The check lives in one place, a `RowPolicyService` method the controller calls first, since the controller is the
only caller of the service's `put`. `DELETE` and `GET` on an enrichment are unchanged, so a leftover policy can be read and
removed. Resolving the table first moves an unknown table ahead of the both-forms check on **every** table: a body
carrying both forms for a missing table answers `404` where it answered `422`. The spec states this order.

Both messages name the source. A new `EnrichmentFollowsSourceException` maps to `409` in `DefaultExceptionHandler`.
The OpenAPI descriptions of both PUTs gain the `409`. The full check order is in the `table-access-control` delta.

*Alternatives.* Silently dropping the keys would accept a request that changes nothing. Removing them from the
response shape would break clients that read all five keys. `409` matches `table_is_system`, the other "this table
kind does not take this list" refusal.

`ENRICHMENT_READ_INERT` and its `canReadSource` helper are deleted, because the grant they reported can no longer be
made. `SENSITIVE_WITHOUT_VERB` on an enrichment is unreachable for the same reason. On a **source** it must change:
`holdsAnyVerb` also counts `modify` on any enrichment of the source, since the source's clearance extends that
`modify` to the enrichment's sensitive columns.

### 4. Remove the sensitive-grant subset rule, for enrichment and aggregate targets

The rule required every role cleared for a derived table's sensitive values to be cleared on each table it reads. The
maintainers do not want that guarantee. One table may hold raw data and another a corrected or reduced form of it,
with different readers, and a role may read the second without reading the first. The rule was also partial, because
it looked only at targets whose author flagged a column `sensitive`. The controls that remain:

- **Authoring entitlement.** A pipeline's author must hold `TABLE_READ` and `TABLE_SENSITIVE` on every sensitive
  column the declaration references (`enrichment-registry`, "Sensitive-column entitlement"). The person who decides
  what a derived column reveals has seen the raw values.
- **Each table's own clearance.** A derived column flagged `sensitive` is read by its own table's clearance holders,
  for an enrichment its source's.
- **Flag propagation on rollups.** A rollup column derived from a sensitive input column must still be declared
  `sensitive` (`aggregate-pipeline`, "Sensitive-column governance on the aggregate path"). That rule is unchanged.

So `SensitiveGrantInvariant`, `SensitiveGrantWidensException` and the `422 sensitive_grant_widens` mapping are
deleted, along with every call:

- `TableAccessService` (the access PUT);
- `TableSchemaService` and `TableSchemaCreationService` (marking a column sensitive);
- `EnrichSpecValidator` (registration, enable and armed patch);
- `AggregateGovernance`, `AggregateSpecValidator` and `AggregateSqlValidator` (aggregate registration, enable and
  refresh).

The edge queries only the invariant used go too, if nothing else needs them: `PipelineRepository`'s
`findEffectiveInputTableOfTarget` and `findTargetsByInputTable`, `PipelineInputDependents`'
`enrichmentsReadByPipelineTargeting` and `targetsReadingEnrichment`, `PipelineGenerationService.referencedEnrichmentsOrRefuse`
and `DeclarationUnresolvableException` with its `409` mapping. The table-delete guard and the freshness diagnostics,
which degrade to "no edges" on a drifted declaration, keep whatever they use.

Because the rule is gone, nothing needs to resolve an enrichment to its source for a cross-table comparison. The
migration cannot leave a stored violation, so no pre-upgrade check for one is needed.

*Alternatives.*
- **Keep the rule for aggregate targets only.** That leaves one model for rollups and another for enrichments,
  although the same principle applies to both.
- **Keep it everywhere, re-pointed to each side's governing table.** It would need a same-source exemption, parse-free
  edge finding, and handling for stored violations, all to enforce a guarantee that is not wanted.

### 5. Leave an enrichment's flag under the ordinary flag rule

Changing an enrichment column's `sensitive` flag needs `TABLE_MODIFY` on the enrichment and `TABLE_SENSITIVE`, the
source's clearance, like any table's flag needs `modify` and the clearance on it. A holder of both can declassify a
derived column, exactly as a holder of both on a source can declassify a source column.

*Rejected alternative.* Requiring a read of the source as well. It is consistent only with the cross-table guarantee
that Decision 4 removes.

## Risks / Trade-offs

- **[Per-table readers of a source gain an enrichment's columns]** A caller kept from an enrichment's columns only by
  that enrichment's empty `read` list now sees them. The most common case is an enrichment with no grants at all. →
  The upgrade note's first query lists, per enrichment, the source grantees it does not name. Operators mark any
  column meant for fewer readers `sensitive`. Global readers saw these columns already.
- **[Clearance holders of a source gain an enrichment's sensitive columns]** Where an enrichment's own `sensitive`
  list was narrower than its source's, the difference now reads its sensitive values. → Same query.
- **[Source getters gain an enrichment's definition]** A caller that may get the source, through its `get`, `read` or
  `modify` list, now gets `get` on
  every enrichment of it: the listing, `GET /v1/tables/{enrichment}`, its revision snapshots
  (`GET /v1/tables/{enrichment}/revision/{revision}`), its inclusion in an export, and the enrichment's non-sensitive
  columns in schema discovery and translation, which work at the `TABLE_GET` bar. Column names and
  definitions are not secret here; values stay behind `read`. → Upgrade note.
- **[Enrichment maintainers gain schema authority over sensitive columns]** A holder of an enrichment's `modify` and
  the source's clearance can see, drop, rename and flag the enrichment's sensitive columns, which its own `sensitive`
  list used to gate. → Upgrade note; the source's `sensitive` list and the global `TABLE_SENSITIVE` mappings show who holds the
  source's clearance.
- **[A derived sensitive column can reach roles not cleared on its origin]** This is intended (Decision 4). An enrichment
  filled from another source, or a rollup, can be cleared for a different audience than the table it reads. → Stated
  in the upgrade note. Pipeline authors decide what a derived column may reveal.
- **[Enrichment-only grantees lose access]** A role that holds a key on an enrichment but not the same key on its
  source loses what that key gave it at deploy: the listing and definition (`get`), the columns (`read`), or the
  sensitive columns (`sensitive`), on query surfaces and in pipeline authoring and scans. A runner cleared only on the
  enrichment stops scanning even when it reads the source. → The second query lists such grantees per key, and should
  return none. They move to the source before upgrading.
- **[Final-capability changes for some callers]** For a per-table reader of an `append` source that could not read
  its enrichment, the entity was not final-capable: it read raw, and `"final": true` or SQL `FINAL` answered
  `400 illegal_final`. Now `supports_final` is `true`, an absent `final` reads final, and those requests succeed. →
  Upgrade note.
- **[Enrichment row policies disappear]** A policy that blanked an enrichment's values for a role is removed, and
  those values become visible on the source rows that role reads. A policy that blanked an enrichment's values on some rows has no equivalent afterwards. A source policy can name only the source's own columns, so a condition on an enrichment column is refused `422`, and it hides whole rows rather than the enrichment's values on them. The options left are marking the enrichment's columns `sensitive`, which hides them on every row from callers without the source's clearance, or a source policy on source columns, which hides those rows entirely. → The first query lists every
  such policy; the upgrade note says each needs one of those decisions before upgrading.
- **[Group-grain values summarise rows a policy hides]** An enrichment written per group holds values derived from
  many source rows, which a source policy does not narrow. → Accepted, as for aggregate targets (see
  `table-access-control`, "Row policies do not propagate to derived tables").
- **[The payload enrichment follows `dial_usage_log`]** Its non-sensitive columns reach every reader of
  `dial_usage_log`, and its sensitive columns reach those holding the usage log's clearance. → Its body columns are
  already flagged `sensitive`. The upgrade note names it.
- **[Mixed versions]** Until every replica runs the new build, old replicas judge enrichments by their own, now empty, lists and still enforce the subset rule: they may refuse enrichment columns to per-table readers of the source and to runner scans, and fail refreshes of rollups that read enrichment columns. These failures are expected, need no action, and stop when the rollout finishes. → The upgrade note says so, and says to make no grant, policy or
  pipeline edits during the rollout.
- **[Revision snapshot lags the migration]** Covered in Decision 2.

## Migration Plan

1. Before upgrading, an operator runs the upgrade note's two queries:
   - **Who gains access:** per enrichment, the source's `read` and `sensitive` grantees it does not name, plus any
     enrichment policy.
   - **Who loses access**, per key: a role on the enrichment's `sensitive` list that the source's `sensitive` list does not name (nor `*`); a role on the enrichment's `read` list that the source's `read` list does not name (nor `*`); and a role on the enrichment's `get` list that holds no `get` on the source through its `get`, `read` or `modify` list (a `*` in `modify` not counting) and is not on the enrichment's own `modify` list. Expected empty; move any grantee it finds to the same key on the
     source. Roles that `roles-mapping` maps to the privilege globally keep it whatever the lists say, and can be ignored.
2. Deploy. `V56` clears the enrichment lists and policies.

**Rollback (manual).** `V56` has no undo, and a build older than this change reads the cleared lists as its own
empty grants. Until they are restored, per-table readers of a source and runner scans lose the enrichment's columns,
rollups that read enrichment columns fail every refresh, and arming or patching the `enrich` pipeline filling an
enrichment is refused. The older build ignores the applied `V56` (Flyway skips migrations newer than the code it
ships), so rolling back is:

1. **Before upgrading**, save every enrichment's read-side lists and row policies, by reading
   `GET /v1/tables/{enrichment}/access` and `GET /v1/tables/{enrichment}/row-policies` for each, or with
   `SELECT name, access, row_policies FROM table_definition WHERE type = 'ENRICHMENT'`.
2. Deploy the previous release and let the rollout finish.
3. For each enrichment, re-apply its saved lists with `PUT /v1/tables/{enrichment}/access`, sending only `get`,
   `read` and `sensitive` so `write` and `modify` are left alone, and each saved policy with
   `PUT /v1/tables/{enrichment}/row-policies/{role}` and its filter as `filter`. The older build accepts both, and
   records an audited revision for each.
4. If the older build refuses a restore with `422 sensitive_grant_widens`, or a rollup still fails its refresh, a
   table was cleared after the upgrade for a role not cleared on a table it reads, which this change allows and the
   older build does not. Narrow that table's `read` or `sensitive` list, or grant the role on the table it reads,
   and retry.
5. An enrichment created after the upgrade has no saved lists. Grant on it the roles of its source's readers that
   should read it, as the older build requires.

Upgrading again later does not re-run `V56`, which is already recorded. The restored lists then confer nothing, and
restored policies keep narrowing (Decision 2) until they are removed: send each enrichment's `get`, `read` and
`sensitive` empty, and `DELETE` each of its policies, after the second upgrade.
