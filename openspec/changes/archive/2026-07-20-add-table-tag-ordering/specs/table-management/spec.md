## ADDED Requirements

### Requirement: Per-table tag ordering

The system SHALL maintain an optional per-table **tag ordering**: an ordered list of tag names that
tells discovery consumers in what order to display tag groups. The ordering SHALL be catalog-only
metadata stored in PostgreSQL as a nullable array column (`tag_order TEXT[]`) on the table's
`table_definition` row (removed with the row on delete) and SHALL never issue ClickHouse DDL. A
table with no ordering set SHALL have a `NULL` column and behave exactly as before this capability
existed. Consistent with the service's `NON_NULL` serialization, "not set" SHALL be omitted (an
absent member, read as null by consumers) on the wire — never rendered as an empty array.

The ordering SHALL be an independent display hint over tag names, deliberately decoupled from the
tags currently carried by the table's columns:

- Entries MAY reference tag names not currently present on any column (pre-setting an order before
  a retag, or referencing enrichment tags, is allowed).
- Column operations (`add`, `drop`, `rename`, and metadata `update`) SHALL NOT mutate the stored
  ordering — a column retagged to an unlisted tag or a new column with a brand-new tag simply
  yields an unlisted tag, which the discovery surface sorts after the listed groups (see the
  `query-schema-discovery` capability's "Tag-ordered field sorting").
- When an ordering is set, both the `/v1/tables` read surface (the `columns` array) and the
  discovery surface (see the `query-schema-discovery` capability's "Tag-ordered field sorting")
  SHALL return their column/field list sorted by the same tag-group rules; when no ordering is set,
  both SHALL preserve the pre-existing declared (ordinal) order. The sort is presentation-only —
  the stored column `ordinal` (and thus physical ClickHouse column order) SHALL NOT change.

`POST /v1/tables` SHALL accept an optional top-level `tag_order` array of tag names for the new
table. `PATCH /v1/tables/{name}/schema` SHALL accept an optional top-level `tag_order` member
following the endpoint's merge-patch convention — absent or `null` leaves the ordering unchanged,
an empty array (`[]`) clears it, and a non-empty array replaces it. `GET /v1/tables/{name}` SHALL
return the table's `tag_order` (the ordered array, or an absent/null member when not set) and, when
an ordering is set, its `columns` array sorted by the tag-group rules (ordinal order preserved when
not set).

Validation (rejections use HTTP 422 and the `ErrorView` contract): each entry SHALL be trimmed and
SHALL be non-blank after trimming; each entry SHALL be at most 64 characters (the tag length
limit); entries SHALL be unique within the list after trimming; the list SHALL contain at most 100
entries. Entries SHALL NOT be validated against the tags currently present on columns.

The catalog migration SHALL seed a taxonomy tag ordering for the seeded `system` tables
(`dial_usage_log`, `rate_analytics`), since system tables reject `PATCH` and can only receive
their ordering via migration.

#### Scenario: Tag order declared at create is returned

- **WHEN** `POST /v1/tables` creates a source with `tag_order: ["identifier", "dimension"]`, then
  `GET /v1/tables/{name}` is called
- **THEN** the response carries `tag_order: ["identifier", "dimension"]`

#### Scenario: Table without an ordering omits tag_order

- **WHEN** `GET /v1/tables/{name}` is called for a table that never had a tag ordering set (or
  whose ordering was cleared)
- **THEN** the response omits the `tag_order` member (absent, read as null by consumers)

#### Scenario: Columns are sorted by the ordering on the read surface

- **WHEN** a table has `tag_order: ["identifier", "cost"]` and columns tagged `cost`,
  `identifier`, `token-usage` (unlisted), plus an untagged column, and `GET /v1/tables/{name}` is
  called
- **THEN** the `columns` array lists the `identifier` column(s) first, then `cost`, then the
  unlisted `token-usage`, then the untagged column — each group sorted alphabetically by exposed
  `name` — and the stored column `ordinal` values are unchanged

#### Scenario: Columns keep ordinal order when no ordering is set

- **WHEN** `GET /v1/tables/{name}` is called for a table with no `tag_order`
- **THEN** the `columns` array is in declared (ordinal) order, exactly as before this capability

#### Scenario: Ordering survives column-metadata and new-tag churn

- **WHEN** a table has `tag_order` set, and subsequent `PATCH` requests `update` a column's tag to
  one not in the list, `update` the only column of a listed tag to a different tag, and `add` a
  column with a brand-new tag
- **THEN** the stored `tag_order` is unchanged by all of these operations

#### Scenario: Ordering entry for a non-existent tag is accepted

- **WHEN** a tag ordering is set that includes a tag name carried by no current column
- **THEN** the request is accepted and the entry is stored and returned verbatim

#### Scenario: Blank or over-long ordering entry is rejected

- **WHEN** a tag ordering is declared (at create or via `PATCH`) containing a blank entry or an
  entry longer than 64 characters
- **THEN** the request is rejected with HTTP 422

#### Scenario: Duplicate ordering entries are rejected

- **WHEN** a tag ordering is declared containing the same tag name twice (after trimming)
- **THEN** the request is rejected with HTTP 422

#### Scenario: Oversized ordering list is rejected

- **WHEN** a tag ordering is declared with more than 100 entries
- **THEN** the request is rejected with HTTP 422

#### Scenario: Seeded system tables carry a taxonomy ordering

- **WHEN** the catalog is read after the tag-order migration runs
- **THEN** `dial_usage_log` carries a non-null `tag_order` listing its taxonomy tags (e.g.
  `identifier` first) and `rate_analytics` carries a non-null `tag_order` over the tags it has

#### Scenario: Deleting a table removes its ordering

- **WHEN** `DELETE /v1/tables/{name}` removes a table that had a tag ordering
- **THEN** the ordering is removed with the table's catalog row

## MODIFIED Requirements

### Requirement: Modify table schema via PATCH

The system SHALL evolve a table's columns at `PATCH /v1/tables/{name}/schema` from a body carrying
`add`, `drop`, `rename`, and `update` operations, plus an optional top-level `tag_order` member.
The structural operations (`add`/`drop`/`rename`) SHALL each be applied as an immediate ClickHouse
`ALTER` (`ADD`/`DROP`/`RENAME COLUMN`) using the writer credential and update the Postgres column
mapping in the same operation. The `update` operation SHALL adjust an existing column's catalog-only
metadata — its `tag`, `display_name`, `sensitive` flag, and `description` — and SHALL NOT issue any
ClickHouse `ALTER`. Each `update` entry declares `{name, tag?, display_name?, sensitive?,
description?}`, keyed by the column's current exposed name, and SHALL follow merge-patch semantics
per field: a metadata field that is **absent or null** SHALL leave that column attribute unchanged;
a string field (`tag`, `display_name`, `description`) that is **present and blank** SHALL clear that
attribute; a string field that is **present and non-blank** SHALL set it (trimmed); and `sensitive`,
when **present**, SHALL set the flag to the supplied boolean. A single `update` entry MAY change
several metadata fields at once. Each `add` declares `{source_name, name, type, nullable?,
description, display_name?, tag?, sensitive?}` (`sensitive` defaults to false when omitted).

The top-level `tag_order` member SHALL set the table's tag ordering following the endpoint's
merge-patch convention: **absent or null** SHALL leave the ordering unchanged; an **empty array**
(`[]`) SHALL clear it (back to "not set"); a **non-empty array** SHALL replace it with the supplied
list of tag names. It is table-level catalog-only metadata (see "Per-table tag ordering") and SHALL
NOT issue any ClickHouse `ALTER`.

Operations SHALL be applied `drop` → `rename` → `add` → `update` → `tag_order`, so an `update` entry
MAY target a just-added or just-renamed column by its resulting exposed name. A column's
`display_name`, `tag`, `sensitive` flag, and `description` SHALL all be preserved across a `rename`.
Schema modification SHALL be breaking and immediate (a drop or rename takes effect at once). In
`oidc` mode this endpoint SHALL be authorized by `FULL_ADMIN` or a matching `modify` access role on
the table (see the `table-access-control` capability). The system SHALL reject: a type-change of an
existing column (422); a drop or rename of a system column (reserved `_`-prefix) or the grain key
(422); an `update` of a column that is not an exposed column of the table after the request's own
add/drop/rename operations are applied (422); a duplicate `update` of the same column within one
request (422); an invalid `tag_order` list (a blank or over-64-char entry, duplicate entries, or
more than 100 entries) (422); and any operation on a table not in the catalog (404). Neither the
`update` operation nor `tag_order` carries a column mutability restriction — system, grain, and
ordering-key columns MAY be tagged, given a display name, marked sensitive, or described, and
`tag_order` MAY name any tag string regardless of which columns carry it.

#### Scenario: Add columns to an enrichment

- **WHEN** `PATCH /v1/tables/{name}/schema` adds valid columns to an `ACTIVE` enrichment
- **THEN** an `ALTER TABLE ... ADD COLUMN` is issued per column, the column mapping is updated, and
  the response reflects the new columns

#### Scenario: Drop and rename apply immediately

- **WHEN** `PATCH /v1/tables/{name}/schema` drops one column and renames another
- **THEN** the corresponding `ALTER TABLE ... DROP COLUMN` / `RENAME COLUMN` are issued and the
  column mapping is updated

#### Scenario: Type change is rejected

- **WHEN** a `PATCH` attempts to change the `type` of an existing column
- **THEN** the request is rejected with HTTP 422

#### Scenario: System and grain columns are protected

- **WHEN** a `PATCH` attempts to drop or rename a `_`-prefixed system column or an enrichment's grain
  key
- **THEN** the request is rejected with HTTP 422

#### Scenario: Update changes several metadata fields at once

- **WHEN** a single `update` entry supplies a `tag`, a `display_name`, and `sensitive: true` for an
  existing column
- **THEN** all three attributes are set on that column and no ClickHouse `ALTER` is issued

#### Scenario: Absent metadata field leaves an attribute unchanged

- **WHEN** an `update` entry supplies only a `tag` for a column that already has a `display_name`
- **THEN** the column's `tag` is set and its existing `display_name`, `sensitive` flag, and
  `description` are unchanged

#### Scenario: Blank metadata field clears an attribute

- **WHEN** an `update` entry supplies a blank (`""`) `tag`, `display_name`, or `description` for a
  column that currently has that attribute set
- **THEN** that attribute is cleared, and no ClickHouse `ALTER` is issued

#### Scenario: Metadata survives a rename

- **WHEN** a `PATCH` `update`s an existing column's `tag`, `display_name`, `sensitive` flag, and
  `description`, then a later `PATCH` renames that column
- **THEN** all four attributes are preserved under the rename, none of which issues a ClickHouse
  `ALTER`

#### Scenario: Update of an unknown column is rejected

- **WHEN** an `update` entry names a column that is not an exposed column of the table (after the
  request's own add/drop/rename operations are applied)
- **THEN** the request is rejected with HTTP 422

#### Scenario: Duplicate update of the same column is rejected

- **WHEN** two `update` entries in one request name the same column
- **THEN** the request is rejected with HTTP 422

#### Scenario: tag_order replaces and clears the ordering

- **WHEN** a `PATCH` carries `tag_order: ["cost", "token-usage"]`, then a later `PATCH` carries
  `tag_order: []`
- **THEN** after the first request the table's `tag_order` is exactly `["cost", "token-usage"]`
  (any previous ordering replaced), after the second it is cleared and reads omit `tag_order`
  (absent, read as null) — neither request issues a ClickHouse `ALTER`

#### Scenario: Absent tag_order leaves the ordering untouched

- **WHEN** a table has a tag ordering and a `PATCH` is applied whose body has no `tag_order`
  member (or an explicit `null`)
- **THEN** the stored ordering is unchanged

#### Scenario: A modify access role authorizes a non-admin schema change

- **WHEN** a non-`FULL_ADMIN` caller holding a matching `modify` access role on the table `PATCH`es its schema
- **THEN** the modification is authorized and processed
