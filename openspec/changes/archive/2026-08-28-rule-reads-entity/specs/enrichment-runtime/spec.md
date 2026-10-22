## MODIFIED Requirements

### Requirement: Scan projection and version isolation

The scan query SHALL read the base source, joined to **each `ACTIVE` enrichment whose columns the scan's
own clauses reference and to no other** — an enrichment nothing references SHALL contribute no join, so a
rule naming none renders the statement it rendered before enrichment references were admitted. Each join
SHALL be a single-key `LEFT JOIN` on the shared grain key, which cannot inflate the source's row count.
The scan SHALL push projection down to `{fields referenced by the rule's filter and input bindings} ∪
{grain id} ∪ {cursor columns}`. The scan SHALL read with `final=1` so only the latest version per source
identity is returned, independent of the projected columns.

An enrichment field SHALL be projected under its **qualified flat name** (`<enrichment>.<column>`), which
is the name the rule declared it under, so a caller reads it back by the name it wrote.

For a rule whose `trigger_kind` is `group`, the projection SHALL additionally include the rule's
`group_by` value, so one scan page tells the caller which groups changed. When `group_by` names an
enrichment column the value SHALL appear **twice**: under that qualified flat name, and again under the
**target enrichment's plain grain key**. Both are required, by different consumers:

- The **qualified** name is the spelling `group_by` carries on the wire, and a consumer addresses the key
  column by the name the rule declares. The Enrichment Runner reads it as `row.get(rule.groupBy())`, and a
  row where that lookup misses is skipped with nothing raised — so omitting this name makes the rule scan
  its page, advance its cursor, and mark no group at all, indistinguishable from an idle source.
- The **plain grain key** is what the target enrichment is physically keyed on, and the spelling the key
  round-trips as through the dirty mark, the assembly request and the write-back.

The plain grain key SHALL be an alias over the joined field rather than a projected column of its own: the
read source does not declare it, so naming it would render a column the `FROM` clause does not carry. The
activity timestamp needed to evaluate an idle window requires no addition: the scan already projects the
source's version and identity columns under their own names.

For a rule whose `trigger_kind` is `group`, the projection SHALL **exclude the rule's bound
input-binding columns**. A group rule's page is consumed by a dirty-mark, which needs to know *which
groups changed* and not what they contain; the bound columns exist to render one prompt per group, and
are read by `POST /v1/rules/{id}/group-rows`, where the row and byte caps that make reading a body safe
are applied. Projecting them on the scan would put an unbounded volume — a group rule may bind
request/response bodies — on the one rule-scoped read that has **no byte cap**, and would charge every
page for data the caller discards. This is the same principle the readiness verdict already follows
below: the page carries what the caller must act on, not the evidence behind it. For every other
`trigger_kind` the bound columns SHALL be projected, because a row-grain rule's scanned row *is* its
unit of work and its chunk payload is mapped from exactly those columns.

The **sensitive-column re-check SHALL continue to cover a group rule's bound input-binding columns**
even though they are no longer projected, and SHALL cover an enrichment column exactly as a source
column. The check governs what a rule reads or derives from, not what a response happens to return, and a
rule binding a `sensitive` column still reads it — at the assembly endpoint rather than at the scan.
Narrowing the projection SHALL NOT become a way for a caller who may not see sensitive columns to
dirty-mark a rule whose groups it would then be refused.

For a group rule declaring `ready_when.signal`, each returned row SHALL additionally carry `__signal`:
the **computed boolean verdict** of that predicate for that row. The predicate SHALL be evaluated
**inside the scan query**, translated from the same parsed form the rule's registration validated, and the
raw columns it references SHALL NOT be projected on its behalf — the page carries the verdict, not the
evidence. The verdict exists so a caller can honour `signal` without holding a second implementation of
ADAS's bounded predicate grammar, which would be free to diverge and would then mark the wrong groups
ready rather than failing; it also keeps values the caller has no other use for inside ClickHouse. A
group rule declaring no `signal`, and every row-grain rule, SHALL carry no `__signal` column at all.

`__signal` SHALL be a JSON boolean on the wire. Because a predicate is answered as an integer — and as a
nullable one when it touches a nullable column — the scan SHALL normalize it, and an unknown (null)
verdict SHALL be reported as `false`: a signal is a positive assertion that a group is complete, so
unknown fails closed and the group is left to its other readiness conditions.

