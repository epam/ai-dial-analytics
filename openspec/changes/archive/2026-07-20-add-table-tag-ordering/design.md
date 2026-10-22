## Context

Schema discovery (`GET /v1/queries/entities/schema/{name}`) returns a flat field list where each
field carries an optional `tag` (free-form label ≤64 chars on `column_mapping.tag`, no fixed
taxonomy, duplicates allowed). UIs group fields into tag sections, but there is no server-side
notion of the order of those sections, so they render arbitrarily.

Current relevant machinery:

- Catalog metadata mutations flow through `PATCH /v1/tables/{name}/schema`
  (`TableController` → `TableMapper.toPatchInput` → `TableSchemaService.patchSchema` →
  `CatalogSchemaWriter`), with ops applied `drop → rename → add → update`. The per-column metadata
  ops were consolidated into a single `update` merge-patch (`PatchSchemaRequest.ColumnMetadataUpdate`)
  built on a `Change<T>` record: `present=false` → leave, `present=true` → set (value may be null =
  clear). The new table-level `tag_order` reuses this same `Change<T>` idiom for consistency (see
  D3).
- `TableDefinitionEntity.orderingKey` models a per-table ordered string list via
  `@ElementCollection @CollectionTable("table_ordering_key") @OrderColumn("ordinal")` — a
  possible precedent, considered and rejected below in favor of a denormalized array column.
- Tests run against real PostgreSQL (Testcontainers), so native Postgres column types (`text[]`)
  are usable without portability concerns.
- Seeded tables `dial_usage_log` and `rate_analytics` are `system=true` and reject every modifying
  `/v1/tables` operation with 409; their metadata is managed by migration only.
- Latest Flyway migration is `V9` (postgres).

## Goals / Non-Goals

**Goals:**

- Optional, per-table ordered list of tag names ("tag order") that admins can set, replace, and
  clear on user tables, and that migrations can seed for system tables.
- Surface the ordering on the table-management read surface and on schema discovery so consumers
  can order tag groups.
- Robustness under metadata churn: retagging columns, adding columns with new tags, dropping or
  renaming columns must never corrupt or silently mutate the stored ordering.

**Non-Goals:**

- No fixed tag taxonomy, no tags registry table, no validation that ordered tags exist on columns.
- No per-user or per-role orderings; the ordering is a single table-level setting.
- No change to the physical column `ordinal` in the catalog — sorting is presentation-only, applied
  when building responses; the stored ordinal (and thus ClickHouse column order) is untouched.

## Decisions

### D1: Ordering is a standalone ordered list of tag names, decoupled from columns

The ordering is stored as an ordered list of tag strings attached to the table definition — not as
a rank column on `column_mapping` and not validated against the tags currently present on columns.

Rationale (this directly answers the retag/new-tag churn cases):

- **Column retagged to a tag not in the list / new column with a new tag**: nothing breaks — the
  new tag is simply "unlisted"; the server sorts unlisted-tag groups after the listed ones (see
  D5). No write amplification, no surprise mutation of the admin's ordering.
- **Last column with tag X retagged away / dropped**: the entry for X stays in the list. It is
  harmless (consumers only use entries for tags that actually occur) and intentional — the order
  survives a transient retag round-trip, and an admin can set the order *before* retagging.
- Alternative considered: a `tag_rank` column on `column_mapping` (rank duplicated per column) —
  rejected: denormalized, must be kept consistent on every retag/add, and cannot represent an
  ordering entry for a tag with currently zero columns.
- Alternative considered: auto-pruning/auto-appending the list as tags come and go — rejected:
  turns unrelated column ops (`update`, `add`, `drop`) into hidden writers of the ordering,
  making behavior order-dependent and hard to reason about. Explicit is better.

### D2: Storage is a denormalized `text[]` column on `table_definition`

The ordering is always read whole, replaced whole, and never joined, filtered, or queried
per-entry — the textbook case where a document/array column beats relational decomposition.
Flyway `V10__add_table_tag_order.sql`:

```sql
ALTER TABLE table_definition ADD COLUMN tag_order TEXT[];
```

Mapped as `List<String> tagOrder` on `TableDefinitionEntity` with
`@JdbcTypeCode(SqlTypes.ARRAY)` (Hibernate 6+ native array mapping — no converter code). `NULL`
column = "ordering not set", mapping the wire `null` directly onto SQL semantics; the value is
removed with the row on delete (no FK/cascade bookkeeping); a replace is a single-column
`UPDATE`; order is inherent in the array (no ordinal column).

Alternatives considered:

- A separate `table_tag_order(table_name, ordinal, tag)` table mirroring `table_ordering_key` —
  rejected: extra table + FK + ordinal bookkeeping and a second SELECT per definition read, for a
  value we never decompose in SQL. The `ordering_key` precedent alone doesn't justify it.
- A `TEXT` column holding JSON with an `AttributeConverter` — rejected: converter code and a
  serialization format for no benefit over the native array (tests run on real Postgres).

The migration also seeds a taxonomy order for the two seeded system tables (which cannot be
patched over the API) via `UPDATE table_definition SET tag_order = ARRAY[...]`, using only tags
those tables actually carry after V7, in this order:
`identifier`, `dimension`, `principal`, `deployment`, `request`, `response`, `token-usage`,
`cost`, `performance`, `system` (subset per table as applicable — e.g. `rate_analytics` only gets
the tags it has).

### D3: API surface — top-level `tag_order` merge-patch member, `tag_order` on create and reads

