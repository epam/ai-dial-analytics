## MODIFIED Requirements

### Requirement: The dry-run preview answers in one shape for both kinds

`POST /v1/pipelines/{name}/preview` SHALL answer with one response shape for every kind it serves. That
shape SHALL carry only what a preview alone can know: the sample, the typed columns of that sample,
the declaration as a query a caller can run through the query API where there is one, the number of
rows an `aggregate` target will hold, and coded notes about it.

A preview SHALL NOT echo any member of the declaration that `GET /v1/pipelines/{name}` serves, whether
authored or derived:

- group keys and measures;
- the transform and its type;
- the response schema and its source;
- the output mapping and the input bindings.

The reasoning:

- An author reaches preview from the pipeline they just wrote, so a client already holds the declaration
  and renders the preview beside it.
- The gate derives the derived members on every declaration write, so they are already on the read:
  `GET /v1/pipelines/{name}` serves an `aggregate` pipeline's expanded `group_by` and resolved measure
  columns, and `GET /v1/pipelines/{name}?view=compiled` serves an `enrich` pipeline's resolved
  `response_schema` and output mapping.
- Every echoed member is one more place where two answers to the same question can disagree.
- It is payload the reader has to wade through to reach the sample.

The response SHALL carry these members:

- **`rows`**: the sample rows. Always present, and `[]` when no row was sampled. Each row is keyed by
  column name.
- **`columns`**: one entry per key of a row, in row order. Always present, including when `rows` is
  empty, so a client can render the sample's header either way. A helper expression the sampling
  statement projects only for the preview's own checks (an ordering value, a window count) SHALL NOT
  appear in `columns` or `rows`. Each entry carries:
  - `name`.
  - `role`: `key` or `measure` on an `aggregate`; `grain_key` and `output` on a Class-1 `sql` pipeline;
    `grain_key` and `input` on a row-grain `llm` one; `input` alone on a `group` one, whose group is
    named once by `group_key` rather than repeated on every member.
  - `type`: the `QueryFieldType` code of the value as sampled.
  - `target_type`: the type of the target column the value is written to. Present for `key`,
    `measure` and `output`; absent for `grain_key` and `input`.
  - `max_bytes`: the largest UTF-8 size, in bytes, of this column's non-null values across the sampled
    rows, measured **before** any value is capped. A `string` value counts as its text and an `object`,
    `array` or `map` value as its compact JSON serialization, before the response's own JSON string escaping. Present
    for `string`, `object`, `array` and `map` columns that sampled at least one non-null value, and
    absent otherwise (including for `uuid` and `enum`, whose width is fixed or small). On an `input`
    column it is the author's hint at what one row or member costs the model.
- **`total_rows`** (`aggregate` only): the number of groups the declaration produces over the whole
  input, which is the number of rows the target holds once the pipeline has caught up. It SHALL be
  counted by the sampling statement that produced `rows`, before its `LIMIT`, and not by a second read.
  The portable `statement` does not reproduce it. It is
  `0`, present rather than absent, when the input yields no group. The preview SHALL carry no row-count
  or "more rows" member on the `enrich` kind, where a count would only measure the source table.
  Preview has no pagination, so a "more rows exist" flag would give a client nothing to act on.
- **`statement`**: the declaration as one `SELECT` in the **ADAS SQL dialect**, the subset
  `POST /v1/queries/execute-sql` accepts, with its values inlined. A caller SHALL be able to send it
  unchanged to that endpoint, or open it in a query builder, and have it execute. It SHALL be written
  against the catalog: it reads the pipeline's input entity by its catalog name, names that entity's
  exposed fields (an enrichment column by its exposed name, with no join), and names every output
  column by the target column it is written to. It SHALL NOT carry:
  - a physical table or column name that the entity does not expose, or a join;
  - an engine-only function, an internal alias (such as a positional `__c<i>`), or a `SETTINGS` or
    `FORMAT` clause;
  - a helper expression the sampling statement projects only for the preview's own checks.

  The statement ADAS executes to produce `rows` is a different, physical statement. It is not returned,
  and it is logged at `DEBUG` on the SQL log as a refresh statement is. Each kind states which parts of
  the sample the portable statement reproduces and where its rows can differ from `rows`.
  - Present for an `aggregate` pipeline and a Class-1 `sql` one, unless the declaration cannot be
    expressed in the ADAS dialect (see `statement_not_portable`).
  - Absent for an `llm` pipeline, whose sample is read through the governed scan or group assembly and
    has no single statement.