The **sensitive-column re-check SHALL cover the columns a governed predicate derives from**, not only the
columns the response returns: a caller who may not see sensitive columns SHALL be refused a scan whose
`ready_when.signal` reads one, exactly as when a binding projects it. Evaluating the predicate
server-side SHALL NOT become a way around that refusal.

Every other aspect of the scan — the keyset predicate, the opaque cursor, the safety-lag, `final=1`,
ordering, and the sensitive-column re-check — SHALL be unchanged for a group rule; the dirty-mark read is
the same endpoint with a **differently scoped** projection, not a second endpoint. Its cost is therefore
proportional to **new rows**, not to the number of groups, and not to the size of their bodies.

#### Scenario: Scan omits the enrichments nothing references

- **WHEN** the scanned source has one or more enrichments and the rule references no column of any of
  them
- **THEN** the statement emits no join, and the returned rows contain only source/projected columns —
  no auto-joined enrichment columns

#### Scenario: Scan carries an enrichment column the rule references

- **WHEN** a rule's `filter_sql` or an input binding references a column of an `ACTIVE` enrichment of the
  scanned source
- **THEN** the statement emits a `LEFT JOIN` for that enrichment on the shared grain key, and the value
  is returned under its qualified flat name

#### Scenario: Projection is limited to referenced columns

- **WHEN** a rule references only a subset of the source's columns
- **THEN** the scan projects only those columns plus the grain and cursor columns

#### Scenario: A group rule's scan projects the group key and the readiness verdict

- **WHEN** `POST /v1/rules/{id}/scan` is called for a rule whose `trigger_kind` is `group` and whose
  `ready_when` declares a `signal`
- **THEN** each returned row additionally carries the rule's `group_by` value, the source's version column
  as an activity timestamp, and `__signal` as a boolean — `true` for a row satisfying the predicate and
  `false` for one that does not

#### Scenario: An enrichment-sourced group key is projected under both names

- **WHEN** `POST /v1/rules/{id}/scan` is called for a group rule whose `group_by` is
  `"usage_client_identity.client_session_id"` and whose target enrichment is keyed on
  `client_session_id`
- **THEN** each returned row carries the value under `"usage_client_identity.client_session_id"` — the
  name the rule declared, which is how a consumer addresses the key column — and under
  `client_session_id`, the spelling that addresses the group in the dirty mark, the assembly request and
  the write-back

#### Scenario: A group rule's scan omits its bound input columns

- **WHEN** `POST /v1/rules/{id}/scan` is called for a rule whose `trigger_kind` is `group` and which
  binds one or more source columns as evaluator inputs
- **THEN** those columns are absent from the returned rows, while the `group_by` value, the cursor
  columns, and any column the rule's `filter_sql` references are present

#### Scenario: A row-grain rule's scan still projects its bound input columns

- **WHEN** the same bindings are declared on a rule whose `trigger_kind` is `on_ingest` or `schedule`
- **THEN** the bound columns are projected, because the scanned row is the unit of work

#### Scenario: A non-entitled caller cannot scan a group rule that binds a sensitive column

- **WHEN** a caller who may not see sensitive columns scans a group rule that binds a `sensitive` source
  column which nothing else references
- **THEN** the request is rejected with HTTP 403 `sensitive_column_not_entitled`, even though that column
  is no longer part of the group rule's projection

#### Scenario: A non-entitled caller cannot scan a rule that reads a sensitive enrichment column

- **WHEN** a caller who may not see sensitive columns scans a rule referencing a `sensitive` column of an
  `ACTIVE` enrichment of its read source
- **THEN** the request is rejected with HTTP 403 `sensitive_column_not_entitled`

#### Scenario: The signal's raw columns are not projected

- **WHEN** the same rule is scanned and its `ready_when.signal` references a column that no input binding
  and no filter reads
- **THEN** that column is absent from the returned rows and only the `__signal` verdict is returned

#### Scenario: A group rule without a signal carries no verdict

- **WHEN** a group rule whose `ready_when` declares only `idle` and/or `max_staleness` is scanned
- **THEN** the returned rows carry no `__signal` column

