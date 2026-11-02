## Purpose

Make an authored analytics dashboard — widgets on a 12-column grid, each running one query and drawing its
result, under one dashboard-wide time intent and period comparison — a durable, addressable object that a
client stores here rather than in its own memory. A dashboard stores authored **intent** only: its layout,
its widgets' query references or inline bodies, and how each widget renders. It stores nothing a run
produces; every widget is executed by the client through the query surface under the viewer's own
credential, so every read rule applies per viewer exactly as it does to any other query.

## ADDED Requirements

### Requirement: Dashboard storage model

The system SHALL persist a dashboard in the metadata database (PostgreSQL), independently of the queried
data store. A dashboard SHALL consist of:

- `id` — server-assigned, opaque, prefixed `dash_`, and not guessable from any other dashboard's id;
- `name` — required, non-blank after trimming, and stored trimmed as a saved query's is;
- `description` — optional free text;
- `visibility` — `private` or `public`;
- `owner_id` — server-assigned from the calling identity; `null` in `none` mode, and on a `public` dashboard
  created by a caller whose principal does not resolve (see **Dashboard ownership**);
- `owner_email` — the author's email at creation, server-assigned, display only and often absent, with
  exactly the semantics the saved-queries capability gives its own `owner_email`;
- `time` — the dashboard's time intent, or `null`, in the saved-query `time` model and validated by the
  same rule (see [saved-queries](../saved-queries/spec.md), "Time intent validation");
- `compare` — the period comparison: `off`, `previous-period`, `previous-month` or `previous-year`; absent
  means off. These tokens are hyphenated, unlike the service's snake_case tokens, because they are the
  wire form the dashboard client already exchanges;
- `widgets` — a required, ordered array of widgets (see **Widget model**), possibly empty;
- `generation` — a counter incremented on every mutation, and the concurrency token of **Generation-checked
  replace**;
- `created_at` / `updated_at` — server-assigned timestamps.

The system SHALL NOT persist, as part of a dashboard, any query result, run outcome, or cached value, nor a
relative time period resolved into absolute instants.

Deleting a catalog table or a saved query SHALL NOT delete or modify any dashboard.

The server-assigned members — `id`, `owner_id`, `owner_email`, `created_at`, `updated_at` — and
`permissions` SHALL be rejected with `422` and machine code `validation_error` when they appear in a create
**or** a replace body, rather than silently ignored. `generation` SHALL be rejected the same way in a create
body and SHALL be required in a replace body (see **Generation-checked replace**). A client replacing a
dashboard therefore sends the document it read with those members removed and `generation` kept.

An explicit `compare` of `off` SHALL be stored and returned as sent; an absent `compare` SHALL stay absent.
The two mean the same comparison.

#### Scenario: A created dashboard is returned with its server-assigned fields

- **WHEN** an authenticated caller in `oidc` mode creates a dashboard with a name and two widgets
- **THEN** the response is `201` and carries a server-assigned `id` beginning with `dash_`, `owner_id`,
  `generation`, `created_at`, and `updated_at`, and the widgets are returned in the order they were sent

#### Scenario: Server-assigned fields are rejected in a create body

- **WHEN** a create request body contains `id`, `owner_id`, `owner_email`, `generation`, `created_at`,
  `updated_at` or `permissions`
- **THEN** the response is `422` with machine code `validation_error` and nothing is persisted

#### Scenario: Server-assigned fields are rejected in a replace body

- **WHEN** a replace request body carries the current `generation` and also `id`, `owner_id`,
  `owner_email`, `created_at`, `updated_at` or `permissions`
- **THEN** the response is `422` with machine code `validation_error` and the dashboard is unchanged

#### Scenario: An explicit off comparison round-trips

- **WHEN** a dashboard is created with `compare` of `off`
- **THEN** reading it back returns `compare` of `off`

#### Scenario: A relative dashboard time is stored as intent

- **WHEN** a dashboard is created with `time` of `{"mode":"relative","period":"last_7d"}` and `compare` of
  `previous-period`
- **THEN** reading it back returns the same `time` and `compare`, and no absolute instants are stored or
  returned

#### Scenario: An absent comparison stays absent

- **WHEN** a dashboard is created without `compare`
- **THEN** reading it back returns no `compare` member

#### Scenario: Deleting a table an inline body reads leaves the dashboard unchanged

- **GIVEN** a dashboard whose widget's inline body reads a table
- **WHEN** that table is deleted
- **THEN** the dashboard's `generation`, `updated_at` and widgets are unchanged

