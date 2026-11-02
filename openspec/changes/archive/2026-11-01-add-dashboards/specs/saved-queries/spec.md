## MODIFIED Requirements

### Requirement: Scope-based visibility and privilege authorization

A saved query SHALL have one of two scopes, with these rules in `oidc` mode:

- **`personal`** — readable only by the caller whose identity equals its `owner_id`, whatever
  privileges any other caller holds, on every surface while it stays `personal`: the live read, the
  revision endpoint and the activity feed (see `audit-trail`). Visibility follows the current scope, so
  once its owner makes it `common`, its earlier revisions are readable as a `common` query's are. Reading a `personal` saved query SHALL require `SAVED_QUERY_READ`;
  creating, replacing, and deleting one SHALL require `SAVED_QUERY_WRITE`, and SHALL be permitted only on
  the caller's own rows.
- **`common`** — readable by any caller holding `SAVED_QUERY_READ`. Creating, replacing, or deleting
  a `common` saved query SHALL require `SAVED_QUERY_PUBLISH`; a caller without it SHALL receive
  `403 forbidden`.

`SAVED_QUERY_READ` MAY be held directly or by implication from `SAVED_QUERY_WRITE` or
`SAVED_QUERY_PUBLISH` (see `authentication`).

`owner_id` SHALL be the author in both scopes. Changing a saved query's scope SHALL require
satisfying the write rule for **both** the stored scope and the requested scope, so a caller without
`SAVED_QUERY_PUBLISH` can neither promote its own row into `common` nor demote an existing `common`
row.

Because the deciding input is the request body's scope rather than the path, this authorization SHALL
be evaluated during request handling and SHALL surface as `403 forbidden` through the same
`ErrorView` contract as any other denial.

A saved query the caller may not read SHALL be indistinguishable from one that does not exist: every
read, replace, and delete addressed to such an id SHALL respond `404 not_found`, never `403`. The
visibility rule SHALL be applied as part of the lookup rather than as a check after fetching, so the
two cases share one code path.

A stored query body MAY name entities and columns the reading caller cannot otherwise reach. The
service SHALL NOT redact stored bodies: consistent with column names not being secret, a `common`
saved query is returned as written, and executing it remains subject to the reader's own
`TABLE_READ` on every resolved table.

#### Scenario: Personal rows are invisible to other callers

- **GIVEN** a `personal` saved query owned by one caller
- **WHEN** a different authenticated caller reads it by id
- **THEN** the response is `404` with machine code `not_found`, not `403`

#### Scenario: A full administrator does not see another caller's personal saved query

- **GIVEN** a `personal` saved query owned by one caller
- **WHEN** a different caller holding every privilege reads it by id or at one of its revisions
- **THEN** the response is `404` with machine code `not_found`

#### Scenario: Personal rows of others are absent from the list

- **WHEN** a caller lists saved queries with `scope=personal`
- **THEN** the response contains only saved queries whose `owner_id` is that caller

#### Scenario: Common rows are readable with SAVED_QUERY_READ

- **WHEN** a caller holding `SAVED_QUERY_READ` lists or reads a `common` saved query
- **THEN** the response is `200` and includes it, with `owner_id` identifying its author

#### Scenario: Writing common requires SAVED_QUERY_PUBLISH

- **WHEN** a caller holding `SAVED_QUERY_WRITE` but not `SAVED_QUERY_PUBLISH` creates, replaces, or
  deletes a saved query with scope `common`
- **THEN** the response is `403` with machine code `forbidden`

#### Scenario: Reading without SAVED_QUERY_READ is forbidden

- **WHEN** a caller holding `TABLE_READ` but no saved-query privilege lists saved queries
- **THEN** the response is `403` with machine code `forbidden`

#### Scenario: A non-publisher cannot promote its own row to common

- **GIVEN** a `personal` saved query owned by a caller without `SAVED_QUERY_PUBLISH`
- **WHEN** that caller replaces it with a body whose scope is `common`
- **THEN** the response is `403` with machine code `forbidden` and the stored scope is unchanged

#### Scenario: Deleting another caller's personal row is not found

- **WHEN** a caller deletes a `personal` saved query it does not own
- **THEN** the response is `404` with machine code `not_found` and the saved query is not deleted

#### Scenario: A stored body naming unreadable tables is returned but not executable

- **GIVEN** a `common` saved query whose body names a table the reading caller cannot read
- **WHEN** that caller reads the saved query and then executes its body
- **THEN** the read returns the body as written, and the execution reports an unknown entity

### Requirement: A saved-query write is authorized against the scope in its body

`POST`, `PUT` and `DELETE` on `/v1/saved-queries` SHALL require `SAVED_QUERY_WRITE` for a
`personal`-scope write and `SAVED_QUERY_PUBLISH` for a `common`-scope one. A replace that changes
scope SHALL satisfy the requirement for **both** the stored and the requested scope, so a caller
holding only `SAVED_QUERY_WRITE` can neither promote its own row into `common` nor demote an existing
`common` row to hide it.

Because scope arrives in the request body, the endpoint gate cannot decide which privilege applies.
The declaration on each of the three mappings SHALL therefore be a **deferred** one, with the check
made at the single service-layer chokepoint that has the scope in hand. That chokepoint SHALL be
total: it decides both scopes, not only `common`. Deciding only `common` left two holes at once — an
authenticated caller holding no saved-query privilege could create a `personal` row, and a
`SAVED_QUERY_PUBLISH` holder was refused at a gate demanding `SAVED_QUERY_WRITE`, so the privilege
whose entire purpose is curating the shared catalogue authorized nothing on its own.

#### Scenario: A publish-only caller curates the shared catalogue

- **WHEN** a caller holding only `SAVED_QUERY_PUBLISH` creates, replaces or deletes a `common` saved
  query
- **THEN** the request succeeds, and the same caller may read every `common` saved query, because
  `SAVED_QUERY_PUBLISH` implies `SAVED_QUERY_READ` (see `authentication`)

#### Scenario: A publish-only caller may not write a personal query

- **WHEN** the same caller creates a `personal` saved query
- **THEN** the response is `403 forbidden`, naming `SAVED_QUERY_WRITE`

#### Scenario: A caller holding no saved-query privilege cannot write

- **WHEN** an authenticated caller holding neither privilege creates a `personal` saved query
- **THEN** the response is `403 forbidden`; the deferred gate admits the call and the chokepoint
  refuses it
