## Context

See proposal.md for the motivation. The request-delta encoder links a chat request to a strictly shorter prefix of
its own history, which is what makes "a request is never its own parent" hold structurally: a rebuild reads any
row carrying the parent's path, and that row can never be the child. Equality has no such asymmetry, so the rule
that keeps a copy from pointing at itself has to be stated.

The payload table is a `ReplacingMergeTree` keyed by `event_id`: a re-ingested event replaces its row. The chat
parent lookup reads without `FINAL` through the hash skip index, so it can return a replaced version.

A chat child whose parent is gone still holds its own messages. A copy holds nothing, so an orphaned copy loses its
whole body until its event is re-ingested. That raises the bar for when a copy may be written.

## Goals / Non-Goals

**Goals:**

- Store an exactly repeated large body that is not JSON once per project, user and UTC day.
- Under one configuration and release, never orphan a copy: whatever is re-ingested and in whatever order, a
  current row holds the body. The cases outside that are named in the spec as an operator's to avoid.
- Leave the chat chain logic, the read statements and the payload table schema as they are.

**Non-Goals:**

- Lenient parsing of truncated JSON.
- Deduplicating small bodies, JSON bodies, responses, or rows already stored.
- A setting for the size bound.

## Decisions

**Only the row holding the body carries the hash.** A carrier has `request_hash` = H; a copy has an all-zero
`request_hash` and `request_parent_hash` = H. A rebuild that looks H up therefore always finds a body.
Alternative rejected: copies carrying H too, as identical chat retries carry their path. The lookup could then
return a copy for a copy.

**A copy needs a carrier with a strictly earlier `request_time`.** The earliest request with a body is then a
carrier in every version of its row, so re-ingesting it, alone or with its copies, in any order, never turns it
into a copy. Ties stay carriers. Alternatives rejected: "any other row carries H" (two carriers written by racing
instances can each become a copy of the other on re-ingest, and the body is lost); ordering by `event_id` (not
time-ordered, so half the repeats would arrive "before" their carrier and stay whole).

**Times are compared as epoch milliseconds, never as text.** The lookup returns
`toUnixTimestamp64Milli(request_time)` and the encoder parses the row's own `request_time` to an instant, cut to
milliseconds as the `DateTime64(3)` column stores it. Text comparison was rejected: any difference between the
server's rendering and the mapping's would order a re-ingested carrier after its own stored row and make it a copy
of itself. A row whose `request_time` does not parse is not eligible; the existing rule already keeps out a time
with a non-zero offset.

**Stored carriers are looked up deduplicated, in a statement of their own.** The chat lookup stays as it is: any
row version carrying a path yields the same history. A carrier is different, since a replaced version that still
shows the hash holds a body the table is about to lose. The carrier statement therefore reads `FINAL`,
deduplicated inside the partition, and returns the hash, `event_id` and `request_time` of each current row
version carrying one of the hashes; a hash normally has one carrier, since later equal bodies are copies.
It still goes through the hash skip index: with `use_skip_indexes_if_final_exact_mode` on, the server widens the
index's selection to every granule that shares a primary-key range with it, so the current version of an event is
read even when only its replaced version matches the index. A probe on the supported server versions confirmed
both halves: with the mode on, a re-ingested event's replaced version is not returned; with it off, it is. The
mode is on by default; the statement sets it explicitly, with `use_skip_indexes_if_final`, so a profile cannot
turn the guarantee off. Alternative rejected: confirming each found carrier by `event_id` in a second read, which
the exact mode makes redundant. What remains is the window between the lookup and the insert, and replica lag;
the spec names both.

**A stored row whose event is in the flush never counts.** The encoder drops, from what the lookup returned, every
carrier whose `event_id` belongs to any row of the batch, eligible or not. The insert replaces those rows, so what
they carry now says nothing about what the table will hold; the row's version in the flush decides instead. This
covers a carrier whose re-ingested version is no longer eligible or hashes differently, and it is a second guard
against a carrier becoming a copy of itself, independent of the time comparison. Alternative rejected: excluding
the ids in the statement. Every chunk of the lookup would carry the whole list, and lookup parameters are chunked
precisely to keep the request small.

