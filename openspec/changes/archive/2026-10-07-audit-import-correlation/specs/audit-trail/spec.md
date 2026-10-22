## ADDED Requirements

### Requirement: Activities carry the import that produced them

Every activity of a revision written while a catalog import (`POST /v1/catalog/import`) applies a bundle SHALL carry an
`importId` equal to the `import_id` that import returns in its `ImportResult`. Every other activity SHALL carry no `importId`.
Presence of the field is what marks an activity as part of an import; its value is what correlates the activities of one
import.

The tag SHALL follow the revision, not the resource type: it covers every activity of such a revision — the `Table` create, the
schema's `Table` update and `TableColumn` creates, each `Pipeline` create, every compensation delete, and any dependent removal a
compensation delete records. It SHALL NOT replace or alter the parent links of "Child activities link to their parent": an
imported `TableColumn` activity still points at its own `Table` activity, and no activity is created to stand for the import
itself.

A revision committed by any other request, or by background work, SHALL NOT carry an `importId`, even while an import is
running at the same time. An import preview writes no revision and therefore tags nothing. Activities recorded before this
field existed SHALL carry no `importId`.

The feed SHALL return `importId` on each activity that has one, from both the listing and single-activity retrieval, and SHALL
accept `importId` as an allow-listed filter and sort column under the same rules as the parent activity identifier: `eq`, `ne`
and `in` apply, the ordering operators and `co`/`nc` are rejected with `invalid_filter_value`, a value that is not an
identifier is rejected with `invalid_filter_value`, and `ne` matches activities that carry no `importId`.

#### Scenario: A completed import is listed by its id

- **WHEN** an import of a bundle with one source table and one pipeline returns `outcome` `completed` and `import_id` `X`, and
  the feed is then requested filtering `importId` `eq` `X`
- **THEN** the result holds the `Table` create, the `Table` update and every `TableColumn` create of the schema, and the
  `Pipeline` create, each carrying `importId` `X`, and nothing else

#### Scenario: Compensation deletes carry the import id

- **WHEN** an import creates its first table and then fails on its second, and the first table is rolled back
- **THEN** the `Delete` activity of the rolled-back table carries the same `importId` as its `Create` activity

#### Scenario: Parent links are unchanged under an import

- **WHEN** an import defines a table's schema
- **THEN** each resulting `TableColumn` activity carries both the import's `importId` and, as its parent, the `Table` activity of
  the same revision

#### Scenario: A hand-made change carries no import id

- **WHEN** a table is created through `POST /v1/tables` while an import is running on another request
- **THEN** that table's activities carry no `importId`

#### Scenario: Activities outside any import are found with ne

- **WHEN** the feed is requested filtering `importId` `ne` an import's id
- **THEN** activities that carry no `importId` are returned, alongside those of other imports

#### Scenario: Ordering an import id is rejected

- **WHEN** the feed is requested filtering `importId` with `lt`
- **THEN** the request is rejected with HTTP 400 and the error code `invalid_filter_value`