#### Scenario: A non-entitled caller cannot scan a rule whose signal reads a sensitive column

- **WHEN** a caller who may not see sensitive columns scans a group rule whose `ready_when.signal`
  references a `sensitive` source column that nothing else projects
- **THEN** the request is rejected with HTTP 403 `sensitive_column_not_entitled` — computing the verdict
  server-side does not make the read permissible

#### Scenario: A group rule's scan keeps row-scan semantics

- **WHEN** a group rule is scanned across pages
- **THEN** paging, cursor advance, safety-lag, `final=1`, and the sensitive-column re-check behave
  exactly as for a row-grain rule

### Requirement: Declarable group member selection

A `group` rule SHALL be able to declare **which** of a group's members bear on its label, through an
optional `member_select` object carrying an optional `prefer_sql`, an optional `order_by`, and a `limit`.
Without it the endpoint's existing policy applies unchanged: the newest members by the source's total
order, bounded by the configured caps.

The three fields SHALL compose as one selection policy, evaluated over the group's members after the
membership `filter` and the safety lag:

- `prefer_sql` — a boolean predicate over the read source's **entity fields**, validated by the same
  closed grammar and field resolution as `filter_sql`. Members satisfying it are the **candidate set**.
  When **no** member satisfies it, the candidate set SHALL fall back to **all** members of the group, so a
  rule declaring a preference still produces a label for a group that cannot satisfy it.
- `order_by` — an ordered list of `{column, direction}` over the read source's **entity fields**, applied
  to the candidate set. It SHALL be followed by the source's total order descending as a deterministic
  tiebreak, so selection is stable across re-assembly of an unchanged group.
- `limit` — the number of candidates retained, at least 1 and at most the configured group-fetch maximum
  row count.

`member_select` SHALL be valid only for a rule whose `trigger_kind` is `group`; declaring it on any other
trigger kind SHALL be rejected with HTTP 422 `rule_validation_failed`. A `prefer_sql` that fails the
grammar, either field naming a field reachable on neither the read source nor its `ACTIVE` enrichments,
or a `limit` outside its range SHALL each be rejected with HTTP 422 `rule_validation_failed`. An
`order_by` direction SHALL be `asc` or `desc`; any other value is rejected as a malformed request body
(HTTP 400), as every other enum-valued field of the API is. A `member_select` referencing a `sensitive`
column — of the read source or of one of its `ACTIVE` enrichments — SHALL be treated exactly as
`filter_sql` is: rule registration and group assembly by a caller who may not see sensitive columns SHALL
be rejected with HTTP 403 `sensitive_column_not_entitled`.

Selection is a statement about **which members qualify** and SHALL NOT change the order members are
returned in: the response ordering remains the source's total order, ascending.

#### Scenario: A preference narrows the candidate set

- **WHEN** a group rule declaring `member_select.prefer_sql` assembles a group in which some members
  satisfy that predicate
- **THEN** only members satisfying it are candidates, and the members that do not are excluded from the
  response

#### Scenario: A preference no member satisfies falls back to the whole group

- **WHEN** the same rule assembles a group in which **no** member satisfies `prefer_sql`
- **THEN** all members of the group become candidates and a label is still produced, rather than the group
  returning empty

#### Scenario: Candidates are ordered by the declared ordering

- **WHEN** a group rule declares `member_select.order_by` over a source column and its candidate set is
  larger than `limit`
- **THEN** the retained members are those ranking first by that ordering, with the source's total order
  descending breaking ties, and re-assembling the same unchanged group retains the same members

#### Scenario: A selection policy over an enrichment column is accepted

- **WHEN** a group rule declares a `member_select.prefer_sql` or `order_by` naming a column of an
  `ACTIVE` enrichment of its read source
- **THEN** the rule is accepted, and the selection phase emits the grain join for that enrichment so the
  predicate and the ordering resolve

#### Scenario: A ranked selection is still returned in the source's total order

- **WHEN** a rule's `order_by` ranks an older member above a newer one and both are retained
- **THEN** the response returns them ascending in the source's total order, not in the order the ranking put
  them in

#### Scenario: A limit smaller than the group retains exactly that many members

