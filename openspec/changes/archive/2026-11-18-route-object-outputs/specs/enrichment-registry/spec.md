## MODIFIED Requirements

### Requirement: The response schema is composed from the target and the transform

The system SHALL compose an `llm` declaration's JSON response schema from the pipeline's target
enrichment and its `transform.outputs`, at pipeline registration, patch and read. Composition happens
on the pipeline because the pipeline is the only object there is: it names both the target and the
transform, and binding the two is definitionally its job. The table SHALL stay ignorant of transforms.

**A refusal of composition SHALL be raised by the static gate**, on every gated request of "Static
governance gate on arming and execution", as HTTP 422 `pipeline_validation_failed` carrying the
composer's message. A write that leaves the pipeline disabled stores the declaration without complaint,
as it does for every other gate check. The read paths compose the same schema with the same rule, but
cannot refuse: a target whose columns drifted under an armed declaration must not fail a listing for
every other pipeline in it, so a read that cannot compose serves the entry with no `response_schema`
and logs a warning for an armed pipeline. The gate is what keeps that state from being armed in the
first place. Without it, a pipeline whose schema never composed would be armed and its model called
unconstrained.

Composition SHALL be driven by the `outputs` **keys**, with each key's structure looked up on the
target column of the same name:

| Schema element | Source |
|---|---|
| property name | the `outputs` key, matched against the column's **exposed `name` only** |
| `type` | the target column's declared type, through the mapping below |
| `enum` values **and order** | the column's `enum_values` verbatim, else the output's `values` |
| `description` | the output's prose, **else the target column's `description`**, else omitted |
| property order and `required` order | the `outputs` declaration order |
| `required` | **every** declared output; for `dial_app_route`, every output bound to a non-nullable column |
| `additionalProperties` | always `false` |

The type mapping SHALL be: `string` → `{"type": "string"}`; `enum` → `{"type": "string", "enum":
[...]}`; `integer` and `long` → `{"type": "integer"}`; `double` and `decimal` → `{"type": "number"}`;
`boolean` → `{"type": "boolean"}`; `timestamp` → `{"type": "string"}` with **no `format`** (strict
schema mode rejects one); `array` → `{"type": "array", "items": <element_type mapped>}`.

For a `dial_app_route` transform the mapping SHALL also cover the two document types: `object` →
`{"type": "object"}`, open, with no `properties` and no `additionalProperties`, so any JSON object
validates; and `map` → `{"type": "object", "additionalProperties": {"type": "string"}}`, which is exactly
what a `Map(String, String)` column can store. A route is not held to the schema by `response_format`. The
runner validates the object it assembled from the outputs' sources, and an open object is valid JSON Schema.
The application's response decides the document's keys, so a key the application leaves out is simply
absent from the stored document rather than written as a zero or an empty string.

Neither column type can be nullable (ClickHouse has no `Nullable(JSON)` or `Nullable(Map)`), so under the
route nullability rule below an output bound to either is always **required** and never accepts `null`. An
empty document is written as `{}`. A value that evaluates to nothing is left out of the assembled object and
fails validation, so the unit is dead-lettered at `validate` instead of being written as a row the column
cannot store. A `source` that may filter every member away therefore has to yield `{}` itself, for example
`$merge([{}, $sift(intent_counts, function($v){ $v != 0 })])`. `$sift` alone evaluates to nothing when no
member passes.

For a `chat_completion` transform, an output bound to an `object` or `map` column SHALL be rejected with
HTTP 422 `pipeline_validation_failed`. The message SHALL name the output, the column, its type and the
`chat_completion` preset, and SHALL say that a route transform or a scalar column can carry it. Strict
mode requires every object in the schema to declare its properties and close `additionalProperties`, and
neither column type declares an inner shape to compose them from. A column of any other type with no
mapping above SHALL be rejected the same way under either preset.

The `description` default SHALL be applied **at composition time and SHALL NOT be written into the
stored declaration**, so that the column remains the single authority for a fact the author did not
supply. It SHALL therefore appear in the composed `response_schema` only, and SHALL NOT appear in the
authored declaration under either projection: a derived default echoed onto the authored surface would
be sent back by the next read-modify-write `PATCH`, become authored, and silently stop tracking the
column. Where an output declares no prose **and** its target column has no `description`, the property
SHALL be composed with no `description` at all rather than refused — a description-less property is
valid under strict mode and is already what a legacy declaration with a stored schema produces — and
`POST /v1/pipelines/{name}/preview` SHALL report it as a note. The default SHALL apply to `llm`
outputs only: a `sql` output's string is an expression rather than prose, cannot be defaulted from any
column, and SHALL keep being required and non-blank.

