## ADDED Requirements

### Requirement: Declarable group member selection

A `group` rule SHALL be able to declare **which** of a group's members bear on its label, through an
optional `member_select` object carrying an optional `prefer_sql`, an optional `order_by`, and a `limit`.
Without it the endpoint's existing policy applies unchanged: the newest members by the source's total
order, bounded by the configured caps.

The three fields SHALL compose as one selection policy, evaluated over the group's members after the
membership `filter` and the safety lag:

- `prefer_sql` — a boolean predicate over the read source's declared columns, validated by the same closed
  grammar and column resolution as `filter_sql`. Members satisfying it are the **candidate set**. When
  **no** member satisfies it, the candidate set SHALL fall back to **all** members of the group, so a rule
  declaring a preference still produces a label for a group that cannot satisfy it.
- `order_by` — an ordered list of `{column, direction}` over the read source's declared columns, applied to
  the candidate set. It SHALL be followed by the source's total order descending as a deterministic
  tiebreak, so selection is stable across re-assembly of an unchanged group.
- `limit` — the number of candidates retained, at least 1 and at most the configured group-fetch maximum
  row count.

`member_select` SHALL be valid only for a rule whose `trigger_kind` is `group`; declaring it on any other
trigger kind SHALL be rejected with HTTP 422 `rule_validation_failed`. A `prefer_sql` that fails the
grammar, either field naming a column absent from the read source, or a `limit` outside its range SHALL
each be rejected with HTTP 422 `rule_validation_failed`. An `order_by` direction SHALL be `asc` or `desc`;
any other value is rejected as a malformed request body (HTTP 400), as every other enum-valued field of the
API is. A `member_select` referencing a `sensitive` source column SHALL be treated
exactly as `filter_sql` is: rule registration and group assembly by a caller who may not see sensitive
columns SHALL be rejected with HTTP 403 `sensitive_column_not_entitled`.

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
  `prefer_sql` or `order_by` names a column absent from the read source, or whose `limit` is below 1 or
  above the configured maximum row count
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A non-entitled caller cannot use a selection over a sensitive column

- **WHEN** a caller who may not see sensitive columns registers or assembles a group for a rule whose
  `member_select` references a `sensitive` source column
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: A rule declaring no member selection is unchanged

- **WHEN** a group rule carrying no `member_select` assembles a group
- **THEN** the members retained are the most recent ones by the source's total order, bounded by the
  configured caps, exactly as before

## MODIFIED Requirements

### Requirement: Rule-scoped group-input assembly endpoint

The system SHALL expose `POST /v1/rules/{id}/group-rows` accepting a body `{groupKey, limit?}` and
returning `{rows[], truncated}`. It SHALL resolve the rule's source, membership `filter`, projection,
member selection, and ordering from the stored rule; the caller SHALL supply **only** the group key, and
SHALL NOT name a table, a filter, a column list, an ordering, or a member-selection policy. The assembled
result SHALL comprise the source rows whose `group_by` column equals `groupKey` and which satisfy the
rule's `filter`, ordered by the source's total order, read with `final=1`, and bounded by the caps and the
member selection below. The rule SHALL be a `group` rule; a request for a rule with any other trigger kind
SHALL be rejected with HTTP 422 `rule_validation_failed`. An unknown rule SHALL return HTTP 404
`unknown_rule`.

The assembly SHALL be read in **two phases**, so that its cost in the data store is proportional to the
members it returns rather than to the size of the group:

1. **Selection** reads only inexpensive columns — the `group_by` column, the cursor columns, the columns
   the membership filter references, and the source's full ordering key — and determines which members
   qualify under the caps and the member selection. It SHALL NOT project the rule's bound input-binding
   columns. The columns `member_select` references are evaluated by the data store, in the preference
   predicate and in the ordering, and SHALL NOT be projected either: they are not part of the response, so
   projecting them would read a value per member only to discard it, and would return a body column to this
   phase for a policy that ranks by one.
2. **Projection** reads the rule's bound input-binding columns for **only the qualifying members**, each
   addressed by its value for **every column of the source's ordering key**, so that the data store can
   prune its read to the storage granules holding those members. Addressing the members by a column that
   the source does not index or order by SHALL NOT be used *in place of* that tuple: it would not prune the
   read and would leave the endpoint's cost proportional to the whole group. The source's identity column
   SHALL be carried **alongside** the tuple, because an ordering key is not required to be unique and two
   members sharing one would otherwise be indistinguishable when the two phases' rows are merged; and the
   phase SHALL read at most one row per qualifying member, so a source whose identity column is not in fact
   unique cannot restore an unbounded read.