- **WHEN** a group has more candidates than `member_select.limit`
- **THEN** exactly `limit` members are returned

#### Scenario: Member selection is rejected on a non-group rule

- **WHEN** a rule whose `trigger_kind` is not `group` is created or updated with `member_select`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An invalid member selection is rejected at registration

- **WHEN** a group rule declares a `member_select` whose `prefer_sql` fails the closed grammar, whose
  `prefer_sql` or `order_by` names a field reachable on neither the read source nor its `ACTIVE`
  enrichments, or whose `limit` is below 1 or above the configured maximum row count
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A non-entitled caller cannot use a selection over a sensitive column

- **WHEN** a caller who may not see sensitive columns registers or assembles a group for a rule whose
  `member_select` references a `sensitive` column of the read source or of one of its `ACTIVE`
  enrichments
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: A rule declaring no member selection is unchanged

- **WHEN** a group rule carrying no `member_select` assembles a group
- **THEN** the members retained are the most recent ones by the source's total order, bounded by the
  configured caps, exactly as before

## ADDED Requirements

### Requirement: An unaddressable group key is excluded by the read

Every rule-scoped read of a group rule whose `group_by` names an enrichment column SHALL carry an
ADAS-emitted predicate excluding a group key that is NULL or the empty string, in addition to the rule's
own `filter_sql`. This covers the incremental scan and the group-input assembly alike.

Both exclusions are required and for different reasons. NULL is what an unmatched outer join yields for
a `nullable` enrichment column. The empty string is a **derived** value — an enrichment may compute it
for a row that genuinely has no key — and, because ClickHouse's `join_use_nulls` is 0, is also how an
unmatched join renders a non-nullable column. Neither value addresses a row of the target enrichment: a
NULL key cannot be attributed to any group, and an empty key would collect every keyless row of the
source into a single group that corresponds to nothing.

The predicate SHALL be emitted by ADAS rather than required of the author's `filter_sql`. A declared
requirement cannot be verified — whether a SQL predicate excludes NULL and the empty string is not
decidable from its parse tree, and any syntactic check is satisfied by a reference that excludes nothing
— so a requirement would read as a guarantee it does not provide. A rule whose `filter_sql` already
excludes those rows SHALL be unaffected, the emitted predicate being redundant for it.

A rule whose `group_by` names a column of the read source itself SHALL NOT gain the predicate, so no
rule registered before this capability changes the statement it renders.

#### Scenario: A NULL group key is excluded from the scan

- **WHEN** a group rule whose `group_by` names an enrichment column is scanned, and a source row's
  enrichment cell is absent so the joined value is NULL
- **THEN** that row is absent from the scan page, and no group is marked for it

#### Scenario: An empty group key is excluded from the scan

- **WHEN** the same rule is scanned and a source row's enrichment cell holds the empty string
- **THEN** that row is absent from the scan page, so keyless rows do not accumulate into a single group

#### Scenario: The same exclusion applies to group assembly

- **WHEN** `POST /v1/rules/{id}/group-rows` is called for that rule
- **THEN** members whose group key is NULL or empty are excluded from the assembled result

#### Scenario: A source-column group key renders unchanged

- **WHEN** a group rule whose `group_by` names a column of its read source is scanned
- **THEN** no additional predicate is emitted and the statement is unchanged

### Requirement: A rule's read window is clamped to its referenced enrichments' positions

Every rule-scoped read that advances a cursor SHALL bound its window to the position through which each
enrichment its statements join has been materialized, taking the **lowest** such position when several
enrichments are joined. This applies to the incremental scan; the Class-1 materialize window carries the
same clamp (see [class1-enrichment](../class1-enrichment/spec.md)).

The clamp exists because an enrichment row lands **after** the source row it derives from, while a
rule's window advances on the source's cursor. Without it a row would be read while its enrichment cell
is still absent, and because the source row does not move again, the consequence is permanent: a group
rule would attribute the row to the wrong group or to none and never revisit it, a `ready_when.signal`
computed inside the scan query would return a verdict derived from an absent value, and an
`input_bindings` value read at assembly would be missing from the prompt. Clamping the window removes
every one of those at once, because a row that does not enter the window contributes to none of them.

