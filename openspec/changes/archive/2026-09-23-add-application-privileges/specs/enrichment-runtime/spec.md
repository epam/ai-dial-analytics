## ADDED Requirements

### Requirement: Pipeline-scoped read and write authorization

The scan, the group assembly, the Class-1 materialize, and the write endpoints SHALL require **both**
`TABLE_READ` on the pipeline's **read source** and `TABLE_WRITE` on its **target enrichment**, each held
globally or granted on that table. A caller lacking either SHALL be rejected with HTTP 403
`pipeline_access_denied`. A caller holding every privilege satisfies the requirement, and does so because
it holds those two privileges globally rather than by any check on a role name.

The write endpoint (`POST /v1/pipelines/{name}/rows`) carries the caller's own rows and reads nothing, so
it SHALL require the target's `TABLE_WRITE` alone.

This replaces authorizing both directions from the target's write grant alone. That earlier rule was
a confused deputy: the endpoints return rows of the read source, so a caller holding only `write` on
a small derived enrichment obtained a read of the table that enrichment derives from — and, because a
scan's projection may include the columns of **other active enrichments of the same source**, of
those siblings too. The `access` model now has a read grant, so the read direction SHALL be
authorized against the table actually read.

This holds when the pipeline declares a **read source** other than its target enrichment's
`source_table` (see [enrichment-registry](../enrichment-registry/spec.md)): the read source is
authorized in its own right, so a pipeline reading a table other than its target's requires a read
privilege on that table. The sensitive-column re-check SHALL continue to be applied against the
pipeline's read source, in addition to this authorization.

An Enrichment Runner therefore needs `REGISTRY_READ` for the pipeline sync feed plus per-table grants on
the sources it scans and the targets it writes; it SHALL NOT need a bundle conferring global read
over every table.

#### Scenario: Least-privilege scan requires source read and target write

- **WHEN** a caller holding `read` on the pipeline's read source and `write` on its target enrichment
  calls `POST /v1/pipelines/{name}/scan`
- **THEN** the scan is authorized without requiring any global read privilege

#### Scenario: Target write alone no longer authorizes a scan

- **WHEN** a caller holding only `write` on the target enrichment calls the scan, group assembly, or
  materialize endpoint
- **THEN** the request is rejected with HTTP 403 and error code `pipeline_access_denied`

#### Scenario: Missing target access is rejected

- **WHEN** a caller holding `read` on the read source but no write access on the target enrichment
  calls any of those endpoints
- **THEN** the request is rejected with HTTP 403 and error code `pipeline_access_denied`

#### Scenario: A declared read source is authorized in its own right

- **WHEN** a caller scans a pipeline whose declared read source is a table other than its target's
  `source_table`, holding `write` on the target but no read privilege on that declared source
- **THEN** the request is rejected with HTTP 403 and error code `pipeline_access_denied`
- **AND WHEN** the caller is additionally granted `read` on the declared source
- **THEN** the scan is authorized, and the sensitive-column re-check is applied against that source's
  columns

#### Scenario: The write-back endpoint needs no source read

- **WHEN** a caller holding only `write` on the target enrichment posts rows to
  `POST /v1/pipelines/{name}/rows`
- **THEN** the write is authorized, because the endpoint carries the caller's own rows and reads nothing

### Requirement: A grant may not launder a restricted column into a laxer target

Because sensitive-column entitlement is now grantable per table, declaring a derived column
`sensitive` no longer implies a uniform barrier: the target table's `read_sensitive` grantees may
differ from the read source's. The service SHALL therefore require that a pipeline's target enrichment
expose a derived sensitive column no more widely than its read source exposes the column it derives
from: **where the target enrichment carries a `sensitive` column**, its `read_sensitive` grantee set
SHALL be a subset of its source's.

The condition is the target carrying such a column, not the narrower "a pipeline derives one from a
sensitive source column", and the difference is deliberate. The narrower reading states the risk more
precisely but is not answerable when a grant is edited without enumerating every pipeline targeting the
enrichment and resolving what each reads — and a predicate that holds at registration and not at grant
time is what let the narrowing direction go unchecked in the first place. One condition evaluated
identically at every entry point is worth being conservative for: the cost is refusing a grant on an
enrichment whose sensitive column happens not to be derived from a protected one, and the remedy is the
same grant on its source.

