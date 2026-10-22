## ADDED Requirements

### Requirement: A parse failure reports its diagnosis, not the grammar

When SQL or a filter predicate fails to **parse**, the client-facing message SHALL carry the parser's
diagnosis — the offending token and its position — and SHALL NOT carry the parser's expectation set.

Calcite reports a parse failure as a short diagnosis followed by an enumeration of every token that would
have been legal at that position. The enumeration runs to dozens of lines of grammar. It is not
actionable: a caller who mistyped an operator does not need the list of all other operators, and the list
is long enough to displace the one line that actually locates the mistake. Returned through `ErrorView`,
it also makes the response unreadable wherever errors are surfaced as a single line.

The full parser output SHALL remain available in the service log at debug level. The requirement is that
verbosity move to where it costs nothing, not that it be discarded.

This applies wherever the shared front end parses caller input — ad-hoc SQL, translation requests,
enrichment rule filters, and aggregate pipeline filters — because all four inherit the same message. It
governs **parse** failures specifically; validation failures already report a single targeted sentence
naming the offending column or construct, and SHALL keep doing so.

The status code, the stable `error` code, and which statements are rejected SHALL be unchanged. Only the
message's shape is constrained.

#### Scenario: A parse failure names the token and position

- **WHEN** a caller submits SQL or a filter that does not parse
- **THEN** the message identifies the offending token and its position, and does not include the parser's
  list of expected tokens

#### Scenario: The full parser output is still recoverable

- **WHEN** a parse failure is returned to a caller
- **THEN** the parser's complete output, including the expectation set, is written to the service log at
  debug level

#### Scenario: Validation failures are unaffected

- **WHEN** a statement parses but fails validation — an unknown column, an unsupported construct, or a
  projection-only violation
- **THEN** the message continues to name the offending column or construct exactly as before

#### Scenario: The rejection itself is unchanged

- **WHEN** a parse failure is returned
- **THEN** its HTTP status and its stable `error` code are the same as before this requirement, so a
  client switching on the code sees no change
