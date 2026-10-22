## ADDED Requirements

### Requirement: Schema-level tag order

The base-schema response at `GET /v1/queries/entities/schema/{name}` SHALL carry a top-level
`tag_order` member surfacing the source table's per-table tag ordering from the table-management
catalog (see the `table-management` capability's "Per-table tag ordering"): the ordered array of
tag names when an ordering is set, or (consistent with the service's `NON_NULL` serialization) an
absent/null member when the table has no ordering. The server SHALL
return the stored ordering verbatim — entries are NOT filtered against the tags present on the
returned fields. `tag_order` SHALL NOT be role-filtered: the same value is returned to admin and
non-admin callers, because ordering entries are display labels that may reference tags with no
columns at all, so no sensitive-column existence can be inferred from them.

#### Scenario: Schema carries the table's tag order

- **WHEN** a source table has a tag ordering set and `GET /v1/queries/entities/schema/{source}` is
  called
- **THEN** the response carries `tag_order` with exactly that ordered list of tag names

#### Scenario: Schema without a tag order omits tag_order

- **WHEN** `GET /v1/queries/entities/schema/{source}` is called for a source with no tag ordering
  set
- **THEN** the response omits the `tag_order` member (absent, read as null by consumers)

#### Scenario: Tag order is returned verbatim even when tags have no fields

- **WHEN** a source's tag ordering includes a tag name that no returned field carries (e.g. the
  ordering was set before a retag, or the caller is a non-admin for whom all fields of that tag
  are sensitive-hidden)
- **THEN** the response still carries the full stored ordering, unfiltered

### Requirement: Tag-ordered field sorting

When the source table has a tag ordering set, the system SHALL return the base-schema `fields`
array already sorted by tag group: fields whose `tag` appears in `tag_order` come first, grouped
in `tag_order` list order; fields whose `tag` is not listed come next, their groups ordered
alphabetically by tag name; untagged fields (null `tag`) come last. Within each group — including
the untagged group — fields SHALL be sorted alphabetically by their flat `name` (bare or
table-qualified). The sort SHALL be applied after role-based sensitive-field filtering and SHALL
only change field order, never field content or presence.

When the source table has no tag ordering set (`tag_order` is `null`), the `fields` order SHALL
remain the pre-existing behavior — the source's columns in column-ordinal order followed by
enrichment fields — unchanged, so tables without an ordering behave exactly as before this
capability existed.

#### Scenario: Fields are grouped and sorted per the tag order

- **WHEN** a source has `tag_order: ["identifier", "cost"]` and columns tagged `cost`,
  `identifier`, and `token-usage` (unlisted), plus an untagged column, and
  `GET /v1/queries/entities/schema/{source}` is called
- **THEN** the response lists all `identifier` fields first, then all `cost` fields, then the
  `token-usage` fields, then the untagged fields — each group sorted alphabetically by flat
  field `name`

#### Scenario: Unlisted tag groups follow listed groups alphabetically

- **WHEN** a source's ordering lists none of `beta` and `alpha`, both of which appear as tags on
  its columns alongside listed tags, and the schema is fetched
- **THEN** the `alpha` group precedes the `beta` group, and both come after every listed-tag
  group and before the untagged fields

#### Scenario: New tag from churn lands after ordered groups without breaking

- **WHEN** a column is added with (or retagged to) a brand-new tag not present in the table's
  `tag_order`, and the schema is fetched
- **THEN** the new tag's fields appear grouped after the listed-tag groups (alphabetically among
  unlisted tags) and before untagged fields, with no error and no change to the stored ordering

#### Scenario: Without an ordering the field order is unchanged

- **WHEN** `GET /v1/queries/entities/schema/{source}` is called for a source with no tag ordering
- **THEN** the `fields` array is in the pre-existing column-ordinal order (enrichment fields
  appended), byte-for-byte as before this capability