A role SHALL be exempt only where the **source's own list names it**. Holding `TABLE_READ_SENSITIVE`
globally SHALL NOT exempt it: the only available answer to "does this role name hold the privilege
globally" is a union across every configured provider's `roles-mapping`, which would exempt a role on
the strength of a mapping that a caller arriving through a different provider does not have. Granting a
globally-entitled role is reported as an ineffective grant in any case.

The relation is **"reads from"**, not "is a child of", and the distinction is the rule's whole
correctness argument. **Four** different edges carry it:

- an enrichment's catalog `source_table`;
- an `ENRICH` pipeline's declared `input_table`, which MAY name **any** ACTIVE source and so need not
  be the target's parent. A rule that followed parentage alone compared the target's grants against the
  wrong table: a pipeline could copy a sensitive column of `s2` into an enrichment of `s1` and have it
  served to `s1`'s grantees;
- an `AGGREGATE` pipeline's `input_table`, whose target has no catalog parent at all (see
  `aggregate-pipeline`);
- and an **enrichment reached through the read source's grain join**. A declaration addresses such a
  column as `"<enrichment>.<column>"`, so the table whose values are copied appears nowhere on the
  pipeline row — its `input_table` is the source, and the enrichment exists only in the parsed
  declaration. Missing this edge left the same disclosure the `input_table` edge closes, one join
  further out: a rollup of a protected enrichment column into a target granted more widely than that
  enrichment, readable by a caller holding nothing on it.

The relation SHALL NOT be narrowed to "a source that itself carries a `sensitive` column". An author MAY
declare a derived column `sensitive` whose origin column is not flagged — the ordinary shape on the
aggregate path, where rolling a protected value up is what the flag is for — so a source with no flagged
column can still be where protected values come from. The cost is that a target carrying a sensitive
column must hold its `read_sensitive` grants within **every** table it reads, enrichments included, and
the remedy is the same grant on that table.

The relation follows the **edge, not the data**: deleting the pipeline, or re-pointing it at another
target, removes the edge while the rows it already wrote remain, after which a grant on the abandoned
target is unconstrained. That is accepted rather than closed — it takes `REGISTRY_ADMIN` and
`TABLE_GRANT` together, and `TABLE_GRANT` is global-only, so such a caller could grant itself the source
directly.

It SHALL be enforced at **four** points, because each closes what the others cannot:

- **pipeline registration and enable** — rejected with `422 pipeline_validation_failed`, this
  capability's code for a governance violation, so the registry does not report one condition under two
  codes;
- **`PUT /v1/tables/{name}/access`** — rejected with `422 sensitive_grant_widens`, in **both**
  directions: widening the enrichment's list, and narrowing the source's while an enrichment of it still
  names a role. Checking only "did this request widen anything" misses the second entirely;
- **`PATCH /v1/tables/{name}/schema`** — when a column is first marked `sensitive`, whether by
  patching an existing column or by adding one already flagged. Until then the pair had nothing to
  protect, so neither of the points above could have seen it;
- **`POST /v1/tables/{name}/schema`** — the same condition on the initial schema of a not-yet-
  materialized table. Leaving this out left the whole rule bypassable through the front door: grant
  `read_sensitive` on a `PENDING` enrichment while it has no columns at all (accepted, because the
  pair has nothing to protect), then define a schema declaring one;

A fifth point existed briefly and was **closed by the evaluator fold rather than by a check**:
registering a new evaluator version changed what every stored pipeline resolving `latest` read,
without any gate running. After the fold a pipeline's `transform` — expressions included — lives on
the pipeline row, so the only way to change what a declaration reads is `PATCH /v1/pipelines/{name}`,
which runs the full registration gate: the grant subset rule above **and** the author's own
sensitive-column entitlement over every reference. The second matters more on that path, because the
subset rule is vacuous whenever the target carries no `sensitive` column.

