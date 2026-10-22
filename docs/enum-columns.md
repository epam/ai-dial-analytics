# Enum columns

A column may be declared **`type: "enum"`** with an `enum_values` list — a closed, ordered set of
string values. It materializes as a ClickHouse `Enum8`/`Enum16`: one or two bytes per row instead of
a variable-length string, compared as an integer, with the server itself refusing a value outside the
set at insert time.

```json
{
  "source_name": "status",
  "name": "status",
  "type": "enum",
  "enum_values": ["pending", "running", "failed"]
}
```

Why it exists: a `status`, `severity`, `span_kind` or `finish_reason` column holds one of a handful of
values, but stored as `String` it costs its bytes on every row and its domain is invisible. Nothing in
the catalog says which values are legal, so a UI building a filter has to `SELECT DISTINCT` to find
out, and a typo (`FAILED` where the convention is `failed`) is accepted silently and then quietly
matches nothing. Declaring the domain fixes both: it is published on the read and discovery surfaces,
and ClickHouse enforces it.

`enum_values` is the type parameter `enum` takes, the way `element_type` is the one `array` takes:
required if and only if the type is `enum`, and rejected on a column of any other type.

## Declared order is significant

Each value's 1-based position in `enum_values` becomes its numeric id in the physical type:

```sql
Enum8('pending' = 1, 'running' = 2, 'failed' = 3)
```

ClickHouse compares an enum by that id, so **the column sorts in declared order, not alphabetically**.
That is usually what you want, and it is the reason the list is stored exactly as declared and never
sorted:

```json
{ "type": "enum", "enum_values": ["low", "medium", "high"] }
```

Sorting ascending by this column returns `low`, `medium`, `high` — not `high`, `low`, `medium`.

## Rules

| Rule | |
|---|---|
| Values | 1–512, distinct after trimming, non-blank, at most 64 characters each; stored and materialized trimmed |
| Storage | `Enum8` up to 127 values, `Enum16` above that |
| Nullability | ordinary rules — source columns default non-nullable, enrichment columns nullable; `Nullable(Enum8(…))` is allowed (unlike `Array` and `Map`) |
| As a key | may be an `ordering_key` entry or an enrichment's `grain_key`; sorts in declared order. An `ordering_key`/`partition_by` column must be non-nullable, whatever its type — ClickHouse refuses a nullable sorting or partition key |
| Not allowed | as a TTL column or a source's `version_column` (both need `timestamp`), or as an `array` `element_type` |

A value may contain any characters, including quotes; they are escaped into the DDL.

## The domain is immutable

`enum_values` **cannot be changed once the column exists**. A `PATCH /v1/tables/{name}/schema`
`update` entry carrying `enum_values` is rejected with 422 rather than ignored.

Widening a ClickHouse enum is an `ALTER TABLE … MODIFY COLUMN` that rewrites the column and fails
outright if any stored row holds a value the new domain drops — not something to do implicitly behind
a metadata patch. Widen a domain the same way you change a column's type: **drop the column and add
it again**, or declare the column `string` if the set is going to move.

A `rename` keeps the domain (and the type) intact.

## Reading a column back

`enum_values` is returned wherever a column's shape is — `GET /v1/tables/{name}`, the list surface,
the revision snapshot, and the flat field on `GET /v1/queries/entities/schema/{name}`:

```json
{ "name": "status", "type": "enum", "enum_values": ["pending", "running", "failed"] }
```

That last one is the point: a consumer can build a value picker, or validate a filter, from discovery
alone.

## Querying an enum column

Values cross the wire as the strings they are — there is no enum literal in the DSL or in SQL. Write a
row with `"status": "running"`, and filter it with a plain string:

```json
{ "op": "eq", "args": [
    { "type": "field", "name": "status" },
    { "type": "value", "value_type": "string", "value": "running" }
]}
```

Equality, `in`, comparison, grouping and sorting all work. On the SQL surface the column is typed
`VARCHAR` and `information_schema` reports `CHARACTER VARYING`, so a BI client handles it as text and
needs no knowledge of the type at all.

**One limitation.** ClickHouse defines comparison over an enum but *not* the string functions, so
`starts_with`, `contains`, `lower`, `upper`, `trim`, `length`, `not_empty` and `split_string` are
refused on an enum field — the DSL rejects them during validation with a message naming the type,
rather than sending a query that fails at the server. The rule follows the operation rather than its spelling: the filter operators `co`, `nc`,
`ico` and `inc` are the same `LIKE`, and are refused the same way — as is a SQL `LIKE`/`ILIKE` over
an enum column on `execute-sql`, which is translated into those operators and validated with them. If
you need substring matching over the values, declare the column `string`.

## Enrichment declarations

A declaration output declared `string` binds to an `enum` target column. `enum` is **not** accepted as an
output variable's declared type: a declaration declares the value it produces, the column declares the domain
that value has to fall into, and the governance gate compares shapes rather than domains — so an
out-of-domain value surfaces when the materialization writes, not at the declaration gate.

See also [column tags](column-tags.md) and [heavy columns](heavy-columns.md) for the other per-column
catalog metadata.