### Requirement: Widget model

A widget SHALL consist of:

- `id` — client-assigned, non-blank, at most 64 characters, unique within its dashboard;
- exactly one query source: `saved_query_id` (a reference to a saved query) or `inline_query` (a query the
  widget holds itself; see **Inline query**);
- `title`, `description` — optional author-written text, returned to every viewer;
- `row_limit` — required, a whole number from 1 to 1000;
- `view` — optional: `query`, `table`, `single_value`, `time_series`, `share` or `heatmap`; absent means the
  query's own view;
- `value_column` — optional, the aggregate a single-value widget shows;
- `change_direction` — optional: `higher_is_better` or `lower_is_better`;
- `layout` — required `{x, y, w, h}`, integers on a 12-column grid;
- `title`, `description`, `value_column` and `saved_query_id` — each at most 1000 characters.

The system SHALL store `view`, `value_column` and `change_direction` as given and SHALL NOT check them
against the query the widget runs: whether a column exists or suits a view is decided when the client runs
and renders the widget.

#### Scenario: A widget round-trips unchanged

- **WHEN** a dashboard is created with a widget carrying every member
- **THEN** reading it back returns that widget with every member unchanged

#### Scenario: Optional widget members are omitted when absent

- **WHEN** a dashboard is created with a widget carrying only `id`, `saved_query_id`, `row_limit` and
  `layout`
- **THEN** the returned widget has no `view`, `title`, `description`, `value_column` or `change_direction`
  member, rather than members with a `null` value

### Requirement: Exactly one query source per widget

A widget SHALL carry exactly one of `saved_query_id` and `inline_query`. A widget carrying both, or neither,
SHALL be rejected with `422` and machine code `validation_error`, and the message SHALL identify the widget
by its `id`. A blank `saved_query_id` SHALL count as absent and SHALL NOT be stored or returned.

#### Scenario: A widget with two sources is rejected

- **WHEN** a create request carries a widget with both `saved_query_id` and `inline_query`
- **THEN** the response is `422` with machine code `validation_error` naming that widget, and nothing is
  persisted

#### Scenario: A widget with no source is rejected

- **WHEN** a create request carries a widget with neither `saved_query_id` nor `inline_query`
- **THEN** the response is `422` with machine code `validation_error`

### Requirement: Dashboard validation

On create and on replace the system SHALL reject, with `422` and machine code `validation_error` and with
nothing persisted or modified:

- a `name` that is absent or blank after trimming, or a `name` or `description` longer than 1000
  characters;
- a `visibility` that is absent;
- a `widgets` member that is absent or `null`;
- a widget whose `row_limit` is absent, not a JSON integer, or outside 1–1000;
- a widget whose `layout` is absent, has a coordinate that is absent or not a JSON integer, or does not fit
  the grid: it SHALL hold `0 <= x <= 11`, `y >= 0`,
  `1 <= w <= 12`, `h >= 1` and `x + w <= 12`, the bounds on `x` and `w` being checked first so the sum
  cannot overflow;
- a widget text member longer than its bound (see **Widget model**);
- a widget whose `id` is absent, blank or longer than 64 characters, or two widgets sharing an `id`;
- more than 100 widgets;
- a `widgets` array whose serialized form exceeds 1 MiB, so that one dashboard cannot hold 100 bodies each
  at the saved-query body maximum;
- a `time` that fails the saved-query time-intent rule.

The system SHALL NOT reject overlapping widgets: placement and collision on the grid are the client's
concern.

A JSON integer is a number token with no fractional part or exponent. `2.5`, `2.0`, `"5"`, `true` and
`null` are not integers and SHALL be refused `422` like an out-of-range value, never coerced; the bounds
are checked on the integer's value however large it is. A layout `y` or `h` SHALL also fit a signed 64-bit
integer (at most `9223372036854775807`); a larger value is refused `422` like any other out-of-range one.

An enum member — `visibility`, `compare`, `view`, `change_direction`, and an inline query's `result_view` —
SHALL be one of its string tokens. A string naming no token, and any non-string value such as a number,
SHALL be rejected with `400` and machine code `bad_request`, the service's binding failure. Neither a
number nor a numeric string such as `"1"` SHALL be read as a constant's position. `time` is the exception: its every malformed shape is the `422` the
time-intent rule defines.

#### Scenario: A blank name is rejected

- **WHEN** a create or replace request supplies a `name` of `"   "`
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: A row limit out of range is rejected