Class-1 enrichment has **no runtime backstop**, which is why the evaluator point is required rather than
merely tidy. A scheduled aggregate refresh re-runs its whole registration gate, this rule included, so a
stored state that drifted is caught on the next tick; `POST /v1/pipelines/{name}/materialize` checks only
the *calling* identity's entitlement, which the runner's elevated identity always satisfies. On that path
the authoring edit is the only moment the rule can be applied.

Where the relation itself **cannot be evaluated** — a stored declaration that no longer parses or whose
read source no longer resolves — the edit SHALL be **refused**, `409 pipeline_declaration_unresolvable`,
naming the declaration. Not skipped: the registry's other consumers (the table-delete guard, the
freshness diagnostics) degrade to "no edges" on a drifted declaration, deliberately, because a broken
pipeline must not make an unrelated table undeletable. Under this rule the same degradation reads as
"this table has no readers", which **accepts** a grant the rule exists to refuse — so one drifted row
would silently reopen the path. Letting the failure escape instead is no better: it turns every grant
edit, on any table, into a `500` naming a pipeline the operator was not touching.

The rule is enforced in application code and has **no database-level backing**: a Flyway migration that
wrote a role into a grant list, or set a column's `sensitive` flag, would bypass it by construction. No
shipped migration does either — `V42` changes only the column DEFAULT, and the two migrations that mark
columns sensitive predate per-table grants entirely — and a migration that needs to do so SHALL apply the
rule itself or state why the pair it touches is vacuous.

The rule SHALL be enforced in `none` mode as well as `oidc`, unlike the privilege checks around it. Those
decide who may act and fail open where nothing is enforced; this decides what stored state may exist, and
a grant document written while security was off goes live the moment a deployment switches to `oidc`,
when refusing it would be too late.

This rule constrains **grants**, not the `sensitive` flag. Derived enrichment columns SHALL continue not
to inherit sensitivity, as `enrichment-registry` specifies: registration never mutates catalog metadata,
and the enrichment author declares the flag through the schema API. The consequence that capability
already states and accepts — a value derived into an unflagged column is readable by callers who may not
read its source — is unchanged and is **not** what this rule closes. This rule bites exactly where the
author did declare the target column sensitive.

#### Scenario: Registration is rejected when the target is laxer

- **GIVEN** a read source whose `read_sensitive` list is `["pii-readers"]`, and a target enrichment that
  carries a `sensitive` column and whose `read_sensitive` list is `["pii-readers","analysts"]`
- **WHEN** a pipeline into that target is registered
- **THEN** the registration is rejected with `422 pipeline_validation_failed`, naming the widening role
  and both tables

#### Scenario: Narrowing the source is rejected while an enrichment still names the role

- **GIVEN** the same pair, with both lists naming `analysts`
- **WHEN** an operator revokes `read_sensitive` on the source
- **THEN** the request is rejected with `422 sensitive_grant_widens` and neither list changes

#### Scenario: Editing a transform re-runs the full gate

- **GIVEN** an enabled `sql` pipeline whose target carries no `sensitive` column
- **WHEN** a caller holding `REGISTRY_ADMIN` but not `TABLE_READ_SENSITIVE` on the read source
  patches its `transform` so an output expression reads a `sensitive` column of the read source
- **THEN** the patch is rejected `403 sensitive_column_not_entitled`, and the stored declaration is
  unchanged — the fold put the expression on the pipeline row, so there is no longer a way to change
  what a stored pipeline reads that bypasses this gate

#### Scenario: Marking a column sensitive is rejected while the grants already widen

- **GIVEN** an enrichment with no sensitive column whose `read_sensitive` list names a role its source's
  does not — accepted, because the pair had nothing to protect
- **WHEN** a schema patch marks one of its columns `sensitive`, or adds a column already flagged
- **THEN** the request is rejected with `422 sensitive_grant_widens` and no column changes

#### Scenario: A later grant that would widen is rejected

- **GIVEN** a registered pipeline reading a sensitive source column into a target enrichment that
  carries a sensitive column
- **WHEN** an operator adds a role to the target's `read_sensitive` list that the read source's list
  does not contain
- **THEN** the request is rejected with `422 sensitive_grant_widens` and the lists are unchanged

### Requirement: A schema edit may not repoint what a stored declaration reads