- **`query`**: the same query as a structured-query DSL document, accepted as is by
  `POST /v1/queries/execute`. Present exactly when `statement` is. It SHALL be the document the SQL front
  end reads `statement` as, so `POST /v1/queries/translate-sql` on `statement` answers exactly `query`,
  with the defaults the front end spells out (`final`, the null ordering of a sort) spelled out alike.
  `query` and `statement` are renderings of the declaration for running elsewhere, not echoes of its
  members, so carrying them does not conflict with the rule above.
- Running `statement` or `query` SHALL go through the governed query path and grant nothing beyond what
  the caller already holds: table grants, sensitive-column clearance, row policies, the query budget and
  the row limits all apply as they do to any other query. A preview SHALL render both against the
  caller's own column visibility, so neither names a column the caller cannot see.
- **`group_key`**: the group the sample describes, as a string, for a `group` pipeline only, whether
  the caller named it or the preview chose it.
- **`notes`**: always present, and `[]` on an unremarkable preview. Each note carries a stable `code`, a
  `severity` (`info` or `warning`), a `subject` naming the column, output or enrichment it is about
  (present exactly for the codes whose meaning below names one), and a prose `message`. A client SHALL
  be able to switch on `code`; `message` is for people. A code that names a subject is emitted once per
  subject.

**Every value in `rows` SHALL be capped at 2048 bytes.** A body column runs to tens of kilobytes per
row (a usage-log body is commonly tens of KB), so five member rows of two body columns make
a response of megabytes that a client cannot usefully render. The cap SHALL apply on every kind, because
an aggregate's `group_uniq_array` measure or a Class-1 extraction can be just as wide.

- A `string` value over the cap SHALL be cut at a Unicode code point boundary (never inside a UTF-8
  sequence or a surrogate pair; a grapheme cluster may be split) and end in `…`, so that the cell,
  `…` included, is at most 2048 bytes.
- An `object`, `array` or `map` value whose JSON serialization exceeds the cap SHALL be replaced by that
  serialization, cut the same way. The cell then holds a string, and the column's `type` still names
  the value's real type.
- Every value at or under the cap SHALL be returned unchanged.

The cap shortens what is **returned**, never what is **read**. Every check, note and count (including
`max_bytes`, `value_out_of_target_range` and `total_rows`) SHALL be computed from the full values. The
cap is a constant, like the `enrich` sample's row cap, and not a configured knob: raising it would
rebuild the response this cap exists to avoid.

A note SHALL never stand in for a refusal. A declaration that cannot be armed is refused with an error,
and a note describes a declaration that passed every check.

The note codes SHALL be the closed set below. A new code SHALL be added only by amending this list.

| `code` | `severity` | Kinds | Meaning |
|---|---|---|---|
| `no_matching_rows` | warning | all | The sample is empty because no input row matches the declaration's `filter` (and `from`/`to`, where given), or, for a named `group_key`, because that group has no member that does |
| `held_by_enrichment` | warning | all | An enrichment the declaration reads withholds every matching input row (it has no freshness bound yet, or, on a clamped `llm` read, every matching row lies above its bound), so an enabled run would write nothing yet; `subject` names it |
| `enrichment_behind` | info | `aggregate`, Class-1, row-grain `llm` | Matching input rows lie above an enrichment's freshness bound. On `aggregate` and Class-1, which sample unclamped, those rows read the enrichment's columns as unmatched (NULL, or the type default for a non-nullable column); on row-grain `llm`, whose read is clamped, they are left out of the sample. The message gives the gap; `subject` names the enrichment |
| `target_type_narrowing` | warning | `aggregate`, Class-1 | A column's sampled `type` is wider than its `target_type`, so a value outside the target's range would be coerced silently on insert; `subject` names the column |
| `value_out_of_target_range` | warning | `aggregate`, Class-1 | A sampled value cannot be held by the target column's physical type, so the insert would wrap or clamp it silently (`integer`, `long`, `date`, `timestamp`) or the refresh would fail with a decimal overflow (`decimal`); `subject` names the column |
| `no_group_found` | warning | `group` | No `group_key` was named and no matching group with a non-empty key exists to choose |
| `group_cut_by_fetch_limit` | warning | `group` | The sampled group has more members than the runner's group fetch would deliver under its row cap, beyond any `member_select` narrowing the declaration asks for; the message names the cap and both counts |
| `output_without_description` | info | `llm` | An output composed no `description`; `subject` names the output |
| `unconstrained_response` | warning | `llm` | The declaration composes no response schema, so the model's answer is held to none |
| `values_truncated` | info | all | One or more of a column's sampled values exceeded the value cap and were shortened in `rows`; the message gives how many and the cap; `subject` names the column |
| `statement_not_portable` | info | `aggregate`, Class-1 | The declaration cannot be expressed in the ADAS SQL dialect or the structured-query DSL, so `statement` and `query` are absent; the sample is unaffected, and the message gives the reason; `subject` names the column at fault where there is one |