Clamping the scan window SHALL be sufficient for a group rule's members: the assembly endpoint selects
the **whole** group by key rather than only newly seen members, so a group that is marked is assembled
from every member it has. The unrecoverable case is a source row that is never marked at all, which is
what the clamp prevents.

The clamp's set and the **join** set are therefore not the same set, and each SHALL be derived for what it
governs. A statement SHALL join only the enrichments its own clauses name, so nothing pays a full hash
read of a table it never selects from. The clamp SHALL additionally cover a group rule's `input_bindings`
columns, which the scan page does not project but the assembly reads for the very rows that page marks: a
bound value read while its enrichment cell is absent is a wrong prompt value, so the enrichment behind it
holds the scan window back even though no clause of the scan names it.

An enrichment that contributes no bound — one no rule targets — SHALL NOT hold the window back. A
`ready_when.idle` window SHALL be measured after the clamp, so a group's idle timer starts from the
activity the scan actually reported; the clamp therefore delays labelling by at most the lag of the
enrichments a rule reads.

A rule joining no enrichment SHALL be unclamped and its window unchanged.

#### Scenario: A window is held back to an enrichment's materialized position

- **WHEN** a rule referencing an enrichment column is scanned while source rows exist above that
  enrichment's recorded materialize position
- **THEN** those rows are excluded from the page, the returned cursor does not advance past that
  position, and a later scan returns them once the enrichment has reached them

#### Scenario: The lowest position bounds a multi-enrichment read

- **WHEN** a rule references columns of two enrichments materialized to different positions
- **THEN** the window is bounded by the lower of the two

#### Scenario: An enrichment with no rule imposes no bound

- **WHEN** a rule references a column of an `ACTIVE` enrichment that no rule targets
- **THEN** the window is not held back, because that enrichment will never gain another row

#### Scenario: A rule joining no enrichment is unclamped

- **WHEN** a rule referencing only its read source's own columns is scanned
- **THEN** the window is bounded only by the cursor, the safety-lag, and any requested bounds, exactly
  as before

#### Scenario: A clamped scan reports its lag rather than failing

- **WHEN** a rule's referenced enrichment has not been materialized at all, so no source row is inside
  the window
- **THEN** the scan returns an empty page and echoes the input cursor, rather than failing

### Requirement: A rule's state reports its freshness exposure

A rule read SHALL report two freshness facts that are true **now** rather than as of its last run:
which enrichments it reads that contribute no bound to its window and why, and whether an enrichment it
reads has had its history re-derived since this rule last wrote. Both SHALL appear on the rule
representation returned by `GET /v1/rules`, `GET /v1/rules/{id}`, and the create and update responses,
mirroring the equivalent fields on a pipeline.

The system SHALL NOT act on either fact. An enrichment whose history moved invalidates the values a
rule derived from it, and re-deriving them SHALL remain an operator action: the downstream of an `llm`
rule is metered, so cascading over history would spend budget no operator approved, and doing it safely
would require ordering, a cost ceiling and a kill switch. Reporting the exposure is what lets an
operator decide.

Both facts SHALL be **derived on read**, not stored: they describe the current state of the rules a
declaration points at, so a stored answer would report nothing for a rule that has never run and a stale
answer for one that ran before an upstream rule changed — in both cases exactly when an operator is
asking. A failure to resolve them SHALL degrade to reporting no diagnostics and SHALL NOT fail the rule
read, which is already reporting the rule's own state.

#### Scenario: An unbounded enrichment read is reported

- **WHEN** a rule reads an enrichment that contributes no bound to its window
- **THEN** the rule's representation names that enrichment and the reason it contributes no bound

#### Scenario: A re-derived upstream is reported and not acted on

- **WHEN** an enrichment a rule reads has its history re-derived after the rule last wrote
- **THEN** the rule's representation reports that enrichment and when its history moved, and no
  re-derivation of the reading rule is triggered

#### Scenario: A rule reading no enrichment reports nothing

- **WHEN** a rule referencing only its read source's own columns is read
- **THEN** it reports no unbounded reads and no re-derived upstream

#### Scenario: Undiagnosable state does not fail the read

- **WHEN** a rule's declaration cannot be resolved against its read source's current schema
- **THEN** the rule read succeeds and reports no freshness diagnostics
