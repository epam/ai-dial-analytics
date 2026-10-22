## MODIFIED Requirements

### Requirement: Register, preview, enable lifecycle

A pipeline SHALL be registered `enabled: false` and SHALL NOT run until explicitly enabled through
`PATCH /v1/pipelines/{name}`. Only a `disabled` pipeline's `spec` SHALL be mutable; a spec edit SHALL be
re-validated in full, exactly as at registration. Editing an `enabled` pipeline's declaration SHALL be
rejected with HTTP 409 — a state conflict, like refreshing a disabled pipeline, not a 422: the declaration
sent may be perfectly valid and only the timing wrong, and reporting it as a validation failure would tell
the client to fix a spec that has nothing wrong with it.

A `disabled` pipeline SHALL NOT write to its target by any route — neither on a schedule nor through
`POST /v1/pipelines/{name}/refresh`, which SHALL be rejected with HTTP 409. Were the on-demand route to
write anyway, `enabled` would describe only the schedule while the gate it exists to enforce stayed open,
and a target could be filled from a declaration nobody had looked at.

A declaration change SHALL reset the pipeline's stored cursor, so the next run reads its input from the
beginning and recomputes every group key under the new declaration. Keeping the cursor would confine the
new declaration to windows not yet read, leaving every key materialized before the edit holding values the
*previous* declaration produced — indefinitely, and with nothing in the target recording it. Rows whose key
the new `group_by` no longer produces are NOT removed: recomputation can only replace keys it derives, so
rebuilding or replacing a re-grained target remains an operator action.

The system SHALL expose `POST /v1/pipelines/{name}/preview` — the **dry-run gate**. Preview SHALL NOT
write to the target and SHALL be permitted on a disabled pipeline, so the declaration can be checked
before it is trusted.

Preview SHALL return **two artefacts that answer different questions**, and the response SHALL make both
legible:

- **The refresh statement.** The compiled `INSERT … SELECT` a refresh would execute *now*, carrying the
  window a refresh would apply — the membership filter, the safety lag, and the seek past any stored
  cursor — omitting only the bounds measured from a probed window (its end tuple, and the dirty-bucket
  range). A statement rendered with no window at all would describe a recompute of the entire input,
  which is not what an enabled refresh runs, and is dangerous to copy out of a response.
- **The sample.** A bounded sample of what the declaration produces over the input **as a whole**,
  deliberately *not* window-restricted. The question a sample answers is "are these the right numbers",
  and a window obscures it: a caller cannot judge a declaration from whatever slice happens to remain
  unread. The sample SHALL be accompanied by its **own** rendered statement, so each half of the response
  carries its provenance.

The two SHALL NOT be presented as though one produced the other. Their divergence is total in the ordinary
case of a pipeline that has drained its input: the refresh statement then selects nothing, while the
sample still shows the declaration's full-input totals. Both are correct answers to their own question,
and a response that returns them unlabelled invites a reader to diff them and conclude the rollup is
broken. Preview exists to be trusted, so it SHALL be self-describing.

#### Scenario: A disabled pipeline cannot be refreshed on demand

- **WHEN** `POST /v1/pipelines/{name}/refresh` is called on a disabled pipeline
- **THEN** the request is rejected with HTTP 409 and the target is unchanged

#### Scenario: Re-declaring rebuilds the rollup

- **WHEN** a disabled pipeline's declaration is changed and the pipeline is enabled and refreshed
- **THEN** its stored cursor was cleared by the edit, and the keys materialized under the previous
  declaration are recomputed under the new one

`DELETE /v1/pipelines/{name}` SHALL remove the pipeline and leave the target table and its rows intact:
a rollup that stops being refreshed is stale, not invalid.

#### Scenario: A registered pipeline does not run until enabled

- **WHEN** a pipeline is registered and the scheduler tick fires
- **THEN** no statement is executed against the target while `enabled` is false

#### Scenario: Preview returns the statement and a sample without writing

- **WHEN** `POST /v1/pipelines/{name}/preview` is called on a disabled pipeline
- **THEN** the response carries the compiled statement and a bounded sample of rows, and the target
  row count is unchanged

#### Scenario: Preview reports the sample's own statement

- **WHEN** `POST /v1/pipelines/{name}/preview` returns a sample
- **THEN** the response also carries the statement that produced that sample, distinct from the refresh
  statement, so neither half of the response is unattributed SQL

#### Scenario: A drained pipeline's two halves disagree, and both are correct

- **WHEN** preview is called on an enabled pipeline whose cursor has reached the end of its input
- **THEN** the refresh statement carries a cursor seek that selects no rows, while the sample still
  reports the declaration's totals over the whole input, each labelled as what it is

#### Scenario: A spec edit is fully re-validated

- **WHEN** `PATCH /v1/pipelines/{name}` changes `group_by` so it no longer matches the target's
  `ordering_key`
- **THEN** the request is rejected with HTTP 422 and the stored spec is unchanged

#### Scenario: Editing an enabled pipeline's declaration is a conflict

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration for a pipeline that is `enabled`
- **THEN** the request is rejected with HTTP 409 and the stored declaration is unchanged

#### Scenario: Deleting a pipeline keeps the rollup

- **WHEN** `DELETE /v1/pipelines/{name}` is called
- **THEN** the pipeline is removed, and the target table and its rows still exist and remain queryable