Composition SHALL apply **only** where the declaration stores no `response_schema` of its own. A
declaration carrying a stored schema serves it verbatim, and a declaration carrying neither a stored
schema nor `outputs` — the shape a folded old-shape `llm` transform can have — composes nothing and
calls the model unconstrained, exactly as it did before the fold.

**Property order SHALL be the author's declared order, and SHALL be preserved through storage.** It
is not cosmetic: under strict schema enforcement the model generates properties in schema order, so a
field whose prose instructs it to derive itself from its siblings must be declarable *after* them.
Order SHALL NOT be taken from the target's column order, which is tag-group-then-alphabetical and
would make generation order a function of column tags. A storage form that normalizes key order SHALL
NOT be used for either the composed schema or the stored `outputs`.

For a `chat_completion` transform, `required` SHALL cover every declared output and SHALL NOT be derived
from column nullability. Strict mode requires every property to be required, so there is no optional-output
shape to express — and the two facts differ anyway: an enrichment column is nullable because a row may not
be labelled yet, while a schema field is required because the model must answer. An output that may
legitimately be absent SHALL be expressed as a sentinel value in `values` plus a `jsonata` mapping it to
NULL.

For a `dial_app_route` transform, nullability SHALL follow the bound column. An output bound to a nullable
column SHALL be left out of `required`, and its property SHALL also accept `null`: its `type` becomes the
pair of its type and `"null"`, and a closed `enum` domain gains `null`. An output bound to a non-nullable
column stays required and non-null. The strict-mode argument does not apply here, because no
`response_format` is sent and nothing holds the application to the schema. The runner validates only the
object it assembled from the outputs' sources, and the column is what says whether NULL can be stored. A
field the application omits is therefore written as NULL where the column allows it, and refused where it
does not. It follows that when every output is bound to a nullable column, a `200` answer of `{}` validates
and is written as an all-NULL row recorded as done.

Composing the enum domain **from the column** SHALL be preferred to declaring it on the output,
and the consequence SHALL be accepted rather than worked around: enum **order** in the prompt is then
whatever order the column's `enum_values` declare, which is chosen for dashboard sorting. Membership
is the half that carries correctness — a ClickHouse enum column refuses an out-of-domain value at
insert, so composing the domain from the column makes "the model returned a value the column cannot
store" unrepresentable — and an order override on the output would rebuild exactly the
two-copies-of-one-domain drift this composition removes.

An output declaring `values` for a column that already declares `enum_values` SHALL be rejected with
HTTP 422 naming both: a field has exactly one authority for its domain, chosen by which of the two
can hold it. `values` SHALL be understood to buy **schema** enforcement only — a `string` column
accepts anything at insert — and the rejection message for an out-of-domain value SHALL NOT imply
otherwise.

Because output names are matched to column names, an output matching no column SHALL be rejected with
HTTP 422 naming the output and the target. A transform belongs to exactly one pipeline, which writes
exactly one target.

**Residual, stated rather than solved.** After composition the target's schema is a live input to both
the prompt and the write mapping, and a column dropped and re-added changes the composed schema
without any pipeline being patched, so `pipeline_generation` — the per-row provenance stamp — does not
move either. Rows labelled either side of such a change therefore carry no per-row record of which
schema produced them.

#### Scenario: A schema is composed from the target's columns

- **WHEN** a pipeline is registered naming a target whose columns include an `enum` column and a
  `decimal` column, and a `transform` declaring an output for each
- **THEN** the composed schema types the first as `{"type": "string", "enum": [...]}` carrying that
  column's `enum_values` in the column's own order, and the second as `{"type": "number"}`

#### Scenario: A declared prose wins over the column description

- **WHEN** an output declares a non-blank `prose` and its target column also carries a `description`
- **THEN** the composed property's `description` is the declared prose, and the column's `description`
  is not consulted

#### Scenario: Neither prose nor column description composes no description

- **WHEN** an output declares no prose and its target column carries no `description`
- **THEN** the composed property carries no `description` member at all, the schema is still valid, and
  the pipeline is neither refused at write nor at enable

#### Scenario: The defaulted description is absent from the authored surface

- **WHEN** a pipeline whose output declares no prose is read with `view=source` and again with
  `view=compiled`