- **WHEN** a create request carries a widget whose `row_limit` is `0`, or `1001`
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: A non-integer row limit is rejected, not coerced

- **WHEN** a create request carries a widget whose `row_limit` is `2.5`, `2.0` or `"5"`, or a layout whose
  `x` is `2.5`
- **THEN** the response is `422` with machine code `validation_error`, and nothing is persisted

#### Scenario: A numeric enum token is a bad request

- **WHEN** a create request carries `visibility` of `1` or `"1"`, `compare` of `1`, a widget whose `view` is
  `2`, or an inline query whose `result_view` is `1`
- **THEN** the response is `400` with machine code `bad_request`, and nothing is persisted

#### Scenario: An oversized widgets document is rejected

- **WHEN** a create request carries widgets whose serialized form exceeds 1 MiB, each inline body within
  the saved-query body maximum
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: A dashboard without widgets is rejected

- **WHEN** a create request has no `widgets` member
- **THEN** the response is `422` with machine code `validation_error`; a create with `widgets` of `[]` is
  `201`

#### Scenario: A widget past the right edge is rejected

- **WHEN** a create request carries a widget with layout `{"x":8,"y":0,"w":6,"h":4}`
- **THEN** the response is `422` with machine code `validation_error`, because `x + w` is `14`

#### Scenario: A huge coordinate does not wrap into the grid

- **WHEN** a create request carries a widget with layout `{"x":2147483000,"y":0,"w":1000,"h":1}`
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: A widget on the right edge is accepted

- **WHEN** a create request carries a widget with layout `{"x":6,"y":0,"w":6,"h":4}`
- **THEN** the response is `201`

#### Scenario: Duplicate widget ids are rejected

- **WHEN** a create request carries two widgets whose `id` is `w1`
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: Too many widgets are rejected

- **WHEN** a create request carries 101 widgets
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: Overlapping widgets are accepted

- **WHEN** a create request carries two widgets whose layouts overlap
- **THEN** the response is `201`

#### Scenario: An unknown view token is a bad request

- **WHEN** a create request carries a widget whose `view` is `pie`
- **THEN** the response is `400` with machine code `bad_request`

### Requirement: Inline query

