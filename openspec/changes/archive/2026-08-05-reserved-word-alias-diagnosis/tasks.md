## 1. Reserved-word vocabulary

- [x] 1.1 In `SqlQueryFrontEnd`, add a lazily-initialised holder for the Babel grammar's reserved words:
      `SqlBabelParserImpl.FACTORY.getParser(new StringReader("")).getMetadata()`, queried with
      `isReservedWord` (uppercased candidate). Document why `isReservedWord` and not `isKeyword` — a
      non-reserved keyword is legal as an identifier and would produce a hint for a word that parses fine.
- [x] 1.2 Document why the set is read from the parser rather than listed: the Babel reserved set is not
      intuitive (`rows` is reserved; `first`, `last`, `turns`, `tokens`, `spend`, `conversations` are not)
      and a hand-kept list would drift on a Calcite bump.

## 2. Parse-error helper

- [x] 2.1 Extract `parseError(String sql, SqlParseException e)` returning the `SqlTranslationException`,
      and route both existing catch sites through it — `plan()` (`:101`) and `screen()` (`:136`). `screen`
      parses first, so a fix applied to only one site would look correct in tests and not for real requests.
- [x] 2.2 Detect the case structurally: `e.getPos()` for the stopping token's position, and
      `SqlParserUtil.allowsIdentifier(e.getTokenImages(), e.getExpectedTokenSequences())` for "an
      identifier was legal here" — Calcite's own test, the same one its reformatting uses. Do not match on
      the message text; guard against null token images / expected sequences.
- [x] 2.3 Resolve candidates from the raw SQL: the reported token itself, and the next word token after
      it. Convert `SqlParserPos`'s line/column to a character offset, skip whitespace, read
      `[A-Za-z_][A-Za-z0-9_$]*`. Two candidates cover `AS rows`, bare `count(*) rows`, and
      `SELECT rows FROM t`.
- [x] 2.4 When a candidate is reserved, **append** a sentence naming the word, its line and column, and
      both fixes (rename, or quote as `"word"`). Never replace Calcite's diagnosis; when no candidate is
      reserved, return exactly today's message.
- [x] 2.5 Javadoc the JavaCC lookahead reason on the helper: `[AS] <IDENTIFIER>` fails before consuming
      `AS`, so `currentToken.next` is `AS` and Calcite's reformatting names it. Without this recorded, the
      next reader reverts the code or re-derives it.

## 3. Spec

- [x] 3.1 Apply the `sql-query-translation` delta: "A parse failure reports its diagnosis, not the
      grammar" gains the reserved-word paragraph, the append-not-substitute rule, and three new scenarios.

## 4. Tests and verification

- [x] 4.1 `SELECT chat_id, count(*) AS rows FROM conversations GROUP BY chat_id ORDER BY rows DESC LIMIT 5`
      — the message names `rows` at line 1 column 29 and states both fixes.
- [x] 4.2 `AS "rows"` and `AS row_count` both parse and execute.
- [x] 4.3 Bare alias `count(*) rows` is also named (the parser stops on `rows` itself here, not on `AS`).
- [x] 4.4 `first`, `last`, `turns`, `tokens`, `spend`, `conversations` still parse as unquoted aliases — no
      hint, no rejection.
- [x] 4.5 An ordinary syntax error with no reserved word involved produces exactly the message it does
      today, and still carries no expectation set.
- [x] 4.6 The HTTP status and stable `error` code are unchanged for every case above.
- [x] 4.7 `./gradlew clean build` (Docker required for the integration tests)
      is green, and `openspec validate reserved-word-alias-diagnosis` passes.
- [x] 4.8 Smoke against `./gradlew bootRun`: `POST /v1/queries/execute-sql` with the `AS rows` statement
      returns the improved message through `ErrorView`.
