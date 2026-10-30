## ADDED Requirements

### Requirement: The pipeline data plane completes a bound computed field

When an ENRICH pipeline's `transform.inputs` binds a computed field — `dial_usage_log_payload.request_body_full` or
`dial_usage_log_payload.request_body_rebuild` (see the data-sync capability, "A rebuilt request body is readable
beside the stored one") — every pipeline read that projects the bound columns SHALL return the field's completed
value under its qualified name: the row-grain scan page (`POST /v1/pipelines/{name}/scan`), the projection phase of
group assembly (`POST /v1/pipelines/{name}/group-rows`), and the dry-run preview's row and member samples. No read
SHALL return a stored delta in a computed field's place without marking it `partial`.

- **Where it is computed.** In group assembly a computed field SHALL be computed in the projection phase only, for
  the qualifying members only. The computed fields an enrichment's declaration binds SHALL together count as one heavy
  column for "The group projection reads one heavy column per statement": they SHALL be read and completed by one
  statement, so a bound `request_body_rebuild` always agrees with the bound `request_body_full`. The selection phase SHALL NOT read it. A group pipeline's scan page SHALL NOT carry it, as it
  carries no bound column.
- **Budget.** The rebuild statements SHALL run under the pipeline read budget of the read that asked for them,
  sharing what is left of its wall-clock ceiling, and under the bounds of the data-sync capability; a rebuild that
  cannot complete SHALL yield `partial` values and SHALL NOT fail the read.
- **Caps.** The group-input byte cap SHALL measure the completed value, which is what the response carries. A
  `partial` value SHALL NOT by itself set `truncated`: it is the stored body, returned whole, and a pipeline that
  needs to tell the two apart SHALL bind `request_body_rebuild` as well.
- **Counting.** Values completed on these reads SHALL be counted under `surface=pipeline`.
- **Query ids.** Each rebuild statement of these reads is a pipeline statement (see the observability capability,
  "Every pipeline ClickHouse statement carries a traceable query id") and SHALL carry a fresh
  `adas-{pipeline}-{stage}-rebuild-{uuid}` id, `stage` being the triggering read's; the call's outcome line SHALL keep
  the triggering read's id.
- **Access.** The data-plane sensitive-column check of these reads SHALL judge a bound computed field by its
  `sensitive` flag, as it judges a catalog column: a caller not entitled to the payload table's sensitive columns SHALL
  be refused with HTTP 403 `sensitive_column_not_entitled`, exactly as when the pipeline binds `request_body`.

#### Scenario: A group member is assembled with its whole request

- **WHEN** a group pipeline binding `dial_usage_log_payload.request_body_full` assembles a group whose selected member
  was stored in delta form
- **THEN** the member's row carries the rebuilt request under `dial_usage_log_payload.request_body_full`, and the
  selection phase read no body

#### Scenario: A partial member is fed as stored

- **WHEN** a selected member's chain cannot be completed
- **THEN** the member's row carries its stored body, a bound `dial_usage_log_payload.request_body_rebuild` reads
  `partial`, and `truncated` is unaffected by it

#### Scenario: A caller without the clearance is refused as for the stored body

- **WHEN** a caller without `TABLE_SENSITIVE` on `dial_usage_log_payload` scans or assembles groups of a pipeline
  binding `dial_usage_log_payload.request_body_full`
- **THEN** the request is rejected with HTTP 403 `sensitive_column_not_entitled` naming the field

#### Scenario: Both bound fields agree

- **WHEN** a group pipeline binds both computed fields and a member's chain reaches a root the rebuild cannot use (for
  example, one that is not a chat request)
- **THEN** the member's `dial_usage_log_payload.request_body_full` carries its stored body and its
  `dial_usage_log_payload.request_body_rebuild` reads `partial`, from one projection statement

#### Scenario: The byte cap counts the rebuilt body

- **WHEN** a group's rebuilt bodies exceed the response byte cap although their stored deltas would not
- **THEN** the group is trimmed by the cap on the rebuilt size and the response sets `truncated: true`

#### Scenario: A row-grain scan page carries the rebuilt body

- **WHEN** a row-grain pipeline binding `dial_usage_log_payload.request_body_full` is scanned
- **THEN** every page row carries the completed value, and the cursor and `has_more` are those the page would have
  without the field

#### Scenario: A rebuild statement is attributable to its pipeline

- **WHEN** the group projection of pipeline `p1` completes `dial_usage_log_payload.request_body_full` for a delta member
- **THEN** every rebuild statement it issues carries its own query id beginning `adas-p1-group-project-rebuild-`, and
  the projection statement keeps its own id

#### Scenario: The preview shows what the runner would receive

- **WHEN** an ENRICH declaration binding `dial_usage_log_payload.request_body_full` is previewed
- **THEN** the sampled rows carry the completed value, as the runner's reads would