A group whose rule binds **no** input columns, and a group whose read source declares **no ordering key**,
SHALL each be assembled by a single-phase read with the projection applied directly — in the first case
there is nothing expensive to defer, and in the second the members cannot be addressed for a second read
at all. The two-phase read is an optimisation of *how* the contract is met and SHALL NOT change the
response: the members, their columns, their ascending order, and `truncated` SHALL be the same as a
single-phase read of the same group would produce.

The endpoint SHALL apply the **same safety-lag** as the scan, in the selection phase, so the members
qualifying are drawn from the same window the scan has surfaced. Assembling from rows the scan has not yet
surfaced would produce a group whose contents disagree with the group version the caller dirty-marked, so
consistency between the two reads takes precedence over assembling the newest possible group.

A group key that matches no rows — because it has aged out of the source's retention, because the
membership filter excluded every member, or because it never existed — SHALL return HTTP 200 with an
empty `rows` array and `truncated: false`. It SHALL NOT be an error: groups are discovered from a row
scan and expire with the source, so an empty group is a normal race rather than a caller mistake.

#### Scenario: A group's members are assembled, filtered and ordered

- **WHEN** `POST /v1/rules/{id}/group-rows` is called with a `groupKey` for a `group` rule
- **THEN** the response contains the source rows sharing that group key which satisfy the rule's
  membership filter, in the source's total order, projected to the rule's referenced columns

#### Scenario: The caller supplies only the group key

- **WHEN** any `group-rows` request is made
- **THEN** the source table, membership filter, projection, member selection, and ordering are resolved
  from the stored rule, and no caller-supplied table, filter, column list, or selection policy is accepted

#### Scenario: Bound input columns are read only for the qualifying members

- **WHEN** a group rule binding a body column assembles a group whose members outnumber those the caps and
  member selection retain
- **THEN** the bound columns are read only for the retained members, addressed by the source's ordering
  key, and are not read for the members that were excluded

#### Scenario: The selection phase does not read the bound input columns

- **WHEN** the selection phase of any assembly runs
- **THEN** it projects the group key, the cursor columns, the membership filter's columns and the source's
  ordering key, and does not project the rule's bound input columns nor the columns its member selection
  merely ranks by

#### Scenario: A rule with nothing to defer is assembled in one phase

- **WHEN** a group rule declares no input bindings, or its read source declares no ordering key
- **THEN** the group is assembled by a single-phase read whose response is identical in members, columns,
  order and `truncated` to what the two-phase read would return

#### Scenario: A non-group rule is rejected

- **WHEN** `POST /v1/rules/{id}/group-rows` is called for a rule whose `trigger_kind` is not `group`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An unknown or empty group returns an empty page

- **WHEN** `group-rows` is called with a `groupKey` matching no rows
- **THEN** the response is HTTP 200 with `rows: []` and `truncated: false`

### Requirement: Group-input payload caps

The group assembly response SHALL be bounded by **both** a row count and a response byte size, each
with a configured default and maximum, and additionally by the rule's `member_select.limit` where one is
declared. A group whose members carry `sensitive` body columns can be megabytes, so a row cap alone would
leave the real failure mode unbounded. The **effective row cap** SHALL be the smallest of the caller's
`limit`, the configured default or maximum that applies to it, and the rule's `member_select.limit`; it
SHALL be applied in the selection phase, so a rule declaring a small `limit` obtains a correspondingly
small read.

When either cap trims the group, **or** the rule's member selection narrowed it, **or** the projection phase
could not find a member the selection phase qualified, the response SHALL set
`truncated: true`, so a caller can record that its input was partial and a downstream consumer can
identify a partial result. Policy narrowing counts as truncation because the resulting label was computed
from part of the group, and a label that saw two of a conversation's three hundred members SHALL NOT
report itself complete. When the response carries every member of the group, `truncated` SHALL be `false`.

