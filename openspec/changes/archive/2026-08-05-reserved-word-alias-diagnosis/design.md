## Context

`SqlQueryFrontEnd` catches `SqlParseException` in two places — `plan()` and the `screen()` pre-pass — and
both do `throw new SqlTranslationException("SQL parse error: " + rootMessage(e), e)`. `rootMessage`
unwraps to the deepest cause and runs `trimExpectations`, which cuts on Calcite's
`Was expecting one of:` marker and returns the text unchanged when the marker is absent.

The reserved-word-alias message has no marker, so it passes through whole. Reading the generated parser
(`SqlBabelParserImpl.convertException`, calcite-babel 1.42.0) explains why it names the wrong token:

```java
final Token token = pex.currentToken.next;
if (!token.image.equals("1")
    && getMetadata().isKeyword(token.image)
    && SqlParserUtil.allowsIdentifier(tokenImage, expectedTokenSequences)) {
    // "Incorrect syntax near the keyword '<token>' at line L, column C."
}
```

`currentToken.next` is the first token the parser could not consume. For `count(*) AS rows`, JavaCC's
optional-alias production `[ [AS] <IDENTIFIER> ]` needs two tokens of lookahead; `rows` is not an
`<IDENTIFIER>`, so the parser never enters the branch and fails at `AS` instead. Calcite's reformatting is
therefore accurate about where it stopped and blind to why.

Usefully, the same two conditions Calcite tests — the stopping token is a keyword, and an identifier was
legal there — are exactly the signature of "a reserved word was written where an identifier belonged",
and both inputs survive onto the `SqlParseException` (`getTokenImages()`, `getExpectedTokenSequences()`,
`getPos()`), all public.

## Goals / Non-Goals

**Goals:**

- Name the word that is actually wrong, and both ways to fix it.
- Derive "reserved" from the Babel grammar itself, not from a list someone maintains.
- Leave every other parse failure byte-identical.

**Non-Goals:**

- Accepting reserved words as aliases. The grammar's reserved set is Calcite's; this change explains the
  rejection, it does not relax it.
- Re-parsing or repairing the statement.
- Touching validation-failure messages, the expectation-set trimming, the status code, or the `error`
  code.

## Decisions

**Append, don't replace.** The hint is a second sentence after Calcite's diagnosis. Replacing the
diagnosis would discard the only thing that is definitely true (where parsing stopped) in favour of an
inference. Appending means a wrong inference costs a redundant sentence rather than a misleading message —
which matters because the identification is positional, and SQL comments or unusual whitespace could in
principle put the scan on a different token than the parser meant.

**Detect structurally, not by matching the message text.** `e.getPos()` gives the stopping token's
position; `SqlParserUtil.allowsIdentifier(e.getTokenImages(), e.getExpectedTokenSequences())` gives
Calcite's own "an identifier was legal here" verdict. Matching on
`"Incorrect syntax near the keyword"` would bind us to a string Calcite is free to reword — the same
fragility `trimExpectations`' javadoc already argues against, and the reason it anchors on a marker rather
than a line count.

**Read the reserved set from the parser's metadata.** `SqlBabelParserImpl.FACTORY.getParser(reader)`
returns a `SqlAbstractParserImpl`, whose public `getMetadata()` exposes `isReservedWord(String)` over the
Babel grammar's actual token set. The generated parser caches its `MetadataImpl` in a static, so the cost
is paid once for the process. The alternatives are both worse:
`SqlAbstractParserImpl.getSql92ReservedWords()` is the SQL:92 set, not Babel's, and a hand-written list
would silently diverge on the next Calcite bump. Use `isReservedWord`, not `isKeyword` — the latter
includes non-reserved keywords, which *are* legal as identifiers and would produce a hint for a word
that is not the problem.

**Consider two candidates: the stopping token and the word after it.** The alias-with-`AS` case reports
`AS` and the offender follows it; the bare-alias case (`count(*) rows`) stops on `rows` itself, as does a
reserved word used as a column reference (`SELECT rows FROM t`). Checking both covers all three with one
rule. The scan reads the raw SQL forward from the end of the reported token — the statement text is
already in hand at both catch sites.

**One helper, both catch sites.** `screen()` parses the statement first and will surface this failure
before `plan()` does; a fix applied to only one of them would appear to work in tests that call `plan`
and not for real requests. Extracting `parseError(String sql, SqlParseException e)` makes that structural
rather than remembered.

**Put the JavaCC explanation in the javadoc.** The naive reading of this code is "Calcite is buggy, it
names the wrong token". It is not; the lookahead behaviour is the reason, and without it recorded the
next person to touch this either reverts it or re-derives it.

## Risks / Trade-offs

- **The positional scan could name the wrong word** (comments between tokens, a quoted identifier
  containing spaces) → mitigated by appending rather than replacing, and by requiring the candidate to be
  in the reserved set before saying anything. A miss produces silence, not a wrong hint.
- **Building the parser metadata has a cost** → paid once, statically cached in the generated parser, and
  only on the first parse failure since the set is built lazily.
- **Calcite could change `Metadata` or `allowsIdentifier`** → both are public API and
  `allowsIdentifier` is used by the generated parser itself, so it moves with the grammar rather than
  against it. A Calcite bump that changed them would fail at compile time, not silently.
- **The hint could mislead on a genuine syntax error that happens to sit near a reserved word** → the
  `allowsIdentifier` gate is what keeps it narrow: it fires only where the grammar would have accepted an
  identifier, which is the case the hint describes.
