## ADDED Requirements

### Requirement: A relative window lives in the body, and `time` stays uninterpreted

A saved query's body MAY express its own window relative to execution, by calling the clock and offset
functions the query surfaces accept (see [structured-query](../structured-query/spec.md), "Relative
time expressions"). Such a body SHALL be validated, stored and returned exactly as any other body is:
it passes the same translation its execute endpoint would run, and the stored text SHALL be returned
unchanged. This holds for both kinds — a `query` body carrying a `date_sub`/`now` expression and a
`sql` body carrying `now() - INTERVAL 2 HOUR` are the same case — because a saved `query` body's time
filter is baked-in literals for exactly the reason a saved `sql` body's is, and neither surface can be
the one that gets this.

The system SHALL NOT resolve such an expression at save time or at read time. It SHALL NOT rewrite the
body into absolute instants, SHALL NOT record the instant the body would have evaluated to, and SHALL
NOT derive or update `time` from the body. A saved query is stored as authored intent; folding the
clock into an instant at save time would destroy the one property the author was reaching for.

The `time` member is unchanged by this and gains no new mode: it remains the caller's time-picker
state, whose relative period this system does not interpret (see "Time intent validation"). What
changes is only that a body need no longer disagree with it — a caller may now write a body whose
window moves the way `time` says it should, and the two are consistent without the service mediating
between them. A body carrying a relative expression and a `time` of `null` SHALL be accepted, as SHALL
one carrying an absolute `time` alongside a relative body: this capability draws no coherence rule
between the two, because `time` describes an editor widget and the body describes the query, and
inventing agreement between them would mean interpreting the period after all.

#### Scenario: A relative SQL body round-trips unchanged

- **WHEN** a caller creates a `sql`-kind saved query whose SQL is
  `SELECT count(*) AS c FROM dial_usage_log WHERE request_time >= now() - INTERVAL 2 HOUR`
- **THEN** the response is `201`, `source` is `["dial_usage_log"]`, and reading it back returns the
  SQL byte-for-byte as authored — no instant substituted for the clock

#### Scenario: A relative structured body round-trips unchanged

- **WHEN** a caller creates a `query`-kind saved query whose filter compares `request_time` against
  `date_sub('hour', 2, now())`
- **THEN** the response is `201` and the stored body is returned unchanged

#### Scenario: The stored relative body is executable as stored

- **WHEN** a caller reads either saved query above and posts its stored body to its execute endpoint
  with no substitution step
- **THEN** the query surface accepts it and the window it covers is measured from that execution

#### Scenario: A relative body does not populate or alter `time`

- **WHEN** a caller creates a saved query with a relative body and no `time`
- **THEN** the stored `time` is absent, and the service has neither derived one from the body nor
  recorded the instant the body would have resolved to