**The carrier's time comes from the flush or the table, not the path cache.** The cache holds paths without their
times and is never checked against the table. Cost: one more lookup statement on a flush that carries an eligible
body. Such flushes are few, because of the size bound and because only non-JSON bodies qualify. The body hashes
of one flush are bounded by the same number as its chat candidates; a row past the bound is a carrier.

**Copies are counted on the encoder's existing per-outcome row counter,** under an outcome of their own, so the
share of rows stored as copies is visible beside roots and children. The counter is not part of the capability's
spec and this change does not add it there.

**A separate hash domain.** The body hash uses its own seed. A system element is hashed as `"key":value`, which
is not JSON, so a non-JSON body with that text would otherwise share a chat request's first prefix hash and be
taken as its root.

**Only a body that is not JSON is eligible.** A copy's `request_body` is empty, so a JSON body stored as a copy
would lose the top-level members readers extract from it; a body that is not JSON has none to lose. The encoder's
parse already fails on such a body, but it also fails on valid JSON that names a member twice or exceeds the
parser's default read limits. A body over the size bound that fails it is therefore read once more, as a token
stream with duplicate detection off and the read limits lifted, to the end of the input, and is eligible only when
that fails too: a valid value followed by anything but whitespace is not JSON.
Alternative rejected: every large non-chat body, which would have covered large MCP and embeddings requests at
the cost of their readable members.

**A fixed bound of 65,536 UTF-16 code units,** which is the length of the body string the encoder holds, so the
check costs nothing. A copy saves its body's length and costs a lookup candidate; below tens of KiB the saving
does not pay for it.
Alternative rejected: a config key. One more knob whose wrong value silently disables the feature or floods the
lookup, for a threshold that does not depend on the deployment.

**The empty string marks a copy.** A delta body is always a JSON object and an eligible body is never empty, so
an empty stored body on a row with a parent is unambiguous. The chain walk needs no change: it resolves H to the
carrier's link and reads its body. The assembly step gains one case, before any JSON is read: an empty stored body
whose chain is exactly one row with no parent returns that row's body. An empty body with any other chain stays
`invalid`. Alternative rejected: a marker in `request_cache_control`, which would make the copy look like a chat
row with markers.

**Splitting a batch re-roots a copy like a chat child.** A link record is kept for a copy as for a chat child
linked within the flush, with the original body; re-rooting restores it and sets `request_hash` = H, which the
existing non-chat branch does not do.

**The catalog description of `request_body` is reworded by a new PostgreSQL migration.** The seeded description
says what a stored body is under `delta`; it has to name the empty body of a copy. Applied migrations are frozen,
so this is a new version, taken at implementation time as the next free one. Like the migration that wrote the
current text, it replaces the description only while it is still that text, so an operator's edit is kept.

## Risks / Trade-offs

- [A consumer reads `request_body` and gets an empty string] → `request_body_full` is the documented field for the
  whole body; the catalog description, `docs/heavy-columns.md` and the upgrade notes name the case.
- [A carrier re-ingested under another configuration or an earlier release loses or changes its hash and orphans
  its copies] → named in the spec with the operator rule: re-ingest whole UTC days under one configuration. The
  copy reads `partial`, never a wrong body.
- [Part-granular TTL drops a carrier's part before its copy's] → as for a chat parent, in the last day of a day's
  retention; the copy reads `partial`.
- [Hash collision returns another request's body] → 128 bits of SHA-256 within one project, user and day, the
  same exposure chat chains accept.
- [One more read per flush that carries an eligible body] → bounded by the lookup's execution-time limit; a
  failure makes carriers.
- [A server profile that turns the exact mode off] → the statement sets it itself.
- [The read side's chain lookup reads `FINAL` through the same index without setting the mode] → it relies on the
  default and re-resolves a stale link; left as it is, outside this change.

## Migration Plan

One PostgreSQL migration rewording a seeded description; no ClickHouse migration. Rows written before the change
stay as they are and are never carriers. During a rolling upgrade an instance of the previous release stores
eligible bodies whole and unhashed, which costs storage only; it must not re-ingest a day that already holds
copies. Rollback: the previous release reads a copy as `partial`, so when copies exist, roll back to
`whole-with-path` on the new release instead.
