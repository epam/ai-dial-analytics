## MODIFIED Requirements

### Requirement: Scan projection and version isolation

The scan query SHALL read the **base source only, with no enrichment LEFT JOINs**, and SHALL push
projection down to `{columns referenced by the rule's filter and input bindings} ∪ {grain id} ∪
{cursor columns}`. The scan SHALL read with `final=1` so only the latest version per source
identity is returned, independent of the projected columns.

For a rule whose `trigger_kind` is `group`, the projection SHALL additionally include the rule's
`group_by` column, so one scan page tells the caller which groups changed. The activity timestamp needed
to evaluate an idle window requires no addition: the scan already projects the source's version and
identity columns under their own names.

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
even though they are no longer projected. The check governs what a rule reads or derives from, not what
a response happens to return, and a rule binding a `sensitive` column still reads it — at the assembly
endpoint rather than at the scan. Narrowing the projection SHALL NOT become a way for a caller who may
not see sensitive columns to dirty-mark a rule whose groups it would then be refused.

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

#### Scenario: Scan omits enrichment-join columns

- **WHEN** the scanned source has one or more enrichments
- **THEN** the returned rows contain only source/projected columns — no auto-joined enrichment columns

#### Scenario: Projection is limited to referenced columns

- **WHEN** a rule references only a subset of the source's columns
- **THEN** the scan projects only those columns plus the grain and cursor columns

#### Scenario: A group rule's scan projects the group key and the readiness verdict

- **WHEN** `POST /v1/rules/{id}/scan` is called for a rule whose `trigger_kind` is `group` and whose
  `ready_when` declares a `signal`
- **THEN** each returned row additionally carries the rule's `group_by` value, the source's version column
  as an activity timestamp, and `__signal` as a boolean — `true` for a row satisfying the predicate and
  `false` for one that does not

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

### Requirement: Group-input payload caps

The group assembly response SHALL be bounded by **both** a row count and a response byte size, each
with a configured default and maximum. A group whose members carry `sensitive` body columns can be
megabytes, so a row cap alone would leave the real failure mode unbounded. When either cap trims the
group, the response SHALL set `truncated: true`, so a caller can record that its input was partial and
a downstream consumer can identify a partial result. When neither cap trims the group,
`truncated` SHALL be `false`.

When either cap trims the group, the members that SHALL be retained are the **most recent** ones — the
tail of the source's total order, not its head. A group-grain label describes the group's current
state, so the members that bear on it are the newest; retaining the oldest would answer a question
about a conversation's outcome from its opening. This is a statement about **which members qualify**,
and is deliberately independent of the **order they are returned in**: the response ordering is
unchanged and remains the source's total order, ascending. A caller that trims further SHALL therefore
receive a window it can trim from the front, and the two layers SHALL agree about which end is
expendable.

The single-member exception is unchanged in effect and improved in outcome: a group whose first
retained member alone exceeds the byte cap SHALL still return that member rather than an empty page, so
"this group has no members" stays distinguishable from "this group has one oversized member" — and that
member is now the newest one.

#### Scenario: A row cap trims the group

- **WHEN** a group has more members than the effective row limit
- **THEN** the response returns at most that many rows and `truncated: true`

#### Scenario: A byte cap trims the group

- **WHEN** a group's members are individually small enough in number but exceed the response byte cap
- **THEN** the response is trimmed to fit the cap and `truncated: true`

#### Scenario: A trimmed group retains its most recent members

- **WHEN** either cap trims a group
- **THEN** the members returned are the most recent ones by the source's total order, and the earlier
  members are the ones dropped

#### Scenario: A trimmed group is still returned in ascending order

- **WHEN** either cap trims a group
- **THEN** the retained members are returned in the source's total order, ascending, exactly as an
  untrimmed group is

#### Scenario: A group within both caps is not marked truncated

- **WHEN** a group fits within both the row and the byte cap
- **THEN** every member is returned and `truncated: false`

#### Scenario: A single oversized member is returned rather than an empty page

- **WHEN** a group's newest member alone exceeds the response byte cap
- **THEN** that member is returned with `truncated: true`, rather than an empty `rows` array