- `PATCH /v1/tables/{name}/schema` body gains an optional **top-level** `tag_order` member (a JSON
  array of strings) — table-level metadata, so it sits alongside `add`/`drop`/`rename`/`update`
  rather than inside the per-column `update` list. It follows the endpoint's merge-patch idiom:
  absent or `null` → **leave unchanged**; present and empty (`[]`) → **clear** (back to "not set");
  present and non-empty → **replace** with the given list. It maps to a `Change<List<String>>` in
  `PatchInput`, reusing the same merge-patch record as the per-column metadata fields for
  consistency: the mapper produces `Change.leave()` when the JSON member is absent/null and
  `Change.set(list)` when present, and the service normalizes a present empty list to "clear"
  (stored as SQL `NULL`) — mirroring how a present blank string clears a column attribute. It is
  applied **last**, after the column `update`s; it is catalog-only and never issues ClickHouse DDL.
- `POST /v1/tables` accepts an optional top-level `tag_order` array with the same validation, so a
  table can be created with its ordering in one call.
- `GET /v1/tables/{name}` (`TableDto`) and `GET /v1/queries/entities/schema/{name}`
  (`QueryEntitySchemaDto`) gain a `tag_order` field: the ordered array, or `null` when not set.
  Discovery returns the source table's own ordering verbatim (an admin may include enrichment-tag
  names in it, since entries are free strings). `tag_order` is not role-filtered — tag names are
  labels, not data, and entries may reference non-existent tags anyway, so no sensitive-column
  existence can be inferred from it.

Array merge-patch semantics: for a string field the codebase treats blank as clear; for the
`tag_order` array the natural analog is the empty array as clear, with `null`/absent as leave — so
a partial client that serializes nulls never wipes an ordering. A blank *entry* inside the array is
a validation error (D4), distinct from an empty array.

### D4: Validation (422 `TableValidationException`, in `TableColumnRules`)

Each entry: trimmed; blank entries rejected (a blank tag can't be targeted — untagged columns are
a client-side "rest" group, not an orderable tag); length ≤ 64 (same `MAX_TAG_LENGTH` as `tag`).
Entries must be unique after trimming. List size capped at 100 entries. Entries need **not**
match any current column tag (see D1). Same rules for `POST /v1/tables` `tag_order` and the
PATCH op.

### D5: Both GET surfaces return columns/fields pre-sorted when an ordering is set

When (and only when) the table has a tag ordering, responses sort the column/field list by tag
group before returning it, using this total order:

1. Items whose tag appears in `tag_order`: by the tag's index in the list.
2. Items whose tag is not listed: after all listed groups, groups ordered alphabetically by tag
   name (deterministic, no reliance on column ordinals).
3. Untagged items (null tag): last.
4. Within a group (and within the untagged group): alphabetically by the item's exposed `name`
   (bare, or table-qualified for enrichment fields on the discovery surface) — `name` is always
   present, unlike `display_name`, and is unique per surface, so the sort is total and stable.

This applies to **both**:

- `GET /v1/queries/entities/schema/{name}` — the flattened `fields` array (`CatalogQueryService`),
  sorted **after** sensitive-field filtering (pure presentation; hiding is unaffected).
- `GET /v1/tables/{name}` — the `columns` array (`TableMapper.toDto` / `TableCatalogService`),
  which is not role-filtered.

The sort rule lives in **one shared comparator/util** (e.g. `TagOrdering`) consumed by both call
sites, so the two surfaces cannot drift. The comparator takes the `tag_order` list plus a
tag-and-name accessor; enrichment flattening/qualified-name construction stays in
`CatalogQueryService` and only the final sort is shared.

When the table has **no** ordering (`tag_order` null), the order is byte-for-byte the pre-existing
behavior — column ordinal order (fields: enrichment fields appended). This keeps "not set"
perfectly backward compatible and makes the feature opt-in per table.

The seeded system tables get an ordering in V10, so their responses become grouped-and-alphabetical
after this change ships — that is the point of the feature and is called out in the spec deltas.

The sort is presentation-only: the catalog `ordinal` (and thus physical ClickHouse column order) is
never rewritten. Alternative considered: documenting the contract and leaving sorting to consumers
— rejected per user decision; every consumer would re-implement the same sort, and the messy
default rendering is exactly the problem this change fixes. `docs/column-tags.md` documents the
server-side rules instead.

## Risks / Trade-offs

- [Stale entries accumulate if admins retag heavily] → Harmless to consumers (unmatched entries
  are skipped); admins see the full list on `GET /v1/tables/{name}` and can re-set it in one
  `set_tag_order` call. Accepted for the simplicity of D1.
- [Typo in a tag name silently does nothing (no existence validation)] → Trade-off of D1;
  mitigated by the ordering being fully visible in reads and trivially replaceable. Strict
  validation would break the set-order-then-retag workflow and migration-time seeding.
- [System tables cannot be re-ordered via API] → Consistent with existing system-owned
  protection; their ordering ships in V10 and changes via future migrations, same as their tags
  did in V7.
- [`text[]` mapping is a new pattern in this codebase (diverges from `ordering_key`'s element
  collection)] → One annotation (`@JdbcTypeCode(SqlTypes.ARRAY)`), covered by integration tests
  on real Postgres; accepted for the simpler storage and clean NULL-means-unset semantics.
- [Element-level constraints (length/dups/size) live only in app code] → Equally true of the
  separate-table alternative in this codebase; enforced centrally in `TableColumnRules`.

## Migration Plan

Single additive Flyway migration `V10__add_table_tag_order.sql` (`ALTER TABLE table_definition
ADD COLUMN tag_order TEXT[]` + system-table seed `UPDATE`s). Existing rows get `NULL` (= not
set); rollback is dropping the column. API changes are additive
(one new optional request member, one new nullable response field on two endpoints), so existing
clients are unaffected.

## Open Questions

None — decisions above cover the churn cases the change was requested for.
