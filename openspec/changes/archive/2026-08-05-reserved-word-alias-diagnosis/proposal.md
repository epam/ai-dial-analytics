## Why

This statement —

```sql
SELECT chat_id, count(*) AS rows FROM conversations GROUP BY chat_id ORDER BY rows DESC LIMIT 5
```

— is rejected with `SQL parse error: Incorrect syntax near the keyword 'AS' at line 1, column 26.` The
offender is **`rows` at column 29**: a reserved word (the window-frame keyword) used as an unquoted alias.
The message accuses the wrong token, so a caller reading it inspects an `AS` that is perfectly correct.

The mechanism is Calcite's, not a defect in ADAS. The generated Babel parser reformats a keyword-adjacent
parse failure around `currentToken.next`, and JavaCC's optional-alias lookahead (`[AS] <IDENTIFIER>`)
fails *before* consuming `AS` — so the token the parser stops on is `AS`, not `rows`. This is residue of
the parse-error-hygiene change rather than a regression of it: that change trims on Calcite's
`Was expecting one of:` marker, this message carries no marker, and unrecognised shapes are deliberately
passed through un-improved rather than mangled.

A caller cannot reason their way out of it either, because the reserved set is not intuitive: `first`,
`last`, `turns`, `tokens`, `spend` and `conversations` all parse fine under BABEL, while `rows` does not.

## What Changes

- When a parse failure stops on a keyword where an identifier was legal, the message SHALL additionally
  name the **reserved word** actually responsible, with its position, and state both fixes: rename it, or
  quote it.
- The hint is **appended** to Calcite's diagnosis rather than replacing it. Calcite is right about where
  the parser gave up; the hint says why. If the hint's identification is ever wrong, the caller still has
  the original message intact.
- Everything needed is public Calcite API — the failure's position, its token images, its expected-token
  sequences, `SqlParserUtil.allowsIdentifier` (Calcite's own "an identifier was legal here" test, the same
  one its reformatting uses), and the Babel grammar's own reserved-word set from the parser metadata. No
  message parsing, no hand-maintained keyword list.
- One front end serves `POST /v1/queries/execute-sql`, `POST /v1/queries/translate-sql` and Flight SQL, so
  this lands once for all of them.

Unchanged: the HTTP status, the stable `error` code, which statements are rejected, and the existing
expectation-set trimming.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sql-query-translation`: "A parse failure reports its diagnosis, not the grammar" is the requirement
  the parse-error-hygiene change created and the right home for this. It gains the reserved-word case.

## Impact

- `service/query/sql/SqlQueryFrontEnd` — a shared parse-error helper used by both existing catch sites
  (`plan()` and `screen()`), plus a lazily-built reserved-word set from the Babel parser's metadata.
- Tests in `SqlQueryFrontEndTest`.
- No API shape change, no configuration, no dependency change, no change to accepted SQL.
