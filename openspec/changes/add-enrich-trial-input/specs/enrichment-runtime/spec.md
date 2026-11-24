## ADDED Requirements

### Requirement: A runner-only trial-input read serves the runner's dry run

The system SHALL expose `POST /v1/pipelines/{name}/trial-input` for `enrich` pipelines whose transform is
`llm`, whatever its preset. It is the read the Enrichment Runner's model-calling dry run is built on, and it
SHALL answer with everything that dry run needs in one consistent snapshot:

- `preview`: the response `POST /v1/pipelines/{name}/preview` would give for the same body, in the shared
  preview shape, values capped as there;
- `pipeline`: the compiled projection of the declaration the sample was read through, in the shape the
  single compiled read of an `enrich` pipeline serves. For a **disabled** pipeline it SHALL be built from the
  declaration as the gate derived it in memory, not from the stored mapping, so a draft whose stored mapping
  is stale is served the mapping arming would store. For an **enabled** pipeline it SHALL be the stored
  declaration's compiled projection;
- `input_rows`: the rows the runner would receive for that sample, keyed as the runner's own reads key them
  and with **no value cap**;
- `truncated`: for a `group` pipeline, whether the group fetch's row or byte cap cut the group, as
  `/group-rows` reports it; absent otherwise.

The body MAY carry `group_key`, `from`, `to` and `limit`, and SHALL be refused with HTTP 400 `bad_request`
for any other member. `group_key`, `from` and `to` mean what they mean on the preview, and SHALL be refused
where the preview refuses them. `limit` is the number of row-grain input rows to return: it SHALL default to
`1`, SHALL be between `1` and `5`, and SHALL be refused with HTTP 422 `pipeline_validation_failed` outside
that range or on a `group` pipeline.

**What `input_rows` holds SHALL depend on the trigger:**

- For a **row-grain** pipeline, `input_rows` SHALL be the first `limit` rows of the preview's newest-first
  sample, in that order, each keyed as a `POST /v1/pipelines/{name}/scan` row of the same pipeline is keyed,
  and `preview.rows` SHALL describe the same rows by their grain key.
- For a **group** pipeline, `input_rows` SHALL be what `POST /v1/pipelines/{name}/group-rows` returns for the
  group the preview chose or was named, under the same membership filter, member selection, ordering, row cap
  and byte cap. `preview.rows` SHALL be the preview's own member sample of that group. It is not required to
  be the head of `input_rows`: the sample takes the members the declaration's `member_select` ranks highest,
  while the fetch returns its members in the source's order.

**Checks and refusals SHALL be the preview's, in the preview's order.** A disabled pipeline SHALL be refused
exactly as arming it would be refused, which includes the `app-routes` allowlist check of a
`dial_app_route` transform; an enabled pipeline SHALL run only the checks the preview runs on one. A
pipeline of kind `aggregate`, and an `enrich` pipeline whose transform is `sql`, SHALL be refused with HTTP
422 `pipeline_validation_failed`, because no model is called for either. An unknown pipeline SHALL be HTTP
404 `unknown_pipeline`.

**It SHALL be authorized as the preview is, against the calling identity:** the `REGISTRY_ADMIN`
privilege, the target's grant, column-policy and sensitive-column entitlement over every column the
declaration reads, and the refusal of a row-confined caller, each with the preview's error code. The runner
calls it with the credential of the person who asked for the dry run, so what it returns is what that
person's preview would show, uncapped. It SHALL NOT be authorized by the data-plane rule of `TABLE_READ` on
the source and `TABLE_WRITE` on the target alone: that rule is what the runner's service identity holds, and
admitting it here would let a dry run read what its author may not.

Every read it issues SHALL run under the pipeline read budget, and a read it refuses SHALL be HTTP 422
`pipeline_preview_budget_exceeded`, as the preview's reads are. A sample that is empty (a
`no_group_found`, `held_by_enrichment` or `no_matching_rows` note) SHALL be answered HTTP 200 with an empty
`input_rows` and the preview's notes, not refused.