**Samples SHALL be newest first.** Ordering by key or by oldest input put test traffic and months-old
rows in front of the author. Most of a
sample could be such groups, with identical rows among them. The newest input is what an author who has just changed a declaration needs to judge,
and it is what the pipeline will process once enabled. Each kind states what "newest" orders by. On a
`group` pipeline "newest" chooses the group; its members keep the order the group assembly delivers
them in, because that is the order the model receives.

#### Scenario: Both kinds answer in the same shape

- **WHEN** `POST /v1/pipelines/{name}/preview` succeeds for an `aggregate` pipeline, and for an
  `enrich` one
- **THEN** both responses carry `columns`, `rows` and `notes` at the top level, and only the
  `aggregate` one carries `total_rows`
- **AND** neither carries `group_by`, `measures`, `transform`, `response_schema`,
  `response_schema_source`, `inputs`, `outputs`, or an `enrich` member

#### Scenario: Columns describe an empty sample

- **WHEN** a preview samples no rows
- **THEN** `rows` is `[]` and `columns` still lists each column the sample would have carried, with
  its role and types
- **AND** `notes` carries a code saying why the sample is empty

#### Scenario: A group sample its members could not be read back for is not blamed on the filter

- **GIVEN** a `group` sample whose member selection chose members, none of whose bound input columns the
  member read then found by their lookup key (the source changed between the two reads)
- **WHEN** it is previewed
- **THEN** `rows` is `[]` and `notes` carries no `no_matching_rows`, since no code in the closed set
  describes the condition and that one would send the author to a filter that is not the cause

#### Scenario: An aggregate preview reports how many rows the target will hold

- **GIVEN** an `aggregate` pipeline whose declaration produces more groups over its input than the
  preview's row cap
- **WHEN** it is previewed
- **THEN** `rows` holds exactly the cap and `total_rows` is the number of groups over the whole input
- **AND** no statement other than the sample's own was issued to count them

#### Scenario: An empty aggregate sample reports zero rows

- **WHEN** an `aggregate` preview's input yields no group
- **THEN** `total_rows` is present and `0`

#### Scenario: The preview statement runs through the query API

- **WHEN** a preview of an `aggregate` or Class-1 pipeline returns a sample, and the caller holds
  `TABLE_READ` on the pipeline's input
- **THEN** `POST /v1/queries/execute-sql` with `statement` as its `sql` answers HTTP 200, with one column
  per entry of `columns`, under the same names
- **AND** `POST /v1/queries/execute` with `query` as its body answers the same columns

#### Scenario: The preview statement carries no physical detail

- **WHEN** a preview of an `aggregate` pipeline that reads an enrichment column returns a sample
- **THEN** `statement` reads the input entity by its catalog name and the enrichment column by its exposed
  name, with no `JOIN`
- **AND** it carries no `SETTINGS` clause, no alias beginning with `__`, and no helper column
- **AND** `columns` and `rows` carry none of the helper columns the executed sampling statement projects

#### Scenario: Statement and query describe the same query

- **WHEN** a preview returns `statement` and `query`
- **THEN** `POST /v1/queries/translate-sql` on `statement` answers a `query` equal to the preview's

#### Scenario: Copying the statement grants no access

- **GIVEN** a preview's `statement`, and a caller who holds `TABLE_GET` but no `TABLE_READ` on the pipeline's
  input
- **WHEN** that caller sends the statement to `POST /v1/queries/execute-sql`
- **THEN** the request is refused as any query over a table the caller may not read is: HTTP 400
  `bad_request` reporting an unknown relation

#### Scenario: A declaration the dialect cannot express previews without a statement

- **GIVEN** an `aggregate` pipeline whose declaration the ADAS SQL dialect cannot express
- **WHEN** it is previewed
- **THEN** the preview succeeds with its sample, `columns` and `total_rows`
- **AND** `statement` and `query` are absent, and `notes` carries `statement_not_portable` whose message
  gives the reason

#### Scenario: A large value is capped and reported

- **GIVEN** a pipeline whose sampled rows carry a body column with a 90 KB value
- **WHEN** it is previewed
- **THEN** that cell holds at most 2048 bytes: the value's leading bytes, cut at a code point boundary,
  followed by `…`
- **AND** the column's `max_bytes` is the full value's size
- **AND** `notes` carries `values_truncated` whose `subject` names the column
- **AND** a column whose values all fit carries no such note and holds its values unchanged

#### Scenario: Notes are coded

- **WHEN** a preview carries a note
- **THEN** the note carries a `code` from the closed set, a `severity` of `info` or `warning`, and a
  `message`
- **AND** a `subject` wherever the code names a column or an enrichment

