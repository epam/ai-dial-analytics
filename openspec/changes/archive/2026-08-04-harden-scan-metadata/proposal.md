## Why

`expose-scan-metadata` made `identity_column`/`version_column` caller-declarable, then in a follow-up
commit made them **required** for every source, on the argument that this makes "every source created
through the API scannable by construction". Validation stayed membership-only, so it does not — and a
required field that cannot be verified gets filled with whatever passes:

- **The identity half cannot always be declared truthfully.** A rollup's row identity is its whole
  `ordering_key`. The canonical rollup in `aggregate-pipeline` is keyed `(deployment, day)`, so under the
  requirement it declares `identity_column: "deployment"` — not unique per row. That is not cosmetic: one
  refresh stamps a single `now64(3)` into `_updated_at` for every row it writes (`AggregateCompiler`), so
  rows sharing `deployment` have identical cursor tuples, and `seekPast` is a strict tuple `>` — once a page
  boundary lands inside such a group the rest are **silently skipped**. The MR's own test helper defaulted
  the identity to the first ordering-key column, which is exactly how that declaration got into the suite.
- **The version half could be unusable.** `CursorPredicates` renders the cursor as
  `toUnixTimestamp64Milli(version)` against `now64(3)`, so a `string`, `long` or `date` version column
  passed define-time validation and then threw inside ClickHouse — at `POST /v1/rules/{id}/scan`, at group
  assembly, at Class-1 materialize, and at `AggregateRefreshService`. A **nullable** member is worse than an
  error: `NULL` fails the safety-lag and the tuple compare, so those rows are absent from every page with no
  diagnostic. A **sensitive** member makes the source's rules operable only by a caller entitled to
  sensitive columns (`ScanService` guards both cursor columns), and puts the identity value inside the page
  cursor.

The requirement's own design (`expose-scan-metadata/design.md`, D3) parked these checks for "whatever change
decides to make the pair required". That change shipped without them.

The same commit also let an **enrichment** declare a `version_column`. Nothing reads it — `RuleService`
resolves the *source's* column, `ScanSourceMetadata` is only ever called with a rule's source, and an
aggregate input must be a `SOURCE` — so it was inert configuration that was nevertheless persisted,
returned, and frozen against a drop.

## What Changes

- **The pair is optional again for a source.** A source declaring neither member materializes normally and
  is not scannable; the scan answers the 400 it already had, naming the missing configuration. That keeps the
  declaration a claim a caller makes when it holds, instead of a field to be satisfied — and it is the only
  answer available for a multi-column-grain rollup, which has no truthful single-column identity.
- **A declared member must be usable by the scan.** Each must be non-nullable and not `sensitive`; the
  version column must be declared `timestamp`, or — on an `upsert_by_key` source — may name the
  ReplacingMergeTree version `_updated_at`. All four properties are visible in the same request, so each is
  a 422 at define time instead of a runtime failure. Naming `_updated_at` on an `append` source now says why
  (the engine version exists only on a ReplacingMergeTree) rather than "not a declared column".
- **An enrichment declares neither member** (422 for either), as it did before the follow-up commit.
- **The claim is narrowed to what is enforced.** No spec text says a source is scannable "by construction":
  what the API checks is that a declared pair is *shaped* for the scan. Uniqueness of the identity and
  monotonicity of the version remain the declaring caller's promise.
- **No test helper invents the pair.** `IntegrationTables` declares none by default; a fixture that wants
  one passes it explicitly.

Explicitly unchanged: no migration (both catalog columns stay nullable); the pair stays readable on
`GET /v1/tables[/{name}]`; the freeze against dropping or renaming a named column stays; the scan,
auto-join, rule wire and grain contract are untouched.

## Capabilities

### New Capabilities

<!-- None. -->

### Modified Capabilities

- `table-management`: the pair is optional for a source, with physical rules on a declared member, and
  rejected for an enrichment.
- `enrichment-runtime`: what the table API verifies, why it is optional, and why a false pair is worse than
  none.
- `aggregate-pipeline`: the input's scannability requirement (previously enforced in code only), and when a
  rollup target may declare the pair at all.

## Impact

**APIs.** `POST /v1/tables/{name}/schema` no longer requires the pair (reverting a breaking change that had
not yet shipped to the default branch), and now rejects a non-`timestamp`, nullable or sensitive scan column, and
an enrichment's `version_column`. No new members, no new response fields.

**Code.** New `service/table/ScanMetadataRules` holds both halves of the contract (which kind may declare
the pair, and what a declared name must be); `TableSchemaCreationService` and `CatalogSchemaWriter` delegate
to it. `ScanSourceMetadata`'s javadoc is corrected — it still described the pair as operator-only, which
`expose-scan-metadata` had already reversed.

**Database.** None.

**Callers.** Strictly fewer rejections than the required-pair contract, plus four new ones for declarations
that could not have worked. A caller that wants a scannable source declares a non-nullable `timestamp`
column to point at.