It SHALL write nothing, SHALL advance no cursor, SHALL be served for a disabled pipeline, and SHALL NOT be
recorded as the runner's contact or attempt: it is called on an author's request, and counting it would make
a stalled runner look alive.

#### Scenario: A row-grain trial input returns the sampled row uncapped

- **GIVEN** an enabled row-grain `llm` pipeline binding `dial_usage_log_payload.request_body_full`, whose
  newest matching row carries a value of tens of KiB in that column
- **WHEN** `POST /v1/pipelines/{name}/trial-input` is called with the matching contract header and no body
- **THEN** the response is HTTP 200 with one entry in `input_rows`, keyed as that pipeline's scan rows are,
  whose value is the full value
- **AND** `preview.rows` carries the same row by its grain key, with the value capped and a
  `values_truncated` note

#### Scenario: A row-grain limit returns that many newest rows

- **WHEN** a row-grain `llm` pipeline with at least three matching rows is called with `{"limit": 3}`
- **THEN** `input_rows` holds the three newest matching rows, newest first, in the order of `preview.rows`

#### Scenario: A limit outside its range or on a group pipeline is refused

- **WHEN** a row-grain pipeline is called with `{"limit": 6}`, or a `group` pipeline with `{"limit": 1}`
- **THEN** the response is HTTP 422 `pipeline_validation_failed` and nothing is read

#### Scenario: A group trial input returns the whole group as the runner fetches it

- **GIVEN** a `group` `llm` pipeline whose newest group has twelve members and a fetch cap above twelve
- **WHEN** `trial-input` is called with no `group_key`
- **THEN** `preview.group_key` names that group, `input_rows` holds all twelve members in the order
  `/group-rows` returns them, `truncated` is `false`, and `preview.rows` holds five of them

#### Scenario: A named group is used as named

- **WHEN** a `group` pipeline is called with `{"group_key": "chat-42"}`
- **THEN** `input_rows` is what `/group-rows` returns for `chat-42` and `preview.group_key` is `chat-42`

#### Scenario: A disabled draft is served its gated declaration

- **GIVEN** a disabled `llm` pipeline saved before its target's columns were defined, so its stored output
  mapping is empty, which arming would accept now
- **WHEN** `trial-input` is called
- **THEN** `pipeline` carries the outputs and response schema the gate derived, not the empty stored mapping

#### Scenario: A route the allowlist does not name is refused before anything is read

- **GIVEN** a disabled `dial_app_route` pipeline whose `(model, route)` pair is not in the `app-routes`
  setting
- **WHEN** `trial-input` is called
- **THEN** the response is HTTP 422 `pipeline_validation_failed` naming the pair and the setting, exactly as
  arming would refuse it, and no row is read

#### Scenario: The author's entitlement governs, not the runner's

- **GIVEN** a pipeline binding a `sensitive` column, and a caller holding `REGISTRY_ADMIN` and the target's
  grant but no clearance for that column
- **WHEN** that caller calls `trial-input`
- **THEN** the response is HTTP 403 `sensitive_column_not_entitled`, as the caller's preview would be

#### Scenario: The data-plane grants alone do not admit a trial input

- **WHEN** a caller holding `read` on the read source and `write` on the target, but not `REGISTRY_ADMIN`,
  calls `trial-input`
- **THEN** the response is HTTP 403 `forbidden`

#### Scenario: A pipeline with no model call is refused

- **WHEN** `trial-input` is called for an `aggregate` pipeline or a Class-1 `sql` enrich pipeline
- **THEN** the response is HTTP 422 `pipeline_validation_failed`

#### Scenario: An empty sample is answered, not refused

- **WHEN** `trial-input` is called for a `group` pipeline with no group to sample
- **THEN** the response is HTTP 200 with an empty `input_rows`, no `truncated` claim of a cut, and a
  `no_group_found` note in `preview.notes`

#### Scenario: A trial-input read over its budget refuses under the preview code

- **WHEN** a read `trial-input` issues exceeds a `pipeline.budget.*` ceiling
- **THEN** the response is HTTP 422 `pipeline_preview_budget_exceeded` naming the read and the ceiling

#### Scenario: A trial input writes nothing and is not runner contact