An `inline_query` SHALL be `{ name, query | sql, result_view?, chart? }`: a required non-blank `name` of at
most 1000 characters, exactly one body — a structured `query` or a `sql` string, a blank `sql` counting as
absent and being neither stored nor returned — an optional `result_view` (`table` or `chart`, defaulting to `table` as a saved query's does), and
an optional `chart` in the saved-query chart model. An absent or blank `name` SHALL be `422
validation_error`.

It SHALL carry no `time`, `tag`, `scope`, `source`, owner, `generation` or timestamp: the widget runs under
its dashboard's time intent and belongs to its dashboard. Those are not members of an inline query, so a
client copying a saved query into a widget SHALL copy only the members above; any other member is an
unrecognised field and SHALL be rejected with the service-wide `400 bad_request` naming it.

On every create and replace the system SHALL validate **every** inline body exactly as the saved-queries
capability validates a saved query body (see [saved-queries](../saved-queries/spec.md), "Saved query body
validation" and "A saved query is not a template"): against the **calling caller's** view of the catalog,
by the same translation the query surface performs, without executing it. A body the translation rejects
SHALL be reported with the status and machine code the query surface gives it, usually `400 bad_request`,
and with that message prefixed by `widget <id>: ` so the client can tell which widget failed. A parameter
expression, an absent
or doubled body, or a body larger than the saved-query body maximum SHALL be `422 validation_error`. A body
binding a literal to a `sensitive` column SHALL be accepted, as a saved query body is.

An inline query is a snapshot. When a client copies a saved query into a widget, the copy SHALL NOT follow
the saved query afterwards, and the service SHALL keep no link between them.

#### Scenario: An inline SQL widget is stored

- **WHEN** a caller creates a dashboard whose widget holds an inline `sql` body the caller may execute
- **THEN** the response is `201` and the widget's `inline_query` is returned as sent, with `result_view`
  filled in when it was absent

#### Scenario: An untranslatable inline body is rejected

- **WHEN** a caller creates a dashboard whose widget holds an inline `sql` body the translation front end
  rejects
- **THEN** the response is `400` with machine code `bad_request` and the message
  `POST /v1/queries/execute-sql` gives for that SQL, prefixed by `widget <id>: `, and nothing is persisted

#### Scenario: An inline body naming an entity the caller cannot resolve is rejected

- **WHEN** a caller creates a dashboard whose inline body reads an entity the caller cannot resolve
- **THEN** the response is `400` with machine code `bad_request`, and does not reveal whether the entity is
  absent or merely invisible to the caller

#### Scenario: An inline query defaults its result view

- **WHEN** a widget's `inline_query` carries a `name` and a `sql` body and no `result_view`
- **THEN** the response is `201` and the stored `inline_query` has `result_view` of `table`

#### Scenario: A saved query copied whole into a widget is rejected

- **WHEN** a widget's `inline_query` carries a saved query's `time` or `scope` member
- **THEN** the response is `400` with machine code `bad_request` naming that member, and nothing is
  persisted

#### Scenario: An inline query with two bodies is rejected

- **WHEN** a widget's `inline_query` carries both `query` and `sql`
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: A replace revalidates every inline body

- **WHEN** a caller replaces a dashboard, changing only its `name`, and one of its stored inline bodies reads
  an entity the caller cannot resolve
- **THEN** the response is `400` with machine code `bad_request` and the dashboard is unchanged

### Requirement: A saved-query reference is loose

A `saved_query_id` SHALL be stored as given. The system SHALL NOT check, on write, that the saved query
exists or that the caller may read it, and SHALL NOT dereference it on read: a dashboard read returns the
reference, never the saved query's content.

Deleting a saved query, or changing its scope, SHALL leave every dashboard that references it unchanged. A
client resolves each reference with the viewer's own credential through `GET /v1/saved-queries/{id}`, which
answers `404` for a saved query that is absent or that the viewer may not read.

#### Scenario: A reference to an absent saved query is stored

- **WHEN** a caller creates a dashboard whose widget references `sq_00000000000000000000000001`, which does
  not exist
- **THEN** the response is `201` and the widget carries that `saved_query_id`

#### Scenario: Deleting a referenced saved query leaves the dashboard unchanged

- **GIVEN** a `public` dashboard with a widget referencing a saved query
- **WHEN** that saved query is deleted
- **THEN** the dashboard's `generation`, `updated_at` and widgets are unchanged, and the widget still
  carries the reference

### Requirement: Dashboard ownership

`owner_id` SHALL be the calling identity's principal, resolved exactly as for a saved query (see
[saved-queries](../saved-queries/spec.md), "Owner identity"), and taken from the credential, never from the
request. `owner_id` and `owner_email` SHALL be preserved across every replace, including one made by a
different caller.

While security is enabled, the system SHALL refuse to store a `private` dashboard whose owner would be
null, because no caller could ever reach it again. A `public` dashboard SHALL NOT be refused on this
ground. The refusal takes one of two forms:

- **A create by a caller whose principal does not resolve.** This is refused with `500` and machine
  code `principal_unavailable`, because the caller's identity failed.
- **A replace that would make a `public` dashboard with a null `owner_id` `private`.** This is refused
  with `422` and machine code `validation_error`, with a message saying that the dashboard has no owner.
  The caller's identity is not at fault, and a replace keeps the stored owner, so this is the only way a
  replace can produce an ownerless `private` row.

In `none` security mode `owner_id` SHALL be `null`, no owner filter SHALL apply, and every read and write
SHALL be permitted at both visibilities. The owner filter SHALL be dropped only in that mode, never because
an authenticated caller's principal came back absent: such a caller owns no `private` dashboard and its
list holds the `public` ones only.

#### Scenario: The principal becomes the owner

- **WHEN** an authenticated caller creates a `private` dashboard
- **THEN** the stored `owner_id` is that caller's principal

#### Scenario: A replace by another publisher does not relabel the owner

- **GIVEN** a `public` dashboard created by one caller
- **WHEN** a different caller holding `SAVED_QUERY_PUBLISH` replaces it
- **THEN** the returned `owner_id` and `owner_email` are still the original author's

#### Scenario: A private dashboard is refused when the principal does not resolve

- **GIVEN** security is enabled
- **WHEN** a caller whose token carries no value for the configured principal claim creates a `private`
  dashboard
- **THEN** the response is `500` with machine code `principal_unavailable` and nothing is persisted

#### Scenario: An ownerless public dashboard cannot be withdrawn

- **GIVEN** security is enabled and a `public` dashboard whose `owner_id` is null
- **WHEN** a caller holding `SAVED_QUERY_WRITE` and `SAVED_QUERY_PUBLISH` replaces it with `visibility` of
  `private`
- **THEN** the response is `422` with machine code `validation_error`, and the dashboard is unchanged

#### Scenario: A caller with no resolvable principal sees no private dashboards

- **GIVEN** security is enabled and other callers own `private` dashboards
- **WHEN** a caller holding `SAVED_QUERY_READ` whose token carries no value for the configured principal
  claim lists dashboards
- **THEN** the response is `200` with the `public` dashboards only

#### Scenario: Dashboards are open in none mode

- **WHEN** the service runs in `none` mode and a caller creates a `private` and a `public` dashboard
- **THEN** both creates succeed with a `null` `owner_id`, and the list returns both

### Requirement: Visibility and privilege authorization

In `oidc` mode a dashboard's visibility SHALL decide who may see and who may write it, using the
saved-query privileges:

- **`private`** — visible only to the caller whose identity equals its `owner_id`. Creating one, and
  replacing or deleting a stored one, SHALL require `SAVED_QUERY_WRITE` and SHALL be possible only on the
  caller's own rows. A publisher withdrawing a `public` dashboard is the one write that leaves another
  caller's `private` row behind (see below).
- **`public`** — visible to any caller holding `SAVED_QUERY_READ`, which `SAVED_QUERY_WRITE` and
  `SAVED_QUERY_PUBLISH` both confer by implication (see `authentication`), so every writer sees it.
  Creating, replacing or deleting one SHALL require `SAVED_QUERY_PUBLISH`.

A `private` dashboard SHALL be visible to its owner alone, whatever privileges another caller holds: a
publisher, or a holder of every privilege, sees the `public` dashboards and its own `private` ones.

Every `/v1/dashboards` read endpoint SHALL require `SAVED_QUERY_READ`, held directly or through
`SAVED_QUERY_WRITE` or `SAVED_QUERY_PUBLISH`; a caller holding none of them SHALL be refused
`403 forbidden` on every read. A caller holding `SAVED_QUERY_PUBLISH` alone therefore reads the `public`
dashboards it curates, as it reads `common` saved queries. Every caller that reaches a write's lookup
holds `SAVED_QUERY_READ` by implication, so a `public` dashboard is visible on every write path that gets
that far.

Publishing is reused rather than given a privilege of its own because publishing a dashboard publishes its
inline queries: a `public` dashboard shows every viewer its inline bodies unredacted, and those are often
copies of the author's personal saved queries. A separate dashboard-publish privilege would let a caller
without `SAVED_QUERY_PUBLISH` put a query definition in front of everyone.

A replace that changes `visibility` SHALL satisfy the write rule for **both** the stored and the requested
visibility, so changing visibility in either direction requires `SAVED_QUERY_PUBLISH`, and a caller without
it can neither publish its own dashboard nor withdraw a `public` one. Ownership is preserved across the
change, so a publisher withdrawing another caller's `public` dashboard returns it to its owner as a
`private` dashboard the publisher can no longer see.

A dashboard the caller may not see SHALL be indistinguishable from one that does not exist: for a caller
that passes the privilege checks preceding the lookup (the read gate, or the write-authority check), a
read, replace or delete addressed to it SHALL respond `404 not_found`, never `403`. A caller refused by
those checks gets `403` for every id alike, so that `403` says nothing about the id either. The visibility rule SHALL be applied as
part of the lookup, so the two cases share one path. A refused write on a dashboard the caller **can** see
SHALL respond `403 forbidden`. A caller holding neither `SAVED_QUERY_WRITE` nor `SAVED_QUERY_PUBLISH` SHALL
be refused `403 forbidden` on every write before any lookup, so a write cannot be used to probe which ids
exist.

The bodies a `public` dashboard returns SHALL NOT be redacted for the reader. Executing a widget stays
subject to the reader's own table grants, sensitive-column clearance and row policies on the query surface.

#### Scenario: A private dashboard is invisible to another caller

- **GIVEN** a `private` dashboard owned by one caller
- **WHEN** a different caller holding `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` and `SAVED_QUERY_PUBLISH`
  reads, replaces or deletes it by id
- **THEN** the response is `404` with machine code `not_found`, and the dashboard is unchanged

#### Scenario: A publish-only caller curates public dashboards

- **WHEN** a caller holding `SAVED_QUERY_PUBLISH` only creates a `public` dashboard, replaces it with the
  `generation` its create response returned, and deletes a `public` dashboard another caller created
- **THEN** the create is `201`, the replace `200` and the delete `204`, and a `GET` of the `public`
  dashboard it created is `200`

#### Scenario: A publisher does not see another caller's private dashboard

- **GIVEN** a `private` dashboard owned by one caller
- **WHEN** a different caller holding `SAVED_QUERY_PUBLISH` reads it by id or lists dashboards
- **THEN** the read is `404 not_found` and the list omits it

#### Scenario: A publish-only caller cannot withdraw a dashboard

- **WHEN** a caller holding `SAVED_QUERY_PUBLISH` only replaces a `public` dashboard it created, with its
  current `generation` and `visibility` of `private`
- **THEN** the response is `403` with machine code `forbidden`, because the `private` side requires
  `SAVED_QUERY_WRITE`

#### Scenario: A write grant reads public dashboards

- **WHEN** a caller holding `SAVED_QUERY_WRITE` only lists dashboards and reads a `public` dashboard another
  caller created
- **THEN** both responses are `200`, the list includes that dashboard, and its `permissions.write` is
  `false`

#### Scenario: A write grant cannot write a public dashboard it sees

- **WHEN** the same caller deletes that `public` dashboard
- **THEN** the response is `403` with machine code `forbidden`, and the dashboard is not deleted

#### Scenario: A public dashboard is readable with SAVED_QUERY_READ

- **WHEN** a caller holding `SAVED_QUERY_READ` but not `SAVED_QUERY_PUBLISH` reads a `public` dashboard
  another caller created
- **THEN** the response is `200` with the full dashboard

#### Scenario: Writing a public dashboard requires SAVED_QUERY_PUBLISH

- **WHEN** a caller holding `SAVED_QUERY_READ` and `SAVED_QUERY_WRITE` but not `SAVED_QUERY_PUBLISH` creates
  a `public` dashboard, or replaces or deletes an existing one — including one it owns
- **THEN** the response is `403` with machine code `forbidden`, and nothing changes

#### Scenario: A non-publisher cannot publish its own dashboard

- **GIVEN** a `private` dashboard owned by a caller without `SAVED_QUERY_PUBLISH`
- **WHEN** that caller replaces it with `visibility` of `public`
- **THEN** the response is `403` with machine code `forbidden` and the stored visibility is unchanged

#### Scenario: Withdrawing a public dashboard requires SAVED_QUERY_PUBLISH

- **GIVEN** a `public` dashboard
- **WHEN** a caller holding `SAVED_QUERY_READ` and `SAVED_QUERY_WRITE` but not `SAVED_QUERY_PUBLISH`
  replaces it with `visibility` of `private`
- **THEN** the response is `403` with machine code `forbidden`

#### Scenario: A publisher may change visibility

- **WHEN** a caller holding `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` and `SAVED_QUERY_PUBLISH` publishes its
  own `private` dashboard
- **THEN** the response is `200`, the dashboard is `public`, and its `owner_id` is unchanged

#### Scenario: Withdrawing another caller's public dashboard returns it to its owner

- **GIVEN** a `public` dashboard owned by one caller
- **WHEN** a different caller holding `SAVED_QUERY_WRITE` and `SAVED_QUERY_PUBLISH` replaces it with
  `visibility` of `private`
- **THEN** the response is `200`, the dashboard keeps its original `owner_id`, the owner can read it, and a
  subsequent read by the withdrawing caller is `404 not_found`

#### Scenario: Reading without SAVED_QUERY_READ is forbidden

- **WHEN** a caller holding `TABLE_READ` but no saved-query privilege lists dashboards
- **THEN** the response is `403` with machine code `forbidden`

#### Scenario: A caller with no write privilege learns nothing from a write

- **WHEN** a caller holding `SAVED_QUERY_READ` only deletes an id that does not exist, and deletes the id of
  a `public` dashboard
- **THEN** both responses are `403` with machine code `forbidden`

### Requirement: Per-caller permissions

Every dashboard the service returns — the full read, the create and replace responses, and each list item —
SHALL carry `permissions: { write, publish }`, computed for the calling caller from the same rule that
enforcement applies, so a client never has to know which privilege a visibility maps to:

- `write` — whether the caller may replace and delete this dashboard at its current visibility;
- `publish` — whether the caller may change this dashboard's visibility. Because a visibility change must
  satisfy the write rule for both visibilities, this requires both `SAVED_QUERY_WRITE` and
  `SAVED_QUERY_PUBLISH`.

The list response SHALL also carry top-level `permissions: { create_private, create_public }`: whether the
caller may create a `private` dashboard, and whether it may create a `public` one.

A flag SHALL report the authorization rules only: the caller's privileges, the dashboard's visibility and
owner, and the unresolved-principal refusal of **Dashboard ownership**. In `oidc` mode a caller whose
principal does not resolve therefore reports `create_private` `false`, and `publish` reports `false` on a
`public` dashboard whose `owner_id` is null, since withdrawing it would leave an unreachable `private` row.
A flag SHALL NOT predict body validation. In particular `write` does not promise that a replace will pass
the revalidation of the stored inline bodies against the caller's view (see **Inline query**), nor the
generation check.

In `none` mode every flag SHALL be `true`. `permissions` SHALL NOT be stored, SHALL NOT be accepted in a
request body, and SHALL NOT be part of the audited state.

#### Scenario: The owner of a private dashboard may write but not publish

- **WHEN** a caller holding `SAVED_QUERY_READ` and `SAVED_QUERY_WRITE` reads its own `private` dashboard
- **THEN** `permissions` is `{"write":true,"publish":false}`

#### Scenario: A viewer of a public dashboard may not write

- **WHEN** a caller holding `SAVED_QUERY_READ` and `SAVED_QUERY_WRITE` reads a `public` dashboard
- **THEN** `permissions` is `{"write":false,"publish":false}`

#### Scenario: A publish-only caller may write a public dashboard but not withdraw it

- **WHEN** a caller holding `SAVED_QUERY_READ` and `SAVED_QUERY_PUBLISH`, but not `SAVED_QUERY_WRITE`, reads
  a `public` dashboard
- **THEN** `permissions` is `{"write":true,"publish":false}`

#### Scenario: A full publisher may write and publish a public dashboard

- **WHEN** a caller holding `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` and `SAVED_QUERY_PUBLISH` reads a
  `public` dashboard
- **THEN** `permissions` is `{"write":true,"publish":true}`

#### Scenario: The list reports what the caller may create

- **WHEN** a caller holding `SAVED_QUERY_READ` and `SAVED_QUERY_WRITE` lists dashboards
- **THEN** the response's top-level `permissions` is `{"create_private":true,"create_public":false}`

#### Scenario: An unresolved principal may not create a private dashboard

- **GIVEN** security is enabled
- **WHEN** a caller holding `SAVED_QUERY_READ` and `SAVED_QUERY_WRITE` whose token carries no value for the
  configured principal claim lists dashboards
- **THEN** the response's top-level `permissions` has `create_private` of `false`

#### Scenario: Supplied permissions are rejected

- **WHEN** a create or replace request body contains `permissions`
- **THEN** the response is `422` with machine code `validation_error`

### Requirement: Generation-checked replace

`PUT /v1/dashboards/{id}` SHALL carry, in its body, the `generation` the client last read. The replace SHALL
succeed only when that value equals the stored `generation`; otherwise the response SHALL be `409` with
machine code `dashboard_conflict` and the dashboard SHALL be unchanged. A replace whose body omits
`generation` SHALL be rejected with `422` and machine code `validation_error`, so no client replaces a
dashboard without the check. The check and the write SHALL be atomic: of two replaces carrying the same
`generation`, exactly one SHALL succeed.

A request body that does not bind — malformed JSON, an unrecognised member, a member of the wrong type, or
a structured inline `query` failing its own contract — SHALL be refused `400 bad_request` before any other
check. That reveals nothing about stored dashboards. After it, a replace's checks SHALL be ordered:

1. the write-authority check that needs no lookup — a caller holding neither `SAVED_QUERY_WRITE` nor
   `SAVED_QUERY_PUBLISH` (`403`);
2. the visibility lookup (`404`);
3. write authority for the stored visibility and, when the body names one, the requested visibility
   (`403`); a body without `visibility` skips the requested half here and is refused `422` at step 5;
4. the generation check (`409`, or `422` when `generation` is absent, `null`, not a JSON integer, or
   outside the range of a signed 64-bit integer);
5. the rest of body validation (`422`, including the ownerless-withdrawal refusal of **Dashboard
   ownership**, or the query surface's status for an inline body the translation rejects).

A caller that may not write the dashboard therefore learns nothing about its generation. A create SHALL be
checked in the order 1, 3 (against the requested visibility, skipped when the body names none), 5, and
then the unresolved-principal refusal for a `private` create (`500 principal_unavailable`).

A successful replace SHALL increment `generation` and refresh `updated_at`. The `If-Match` header SHALL NOT
be a concurrency token. `DELETE` SHALL carry no `generation`. However, a delete and a replace that race on
the same dashboard SHALL NOT both succeed: the one that loses SHALL answer `409 dashboard_conflict`, or
`404 not_found` when the delete committed first. A client receiving either re-reads the dashboard.

`generation` SHALL be accepted only on a replace: a create body carrying it is a server-assigned field and
SHALL be rejected as such.

#### Scenario: A replace with the current generation succeeds

- **GIVEN** a dashboard at `generation` 3
- **WHEN** a caller that may write it replaces it with `generation` 3 in the body
- **THEN** the response is `200` with `generation` 4

#### Scenario: A stale replace is refused and changes nothing

- **GIVEN** a dashboard at `generation` 4
- **WHEN** a caller replaces it with `generation` 3 in the body
- **THEN** the response is `409` with machine code `dashboard_conflict`, and a subsequent read returns the
  dashboard unchanged at `generation` 4

#### Scenario: A replace without a usable generation is rejected

- **WHEN** a caller replaces a dashboard with a body whose `generation` is absent, `null`, `"3"` or `3.5`
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: If-Match is not a concurrency token

- **GIVEN** a dashboard at `generation` 3
- **WHEN** a caller replaces it with `generation` 3 in the body and an `If-Match` header naming any other
  value
- **THEN** the response is `200`

#### Scenario: Two concurrent deletes

- **WHEN** two callers that may write a dashboard delete it concurrently
- **THEN** one receives `204`, and the other `404 not_found` or `409 dashboard_conflict`

#### Scenario: Two concurrent replaces from the same read

- **GIVEN** two clients that both read a dashboard at `generation` 5
- **WHEN** both replace it with `generation` 5
- **THEN** exactly one receives `200` and the other `409 dashboard_conflict`

#### Scenario: A forbidden replace does not reveal staleness

- **WHEN** a caller without `SAVED_QUERY_PUBLISH` replaces a `public` dashboard with a stale `generation`
- **THEN** the response is `403` with machine code `forbidden`, not `409`

### Requirement: Dashboard API

The system SHALL expose dashboards under `/v1/dashboards`, answering every failure with the `ErrorView`
contract:

- `GET /v1/dashboards` — every dashboard the caller may see, `private` and `public` together, most recently
  updated first, as `{ "dashboards": [ ... ], "permissions": { create_private, create_public } }`. Each list item SHALL
  carry `id`, `name`, `description`, `visibility`, `updated_at` and `permissions`, and no widgets.
- `POST /v1/dashboards` — create; responds `201` with the full dashboard.
- `GET /v1/dashboards/{id}` — the full dashboard.
- `PUT /v1/dashboards/{id}` — whole-document replace of the caller-supplied fields; responds `200` with the
  full dashboard. `id`, `owner_id`, `owner_email` and `created_at` SHALL be preserved.
- `DELETE /v1/dashboards/{id}` — responds `204`.

A member with no value SHALL be omitted from a response rather than emitted as `null`. The number of
dashboards in a list is not bounded by this capability; should it need bounding, it SHALL be bounded by
paging, not by dropping fields from list items.

The capability SHALL expose no endpoint that executes a dashboard or a widget, and SHALL NOT cache any
query result per dashboard.

#### Scenario: The list carries the caller's own and the public dashboards

- **GIVEN** a caller owning one `private` dashboard, another caller owning a second `private` one, and one
  `public` dashboard
- **WHEN** the first caller lists dashboards
- **THEN** the list holds its own `private` dashboard and the `public` one, and not the other caller's

#### Scenario: List items carry no widgets

- **WHEN** a caller lists dashboards
- **THEN** each item carries `id`, `name`, `visibility`, `updated_at` and `permissions`, its `description`
  when it has one, and no `widgets` member

#### Scenario: The list is ordered by most recent update

- **GIVEN** two dashboards, the older of which is then replaced
- **WHEN** a caller lists dashboards
- **THEN** the replaced one comes first

#### Scenario: Delete removes the dashboard

- **WHEN** a caller deletes a dashboard it may write
- **THEN** the response is `204`, and a subsequent read of that id responds `404 not_found`

#### Scenario: An unknown id is not found

- **WHEN** a caller holding `SAVED_QUERY_READ` and `SAVED_QUERY_WRITE` reads, replaces or deletes an id that
  exists at no visibility
- **THEN** the response is `404` with machine code `not_found`

#### Scenario: There is no execute endpoint for a dashboard

- **WHEN** a caller posts to a dashboard's id with an execute path segment
- **THEN** the response is `404` with machine code `not_found`