When either cap trims the group, the members that SHALL be retained are the **most recent** ones — the
tail of the source's total order, not its head — unless the rule declares a `member_select`, in which case
its policy decides which members qualify. A group-grain label describes the group's current state, so
absent a declared policy the members that bear on it are the newest; retaining the oldest would answer a
question about a conversation's outcome from its opening. This is a statement about **which members
qualify**, and is deliberately independent of the **order they are returned in**: the response ordering is
unchanged and remains the source's total order, ascending. A caller that trims further SHALL therefore
receive a window it can trim from the front, and the two layers SHALL agree about which end is
expendable.

That agreement is automatic only for the **default** policy, where the retained members are the newest and
the oldest is by definition the expendable end. A rule declaring a `member_select` chooses its members by
its own ranking, which the ascending response does not express, so a caller trimming from the front may drop
the member the rule ranked highest. Such a rule's `limit` SHALL therefore be understood as a selection the
caller is expected to consume whole, and SHOULD be set low enough to fit the caller's own budget rather than
relying on the caller to trim it.

The single-member exception is unchanged in effect: a group whose first retained member alone exceeds the
byte cap SHALL still return that member rather than an empty page, so "this group has no members" stays
distinguishable from "this group has one oversized member".

#### Scenario: A row cap trims the group

- **WHEN** a group has more members than the effective row limit
- **THEN** the response returns at most that many rows and `truncated: true`

#### Scenario: A byte cap trims the group

- **WHEN** a group's members are individually small enough in number but exceed the response byte cap
- **THEN** the response is trimmed to fit the cap and `truncated: true`

#### Scenario: A declared member limit lowers the effective row cap

- **WHEN** a group rule declares `member_select.limit` below the configured and caller-supplied row limits
- **THEN** the effective row cap is the declared limit, it is applied in the selection phase, and at most
  that many members have their bound columns read

#### Scenario: A member lost between the two phases reports truncation

- **WHEN** the projection phase cannot find a member the selection phase qualified, because the source
  re-ingested that row between the two reads
- **THEN** that member is omitted rather than failing the request, and the response sets `truncated: true`

#### Scenario: A group narrowed by member selection reports truncation

- **WHEN** a rule's member selection retains fewer members than the group contains
- **THEN** the response sets `truncated: true` even though no configured cap was reached

#### Scenario: A trimmed group retains its most recent members

- **WHEN** either cap trims a group for a rule declaring no member selection
- **THEN** the members returned are the most recent ones by the source's total order, and the earlier
  members are the ones dropped

#### Scenario: A trimmed group is still returned in ascending order

- **WHEN** either cap trims a group, or a member selection narrows it
- **THEN** the retained members are returned in the source's total order, ascending, exactly as an
  untrimmed group is

#### Scenario: A group within both caps is not marked truncated

- **WHEN** a group fits within both the row and the byte cap and its every member is retained
- **THEN** every member is returned and `truncated: false`

#### Scenario: A single oversized member is returned rather than an empty page

- **WHEN** a group's first retained member alone exceeds the response byte cap
- **THEN** that member is returned with `truncated: true`, rather than an empty `rows` array

### Requirement: Sensitive-column re-check on group assembly

The group assembly endpoint SHALL re-apply the sensitive-column rule against the **calling** identity,
exactly as the scan does, and SHALL bind the check to the columns the **rule declares** — its bound
input bindings, its membership filter, and its `member_select` — rather than to the columns a given read
phase happens to project. If any of those is a `sensitive`-flagged source column and the caller may not
see sensitive columns (not `FULL_ADMIN`, and `security.mode` is not `none`), the request SHALL be rejected
with HTTP 403 `sensitive_column_not_entitled`, **before** either phase reads anything. Sensitive columns
SHALL NOT be silently stripped from the projection — a group input missing the very column the rule was
written to read would yield a quietly wrong label, so the misconfiguration fails loudly instead. The scan
and the group assembly SHALL share one implementation of this check so their behaviour cannot diverge.

#### Scenario: An entitled caller receives body columns

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) assembles a group for a rule whose
  projection includes `request_body` or `response_body`
- **THEN** those columns are present in the returned member rows

#### Scenario: A non-entitled caller is rejected rather than served a stripped group

- **WHEN** a caller who may not see sensitive columns assembles the same group
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and no
  partially-projected group is returned

#### Scenario: Entitlement is enforced before the selection phase reads

- **WHEN** a caller who may not see sensitive columns assembles a group for a rule that binds a sensitive
  column, even though the selection phase would not project it
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and
  neither phase issues a read