A column any stored declaration reads SHALL NOT be dropped, renamed, or **renamed onto** — on an
enrichment, where a declaration addresses it as `"<enrichment>.<column>"`, and equally on a **source**,
where it is addressed by its bare name. Refused `409 table_conflict`, naming the declarations.

**The source half is a security control, not only an availability one.** A rename preserves the
`sensitive` flag and changes only the name, so on a source a holder of `TABLE_MODIFY` — which implies
nothing but `TABLE_GET` — could free the name an expression reads and move a protected column into it.
The declaration is untouched and the flag is untouched, so neither the `TABLE_GRANT` bar on the flag nor
the subset rule above fires; the pipeline's elevated runner then copies the protected values into an
unflagged enrichment column that plain `TABLE_READ` reads. Before privileges existed this was
unreachable, because `PATCH .../schema` required the full administrative role, which conferred global
sensitive read; separating `TABLE_MODIFY` from `TABLE_READ_SENSITIVE` is what opened it.

**Both halves of a rename SHALL be asked**, because freeing a read name and occupying one are the two
ways to repoint a reference, and only the second carries the values.

A source column counts as read when **any** SQL fragment of the declaration names it as a bare
identifier — the filter, the `ready_when` signal, the member-selection `prefer_sql`, and each `sql`
output expression — as well as through the declaration's structured members. A scan for qualified
`table.column` tokens is not enough: a source column has no qualified spelling, so an expression reading
`length(nick)` would be invisible to it, and that is exactly the shape a rename-repoint uses. Every
identifier SHALL count, keywords and string contents included; a false positive refuses a rename of a
column nobody reads, and a false negative is impossible.

#### Scenario: Renaming a source column a pipeline reads is refused

- **GIVEN** an enabled `sql` pipeline whose expression reads the source column `nick`
- **WHEN** a caller holding `TABLE_MODIFY` on that source renames `nick`
- **THEN** the request is refused `409 table_conflict`, naming the pipeline, and no column changes

#### Scenario: Renaming a sensitive column onto a read name is refused

- **GIVEN** the same pipeline, and a `sensitive` column `user_email` on the same source
- **WHEN** the same caller renames `user_email` to `nick`
- **THEN** the request is refused `409 table_conflict` — the rename would repoint the expression at a
  protected column without touching the declaration, the flag, or any grant list

### Requirement: An enrich read egress reads only the sensitive tables its pipeline was approved to read

Every governance pass that accepts an `enrich` declaration — registration, re-declaration, and enable —
SHALL record on the pipeline the set of **owning tables whose `sensitive` columns the declaration
reads**: the read source when it reads one of the source's sensitive columns, and each enrichment whose
sensitive column it reads through the grain join. The gate checks the author's sensitive-column
entitlement on each of those tables, so the recorded set is what an entitled author approved. It SHALL
be overwritten on every pass, because that pass's author is who decides now, and it SHALL be part of
the audited declaration. It SHALL be recorded only under enforced security: in `none` mode the
entitlement check passes every caller, so the set would name reads no authenticated author decided and
would then be trusted after a switch to `oidc`. A pipeline written in `none` mode is left unrecorded and
seeds on its first read under enforcement, like any pipeline that predates approvals.

Each read egress through which ADAS reads source values for derivation — `POST
/v1/pipelines/{name}/materialize`, `POST /v1/pipelines/{name}/scan` and group assembly — SHALL recompute
that set over the fields it actually reads, with the same classification, and SHALL refuse the request
with `409 sensitive_read_unapproved` when it names a table not in the recorded set. The refusal SHALL name
the unapproved tables and the remedy. It is a `409` and not the `403 sensitive_column_not_entitled` the
entitlement check answers with: the caller is usually an entitled runner, and what is missing is an
entitled author's decision rather than a privilege.

**This is the enrich lane's runtime backstop, and the reason it exists is stated so it is not mistaken for
redundancy.** The design accepts that a value derived from a sensitive column may be readable by callers
who cannot read its source, because *the pipeline governs who may reference the column*. That premise
held only at the edits that re-ran the gate; each edit that changed what a stored declaration read
without doing so — the evaluator-version path before the fold, a source-column rename before its guard,
a read column marked `sensitive` afterwards — broke it. The aggregate lane re-runs its whole gate on
every refresh and never had the problem. This requirement makes the premise a checked fact at the only
places ADAS itself reads source values, whichever edit moved the reads.