- **WHEN** `trial-input` is served for an enabled pipeline
- **THEN** the target, the pipeline's stored declaration and every cursor are unchanged, and the
  pipeline's runner contact and attempt series are not advanced

## MODIFIED Requirements

### Requirement: The scan carries an aligned target's partition value and the write-back returns it

The scan (`POST /v1/pipelines/{name}/scan`) of a row-trigger pipeline whose target is aligned SHALL return, in
every row, a member `__partition_v` holding the source row's alignment-column value (`request_time` for
`dial_usage_log`), whatever the declaration's inputs. The pipeline write-back (`POST /v1/pipelines/{name}/rows`)
for such a pipeline SHALL require `__partition_v`, non-null, on each row and SHALL write it into the target's
alignment column. A row without it, or with it `null`, SHALL be refused with HTTP 422 `validation_error` naming
`__partition_v`, before any row of the request is written. A scan or write-back of a pipeline whose target is not
aligned SHALL neither return nor accept the member: the write-back refuses it as a system column with HTTP 422
`write_column_not_allowed`. The scan and the write-back SHALL decide whether the member applies by one shared rule,
so the write-back requires it exactly when the scan returns it.

On the write-back the member SHALL be the only source of the alignment column's value. A row naming the alignment
column itself SHALL be refused with HTTP 422 `write_column_not_allowed` unless the declaration binds an output to
it; a row naming both a bound alignment column and `__partition_v` SHALL be refused with HTTP 422
`validation_error` when the two values differ, rather than one silently overwriting the other.

The write-back's inserts SHALL NOT be limited by ClickHouse's `max_partitions_per_insert_block`: a batch spanning
more than 100 day partitions of an aligned target is written, not refused.

The value travels with the row because the write-back is the only writer of an LLM pipeline's rows and ADAS
holds no per-caller state between the two calls: looking it up at write-back would scan the source's identity
column across every partition per batch, which is the cost the aligned layout removes. The Enrichment Runner
carries it from the scanned row to the write row unchanged; a wrong value only leaves that row unmatched under
the tuple join, and attaches it to no other source row.

The runner contract version this build serves SHALL be `6`. A runner that does not echo the value, cannot
decode the `dial_app_route` transform preset (see [enrichment-registry](../enrichment-registry/spec.md), "Both
projections carry the transform's route"), or does not read `advanced.input_token_budget` (see
[enrichment-registry](../enrichment-registry/spec.md), "A group pipeline may declare its own input token budget")
is refused by the exact-match contract check, rather than having its rows or its pipelines refused one by one, or
a pipeline's declared budget silently ignored.

#### Scenario: The scan returns the partition value of an aligned target

- **GIVEN** a row-trigger `llm` pipeline targeting an aligned enrichment of `dial_usage_log`
- **WHEN** it is scanned
- **THEN** every returned row carries `__partition_v` equal to its source row's `request_time`

#### Scenario: The echoed value lands in the alignment column

- **WHEN** the runner writes back a row carrying the `__partition_v` it was scanned with
- **THEN** the row is written with that `request_time` and a query projecting the enrichment over its source
  row reads the written labels

#### Scenario: A write-back row without the value is refused

- **WHEN** a write-back row for an aligned target omits `__partition_v`, or carries it as `null`
- **THEN** the response is HTTP 422 `validation_error` naming `__partition_v`, and nothing is written

#### Scenario: A write-back row carrying the alignment column instead of the value is refused

- **GIVEN** a row-trigger `llm` pipeline targeting an aligned enrichment, with no output bound to `request_time`
- **WHEN** a write-back row carries `request_time` but no `__partition_v`
- **THEN** the response is HTTP 422 `write_column_not_allowed` and nothing is written

#### Scenario: An unaligned target is unaffected

- **GIVEN** a row-trigger `llm` pipeline whose target is not aligned
- **WHEN** it is scanned and written back
- **THEN** the scan rows carry no `__partition_v`, the write-back does not require it, and a row carrying it is
  refused with HTTP 422 `write_column_not_allowed`

#### Scenario: The served contract version is 7

