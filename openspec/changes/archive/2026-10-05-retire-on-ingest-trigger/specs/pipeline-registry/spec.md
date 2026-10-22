## MODIFIED Requirements

### Requirement: A declaration field belonging to another kind is rejected

The system SHALL reject a registration or patch carrying a declaration field that belongs to a kind
other than the request's own `kind`, with HTTP 422 naming the offending field. Silently ignoring it
SHALL NOT be an option.

The registry serves one flat request and response shape across kinds, so a caller can send any
field to any kind and the binding will accept it. Cross-kind rejection is what makes that flat shape
safe, and it is the only thing that does: a field ignored rather than refused reads back absent from
the stored pipeline, so a caller who mistook one kind's vocabulary for another's would be told their
declaration was accepted while the part they cared about was discarded.

Consequently a field that is required for one kind and meaningless for another SHALL NOT be enforced
by the request shape alone. Such a field SHALL be optional at the binding and required by the
kind-dispatched validation, so the failure is the 422 above rather than a generic deserialization
400.

Where a kind's fields are **nested** inside a block, the cross-kind rejection SHALL name
`<block>.<member>` rather than the block. Naming only the block throws away the half of the message
that tells the caller which field they misplaced, which is the whole purpose of the rejection. The
`enrich` kind's **`transform`** block SHALL be listed this way, member by member — `transform.type`,
`transform.model`, `transform.preset`, `transform.params`, `transform.request_template`,
`transform.inputs`, `transform.outputs`, `transform.output_vars`, `transform.response_schema` — exactly
as `advanced` is, so an `aggregate` declaration carrying a prompt is told which member it misplaced.

One member of that list is **verb-dependent**, and SHALL be listed with the qualifier rather than
flatly: `transform.output_vars` is not a member of the create-side record at all (a new declaration
authors `outputs`), so on `POST` it is refused at the binding with HTTP 400 on **either** kind, under
the rule below, and the 422 cross-kind refusal applies to it only on `PATCH`.

A field that **no longer belongs to any kind** SHALL be refused at the binding with HTTP 400 naming
it, exactly as a field that never existed — not accepted for a transitional release and not
cross-checked against a derived value. Accepting a spelling the read never echoes is the same failure
strict request parsing exists to prevent: the request succeeds while doing something other than what
the caller wrote. It follows that a removed **flat** spelling is refused at the binding on **either**
kind, before the cross-kind check is reached, so nothing has to be double-listed and there is no
window in which the flat spelling stops being refused on the kind it never belonged to.

**`evaluator_name` and `evaluator_version` move from the first rule to the second** when the evaluator
is folded into the declaration. They were enrich-only members and were cross-kind-rejected with 422 on
an `aggregate` declaration; after the fold they are members of no kind, so they are removed from the
request records and refused at the binding with **400** on either kind. The status change is not
cosmetic — it is the same distinction the two rules already draw, applied as a member leaves the
vocabulary rather than moves between kinds — and it is called out because a test asserting 422 for
`evaluator_name` on an `aggregate` declaration will now see 400 and is asserting the wrong rule, not
finding a regression.

#### Scenario: An aggregate-only field on an enrich declaration is refused

- **WHEN** `POST /v1/pipelines` registers `kind: enrich` carrying aggregate `measures`
- **THEN** the request is rejected with HTTP 422 naming the field, and nothing is persisted

#### Scenario: An enrich-only field on an aggregate declaration is refused

- **WHEN** `POST /v1/pipelines` registers `kind: aggregate` carrying `transform.request_template`
- **THEN** the request is rejected with HTTP 422 naming `transform.request_template`, not `transform`,
  and nothing is persisted

#### Scenario: A field required only for another kind is not required by the binding

- **WHEN** `POST /v1/pipelines` registers a kind for which `inputs` is optional, omitting it
- **THEN** the request is accepted, rather than failing the binding that another kind's requirement
  would impose

#### Scenario: A field belonging to no kind is refused by the binding

- **WHEN** a registration or patch carries a member no kind declares — a misspelling such as
  `member_select.order` for `order_by`, at any nesting depth
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the unrecognized field, and nothing
  is persisted

The two refusals are deliberately different statuses, and the boundary is which vocabulary the field
belongs to. A field this registry knows but the request's `kind` does not is a semantic error the
kind-dispatched validation reports (422, above). A field the shape does not define at all cannot be
attributed to a kind, so it is refused at the binding (400). What matters is that neither is accepted
silently: the flat shape makes both spellings bind cleanly, and an all-optional record cannot tell them
apart by validation.

#### Scenario: A nested enrich-only member on an aggregate declaration names the member

- **WHEN** `POST /v1/pipelines` registers `kind: aggregate` carrying `advanced.rows_per_scan`
- **THEN** the request is rejected with HTTP 422 naming `advanced.rows_per_scan`, not `advanced`

#### Scenario: A removed flat spelling is refused on either kind

- **WHEN** `POST /v1/pipelines` carries `cadence` on `kind: enrich` or on `kind: aggregate`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, before any cross-kind
  check is reached

#### Scenario: The retired scan_every member is refused at the binding on either kind

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` carries `advanced.scan_every` on
  `kind: enrich` or on `kind: aggregate`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming `advanced.scan_every`, not
  cross-kind-rejected with 422, because after its retirement the member belongs to no kind

#### Scenario: A retired evaluator reference is refused at the binding, not cross-kind

- **WHEN** `POST /v1/pipelines` carries `evaluator_name` or `evaluator_version` on `kind: enrich` or on
  `kind: aggregate`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, before any cross-kind
  check is reached — the member belongs to no kind rather than to the other one