- **THEN** neither projection's authored `transform.outputs` carries a prose for that output, only the
  derived `response_schema` carries the column's `description`, and sending the `view=source` body back
  as a `PATCH` changes nothing

#### Scenario: A stored response schema is unaffected by the default

- **WHEN** a declaration stores its own `response_schema` and one of its outputs declares no prose
- **THEN** the stored schema is served verbatim, no composition runs, and no column `description` is
  read

#### Scenario: Property order follows the outputs declaration

- **WHEN** a `transform` declares outputs in the order `data_exposure`, `prohibited_request`,
  `risk_level`
- **THEN** the composed schema's `properties` and `required` carry that order, and it survives a
  round trip through storage and a pipeline read

#### Scenario: Every declared output is required

- **WHEN** a `chat_completion` transform declares an output bound to a nullable target column
- **THEN** the composed schema lists that property in `required`, and `additionalProperties` is
  `false`

#### Scenario: A domain the column cannot carry is declared on the output

- **WHEN** an output declares `values` for a target column declared plain `string`
- **THEN** the composed schema carries those values as the property's `enum`, and the domain is
  enforced at the model boundary rather than at insert

#### Scenario: A chat completion output bound to a document column is rejected

- **WHEN** a `chat_completion` transform declares an output resolving to a target column of type
  `object` or `map`, and the pipeline is registered with `enabled: true`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` whose message names the
  output, the column, its type and the `chat_completion` preset

#### Scenario: A schema that cannot compose is refused when armed, not when drafted

- **GIVEN** an `llm` declaration whose schema cannot be composed against its target
- **WHEN** it is written with `enabled: false`, and then patched to `enabled: true`
- **THEN** the first write stores the draft, and the second is rejected with HTTP 422
  `pipeline_validation_failed` carrying the composer's message, leaving the pipeline disabled

#### Scenario: A route output bound to an object column composes an open object

- **GIVEN** a `dial_app_route` transform whose output `intent_counts` binds an `object` column of the same
  name
- **WHEN** the pipeline is registered and read with `view=compiled`
- **THEN** registration succeeds, the composed property is `{"type": "object"}` with no `properties` member,
  and `intent_counts` is listed in `required`

#### Scenario: A route output bound to a map column composes a string-valued object

- **GIVEN** a `dial_app_route` transform whose output `labels` binds a `map` column of the same name
- **WHEN** its schema is composed
- **THEN** the property is `{"type": "object", "additionalProperties": {"type": "string"}}` and `labels`
  is listed in `required`

#### Scenario: A route writes a sparse object as the application returned it

- **GIVEN** an armed `dial_app_route` pipeline whose output `intent_counts` binds an `object` column, with
  `source` `$merge([{}, $sift(intent_counts, function($v){ $v != 0 })])`
- **WHEN** the route answers `{"intent_counts": {"fix": 2, "plan": 0, "create": 1}}` and the row is written
- **THEN** the stored document holds the members `fix` and `create` only, and reading
  `json_extract_int(intent_counts, 'plan')` yields `0`, the extracting type's default for an absent path

#### Scenario: An all-zero object is written as an empty document

- **GIVEN** the same pipeline
- **WHEN** the route answers `{"intent_counts": {"fix": 0, "plan": 0}}`
- **THEN** the row is written with `intent_counts` stored as `{}`

#### Scenario: An object output that evaluates to nothing is dead-lettered

- **GIVEN** a `dial_app_route` output `intent_counts` bound to an `object` column, with `source`
  `$sift(intent_counts, function($v){ $v != 0 })`
- **WHEN** the route answers with every member of `intent_counts` equal to `0`
- **THEN** the assembled object lacks the required `intent_counts`, and the unit is dead-lettered at stage
  `validate` with the raw route body, and no row is written

#### Scenario: A timestamp property carries no format

- **WHEN** an output resolves to a `timestamp` target column
- **THEN** the composed property is `{"type": "string"}` with no `format` member

#### Scenario: A declaration with no outputs composes nothing

- **WHEN** an `llm` declaration folded from an old-shape evaluator version carries `output_vars` and
  no `response_schema`
- **THEN** no schema is composed, the entry is served without one, and the model is called
  unconstrained as it was before the fold

#### Scenario: A route schema leaves a nullable column's output optional

- **GIVEN** a `dial_app_route` transform whose outputs bind a nullable `decimal` column `cost` and a
  non-nullable `string` column `title`
- **WHEN** its schema is composed
- **THEN** `required` lists `title` only, `cost` has `type` `["number", "null"]`, and `title` has `type`
  `"string"`