The set is of owning **tables**, not columns, because sensitive-column entitlement is per owning table:
an author entitled to one sensitive column of a table is entitled to all of them, so a column renamed
within a table stays within the approval and no author identity has to be kept and re-evaluated.

A pipeline stored before approvals were recorded SHALL be **seeded** on its first egress under enforced
security, and SHALL NOT be refused for lacking one. The seed SHALL cover **everything the declaration may
read**, not only what that egress reads: the egresses read different subsets — a group pipeline's scan
reads its `ready_when` signal and not its member-selection policy, group assembly the reverse — so a seed
taken from whichever ran first would make the other refuse a pipeline nobody changed. The
declaration-wide set is an over-approximation by design, so a seed can be wider than the reads but never
narrower. The trust anchor is weaker for a pipeline last written in `none` mode — nothing authenticated
its author — and is accepted there because `none` mode already lets any caller re-declare anything. Everything stored before this
change was authored when only the full administrative role could author, and that role conferred every
sensitive read. The seed SHALL NOT overwrite an approval another egress has already recorded, and it
opens no audit revision.

The rule SHALL be inert in `none` security mode, like the checks it sits beside: a seed taken there would
approve reads no authenticated author decided. The Class-1 preview is out of scope — it writes nothing,
already enforces its caller's own entitlement, and is where an author diagnoses a refused pipeline.

#### Scenario: A read column marked sensitive after approval stops the pipeline

- **GIVEN** an enabled `sql` pipeline registered while the source column its expression reads was not
  `sensitive`
- **WHEN** that column is marked `sensitive` — by whatever edit — and any caller, including one holding
  every privilege, materializes the pipeline
- **THEN** the request is refused `409 sensitive_read_unapproved`, naming the read source

#### Scenario: A governance pass by an entitled author re-approves

- **GIVEN** the refused pipeline above
- **WHEN** an author holding `TABLE_READ_SENSITIVE` on the read source disables and re-enables it
- **THEN** the gate records the read source as approved, and the next materialize proceeds

#### Scenario: A pipeline registered through the API reads within its approval

- **WHEN** an entitled author registers a `sql` pipeline whose expression reads a `sensitive` column, and
  an entitled runner materializes it
- **THEN** the materialize proceeds, because the gate recorded the column's owning table

#### Scenario: A pipeline stored before approvals is seeded, not refused

- **GIVEN** an `enrich` pipeline with no recorded approval
- **WHEN** its first egress runs under enforced security
- **THEN** the egress proceeds and records the owning tables it read as the approval

## MODIFIED Requirements

### Requirement: Sensitive-column re-check on scan

Independently of the target-enrichment access grant, the scan SHALL re-apply the sensitive-column
declaration (see [enrichment-registry](../enrichment-registry/spec.md)) against the **scanning caller's**
identity: if the scan's projection includes a `sensitive`-flagged column of the pipeline's **read source**
and the caller may not see them (no `TABLE_READ_SENSITIVE` on the table that owns the column, and
`security.mode` is not `none`), the
scan SHALL be rejected with HTTP 403 `sensitive_column_not_entitled`. This holds even though the stored
declaration was registered by an entitled author, so a runner without that entitlement cannot read
sensitive data,
and it holds whether the read source is the target enrichment's `source_table` or a declared one.

The refusal SHALL carry that code **however the sensitive column is reached** — projected directly via a
bound column, or referenced by the pipeline's `filter` or `trigger.ready_when.signal`. A non-entitled caller
cannot even parse the stored predicate, because the SQL front end validates against a schema from which
sensitive columns are hidden for that caller; such a parse failure SHALL be re-attributed to
`sensitive_column_not_entitled` rather than reported as a malformed declaration (422), which would both mislead
and misstate whose problem it is. An entitled caller's parse failure SHALL be reported unchanged, so a
genuinely broken declaration still reports accurately to the caller who can fix it.

#### Scenario: Non-entitled runner scanning a sensitive-referencing pipeline is rejected