- **WHEN** the runner calls any pipeline endpoint
- **THEN** the response carries `Adas-Contract-Version: 7`, and a runner declaring `6` is refused

### Requirement: The governed data plane requires an exact runner contract version

Every request to a runner-only data-plane endpoint SHALL carry the `Adas-Contract-Version` header naming
the contract the caller was built against, and SHALL be refused unless it equals the version this service
serves. The endpoints SHALL be `POST /v1/pipelines/{name}/scan`, `POST /v1/pipelines/{name}/group-rows`
and `POST /v1/pipelines/{name}/rows`, plus `POST /v1/pipelines/{name}/materialize` (see the
`class1-enrichment` capability) and `POST /v1/pipelines/{name}/trial-input` (see "A runner-only trial-input
read serves the runner's dry run"). The version this service serves SHALL be `7`, raised from `6` when
`trial-input` joined the set: a runner built for `6` does not know the endpoint exists, and one built for
`7` must not call an ADAS that lacks it.

The version SHALL be a single opaque integer compared for **equality**, not a range and not an ordering:
the service SHALL NOT attempt to serve a caller that declares any other value. It SHALL be a property of
the build, not configuration — a deployment that could set it to the wrong value could disable every
pipeline at once, silently, which is the failure this requirement exists to remove. It SHALL be raised
whenever a breaking change lands in the compiled projection or in any of these five endpoints' request or
response shapes.

Refusals SHALL use the shared error contract: a missing header SHALL be HTTP 409
`runner_contract_required` and a header that does not match SHALL be HTTP 409
`runner_contract_mismatch`, whose message SHALL name both the version served and the version declared so
the reader learns which side is behind. The refusal SHALL be decided **before** any pipeline lookup, the
per-call grant check and any query, so a skewed caller cannot consume a read budget or reach ClickHouse.
It SHALL be decided **after** authentication: an unauthenticated caller SHALL still be answered `401`,
because "who is calling" is settled before "which build is calling".

This requirement exists because the transport survives a version skew that the definition does not. A
declaration-shape change can leave these endpoints unchanged, so a runner built against the older
shape keeps calling them successfully while holding a declaration it cannot execute — and the scan
cursor, which advances at enqueue and is never re-examined, runs through the backlog producing nothing.
The requirement is therefore stated over the *transport* rather than over any one missing field: each
shape change removes a different field, and the endpoints stay just as willing.

Refusing the call is what this service can guarantee on its own: the cursor is untouched and nothing is
written, whatever the caller does next, so the skew costs a stall rather than a loss. It is deliberately
*not* the whole of "equivalent to a runner scaled to zero" — a chunk already queued on the caller's side
reaches its model call without asking this service anything, so suppressing the spend is the runner half's
responsibility and is specified there. This requirement covers what is decidable from the request alone.

The operator surfaces `POST /v1/pipelines/{name}/preview` and `/refresh`, pipeline registration, and both
read projections SHALL NOT require the header, so a management client, the OpenAPI console and an
operator's own reads are unaffected by it.

#### Scenario: A scan without the contract header is refused before any lookup

- **WHEN** `POST /v1/pipelines/{name}/scan` is called with no `Adas-Contract-Version` header
- **THEN** the response is HTTP 409 `runner_contract_required`, no scan is executed, and the pipeline's
  cursor position and the caller's read budget are untouched

#### Scenario: A caller declaring a different contract version is refused on every data-plane endpoint

- **WHEN** `scan`, `group-rows`, `materialize`, `rows` or `trial-input` is called with an `Adas-Contract-Version` that
  differs from the version served
- **THEN** each response is HTTP 409 `runner_contract_mismatch` with a message naming both versions, and
  no row is read, computed or written

#### Scenario: A matching caller is served exactly as before

- **WHEN** the same requests carry the matching `Adas-Contract-Version`
- **THEN** each endpoint behaves exactly as specified elsewhere in this capability, the header having no
  effect beyond admission

#### Scenario: An operator surface does not require the header

- **WHEN** `POST /v1/pipelines/{name}/preview` or `/refresh` is called with no `Adas-Contract-Version`
  header
- **THEN** the request is served normally, because these are operator surfaces rather than the runner's
