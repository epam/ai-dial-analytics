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
the same endpoint with a richer projection, not a second endpoint. Its cost is therefore proportional to
**new rows**, not to the number of groups.

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