- **WHEN** a caller who may not see sensitive columns calls `POST /v1/pipelines/{name}/scan` for a pipeline
  whose projection includes a `sensitive`-flagged column of the pipeline's read source
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: The refusal holds for a sensitive column referenced only by the pipeline's filter

- **WHEN** the same caller scans a pipeline whose `filter` (or `trigger.ready_when.signal`) references a
  `sensitive` column of the read source that no binding projects
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, not with
  a validation error

#### Scenario: FULL_ADMIN runner scanning a sensitive-referencing pipeline is allowed

- **WHEN** a caller holding `TABLE_READ_SENSITIVE` on the read source (or any caller in `none` mode)
  calls the same scan
- **THEN** the sensitive column is projected and the scan proceeds normally

### Requirement: Sensitive-column re-check on group assembly

The group assembly endpoint SHALL re-apply the sensitive-column rule against the **calling** identity,
exactly as the scan does, and SHALL bind the check to the columns the **declaration declares** — its bound
input bindings, its membership filter, and its `trigger.member_select` — rather than to the columns a given read
phase happens to project. If any of those is a `sensitive`-flagged column of the read source's
entity — a source column or an enrichment column reached through the grain join, the two governed
alike — and the caller may not see them (no `TABLE_READ_SENSITIVE` on the owning table, and
`security.mode` is not
`none`), the request SHALL be rejected
with HTTP 403 `sensitive_column_not_entitled`, **before** either phase reads anything. Sensitive columns
SHALL NOT be silently stripped from the projection — a group input missing the very column the pipeline was
written to read would yield a quietly wrong label, so the misconfiguration fails loudly instead. The scan
and the group assembly SHALL share one implementation of this check so their behaviour cannot diverge.

Naming the owning table SHALL NOT be a way around the check: a declaration that reads
`"dial_usage_log_payload.request_body"` is refused for an unentitled caller on the same terms as one
reading a `sensitive` column of the source itself, and the refusal SHALL carry
`sensitive_column_not_entitled` rather than an unknown-field error, so the operator sees why the
declaration was refused rather than being told the column does not exist.

#### Scenario: An entitled caller receives body columns

- **WHEN** an entitled caller (or any caller in `none` mode) assembles a group for a pipeline whose
  projection includes `"dial_usage_log_payload.request_body"` or
  `"dial_usage_log_payload.response_body"`
- **THEN** those columns are present in the returned member rows, joined from the payload table by the
  grain key

#### Scenario: A non-entitled caller is rejected rather than served a stripped group

- **WHEN** a caller who may not see sensitive columns assembles the same group
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and no
  partially-projected group is returned

#### Scenario: Entitlement is enforced before the selection phase reads

- **WHEN** a caller who may not see sensitive columns assembles a group for a pipeline that binds a sensitive
  column, even though the selection phase would not project it
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and
  neither phase issues a read

#### Scenario: A member row whose payload has expired carries nulls

- **WHEN** an entitled caller assembles a group whose members include an event older than the payload
  table's retention
- **THEN** the member row is returned with its body columns null, and the assembly does not fail

## REMOVED Requirements

### Requirement: Pipeline-scoped scan and write authorization

**Reason**: Authorizing both directions from the target enrichment's write grant was a confused
deputy — the scan and group-assembly endpoints return rows of the **read source**, and a scan's
projection may include the columns of other active enrichments of that source, so a `write` grant on
a small derived enrichment was a read capability over the table it derives from and over its
siblings. The `access` model now carries a read grant, so the read direction is authorized against
the table actually read. Replaced by **Pipeline-scoped read and write authorization**.

**Migration**: A deployment whose Enrichment Runner holds a bundle conferring global `TABLE_READ`
(for example `READ_ONLY_ADMIN`, which it needs today for the sync feed) is unaffected. A runner
narrowed to `REGISTRY_READ` plus per-table grants must additionally be granted `read` on each source
its pipelines scan; the grant is the same `PUT /v1/tables/{name}/access` used for its targets, and it is
settable on `dial_usage_log` and `rate_analytics` because the system-table refusal moved from the object
to the keys. The sensitive-column re-check against the read source is unchanged and continues to apply on
top of this authorization.
