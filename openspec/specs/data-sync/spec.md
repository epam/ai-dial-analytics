# data-sync Specification

## Purpose

Ingest DIAL usage logs from blob storage into ClickHouse on a daily schedule. The service
discovers the previous day's log files by a date-derived blob prefix, parses each gzip NDJSON
file, maps each record to columns via a configurable mapping, and writes the resulting rows into
the `dial_usage_log` table in batches using a dedicated writer credential —
keeping the read/discovery path read-only. Each fully ingested blob is recorded in a persisted ingest
ledger in ClickHouse, and every ingest path skips the blobs the ledger records, so a blob is normally
written once; the duplicates that remain (a blob re-ingested after a crash between its rows and its
ledger entry, a backfill that includes already-ingested blobs) are collapsed at the row level by the
table's `ReplacingMergeTree` engine, which collapses records sharing its sort key.
## Requirements
### Requirement: Scheduled daily ingestion

The service SHALL run a data-sync job automatically on a daily schedule driven by a configurable
cron expression. This scheduled run serves as a **reconciliation safety net** that complements the
near-real-time polling path: it lists the target prefix once a day, ingests the blobs the ingest ledger
does not record — those the poller missed (e.g. during downtime longer than the poller's restart lookback
window, or finalization beyond the poller's band) — and skips, and counts, the blobs it does record. It
records each blob it ingests as soon as that blob's rows are written. The job MUST run in-process and MUST
NOT be triggerable via an HTTP endpoint in this phase. The scheduler MUST be configured such that a new run
cannot start while a previous run is still in progress. A run that fails is not retried by a later scheduled
run, which targets its own day; a failed day is recovered by an operator backfill, which ingests only the
blobs the failed run did not finish.

#### Scenario: Job runs on schedule

- **WHEN** the configured cron time is reached and no sync is currently running
- **THEN** the service starts a reconciliation sync run without any external trigger

#### Scenario: Overlapping runs are prevented

- **WHEN** the cron time is reached while a previous sync run is still in progress
- **THEN** the service does not start a second concurrent run

#### Scenario: Reconciliation skips what the poller already ingested

- **WHEN** the daily run lists the target prefix and the ledger records some of its blobs
- **THEN** the run does not read or insert those blobs, counts them as skipped, and ingests only the
  remaining blobs

#### Scenario: Reconciliation re-ingests what the poller missed

- **WHEN** the daily run lists a blob the ledger does not record, for example one finalized beyond the
  poller's band or written while the service was down
- **THEN** the run ingests it and records it, so no blob is lost to a poller gap

#### Scenario: A failed run keeps what it wrote

- **WHEN** a reconciliation run fails after some of its writes succeeded
- **THEN** the blobs whose rows were all written are recorded and the rest are not

### Requirement: Near-real-time polling ingestion

The service SHALL ingest new log blobs in near-real-time by polling blob storage on a short,
configurable interval, using ONLY the provider-agnostic blob-listing abstraction and WITHOUT
depending on any cloud-provider-specific notification service (e.g. Azure Event Grid, AWS S3 event
notifications, GCS Pub/Sub) or any externally provisioned infrastructure. On each poll cycle the
service SHALL list the active date-derived prefix(es) — the current day, and within a configurable
post-midnight grace window also the previous day — and SHALL select for ingestion every blob whose
last-modified time is at or after a moving watermark less a configurable overlap, and that the ingest
ledger does not record.

To bound listing cost, the service SHALL by default restrict each listed prefix to a recent time band
derived from the blob key's leading (start) timestamp instead of listing the full day, and SHALL
offer a configurable fallback that lists the full active day prefix(es). Because the blob key orders
lexicographically by its embedded start time while the selection watermark tracks the blob's
last-modified (finalization) time — and finalization can lag the start time by the request/stream
duration — a bounded band SHALL extend its lower bound back from the cutoff (watermark less overlap)
by at least a configurable **finalization lag**, so that the band still lists every blob whose
last-modified time is at or after the cutoff. Because a band is a time slice while a prefix is bound
to one date directory, a band spanning a UTC date boundary SHALL be listed under every date directory
it spans, so that narrowing the listing never narrows what is discoverable within the requested range.
A blob whose finalization lags beyond the configured finalization lag MAY be missed by the poller and
SHALL remain recoverable by the scheduled reconciliation path. The selection of blobs from whatever
is listed SHALL be identical regardless of which listing mode is in effect.

Each selected blob
SHALL be ingested through the same parse → map → batch-insert pipeline used by the scheduled path,
and the watermark SHALL then advance to the greatest last-modified time observed. The service MUST
read each blob's last-modified time from the listing itself and MUST NOT require a per-blob metadata
fetch. The poller SHALL record each blob it ingested in the ledger as soon as that blob's rows are written —
normally at the cycle's final flush, earlier when a batch fills during the cycle. The service SHALL keep an
in-memory watermark (initialized on startup to the current time less a configurable lookback) and an
in-memory set of the blobs, within the overlap window, that it ingested or found recorded, and MUST NOT
require a persisted cursor: the ledger, not a cursor, is what stops a restart from re-ingesting the lookback
window, and where the ledger has no entry for a blob, correctness under overlap, last-modified ties, clock
skew, and restarts SHALL still rest on the target table's primary-key deduplication. A failure to ingest a
single blob (e.g. a corrupt
file) MUST NOT abort the poll cycle or hold back the watermark: the service SHALL count and skip that
blob, continue ingesting the remaining blobs, and still advance the watermark, so that one bad blob
cannot wedge the poller — the skipped blob is not recorded, remains eligible for retry within the overlap
window and is otherwise recovered by the reconciliation path. A failure to list blobs (e.g. the store is
unreachable) SHALL instead leave the watermark unchanged so the next cycle re-covers the window. The
poller MUST be controlled by an enable flag, MUST run in-process with no HTTP trigger, and MUST NOT
allow a new poll cycle to start while a previous one is still in progress.

#### Scenario: New blob within the window is ingested

- **WHEN** the poller is enabled and a not-yet-ingested log blob under the active prefix has a
  last-modified time at or after the watermark
- **THEN** the service ingests that blob's rows, records the blob in the ledger, and advances the
  watermark to that blob's last-modified time

#### Scenario: Blob older than the watermark is not ingested

- **WHEN** a blob under the active prefix has a last-modified time before the watermark less the
  configured overlap
- **THEN** the service does not ingest that blob

#### Scenario: Re-observed blob does not duplicate rows

- **WHEN** a blob that was ingested is observed again within the watermark overlap, on a clock-skew
  boundary, or after a restart re-scans the lookback window, and the ledger records it
- **THEN** the service does not read or insert it again; a blob skipped because the ledger records it is
  counted as skipped by the ledger, and one skipped because the in-memory set already holds it is not counted

#### Scenario: Re-observed blob without a ledger entry collapses in the table

- **WHEN** a blob that was ingested is observed again and the ledger has no entry for it, because the
  service stopped, or the ledger write failed, between the write of its rows and the entry
- **THEN** the blob is re-inserted at most as duplicate rows that the target table's `ReplacingMergeTree`
  engine collapses on merge, so no duplicate row survives

#### Scenario: A bad blob does not wedge the poller

- **WHEN** one blob in the active prefix fails to ingest (e.g. it is corrupt) while other newer blobs
  are present
- **THEN** the service counts and skips the failing blob without recording it, ingests the other blobs,
  and advances the watermark, so the failing blob does not block subsequent ingestion

#### Scenario: A listing failure holds the watermark

- **WHEN** a poll cycle cannot list the active prefix (e.g. the store is unreachable)
- **THEN** the service leaves the watermark unchanged so the next cycle re-covers the same window

#### Scenario: A failed write holds the ledger

- **WHEN** the cycle's final flush of rows to a target table fails
- **THEN** the cycle's blobs whose rows were not all written are not recorded in the ledger, the watermark
  holds, and the next cycle ingests them again

#### Scenario: Poller disabled

- **WHEN** the poller enable flag is not set
- **THEN** the service does not poll for new blobs and ingestion proceeds only via the scheduled
  reconciliation path

#### Scenario: Bounded listing discovers a blob finalized within the finalization lag

- **WHEN** bounded (band) listing is in effect (the default) and a freshly written blob has a
  last-modified time at or after the cutoff but an embedded start time older than the cutoff by no more
  than the configured finalization lag
- **THEN** the narrowed prefix band still lists that blob and the service ingests it

#### Scenario: A blob finalizing beyond the finalization lag is left to reconciliation

- **WHEN** bounded (band) listing is in effect and a blob's last-modified time is at or after the
  cutoff but its embedded start time precedes the cutoff by more than the configured finalization lag
- **THEN** the narrowed poll cycle MAY not list that blob, and the blob is recovered by the scheduled
  reconciliation path

#### Scenario: Full-day fallback lists the full day

- **WHEN** the operator selects the full-day (`day`) listing fallback
- **THEN** the poller lists the full active day prefix(es) and selects blobs exactly as it did before
  bounded listing existed

### Requirement: Workload-identity credentials for an S3 blob source

The service SHALL authenticate to an `aws-s3` blob source with **workload-identity** credentials whenever
the configured blob identity and blob credential are **both blank**, and with the configured access-key
pair whenever both are present — the same rule the `azureblob` provider already applies to decide
shared-key versus managed identity.

The trigger SHALL be the blank pair itself, not a new property. A deployment that already supplies an S3
access key pair SHALL be unaffected, because a present pair continues to win; a deployment that supplies
neither is today authenticated with inert placeholder values that no bucket accepts, so the blank pair
carries no meaning that this requirement takes away. Confining the placeholder defaults to the providers
that still need them (`filesystem` in tests) is part of the same rule: an `aws-s3` source SHALL NOT be
handed a placeholder credential, because the resulting `403` describes neither what is missing nor where.

Selecting workload identity SHALL require nothing of the deployment beyond the blob source's own settings
and what the cluster injects into the pod — no key material in configuration, no secret to rotate, and no
provider-specific override to set.

#### Scenario: A blank credential pair on an S3 source selects workload identity

- **WHEN** the blob provider is `aws-s3` and both the blob identity and the blob credential are unset or blank
- **THEN** the source is authenticated with workload-identity credentials obtained at runtime, and no
  operator-supplied key material is required

#### Scenario: A configured key pair still wins

- **WHEN** the blob provider is `aws-s3` and both the blob identity and the blob credential are set
- **THEN** that access-key pair is used exactly as before, and no workload-identity resolution is attempted

#### Scenario: Other providers keep their existing credential handling

- **WHEN** the blob provider is anything other than `aws-s3` or `azureblob`
- **THEN** its credential handling is unchanged, including the placeholder defaults used when no pair is
  configured

### Requirement: S3 workload identity resolves from the projected web-identity token only

The service SHALL resolve S3 workload-identity credentials **exclusively** by exchanging the projected
web-identity token for a role session, reading the role ARN and the token-file path the cluster injects
into the pod's environment, and SHALL NOT fall back to any other credential source — not the instance
metadata service, not an instance profile or node role, not environment-variable keys, and not a shared
credentials profile.

The absence of a fallback chain is the point of the requirement, not an omission. A chain that reaches the
instance metadata service turns a pod whose role association was removed or mis-annotated into a pod that
**still reads the bucket**, under the node's identity rather than the one the deployment was granted:
the read succeeds, the audit trail names the wrong principal, and nothing in the service reports a
problem. A missing association SHALL therefore be a failure, never a silent downgrade to whatever identity
the host happens to carry.

The exchange itself SHALL be performed unauthenticated, as the API it calls requires no caller credentials.
This SHALL NOT be treated as an implementation detail: giving the exchange no credentials of its own is
what makes the no-fallback rule hold in every path, since a credential resolver attached to it would
reintroduce exactly the metadata-service lookup the previous paragraph forbids.

Support for other workload-identity mechanisms is out of scope here and SHALL remain an additive change —
resolving through an ordered set of sources is compatible with this requirement only if every source in it
is a workload identity, never a host identity.

#### Scenario: Credentials come from the projected token

- **WHEN** an `aws-s3` source in workload-identity mode authenticates, and the role ARN and token-file path
  are present in the pod's environment
- **THEN** the role session obtained by exchanging that token is what signs the bucket requests

#### Scenario: A pod without a role association fails rather than using the node identity

- **WHEN** the role ARN or the token-file path is absent — for example because the pod lost its
  workload-identity association
- **THEN** credential resolution fails, and no request is signed with an instance profile, node role, or
  any other ambient host identity

#### Scenario: The credential exchange never consults the metadata service

- **WHEN** credentials are resolved in workload-identity mode
- **THEN** the exchange itself is performed without credentials, so no metadata-service lookup is made for
  the exchange, for a region, or for anything else

### Requirement: Temporary S3 credentials carry a session token and refresh for the process lifetime

The service SHALL sign S3 requests in workload-identity mode with **temporary** credentials — an access key
id, a secret key and a **session token**, presented together — and SHALL keep them valid for as long as the
process runs, renewing them before they expire **without a restart**.

Renewal SHALL re-read the projected token file rather than caching its contents, so a token re-projected by
the cluster on its own schedule is picked up by the next renewal. A deployment SHALL NOT need to restart
the service, cycle the pod, or change any setting when either the session or the projected token rolls
over.

Renewal SHALL be driven by the credentials' own expiry, not by a fixed interval, and SHALL happen ahead of
it, so an ordinary sync cycle never waits on an expired session and a brief failure of the exchange inside
that margin is absorbed rather than surfaced. Renewal SHALL NOT require a background thread that outlives
the service's shutdown: the credential resolver and anything it owns SHALL be released when the service
stops.

Resolved credentials SHALL NOT be logged. The access key id and the session token are sufficient, with the
secret, to impersonate the role for the session's remaining life, and a log line is the one place they
would otherwise outlive the session's expiry.

#### Scenario: Requests carry the session token

- **WHEN** a listing or read is signed with workload-identity credentials
- **THEN** the request carries the session token alongside the signature, and the bucket accepts it

#### Scenario: An expiring session is renewed in place

- **WHEN** a sync cycle runs after the current session has reached its renewal margin
- **THEN** a new session is obtained and the cycle proceeds, with no restart and no operator action

#### Scenario: A re-projected token file is picked up

- **WHEN** the cluster replaces the projected token file while the service is running
- **THEN** the next renewal reads the new file, and the service keeps authenticating

#### Scenario: Credentials are not written to the log

- **WHEN** credentials are resolved, renewed, or fail to resolve
- **THEN** no log record contains the access key id, the secret key, or the session token

### Requirement: The STS region is derived from the effective S3 endpoint

The service SHALL derive the region used for the credential exchange from the **effective S3 endpoint** —
the configured blob endpoint, or the provider's default endpoint when none is configured — using the same
host-parsing rule that determines the region the S3 requests are signed for.

Deriving it from that one source is what makes the two regions agree **by construction**. A session issued
for one region and a signature computed for another is a `403` whose message names neither setting, and a
region taken from any independent source — an added property, an environment variable, or a lookup — is a
second value that a deployment can set to something the signer does not use. There SHALL be no such second
source and no separate region property.

The derivation SHALL NOT have a failure branch: a host the rule does not recognise SHALL resolve to the
same default the signer itself uses, so an unusual endpoint produces a request that is merely signed for
the default region rather than a startup or cycle failure with no region at all. This SHALL hold for an
endpoint the derivation cannot read at all — one with no scheme, a host the URI parser rejects, or
surrounding whitespace — which SHALL also resolve to the default region and SHALL be reported as a warning
naming the endpoint setting. Such a value is rejected later by the blob provider on its own terms; the
region derivation must not be what fails first, because its failure names neither the setting nor a region.
In particular the service SHALL NOT consult an ambient region source — a metadata-service lookup, a
profile, or an environment variable — for this value.

Because a blank endpoint resolves to the provider's global default and therefore to its default region, a
bucket outside that region SHALL require the blob endpoint to be set to the bucket's regional endpoint.
This is the same requirement the access-key path already has; workload identity neither adds it nor
removes it. Since a blank endpoint is otherwise indistinguishable from a correct one until the store
returns a signature error, an S3 source configured without an endpoint SHALL warn at context build, as the
keyless Azure path already does for its own endpoint requirement.

#### Scenario: A regional endpoint selects its own region

- **WHEN** the blob endpoint names a regional S3 host
- **THEN** the credential exchange is performed for that region, and the bucket requests are signed for the
  same region

#### Scenario: A blank endpoint uses the provider's default region

- **WHEN** no blob endpoint is configured
- **THEN** both the credential exchange and the request signature use the provider's default region, and
  the two still agree

#### Scenario: An unrecognised host does not fail

- **WHEN** the blob endpoint names a host the region rule does not recognise
- **THEN** the default region is used for both the exchange and the signature, and no startup or sync
  failure is raised for the region alone

#### Scenario: An endpoint the derivation cannot read does not fail

- **WHEN** the blob endpoint has no scheme, carries a host the URI parser rejects, or is surrounded by
  whitespace
- **THEN** the default region is used, a warning names the endpoint setting and the region it fell back to,
  and the cycle fails only if the blob provider itself rejects the endpoint

#### Scenario: An S3 source with no endpoint is warned about

- **WHEN** an `aws-s3` blob source is configured without a blob endpoint
- **THEN** a warning states that requests will be signed for the default region and names the endpoint
  setting and the regional endpoint form
- **AND** the source still operates, because a bucket in that default region is a valid configuration

### Requirement: A credential failure fails the sync cycle, not startup

The service SHALL treat a failure to obtain S3 workload-identity credentials as a **failure of the sync
cycle that needed them**, and SHALL NOT refuse to start because of one. A missing role ARN, a missing
token-file path, or a path pointing at a file that does not exist SHALL fail that cycle whole, leave the
ingest watermark where it was, and be retried by the next scheduled cycle with no operator action.

Deferring the failure to the cycle is deliberate. The blob source is one of several things the service
does; a credential fault that is fixed by the cluster re-associating the role resolves without a deploy,
and a service that refused to start would stay down after the fault had cleared, taking the query surface
with it for a reason unrelated to it. The retry loop therefore SHALL be the ordinary sync cadence, and the
failure SHALL be visible through the existing sync failure reporting — the cycle's failure outcome, the
held watermark and its growing lag — rather than through a new health indicator or metric.

The credential resolver SHALL be created **once** and reused across cycles, including cycles that failed
after it was created, and SHALL be released when the service shuts down. A resolver built afresh by every
failing cycle would leak the client it owns once per cycle, which is a slow failure introduced by the
error path rather than by the error.

#### Scenario: A missing role association fails the cycle and is retried

- **WHEN** a sync cycle needs S3 credentials and the role ARN or token-file path is absent
- **THEN** that cycle fails as a whole with the ingest watermark held, the failure is reported through the
  existing sync failure outcome, and the next scheduled cycle attempts it again

#### Scenario: A token file that does not exist behaves the same way

- **WHEN** the token-file path is set but names a file that does not exist
- **THEN** the cycle fails and is retried on the next cadence, exactly as for an absent setting

#### Scenario: Startup is unaffected by a credential fault

- **WHEN** the service starts with an `aws-s3` blob source whose workload identity cannot be resolved
- **THEN** startup completes and the query surface serves normally; only the sync cycles fail

#### Scenario: A failing cycle does not accumulate credential clients

- **WHEN** many consecutive sync cycles fail to obtain credentials
- **THEN** the credential resolver and the client it owns are created once and reused, and they are
  released at shutdown

### Requirement: Date-derived prefix file discovery

The service SHALL determine which files to ingest by resolving a date-derived blob prefix and listing
all blobs under that prefix. The prefix SHALL take the form `<path-prefix>/<date-path>/`, where
`<path-prefix>` is the configurable base path within the container and `<date-path>` is the target date
rendered through a **configurable date-directory layout** whose default is `{yyyy}/{MM}/{dd}`. The
layout SHALL be expressible as a single value covering both the three-segment form
(`{yyyy}/{MM}/{dd}`, e.g. `2026/06/18`) and a single-segment form (e.g. `2026-06-18`), since different
DIAL deployments bucket their log blobs by either.

The date-directory layout SHALL additionally be permitted to be **blank**, which SHALL mean the
deployment stores no date directory at all. In that mode the whole-day prefix SHALL absorb the
configured file-name head and SHALL take the form `<path-prefix>/<file-name-head>` — the head rendered
for the target date, with **no trailing separator** — e.g. `logs/date=2025-10-22` for a base path of
`logs` and a head layout of `'date='yyyy-MM-dd`. The head is what pins the day in that mode, so the
prefix remains date-derived and each whole-day listing still covers exactly one date. A blank layout
SHALL be admitted only where that holds; see *Date-directory layout is validated at startup*.

An empty date segment SHALL NOT be read as "a date directory rendering to nothing", i.e. the prefix
SHALL NOT degrade to `<path-prefix>/`. Such a prefix is independent of the date, so every whole-day
listing — the scheduled run, the operator backfill and the poller's full-day fallback alike — would
cover the entire base path and re-ingest every day it holds.

The target date SHALL be the current date minus a configurable day offset (default 1, i.e. the previous
day). The service MUST NOT require an exact blob key, since blob names contain an unpredictable suffix.
Every blob listed under the prefix SHALL be considered; which of them are then ingested is decided by the
ingest ledger (see "Ingested blobs are recorded in a persisted ledger").

#### Scenario: Prefix is resolved from the day offset

- **WHEN** a sync run starts with day offset 1 on 2026-06-19, with base path `logs` and the default
  date-directory layout
- **THEN** the service lists blobs under the prefix `logs/2026/06/18/`

#### Scenario: Prefix is resolved with a single-segment date layout

- **WHEN** a sync run starts with day offset 1 on 2026-06-19, with base path `your_dir` and a
  date-directory layout of `yyyy-MM-dd`
- **THEN** the service lists blobs under the prefix `your_dir/2026-06-18/`

#### Scenario: Prefix is resolved with no date directory

- **WHEN** a sync run starts with day offset 1 on 2025-10-23, with base path `logs`, a blank
  date-directory layout and a file-name head layout of `'date='yyyy-MM-dd`
- **THEN** the service lists blobs under the prefix `logs/date=2025-10-22`, carrying no trailing
  separator, and ingests the flat keys `logs/date=2025-10-22<epoch>-<uuid>.log.gz`

#### Scenario: A blank date layout does not widen the listing to the base path

- **WHEN** the date-directory layout is blank
- **THEN** no discovery path lists `<path-prefix>/` on its own, and a whole-day listing for one date
  does not return the keys of another date stored under the same base path

#### Scenario: All files under the prefix are processed

- **WHEN** the resolved prefix contains multiple blobs
- **THEN** the service considers every blob found under that prefix, ingesting each one the ledger does not
  record and skipping each one it does

#### Scenario: Empty prefix completes cleanly

- **WHEN** the resolved prefix contains no blobs
- **THEN** the run completes successfully having ingested zero rows and reports zero files processed

### Requirement: Uniform date-directory layout across discovery paths

Every ingestion path SHALL resolve its blob prefix through the configured date-directory layout, so
that the scheduled reconciliation run, both poller listing modes (`band` and the full-day fallback),
and the operator-triggered backfill agree on which keys belong to a given UTC date. A deployment
SHALL therefore become ingestible by configuration alone when its log blobs differ from the default
layout only in their date directory — **or carry no date directory at all** — with no code change and
no path left on the default.

The configured date-directory layout SHALL apply only to the date directory. The portion of the blob
**file name** that precedes the embedded timestamp is governed separately, by its own configured
layout (see *Configurable file-name head preceding the embedded timestamp*), because it too differs
between producers. Where the date-directory layout is **blank** the two compose differently but SHALL
NOT diverge: the file-name head is then the only date-bearing text there is, so the whole-day paths
carry it, and bounded (`band`) listing SHALL resolve to that same whole-day prefix extended by the
embedded-timestamp digits — never to the head twice.

Where a date directory **is** configured, bounded listing, the whole-day paths and the band-shape
check SHALL produce byte-identical prefixes to the ones they produced before a blank layout became
legal. Admitting the flat form SHALL be a widening of what is configurable, not a change to any
already-working deployment.

The embedded **timestamp** itself — its unit, its width and its position immediately after that head —
is produced by the log producer and SHALL NOT be governed by configuration; an environment whose file
names carry no such timestamp SHALL remain served by the full-day (`day`) listing fallback.

#### Scenario: Poller band listing uses the configured layout

- **WHEN** bounded (`band`) listing is in effect and the date-directory layout is `yyyy-MM-dd`
- **THEN** each listed band prefix places the date directory in the configured form, follows it with
  the configured file-name head (e.g. `your_dir/2026-06-18/date=2026-06-18…`), and leaves the
  embedded-timestamp digits of the key unchanged

#### Scenario: Poller full-day fallback uses the configured layout

- **WHEN** the full-day (`day`) listing fallback is in effect and the date-directory layout is
  `yyyy-MM-dd`
- **THEN** the poller lists the active day (and, within the grace window, the previous day) under
  prefixes carrying the configured date directory

#### Scenario: Default layout preserves existing prefixes

- **WHEN** no date-directory layout is configured
- **THEN** every path resolves exactly the prefixes it resolved before the layout was configurable,
  on the scheduled, poller and backfill paths alike

#### Scenario: Every path agrees under a blank date layout

- **WHEN** the date-directory layout is blank, the base path is `logs` and the file-name head layout
  is `'date='yyyy-MM-dd`
- **THEN** the scheduled run, the operator backfill and the poller's full-day fallback all list
  `logs/date=<date>`, each band prefix reads `logs/date=<date><band digits>` with the head appearing
  exactly once, and the band-shape check expects that same `logs/date=<date>` followed by a digit

### Requirement: Date-directory layout is validated at startup

The service SHALL validate the configured date-directory layout at startup and SHALL abort startup
when it is unusable, rather than starting and listing a prefix that matches no blob — or one that
matches far more than its target date. A layout is unusable when:

- it cannot be interpreted as a date layout;
- it cannot render a date — for example because it names a time-of-day field no date can supply;
- the **whole-day prefix** it produces renders any two distinct dates of a multi-year window
  identically. Such a layout collapses distinct dates onto one prefix, so each run lists far more than
  its target date while reporting success. The sweep SHALL be made over the rendered whole-day prefix
  rather than over the date segment alone, because the date segment is not always what pins the day:
  where the layout is blank the whole-day prefix is the rendered file-name head, and where a date
  directory is configured the head is not part of the whole-day prefix at all — so the same sweep
  keeps refusing `yyyy/MM` in the ordinary case and admits a blank layout exactly when the head pins
  the day. The check SHALL be made by rendering every date of the window, not a handful of probe
  dates: probes isolating the year, the month and the day catch a layout that omits a field (`MM/dd`
  collides across years) but admit one that merely weakens it (`yyyy/MM/E` carries a weekday where a
  day of the month belongs, and collides four times a month). The failure SHALL name a colliding pair
  of dates. The window SHALL span years rather than centuries, so a two-digit year stays a usable
  layout;
- one day's whole-day prefix is a **proper prefix of another day's**. A whole-day prefix that ends in
  a path separator cannot be, which is what rules this out wherever a date directory is configured; a
  blank date layout produces one that does not, and a variable-width head then collides —
  `'d='yyyy-M-d` renders `d=2025-1-1`, which prefix-matches `d=2025-1-10` … `d=2025-1-19`, so one
  whole-day listing would silently pull in ten other days while reporting success. Distinctness does
  not catch this, because the strings differ. The service SHALL sort the swept whole-day prefixes and
  SHALL refuse the layout when any entry is a proper prefix of its successor, naming the two days. A
  fixed-width head such as `'date='yyyy-MM-dd` passes.

A blank date-directory layout SHALL NOT be refused for being blank. It SHALL mean "no date directory",
and SHALL be admitted exactly when the checks above pass — that is, when the file-name head alone pins
the day and is prefix-free across days. A blank layout paired with a blank or non-date head therefore
still aborts startup, through the same whole-day sweep rather than through a rule of its own: both
days render `<path-prefix>/`, so they collide on the second date swept. `null` and blank SHALL be
treated alike, as the file-name head already treats them.

Every refusal SHALL name the text the operator actually configures — the rendered **date segment**,
or, where the date layout is blank, the rendered **file-name head** — rather than the whole prefix,
together with the offending configuration property and its environment variable.

The service SHALL log the resolved discovery prefix template once at startup, and that log SHALL
reflect the layout actually in effect — including the head-absorbed form where the date layout is
blank — so an operator can confirm what will be listed without waiting for a run.

#### Scenario: Unparsable layout aborts startup

- **WHEN** the configured date-directory layout is not a valid date layout
- **THEN** startup fails with an error naming the configuration property and its environment
  variable, and the service does not begin ingesting

#### Scenario: Layout naming a time field aborts startup

- **WHEN** the configured date-directory layout names a field a calendar date cannot supply (e.g. an
  hour field)
- **THEN** startup fails with an error naming the configuration property and its environment
  variable

#### Scenario: Layout omitting a date field aborts startup

- **WHEN** the configured date-directory layout omits a date field, so that two dates differing only
  in that field render the same text (e.g. a layout carrying month and day but no year)
- **THEN** startup fails with an error naming the configuration property and its environment
  variable

#### Scenario: Layout with a too-coarse date field aborts startup

- **WHEN** the configured date-directory layout names every date field but one of them is too coarse
  to separate days (e.g. a weekday in place of the day of the month, or a quarter in place of the
  month)
- **THEN** startup fails with an error naming the configuration property, its environment variable,
  and two dates the layout renders identically

#### Scenario: Blank layout whose head cannot pin the day aborts startup

- **WHEN** the configured date-directory layout is blank and the file-name head layout is blank too
  (or names no date field)
- **THEN** startup fails with an error naming the configuration property and its environment
  variable, and two dates whose whole-day prefixes are identical, rather than resolving a prefix that
  lists the entire base path

#### Scenario: Blank layout whose head pins the day is accepted

- **WHEN** the configured date-directory layout is blank and the file-name head layout is
  `'date='yyyy-MM-dd`
- **THEN** startup succeeds and the logged discovery prefix template shows the head-absorbed form
  (e.g. `logs/date=<date>`) with no empty date segment and no trailing separator

#### Scenario: A whole-day prefix that is a proper prefix of another day's aborts startup

- **WHEN** the configured date-directory layout is blank and the file-name head layout renders
  variable-width dates (e.g. `'d='yyyy-M-d`, which renders `d=2025-1-1` and `d=2025-1-10`)
- **THEN** startup fails with an error naming the configuration property, its environment variable,
  and the two days whose prefixes nest, rather than starting and listing ten days as one

#### Scenario: Refusal names the configured text, not the whole prefix

- **WHEN** a layout is refused for not distinguishing dates
- **THEN** the message quotes the rendered date segment — or, where the date layout is blank, the
  rendered file-name head — and the pair of dates it renders identically, so the operator reads back
  the value they set

#### Scenario: Valid layout is reported at startup

- **WHEN** the configured date-directory layout is valid
- **THEN** the service starts and logs the resolved discovery prefix template it will list under

### Requirement: Configurable file-name head preceding the embedded timestamp

The service SHALL resolve the text between the date directory and the blob file name's embedded
timestamp from a configured layout, so that a deployment whose producer names files differently
becomes ingestible on bounded (`band`) listing by configuration alone. The layout SHALL be
interpreted as a date layout rendered for the day the prefix covers, because a producer may embed the
date in that text (the default does). It SHALL default to the form the service resolved before it was
configurable, so no existing deployment's prefixes change.

A blank layout SHALL be accepted **where a date directory is configured**, and SHALL mean the embedded
timestamp begins the file name: an absent file-name head does not widen any listing there, because the
date directory still bounds it. Where the date-directory layout is itself blank the head is the only
text that can pin the day, so a blank head SHALL NOT be accepted in that combination — not by a rule of
its own, but because the whole-day distinctness sweep refuses it (see *Date-directory layout is
validated at startup*).

The head's reach SHALL likewise depend on the date-directory layout. Where a date directory is
configured, the head SHALL narrow bounded (`band`) listing only, and the whole-day prefixes used by the
scheduled reconciliation run, the operator backfill and the poller's full-day fallback SHALL be
unaffected by it. Where the date-directory layout is blank, the head is part of the whole-day prefix by
construction, so it SHALL govern every discovery path, and it SHALL still appear exactly once in a band
prefix.

The layout SHALL govern only that text. The digits of the embedded timestamp SHALL continue to be
derived from the requested time range, never configured.

#### Scenario: Default head preserves existing band prefixes

- **WHEN** no file-name head layout is configured
- **THEN** each band prefix carries the `date=<ISO date>` head it carried before the head was
  configurable (e.g. `logs/2026/06/24/date=2026-06-241782297`), on every band the range touches

#### Scenario: Blank head places the timestamp directly after the date directory

- **WHEN** the file-name head layout is blank, the base path is `your_dir` and the date-directory
  layout is `yyyy-MM-dd`
- **THEN** each band prefix reads `your_dir/<date directory>/<timestamp digits>` with no head between
  them (e.g. `your_dir/2026-06-24/1782297`)

#### Scenario: Head and date directory compose

- **WHEN** a blob whose file name begins with the embedded timestamp is stored under a
  single-segment date directory, the date-directory layout is `yyyy-MM-dd` and the file-name head
  layout is blank
- **THEN** bounded (`band`) listing discovers that blob and it is ingested

#### Scenario: Head applies to bounded listing only under a date directory

- **WHEN** a file-name head layout is configured and the date-directory layout is not blank
- **THEN** the whole-day prefixes used by the scheduled reconciliation run, the operator backfill and
  the poller's full-day fallback are unaffected by it

#### Scenario: Head governs every path under a blank date layout

- **WHEN** the date-directory layout is blank and the file-name head layout is `'data='yyyy-MM-dd`
- **THEN** the whole-day prefixes of the scheduled run, the backfill and the poller's full-day
  fallback all read `<path-prefix>/data=<date>`, and each band prefix reads
  `<path-prefix>/data=<date><band digits>` with the head appearing once, not twice

#### Scenario: A head rendering a leading separator does not double it

- **WHEN** the configured file-name head layout renders text beginning with a path separator
- **THEN** the resolved band prefix carries a single separator after the date directory

### Requirement: File-name head layout is validated at startup

The service SHALL validate the configured file-name head layout at startup and SHALL abort startup
when it is unusable, rather than starting and listing a prefix that matches no blob. A layout is
unusable when it cannot be interpreted as a date layout, or when it cannot render a date — for
example because it names a time-of-day field no date can supply, which is accepted as a layout and
fails only when a prefix is first resolved, i.e. on the first poll cycle.

Whether the head must **distinguish dates** SHALL depend on the date-directory layout, and SHALL NOT
be decided by this layout alone. Where a date directory is configured, the validation SHALL NOT
require the head to distinguish dates, and SHALL NOT refuse a blank or purely literal head: it is the
date directory, not this text, that must separate days, and refusing otherwise would refuse exactly
the value a producer writing `<epoch>-<uuid>.log.gz` needs. Where the date-directory layout is blank,
the head is the only text that can separate days, so it SHALL be required both to distinguish every
pair of dates and to be prefix-free across them — enforced by the whole-day checks in *Date-directory
layout is validated at startup*, whose failures name the rendered head.

The failure SHALL name the offending configuration property and its environment variable.

The service SHALL log a resolved band-prefix example once at startup, alongside the whole-day prefix
template, so an operator can confirm both assumptions against a real blob key without waiting for a
poll cycle.

#### Scenario: Unparsable head aborts startup

- **WHEN** the configured file-name head layout is not a valid date layout
- **THEN** startup fails with an error naming the configuration property and its environment
  variable, and the service does not begin ingesting

#### Scenario: Head naming a time field aborts startup

- **WHEN** the configured file-name head layout names a field a calendar date cannot supply (e.g. an
  hour field)
- **THEN** startup fails with an error naming the configuration property and its environment
  variable

#### Scenario: Blank head is accepted under a date directory

- **WHEN** the configured file-name head layout is blank and a date-directory layout is configured
- **THEN** startup succeeds and band prefixes place the embedded timestamp directly after the date
  directory

#### Scenario: Purely literal head is accepted under a date directory

- **WHEN** the configured file-name head layout names no date field at all and a date-directory
  layout is configured
- **THEN** startup succeeds and every band prefix carries that literal text

#### Scenario: A head that cannot separate days is refused under a blank date layout

- **WHEN** the date-directory layout is blank and the configured file-name head layout renders the
  same text for every date (blank, or purely literal)
- **THEN** startup fails with an error naming the configuration property, its environment variable
  and a colliding pair of dates, because nothing else in the prefix pins the day

#### Scenario: Resolved band prefix is reported at startup

- **WHEN** the service starts with a valid configuration
- **THEN** it logs a rendered band-prefix example reflecting the layouts actually in effect

### Requirement: A file name bounded listing cannot match is reported

The service SHALL report blob file names that the configured file-name head cannot match, rather than
leaving a misconfigured bounded (`band`) listing to report healthy cycles that discover nothing. On
every whole-day listing it already performs, the service SHALL check each listed key's file name
against the configured head followed by at least one digit, SHALL count each key that does not match,
and SHALL log a warning identifying a non-matching key, the shape expected, and the remedies
available.

The check SHALL NOT perform any additional listing: it is an assertion over keys already retrieved,
not a probe of the container. It SHALL NOT change which blobs are ingested — a non-matching key is
still ingested by the whole-day paths exactly as before — because a mismatch is a discovery-narrowing
defect, not a data defect.

The warning SHALL be logged at most once per process, while the count SHALL be recorded on every run,
so the condition is visible to monitoring for as long as it persists without the log repeating it.

Where the date-directory layout is **blank** the check SHALL remain in force but is necessarily weaker,
and the service SHALL NOT claim otherwise. The whole-day prefix there already carries the head, so a key
the listing returned cannot fail the head comparison and only the trailing-digit test still applies; a
*wrong* head makes the whole-day listing itself return nothing, which no assertion over its results can
distinguish from an idle day. The remedy the warning names SHALL therefore differ by mode: falling back to
whole-day listing SHALL be offered only where a date directory exists, since without one the whole-day
paths list under that same head and no fallback exists. The rendered prefix logged at startup — which an
operator can hold against a real key — SHALL be what covers the wrong-head case in that mode.

#### Scenario: Matching file names are not reported

- **WHEN** a whole-day run lists blobs whose file names all begin with the configured head followed by
  the embedded timestamp
- **THEN** nothing is counted and no warning is logged

#### Scenario: A non-matching file name is counted and warned

- **WHEN** a whole-day run lists a blob whose file name does not begin with the configured head
  followed by a digit
- **THEN** the run counts it, logs a warning naming the offending key and the expected shape, and
  still ingests the blob

#### Scenario: The warning does not repeat

- **WHEN** a second whole-day run in the same process again lists non-matching file names
- **THEN** the count increases again and no further warning is logged

#### Scenario: The remedy offered depends on whether a date directory exists

- **WHEN** the warning is logged and the date-directory layout is blank
- **THEN** it does not offer full-day listing as an unaffected fallback, and states instead that the
  file-name head is what every discovery path lists under
- **AND** with a date-directory layout configured it offers that fallback as before

### Requirement: Log parsing and transformation

The service SHALL read each blob as a gzip-compressed, newline-delimited JSON (NDJSON) stream and
transform each record into a ClickHouse row according to an external column mapping (see "Configurable
column mapping"). For each declared **mapped** column the service SHALL resolve the configured source —
a dotted JSON path within the record, or the `$sourceFile` synthetic token — coerce the resolved value
to the column's declared extraction kind, apply the configured transform (if any), and resolve absent
or blank source values as follows: if the column declares a default, the service SHALL use that
default; otherwise, if the column is declared `nullable`, the service SHALL emit a JSON `null` for that
column; otherwise the service SHALL fall back to the kind's zero value (so a non-nullable column
without a producer value still inserts safely). For a declared **derived** column the service SHALL
instead resolve each path in the column's `sources` list to a node and compute the value via the named
deriver (see "Closed named deriver registry"), then apply the same absent/blank fallback. The merged
streaming `assembled_response` field MAY be read as a deriver source (e.g. for `response_id` and
body-derived token counts) and MAY additionally be persisted as a column of its own (see "The merged
assembled response is persisted"); the two uses are independent. Columns whose source is a JSON
array SHALL be mapped using the `string-array` transform with the `array<string>` kind. The transform
that converts an ISO-like timestamp to a ClickHouse `DateTime64` literal (replacing `T` with a space)
SHALL be available as a named transform. Monetary columns (`total_price`, `deployment_price`) SHALL be
coerced so the producer's decimal value is preserved exactly (passed through without lossy binary
floating-point conversion). `response.status` SHALL be mapped with the numeric (long) kind so it is
stored as an integer.

#### Scenario: NDJSON record is mapped via the configured mapping

- **WHEN** a blob contains a valid NDJSON usage-log record
- **THEN** the service produces a row whose keys are exactly the configured column names, each value
  resolved from its configured source path (or computed by its configured deriver), coerced to its
  declared kind, and passed through its configured transform

#### Scenario: Derived column is computed at ingest

- **WHEN** a declared column specifies a `derive` name with `sources` instead of a single `source`
- **THEN** the service computes its value with the named deriver from the resolved source nodes and
  applies the column's absent/blank fallback

#### Scenario: Response status is stored as a number

- **WHEN** a record's `response.status` is mapped with the numeric kind
- **THEN** the inserted value is the status as an integer suitable for a `UInt16` column

#### Scenario: Absent value in a nullable column becomes null

- **WHEN** a declared column is nullable, has no configured default, and its source value is absent or
  blank in the record
- **THEN** the row emits a JSON `null` for that column rather than a zero or empty value

#### Scenario: Absent value in a non-nullable column without a default falls back to zero

- **WHEN** a declared column is not nullable, has no configured default, and its source value is absent
  or blank
- **THEN** the row uses the extraction kind's zero value so the insert remains valid

#### Scenario: Missing source falls back to the configured default

- **WHEN** a declared column's source value is absent or blank in the record and the column declares a
  default
- **THEN** the row uses the configured default for that column

#### Scenario: Monetary value is preserved exactly

- **WHEN** a record carries a `token_usage.price` or `token_usage.deployment_price` decimal value
- **THEN** the inserted value preserves the producer's decimal precision without binary
  floating-point rounding

### Requirement: Reported cache write tokens are persisted

The service SHALL persist a producer-reported cache-write token count (nested under `token_usage` as
the provider's prompt-tokens-details cache-write count) to the `cache_creation_tokens` column, and
SHALL NOT store `0` for a record that reports a non-zero count. When the producer does not report the
count, the column SHALL be NULL per the nullable-column absent/blank fallback, so "not reported" stays
distinguishable from a reported zero.

The guarantee SHALL hold independently of the response's transport and upstream API shape — a streamed
response, whose body and merged response are an SSE transcript rather than a JSON document, SHALL
persist the count exactly as a non-streamed one does. Deriving the value from the response payload
cannot meet this, since an SSE transcript does not parse as JSON and normalized/native payloads spell
the count differently; the count is therefore read from the record's own `token_usage` accounting,
which the producer populates for every shape.

This is a behavioral guarantee about the ingested value; the specific JSON source path remains an
implementation detail of the (overridable) default column mapping and is not pinned here.

#### Scenario: Reported cache-write token count is stored

- **WHEN** a record's `token_usage` reports a cache-write token count of N (including 0)
- **THEN** the ingested row's `cache_creation_tokens` equals N

#### Scenario: Streamed response reports a cache write

- **WHEN** a record for a streamed response carries an SSE transcript in `response.body` and
  `assembled_response`, and its `token_usage` reports a cache-write token count of N
- **THEN** the ingested row's `cache_creation_tokens` equals N, not `0`

#### Scenario: Absent cache-write token count is null

- **WHEN** a record's `token_usage` does not report a cache-write token count (including when it
  carries no prompt-tokens-details object at all)
- **THEN** the ingested row's `cache_creation_tokens` is NULL

### Requirement: Reported operation duration is persisted

The service SHALL persist a producer-reported operation duration, in milliseconds, to the
`operation_duration_ms` column of `dial_usage_log`, and SHALL NOT store `0` for a record that reports a
non-zero duration. The value SHALL be stored as reported, without re-derivation from the record's
timestamps: the producer measures the interval it alone can see (from accepting the request to having the
full response body for a live request; from accepting it to emitting the log entry for a background job,
which includes the producer's own detection lag), and a duration recomputed here from `request.time` and
ingest time would describe a different interval.

A reported duration of `0` SHALL be stored as `0`, because the producer clamps the measurement at zero and
an operation rejected before any upstream work legitimately completes inside a millisecond.

The column is not nullable. When a record carries no duration — which for this field means it was produced
by a DIAL Core older than the release that introduced it, since the producer emits it unconditionally
otherwise — the column SHALL take the non-nullable absent/blank fallback and store `0`. "Not reported" is
therefore indistinguishable from a measured zero on this column, by decision: the distinction identifies a
producer version rather than a property of the request, and the correction is to upgrade the producer.

This is a behavioral guarantee about the ingested value; the specific JSON source path remains an
implementation detail of the (overridable) default column mapping and is not pinned here.

#### Scenario: Reported operation duration is stored

- **WHEN** a record reports an operation duration of N milliseconds
- **THEN** the ingested row's `operation_duration_ms` equals N

#### Scenario: A reported zero duration stays zero

- **WHEN** a record reports an operation duration of `0`
- **THEN** the ingested row's `operation_duration_ms` is `0`, not treated as an absent value

#### Scenario: A record from a producer that does not report the duration

- **WHEN** a record carries no operation duration field
- **THEN** the ingested row's `operation_duration_ms` is `0` (the non-nullable fallback), and the record is
  ingested normally rather than dropped

### Requirement: Reported route is persisted

The service SHALL persist the route a producer reports as having served a request in two columns of
`dial_usage_log`:

- `route_name` holds the route's name as reported. The service SHALL store it verbatim and SHALL NOT
  normalize, lower-case or resolve it against any configuration.
- `route_type` holds the kind of route as reported. The current producer reports `global` (a route
  configured in its `routes` section) or `application` (a route configured under an application). The
  service SHALL store the reported value verbatim and SHALL NOT reject a value outside that pair, so that
  a new route kind ingests without a service change.

Both columns are `Nullable`. A record that carries neither field, which the producer emits for every
request that no route served, SHALL store NULL in both columns and SHALL be ingested normally rather than
dropped. The two fields are independent: a record that reports one and omits the other SHALL store the
reported value and NULL for the other. A blank value is stored as NULL, like an absent one. NULL is
therefore the "not served by a route" signal, so it never collides with a reported value.

A route name is unique only within its kind and, for an application route, within its application. The
service SHALL NOT combine the two columns, or the route columns with `deployment`, into a derived key; a
consumer that needs a unique route identity groups on the columns it needs.

The route columns SHALL NOT change how `event_kind` is classified. `event_kind` stays derived from the
request URI.

This is a behavioral guarantee about the ingested values. The specific JSON source paths remain an
implementation detail of the (overridable) default column mapping and are not pinned here.

#### Scenario: A request served by a global route

- **WHEN** a record reports `route_name` `vector-store` and `route_type` `global`
- **THEN** the ingested row's `route_name` is `vector-store` and its `route_type` is `global`

#### Scenario: A request served by an application route

- **WHEN** a record reports `deployment` `my-app`, `route_name` `index-search` and `route_type`
  `application`
- **THEN** the ingested row's `deployment` is `my-app`, its `route_name` is `index-search` and its
  `route_type` is `application`

#### Scenario: A request no route served

- **WHEN** a record carries neither `route_name` nor `route_type`
- **THEN** the ingested row's `route_name` and `route_type` are both NULL, and the row is ingested
  normally

#### Scenario: Only one of the two fields is reported

- **WHEN** a record reports `route_name` `vector-store` and carries no `route_type`
- **THEN** the ingested row's `route_name` is `vector-store` and its `route_type` is NULL

#### Scenario: A blank route field reads as absent

- **WHEN** a record reports `route_name` as an empty string
- **THEN** the ingested row's `route_name` is NULL

#### Scenario: An unrecognized route kind

- **WHEN** a record reports a `route_type` other than `global` or `application`
- **THEN** the ingested row stores that value as reported, and the record is not rejected

#### Scenario: Rows ingested before the columns existed

- **WHEN** the route columns are added to a table that already holds rows
- **THEN** those rows read NULL in both columns until their source blobs are re-ingested

#### Scenario: Route columns do not reclassify the event

- **WHEN** a record reports `route_type` `global` for a request URI that the `event_kind` classifier does
  not recognize as a route
- **THEN** the ingested row's `event_kind` is whatever the URI classifier yields, regardless of the route
  columns

### Requirement: The merged assembled response is persisted

The service SHALL persist the producer's merged streaming response, `assembled_response`, to a column
of `dial_usage_log_payload` when the configured mapping declares one, storing the producer's value
verbatim with no reassembly, parsing, or normalization of its own. There is one exception: when
`sync.inline-files-mode` is `drop`, the payloads of inline files are removed from the value, and when it is
`drop-truncated`, a value DIAL Core cut at 4 MiB inside a file is cut back to before that file (see "Inline
files are removed from payload bodies in drop mode"). That parse locates files only and never validates,
rejects or repairs the value. A value that does not parse is handled by that requirement's "Body formats" and
"Truncated bodies" rules: removals found before the parse error are kept, and a truncated body is cut back to
before the file the cut landed in; nothing else in such a value is changed. The column SHALL be nullable, so a
record that carries no `assembled_response` stores `NULL` rather than an empty string and "the producer
sent nothing" stays distinguishable from "the producer sent an empty response".

The expected value is a **merged JSON response message**, for a streamed and a non-streamed call
alike — that is what makes the column readable as an answer where `response.body` on a streamed call is
a raw chunk transcript. The service SHALL nonetheless store the value as reported **whatever its
shape**, and SHALL NOT validate, parse, or reject it on shape: deciding what arrived would mean parsing
a payload the service otherwise never reads, and a row rejected or emptied on that basis would report
"no response" for a request that had one.

A stored value that does not parse as JSON therefore SHALL NOT be treated as an ingest failure, and a
consumer that needs a message SHALL parse the column defensively. A minority of records — including
current ones, not only historical ones — carry a value that does not parse, some of them an SSE
transcript in place of the merged message. The persisted shape is the producer's contract, not this
service's: ADAS neither guarantees JSON nor repairs a payload that is not, so a consumer that assumes
JSON will fail on that minority rather than on nothing.

Persisting the merged response SHALL NOT change how it feeds derived columns: it remains available as
a deriver source for the `dial_usage_log` mapping, and a column derived from it is unaffected by
whether it is also stored, or by which table stores it. `response_id` and `reasoning_tokens` therefore
remain columns of `dial_usage_log` while their source is stored on `dial_usage_log_payload`, because a
deriver reads the parsed record rather than a stored column.

#### Scenario: The assembled response is stored when the mapping declares it

- **WHEN** a record carries `assembled_response`, the payload mapping declares the corresponding column,
  and `sync.inline-files-mode` is `keep`
- **THEN** the inserted `dial_usage_log_payload` row stores the producer's value verbatim

#### Scenario: Drop mode removes files from the assembled response

- **WHEN** `sync.inline-files-mode` is `drop` and a chat record's `assembled_response` carries
  `choices[0].message.audio.data`
- **THEN** the stored `assembled_response` carries that value as `""` and is otherwise the producer's
  value verbatim

#### Scenario: A record without an assembled response stores null

- **WHEN** a record carries no `assembled_response` and the mapping declares the column as nullable
- **THEN** the inserted row stores `NULL` for it, and the record is ingested normally

#### Scenario: A value that is not JSON is stored unchanged

- **WHEN** a record's `assembled_response` is not a JSON document (for example an SSE transcript), and
  `sync.inline-files-mode` is `keep`, or DIAL Core did not cut the value and the mode is `drop-truncated`
  or the value holds no inline file at a location of the record's API
- **THEN** the inserted row stores that value verbatim, and ingest neither fails, skips, nor empties the
  column (otherwise an SSE transcript is scanned event by event, as the inline-files requirement says)

#### Scenario: Deriving from the assembled response is unchanged

- **WHEN** a `dial_usage_log` column derives its value from `assembled_response` and the payload mapping
  also declares `assembled_response` as a stored column
- **THEN** the derived column's value is exactly what it would be were the column not stored, and it is
  unaffected by the payload column living in a different table

### Requirement: Persisted columns are governed by the mapping (allowlist)

The service SHALL persist only the columns declared in the configured mapping (allowlist semantics):
a field that is not declared MUST NOT be written, regardless of its presence in the source record.
The shipped **payload** mapping declares the heavy body columns `request_body`, `response_body`, and
`assembled_response`, so raw prompt content, raw response content, and the producer's merged response
all ARE persisted to ClickHouse. Whether heavy content is captured is therefore controlled entirely by
that mapping (removing those columns from it, or removing the payload target itself, stops capturing
them). When captured, access to those columns and their retention MUST be governed
operationally — column-level access controls plus a retention/TTL policy — because the service applies
no masking or redaction of its own. Removing inline files under `sync.inline-files-mode=drop` or
`drop-truncated` (see "Inline
files are removed from payload bodies in drop mode") is not redaction: it removes file payloads at fixed
locations only, and every text value is still stored as captured. Personal data in prompt text or file
names, and in any file kept under `keep`, remains covered only by those operational controls.

`assembled_response` is captured **in addition to** `response_body` rather than instead of it, and the
two overlap: for a streamed call the raw body is a chunk transcript while the merged response is the
same content assembled. Capturing both is a deliberate cost — one more unbounded-size column of
verbatim user-visible content per row — accepted because the merged response is the only form in which
a consumer can read an answer without reassembling a stream, and because dropping the raw body would
lose the transport-level detail an operator needs to diagnose a malformed stream. An installation that
does not want the merged response captured removes its column from the payload mapping, exactly as for
the other two.

#### Scenario: Declared heavy payload columns are captured

- **WHEN** a record contains `request.body`, `response.body`, and/or `assembled_response` and the
  payload mapping declares the corresponding columns
- **THEN** the inserted `dial_usage_log_payload` row contains those fields' values, less the payloads of
  inline files when `sync.inline-files-mode` is `drop`, and cut back before the file the cut landed in
  for a body DIAL Core cut at 4 MiB when it is `drop` or `drop-truncated`

#### Scenario: An undeclared payload column is not captured

- **WHEN** a record contains `assembled_response` and no mapping declares a column for it
- **THEN** no inserted row contains an `assembled_response` column, though its value MAY still feed
  derived columns such as `response_id`

#### Scenario: Removing the payload target stops capturing content

- **WHEN** the payload target is removed from the configured target list
- **THEN** ingestion continues to write `dial_usage_log` rows and writes no verbatim body content
  anywhere, and the derived columns computed from the record's bodies are unaffected

#### Scenario: Only declared columns are written

- **WHEN** a record contains fields that are not declared as columns in the mapping
- **THEN** the inserted row contains only the declared columns and none of the undeclared fields

### Requirement: Malformed lines are skipped

The service SHALL skip any NDJSON line that cannot be parsed, log a warning for it, and count it,
without failing the rest of the run. A malformed line MUST NOT abort ingestion of the remaining
lines in the file or the remaining files in the run.

A line whose decompressed length exceeds `sync.max-line-bytes` SHALL be skipped and counted the same
way, and the service MUST NOT hold more than that many bytes of it in memory while reading past it, so
a blob consisting of one enormous line cannot exhaust the heap or make every poll cycle fail on the
same blob. The warning SHALL state the line's length and the configured cap. Each such line SHALL also
increment the `sync.lines.oversized` counter (scheduled sync and backfill alike), so oversized skips
can be told apart from malformed ones; both remain part of the skipped-rows count.

The cap bounds the raw line buffer, not the memory a line costs. A line just under the cap is held at
once as its bytes, its decoded string (up to twice the byte count for non-Latin-1 text), its JSON tree,
its mapped row and its share of the open batch, so one parsing thread needs a small multiple of the
cap, and the scheduled sync and a backfill can each be parsing at the same time. The cap SHALL be
between 1 KiB and 1 GiB. A line buffer grown well past the read chunk by one long line SHALL be
released once that line is consumed rather than kept for the rest of the blob.

Lines SHALL be split on LF only. A CR SHALL be stripped only when it directly precedes the LF (CRLF);
a lone CR does not end a line and stays part of it.

#### Scenario: A bad line does not abort the run

- **WHEN** a blob contains a line that cannot be parsed as a valid record
- **THEN** the service logs a warning, increments the skipped-rows count, and continues processing
  the remaining lines and files

#### Scenario: An oversized line is skipped without being buffered

- **WHEN** a blob contains a line longer than `sync.max-line-bytes`, with or without a trailing newline
- **THEN** the service logs a warning naming the line's length and the cap, increments the skipped-rows
  count, and parses the lines after it

#### Scenario: Oversized skips are counted apart from malformed ones

- **WHEN** a blob contains two lines longer than `sync.max-line-bytes` and one line that is not valid JSON
- **THEN** the skipped-rows count grows by three and the `sync.lines.oversized` counter by two

#### Scenario: A CRLF line ending is accepted

- **WHEN** a blob's lines end with CRLF
- **THEN** each line is parsed without its trailing CR, and a line exactly at the cap is not skipped

### Requirement: Batched inserts via a dedicated writer credential

The service SHALL insert rows into ClickHouse in batches, using a ClickHouse credential that is
separate from the read-only credential used by the query/discovery path. The read path's ClickHouse
access MUST remain read-only.

Within a single ingestion run (a poll cycle or a scheduled reconciliation run), the service SHALL
accumulate mapped rows across the run's blobs into shared batches and flush a batch when it reaches
**either** the configured row count (`sync.batch-size`) **or** a configured accumulated serialized-byte
size (`sync.max-batch-bytes`), whichever is reached first, and once more at the end of the run — rather
than flushing a separate partial batch per blob — so that a run of many small blobs produces few large
inserts rather than one insert per blob. The byte-size bound SHALL ensure that accumulating rows across
a run's blobs cannot, on its own, grow a batch's in-memory serialized payload past the JVM's maximum
array length — the bound caps the multi-row buffer, not a single row, so a lone row whose own serialized
form exceeds the array length remains an inherent limit it cannot guard against. The accumulated-byte
estimate SHALL approximate each row's **escaped UTF-8** serialized size — accounting for JSON string
escaping and multi-byte characters — so that the bound reflects the payload actually produced at flush
rather than an undercount, while remaining a cheap estimate rather than a real serialization.

When the writer serializes a batch for insertion it MUST NOT first materialize the entire batch as a
single in-memory buffer (neither one `String` nor one `byte[]`); it SHALL stream the batch to ClickHouse
row by row, so the peak insert memory beyond the retained row buffer is bounded by roughly a single
row's serialized form regardless of batch size. The shipped default byte bound (`sync.max-batch-bytes`)
SHALL be heap-relative — small enough that a full batch flushes safely under a typical container heap —
while remaining operator-overridable within the validated range.

The isolation of a single blob's ingest failure (counted and skipped, the run continuing and the
watermark still advancing) SHALL be preserved, and such a failure MUST NOT discard rows already
accumulated from other blobs in the same run.

#### Scenario: Rows are inserted in batches

- **WHEN** a file yields more rows than the configured batch size
- **THEN** the service inserts the rows in multiple batches of at most the configured size

#### Scenario: A run's small blobs share batches

- **WHEN** an ingestion run processes many blobs that each yield fewer rows than the configured batch
  size
- **THEN** their rows accumulate into shared batches flushed when the configured size is reached and
  once at the end of the run, rather than one insert per blob

#### Scenario: A large-row batch flushes on the byte bound before the row count

- **WHEN** the rows accumulated in the shared buffer reach the configured serialized-byte size before
  the buffer reaches the configured row count
- **THEN** the service flushes the buffered rows as a batch at that point, so the in-memory payload
  stays bounded regardless of individual row size

#### Scenario: A batch is streamed to ClickHouse without a whole-batch buffer

- **WHEN** the writer flushes a batch to ClickHouse
- **THEN** it serializes and sends the rows incrementally rather than first assembling the entire batch
  as one in-memory `byte[]` or `String`, so peak insert memory beyond the retained row buffer does not
  scale with the batch's total serialized size

#### Scenario: A failed blob does not discard the run's buffered rows

- **WHEN** one blob fails to ingest after earlier blobs in the same run have already contributed rows
  to the shared batch
- **THEN** the service counts and skips the failing blob and retains the rows already accumulated from
  the other blobs

#### Scenario: Writer credential is isolated from the read path

- **WHEN** the sync job writes to ClickHouse
- **THEN** it uses the dedicated writer credential, and the read/discovery path continues to use its
  own read-only credential

### Requirement: An insert refused for lack of disk space is classified distinctly

The service SHALL recognise a ClickHouse write refused for lack of disk space (server error code 243,
`NOT_ENOUGH_SPACE`) as its own failure mode, distinct from an otherwise-failed insert, and SHALL report
it as such in logs and metrics, naming the target table. Recognition SHALL be by the numeric server
error code rather than by matching message text.

A failed batch insert is a **run-level** fault, not the blob-scoped failure the poller isolates (a
corrupt blob is counted, skipped, and does not hold the watermark): whatever the reason, the run stops
writing, and a poll cycle leaves its watermark unchanged and records in the ingest ledger only the blobs
whose rows were all written before the failure, so every other blob it read stays eligible for re-ingest and
the next cycle retries. Daily ingest therefore resumes on
its own once space is available, with no operator action and no rows lost. Rows an earlier attempt
already flushed and a retry writes again SHALL collapse on the table's deduplication key rather than
double-count, exactly as any other re-ingest does.

A failure the service cannot attribute to disk exhaustion SHALL keep its existing generic treatment, so
a driver or server change that stops surfacing code 243 costs the specific classification and nothing
more.

#### Scenario: A disk-full insert is named in logs and metrics

- **WHEN** ClickHouse refuses a batch insert with error code 243 (`NOT_ENOUGH_SPACE`)
- **THEN** the service logs the failure as disk exhaustion, naming the target table, and increments the
  insert-failure metric with a reason distinguishing it from other insert failures

#### Scenario: The poller holds its watermark and recovers unattended

- **WHEN** a poll cycle's batch insert fails — whether refused for lack of disk space or for any other
  reason — after one or more blobs have contributed rows
- **THEN** the cycle fails without advancing the watermark, the blobs whose rows the failed insert carried
  are not recorded in the ledger, and a later cycle re-ingests them successfully once the write path is
  healthy again

#### Scenario: Another insert failure keeps its generic treatment

- **WHEN** a batch insert fails for a reason other than error code 243
- **THEN** the failure is surfaced as an ordinary run-level insert failure, not as disk exhaustion

### Requirement: Ingest observability

The service SHALL expose ingest observability through logs and Micrometer metrics, including counts
of files processed, rows inserted, rows skipped (unparseable or oversized lines), rows dropped (records lacking the
deduplication key), run outcome, run duration, and the timestamp of the last successful run. The
service SHALL additionally expose poller metrics: poll cycles executed, blobs discovered and ingested
per cycle, poll failures, and the current watermark lag (how far the watermark trails the current
time). It SHALL expose, for each ingest path (reconciliation run, poller and backfill), the number of blobs
skipped because the ingest ledger records them. The files-processed count and the poller's blobs-discovered
count SHALL count only blobs selected for ingestion, never blobs skipped by the ledger. No HTTP status
endpoint is provided in this phase.

The ingest write path SHALL additionally expose, per target table, the number of batch inserts it
issued and the number that failed, the latter tagged by reason so that a write refused for lack of disk
space is countable separately from other insert failures. One batch insert creates one part per
partition it touches, so the insert count is the service's own measure of the part pressure it imposes
on the store — a number it cannot obtain by querying, because the query surface reaches only
catalog-registered tables and ClickHouse's parts metadata is not among them. These two counts are
attributed to the target table rather than to the ingest path that produced them, unlike the per-path
row counts above: the store's part pressure is a property of the table, and every ingest path writes
through the same write path. The ledger's own inserts SHALL be counted the same way, under the ledger
table's name, so the parts the ledger adds are observable beside those of the target tables.

Under `sync.inline-files-mode=drop` or `drop-truncated`, the service SHALL additionally expose four
counters, each tagged by target table and column:
- the inline files it removed;
- the bytes of file payload it removed;
- the truncated bodies it cut back before a file;
- the bodies, or SSE events, it could not scan because they do not parse.

A truncated body that fails to parse at its cut counts as a scanned body, not as an unscannable one.
These counters are attributed to the table and column rather than to the ingest path, like the
batch-insert counts, because every ingest path writes through the same mapping. Under `keep` they SHALL
NOT be incremented. Under `drop-truncated` no complete file is removed, so the removed-files count SHALL
stay at zero; the removed bytes are those of the half files cut back over.

#### Scenario: Removed inline files are counted

- **WHEN** a run under `drop` ingests a record whose `request_body` holds two inline images totalling
  1 MB of payload
- **THEN** the removed-files count for `dial_usage_log_payload` / `request_body` is incremented by 2, and
  its removed-bytes count by the size of the two removed payloads

#### Scenario: An unscannable body is counted and stored unchanged

- **WHEN** a run under `drop` ingests a record whose `response_body` is not JSON and has no `data:` events
- **THEN** the unscannable-bodies count for that table and column is incremented by 1, and the body is
  stored unchanged

#### Scenario: Metrics reflect a completed run

- **WHEN** a sync run or poll cycle completes
- **THEN** the corresponding metrics (files processed, rows inserted/skipped/dropped, run/poll
  outcome, duration, last-success timestamp, and poller discovery/lag counters) are updated to
  reflect that run

#### Scenario: Ledger skips are counted per path

- **WHEN** a reconciliation run, poll cycle or backfill skips blobs because the ledger records them
- **THEN** that path's skipped-by-ledger count is incremented by the number of such blobs, and neither its
  files-processed nor its blobs-discovered count is

#### Scenario: Ledger inserts are counted under the ledger table

- **WHEN** a path records blobs in the ledger
- **THEN** the per-table batch-insert count for the ledger table is incremented, separately from the target
  tables' counts

#### Scenario: Dropped records are counted

- **WHEN** a run encounters records lacking a deduplication key
- **THEN** the dropped-rows metric is incremented by the number of such records

#### Scenario: Batch inserts are counted per table

- **WHEN** an ingestion run flushes batches to more than one target table
- **THEN** each table's insert count reflects the batch inserts issued for that table, so the
  part-creation rate per table is observable independently of the row counts

#### Scenario: A refused insert is counted by reason

- **WHEN** a batch insert fails because ClickHouse refused it for lack of disk space
- **THEN** the insert-failure metric for that table is incremented with a reason distinguishing disk
  exhaustion from other insert failures

### Requirement: Supported ClickHouse server version

The service SHALL run against ClickHouse **25.3 LTS or newer** and SHALL be verified through
**26.8 LTS**.

The floor is 25.3 because that is where the service's only hard server requirement sits — the `JSON`
type used by `jwt_claims`/`request_tags` becoming generally available. Nothing in the service requires
26.x, so the floor SHALL NOT be raised merely because a newer line has been verified. The upper bound
names the newest version the ClickHouse-backed integration suite is run against; a deployment inside
the range SHALL require no service configuration change, and no migration, to move between versions.

An optional capability MAY require a server newer than the floor without raising it, provided the
capability is off by default and its own spec names the version it needs, so that a deployment on the
floor loses nothing it has not turned on. The AI query functions are the one such capability: they
render onto ClickHouse's native AI functions, which the server ships from **26.8** and not at all on
26.3, so enabling them requires 26.8 while every other surface keeps the 25.3 floor.

The service SHALL NOT inspect or gate on the server version. There is no version check and none is to
be added: a server that lacks a feature the service needs SHALL fail at that feature — a `CREATE TABLE`
that cannot be provisioned aborts startup by the existing migration-failure path — rather than at a
version comparison, which would refuse a working server for a version string.

Where a server default changes inside the supported range, the service SHALL pin the setting when a
shipped guarantee depends on its value, and SHALL otherwise follow the server rather than tracking
ClickHouse defaults. `async_insert` — which the server defaults to `1` from 26.2 — SHALL be pinned to
`0` on every client that inserts rows, because the deduplication version columns
(`dial_usage_log._ingested_at`, and `_updated_at` on a catalog table) are server-evaluated
`DEFAULT now64(3)` values that no client supplies, and the async buffer evaluates a column default once
per flush rather than once per insert. Writes coalesced into one flush would therefore share a single
version stamp, leaving `ReplacingMergeTree` no ordering to collapse by, whereas one insert per request
orders duplicate writes of a key by the millisecond they arrived in. The ordering the pin restores is
that of the version column's own resolution and no finer: writes of the same key inside one millisecond
tie whether or not the buffer is in play, and on a tie the surviving row is unspecified — so
`table-management`'s version contract holds per writer at millisecond granularity, not for arbitrarily
concurrent writers. Pinning has no effect on a server older than 26.2, so ingest semantics SHALL be
identical across the supported range. `INSERT … SELECT` and DDL are not routed through the buffer and
SHALL need no pin.

The documentation SHALL state the rollback constraint: a part written by 25.11 or newer cannot be read
by a server older than 25.10, and a `JSON` column written by 25.12 or newer cannot be read below 25.12,
so an operator who must preserve the ability to roll back SHALL set the server's `compatibility`
profile — naming the version to roll back to — before starting the newer version. These are MergeTree
settings rather than query settings, so the service cannot set them per statement, and nothing can
retrofit a part already written; that is why it is a documented operator obligation and not a runtime
check. The documentation SHALL also state that the profile reverts every server default changed after
the version it names, not only the part format, so that the operator sees what else the choice costs.

The documentation SHALL also state the softer constraint 26.8 adds: from 26.8 the server writes a part's
skip indexes into one packed file (`packed_skip_index_max_bytes`, `0` before 26.8), which an older server
reads past rather than refuses, so after a rollback to 26.3 every index on a part written by 26.8 is
inert until that part is re-merged — a cost in read work, not in data. It SHALL name the two ways to keep
the classic layout while on 26.8 — the `compatibility` profile, or `packed_skip_index_max_bytes = 0` in
the server's `merge_tree` configuration — and SHALL warn against carrying that setting in a table's own
definition, which the older server cannot load. No serialization-format default moved between 26.3 and
26.8, so the 25.10 / 25.12 floors above are the only data-level doors in the supported range.

#### Scenario: Provisioning and querying on the newest verified version

- **WHEN** the service starts against ClickHouse 26.8 LTS with migrations enabled and a server-side
  encryption key configured
- **THEN** Flyway provisions `dial_usage_log` — including the `AES_256_GCM_SIV` body columns and the
  `jwt_claims`/`request_tags` `JSON` columns — and the query, ingest and enrichment paths serve
  requests, with no service configuration that names a server version

#### Scenario: Provisioning and querying on the floor version

- **WHEN** the service starts against ClickHouse 25.3 LTS
- **THEN** provisioning and the query, ingest and enrichment paths return the same results, because the
  supported contract is a range and not a single pinned version; what the range does carry is
  server-side planning differences that change cost rather than answers — the server does not consult
  skip indexes under `FINAL` before 25.6 — and the floor is not covered by the integration suite, which
  runs against the newest verified version only

#### Scenario: An optional capability above the floor costs the floor nothing

- **WHEN** the service starts against ClickHouse 26.3 with the AI query functions left disabled
- **THEN** every other surface works as on 26.8, and nothing the service runs on that server refers to
  an AI function; enabling the capability is what requires 26.8

#### Scenario: No version gate refuses a working server

- **WHEN** the service starts against any ClickHouse server in the supported range
- **THEN** the service performs no server-version comparison, and a server missing a required feature
  fails at the DDL or query that needs it rather than at a version check

#### Scenario: A row insert is one server-side insert whatever the server default

- **WHEN** the server defaults `async_insert` to `1` and the service inserts a `JSONEachRow` batch
- **THEN** the batch is not routed through the server's async-insert buffer, so it carries its own
  evaluation of the target's version-column default rather than sharing one with every other batch in
  the same flush window, and a re-ingested batch written a millisecond or more later collapses to the
  newer version

#### Scenario: The rollback constraint is documented for operators

- **WHEN** an operator reads the ClickHouse server requirements before moving a deployment onto 26.x
- **THEN** it states that a part written by 25.11 or newer cannot be read below 25.10 (25.12 for the
  `JSON` columns), that the `compatibility` profile must name the rollback target and be set before the
  upgrade, what else that profile reverts, and that staging through an intermediate LTS does not avoid
  the constraint

#### Scenario: The packed-skip-index rollback cost is documented for operators

- **WHEN** an operator reads the ClickHouse server requirements before moving a deployment from 26.3 to
  26.8
- **THEN** it states that 26.3 reads parts written by 26.8 but ignores their packed skip indexes until the
  parts are re-merged, names the `compatibility` profile and the server-level
  `packed_skip_index_max_bytes = 0` as the two ways to keep the classic layout, and warns that the setting
  must not be written into a table definition that the older server would have to load

### Requirement: Target tables are provisioned by versioned ClickHouse migrations

By default the service SHALL provision the target ClickHouse tables (`dial_usage_log` and
`dial_usage_log_payload`) and the ingest ledger table (`_dial_internal_ingested_blob`) on startup by running Flyway migrations under `classpath:db/migration` over
the ClickHouse JDBC driver, using the writer credential (which must therefore additionally hold
`CREATE TABLE` grants, and `ALTER DROP COLUMN` on `dial_usage_log`). Provisioning SHALL be gated by
`clickhouse.migrations.enabled` (default true) and SHALL run before any sync run or startup schema
validation; a migration failure SHALL abort startup. When migrations are disabled, the service SHALL
issue no DDL and the operator SHALL provision the tables out of band by applying the same migration
DDL, in version order: the `CREATE TABLE`
(`src/main/resources/db/migration/clickhouse/V1__create_dial_usage_log.sql`) followed by every later
migration that alters it or creates a sibling table. An operator who skips a later migration SHALL be
stopped by startup schema validation rather than ingesting against a stale table. The ingest ledger table is
not an ingest target, but startup schema validation SHALL cover it as well, so a deployment that skipped its
migration does not start.

Whether applied by Flyway or by an operator, the `dial_usage_log` definition SHALL declare the `events`
column set: a synthetic `event_id UUID` and an `event_kind` classifier; the direct columns carried from
the source (timestamps, ids, `user_hash`, tokens — including the producer's reported cache-write count
in `cache_creation_tokens` — prices, the producer's reported `operation_duration_ms`, the producer's
reported route in `route_name` and `route_type`, etc.); and the calculated columns (`response_id`,
`reasoning_tokens`, `number_request_messages`, `success`, `mcp_method`, `mcp_tool_call_name`,
`request_body_bytes`, `response_body_bytes`). It SHALL NOT declare the five verbatim-content columns — the three bodies and the two JSON documents. They were created by
`V1`/`V7`, moved to `dial_usage_log_payload` by `V10` and the mapping cutover, and dropped from
`dial_usage_log` by `V11`; the migration set as a whole therefore leaves them on the payload table
only. The two `*_bytes` columns SHALL survive that drop, because they size a payload without holding
one and must remain answerable past the payload table's retention.

On `dial_usage_log_payload`, the JSON columns use ClickHouse's `JSON` type, which is generally
available across the supported server range (ClickHouse **25.3 LTS or newer**, verified through
**26.8 LTS**) and SHALL require no experimental enablement setting; the body columns use the
`AES_256_GCM_SIV` codec, which requires a server-side encryption key or `CREATE TABLE` fails. That key
SHALL also be required to provision `dial_usage_log` from scratch, because `V1` creates its body
columns under the same codec before `V11` drops them. For record-level deduplication to function,
`dial_usage_log` SHALL use the `ReplacingMergeTree` engine versioned by `_ingested_at`, with a sort key
(`ORDER BY`) ending in the deduplication key columns `trace_id` and `core_span_id`, partitioned by
`toYYYYMMDD(request_time)`. The definition SHALL also pin UTC on its timestamp columns, store monetary
columns as `Decimal`, store `response_status` as `UInt16`, declare the producer-optional columns as
`Nullable` — including every producer-reported token count and the two route columns — and apply a
row-level retention `TTL`.

Each table SHALL leave exactly one column to a table-side `DEFAULT` and populate every other column its
mapping declares: `_ingested_at` on `dial_usage_log`, `_updated_at` on `dial_usage_log_payload`.

#### Scenario: Service provisions the table on startup

- **WHEN** migrations are enabled (the default) and the service starts
- **THEN** Flyway applies the `db/migration` DDL with the writer credential, creating `dial_usage_log`,
  `dial_usage_log_payload` and `_dial_internal_ingested_blob` if they do not exist, before any sync run or schema
  validation

#### Scenario: JSON columns provision without an experimental flag

- **WHEN** the table is provisioned on a supported server version (ClickHouse 25.3 LTS or newer,
  verified through 26.8 LTS) with no `allow_experimental_json_type` setting configured
- **THEN** the `CREATE TABLE` for the `jwt_claims`/`request_tags` `JSON` columns succeeds, because the
  `JSON` type is generally available on that version

#### Scenario: Migration failure aborts startup

- **WHEN** a startup migration fails (e.g. the writer credential lacks `CREATE TABLE` or a required
  server-side feature is unavailable)
- **THEN** the service fails to start rather than proceeding to run sync against an unprovisioned table

#### Scenario: Migrations disabled — operator provisions out of band

- **WHEN** `clickhouse.migrations.enabled` is false
- **THEN** the service issues no DDL and inserts into the tables the operator provisioned out of band
  from the same migration DDL

#### Scenario: Operator misses a later migration

- **WHEN** `clickhouse.migrations.enabled` is false and the operator provisioned `dial_usage_log` from
  the `CREATE TABLE` alone, omitting the later migration that creates `dial_usage_log_payload`
- **THEN** startup schema validation fails, because the payload mapping cannot be reconciled against a
  table that does not exist, and the service does not start

#### Scenario: Table definition supports record-level dedup

- **WHEN** the tables are provisioned (by Flyway or by an operator) from the migration DDL
- **THEN** `dial_usage_log` is a `ReplacingMergeTree` whose sort key ends in `trace_id` and
  `core_span_id`, and `dial_usage_log_payload` is a `ReplacingMergeTree` ordered by `event_id`, so
  re-ingested rows for the same span collapse on merge in both

#### Scenario: Every user column is populated

- **WHEN** the tables are provisioned and ingestion runs with the shipped mappings
- **THEN** every column of each table that its mapping declares is populated by that mapping, and the
  only columns left to a value the mapping did not supply are the two table-side defaults
  (`_ingested_at`, `_updated_at`)

#### Scenario: Operator misses the ledger migration

- **WHEN** `clickhouse.migrations.enabled` is false, schema validation is enabled, and the operator
  provisioned every ingest table but not `_dial_internal_ingested_blob`
- **THEN** startup schema validation fails and the service does not start

### Requirement: Retention expiry drops whole daily partitions

Every ingest target's `TTL` expiry SHALL drop whole parts rather than rewrite them
(`ttl_only_drop_parts = 1`), so that deleting expired rows requires no free disk space. A row-level TTL
rewrite writes its output before releasing its input, so it needs headroom equal to the part it
rewrites — on a volume that has filled up, the mechanism meant to reclaim space is the one thing that
cannot run.

Because every such table is partitioned by `toYYYYMMDD(request_time)` and expresses its `TTL` on
`request_time`, a part holds one day's rows and they expire within the same day of each other. Expiry is
therefore quantized to a part rather than exact to the row: a part is dropped once its newest row has
expired, so a row MAY outlive its `TTL` by up to the span its part covers (at most one day). Against a
one-year retention, and against the payload table's ninety days, this is immaterial, and it is the
deliberate trade for expiry that can run on a full volume.

The setting SHALL be applied by a versioned ClickHouse migration — in the `CREATE TABLE` for a table
that declares it from the outset, or by a `MODIFY SETTING` migration for one provisioned before the rule
existed — so that it is provisioned identically whether Flyway applies it or an operator applies the DDL
out of band in version order.

#### Scenario: Provisioned table declares whole-part expiry

- **WHEN** the ingest target tables are provisioned (by Flyway or by an operator applying the migrations
  in version order)
- **THEN** each declaration carries `ttl_only_drop_parts = 1`, so a later `TTL` expiry drops expired
  daily parts instead of rewriting them

#### Scenario: Expiry needs no free space

- **WHEN** a retention `TTL` becomes due for a day's partition while the volume has no headroom for a
  part rewrite
- **THEN** the expired parts are dropped rather than rewritten, so expiry proceeds and returns space
  instead of failing for lack of it

### Requirement: Authentication claims and request headers are captured

The service SHALL populate the `jwt_claims` column from the record's top-level `claims` object and the
`request_tags` column from the record's top-level `headers` object, storing each as a JSON object that
preserves the source subtree verbatim. `request_tags` SHALL capture the headers as emitted by the
producer without any allowlist or denylist filtering in this service; excluding transport/SDK-noise
headers is a producer-side (DIAL Core) responsibility. When the corresponding source object is absent
from the record, the column SHALL store the empty JSON object (the ClickHouse `JSON` column's default),
because these columns are not nullable.

#### Scenario: JWT claims are captured verbatim

- **WHEN** a record carries a top-level `claims` object
- **THEN** the ingested row's `jwt_claims` is a JSON object equal to that `claims` subtree

#### Scenario: Request headers are captured verbatim

- **WHEN** a record carries a top-level `headers` object
- **THEN** the ingested row's `request_tags` is a JSON object equal to that `headers` subtree, with no
  headers dropped or filtered by the service

#### Scenario: Absent claims or headers store an empty object

- **WHEN** a record has no top-level `claims` (or no `headers`) object
- **THEN** the corresponding column stores the empty JSON object `{}`

### Requirement: Configurable column mapping

The service SHALL load each ingest target's column structure from an external mapping file whose path
is configured per target (via `sync.targets[*].mapping-file`; the first target's location is
overridable through the `SYNC_MAPPING_FILE` environment-driven property). The mapping SHALL declare an
ordered list of columns. A **mapped** column entry SHALL specify a ClickHouse column name, a `source`
(a dotted JSON path or the `$sourceFile` synthetic token), and an extraction kind, and MAY specify a
named transform and a default value. A **derived** column entry SHALL instead specify a `derive` name
and an ordered non-empty `sources` list (and MAY specify an `args` map and a default), and MUST NOT
specify a `source`. The service MUST NOT hard-code column names, source paths, or types in code —
derived columns keep their column name and source paths in the mapping, with only the reusable
derivation logic in code (see "Closed named deriver registry"). The `$sourceFile` token SHALL resolve
to the originating blob name; any other `$`-prefixed source token MUST be rejected as a configuration
error at load time. The shipped default mapping SHALL produce the `events` column set.

A mapped column of kind `string` MAY declare an `inline-files` attribute, which marks the column as a
body whose inline files `sync.inline-files-mode` governs (see "Inline files are removed from payload
bodies in drop mode"). The attribute SHALL carry:
- `body`, either `request` or `response`, which selects the location set the column is scanned against;
- `uri`, the dotted source path of the record's request URI, from which the API is inferred.

The service SHALL reject a mapping at load time when any of these holds:
- the attribute sits on a derived column, a `$sourceFile` column or a non-`string` column;
- the attribute sits on a column that also declares a `transform`;
- `body` is missing or is not `request` or `response`;
- `uri` is missing, blank or a `$`-prefixed token.

The attribute changes nothing about how the column's value is resolved, coerced or defaulted: the removal
applies to the resolved value, and the absent/blank fallback applies as for any column. The shipped payload
mapping SHALL declare `inline-files` on `request_body` (`body: request`) and on `response_body` and
`assembled_response` (`body: response`), each with `uri: request.uri`.

#### Scenario: Columns come from the mapping file

- **WHEN** the service starts with a mapping file declaring a set of columns
- **THEN** the inserted rows contain exactly those columns, sourced (or derived) and typed as the
  mapping declares

#### Scenario: Derived column entry is loaded

- **WHEN** a column entry declares a `derive` name with a `sources` list
- **THEN** the service loads it as a derived column populated at ingest by the named deriver

#### Scenario: Source file token is injected

- **WHEN** a column declares `$sourceFile` as its source
- **THEN** that column is populated with the originating blob name for every row

#### Scenario: Unknown synthetic token is rejected

- **WHEN** the mapping declares a `$`-prefixed source other than `$sourceFile`
- **THEN** the service rejects the mapping at load time as a configuration error

#### Scenario: An inline-files body column is loaded

- **WHEN** a mapped `string` column declares `inline-files: { body: request, uri: request.uri }`
- **THEN** the service loads it as a request body column whose inline files the configured mode governs

#### Scenario: A misplaced inline-files attribute is rejected

- **WHEN** a derived column, a non-`string` column, or a column that also declares a `transform` declares
  `inline-files`, or the attribute's `body` is neither `request` nor `response`
- **THEN** the service rejects the mapping at load time as a configuration error

### Requirement: Multiple ingest targets

The service SHALL support a configurable list of ingest **targets**, where each target pairs a
ClickHouse table name with its own column mapping (see "Configurable column mapping") and an optional
match predicate (see "Conditional row emission via a match predicate"). During a run, each parsed
record SHALL be offered to every configured target; a target that emits for the record (its predicate
matches, or it declares none) produces one row from that target's mapping, so a single record MAY
produce rows in more than one target table. Each target SHALL batch and insert into its own table via
the dedicated writer credential, and the fail-fast startup schema validation SHALL be applied to each
target independently. The default configuration SHALL include three targets — `dial_usage_log` and
`dial_usage_log_payload` with no match predicate, and `rate_analytics` with one — and the configuration
SHALL be environment-driven, each target's table name and mapping file independently overridable.

The deduplication-key drop rule (see "Records without a deduplication key are dropped") SHALL apply
per target: a record lacking the key columns a target relies on is dropped for that target and counted,
independently of other targets.

#### Scenario: A record fans out to multiple targets

- **WHEN** a run has two configured targets and a parsed record satisfies both targets' emission rules
- **THEN** the service writes one row into each target's table from that target's mapping

#### Scenario: Default single target preserves existing behavior

- **WHEN** only the `dial_usage_log` target is configured
- **THEN** every parsed record is mapped and written to `dial_usage_log` exactly as before, and no
  other table is written

#### Scenario: The shipped configuration writes the log and its payload

- **WHEN** the default target list is configured and a record is parsed
- **THEN** the service writes a `dial_usage_log` row and a `dial_usage_log_payload` row for it, and a
  `rate_analytics` row as well when the record matches that target's predicate

#### Scenario: Per-target startup validation

- **WHEN** the service starts with multiple targets configured
- **THEN** it validates each target's mapping against its own table schema and fails startup if any
  target's mapping diverges from its table

### Requirement: Conditional row emission via a match predicate

The service SHALL emit a target's row conditionally based on an optional `match` block that the
target's mapping MAY declare: an ordered list of conditions, each naming a dotted source path within
the record, a comparison operator (at least `ends-with`, `equals`, and `contains`), a value, and an
optional case-insensitivity flag (`ignore-case`, default false) that folds case on both sides of the
comparison. The service SHALL emit a row for that target only when ALL declared conditions hold
(logical AND) for the record. A mapping that declares no `match` block SHALL emit a row for every
record. Unknown operators SHALL be rejected at load time (fail-fast), consistent with the closed
transform and deriver registries.

#### Scenario: Record matches the predicate

- **WHEN** a target mapping declares `match` requiring `request.uri` ends-with `/rate` and
  `request.method` equals `POST`, and a record satisfies both
- **THEN** the target emits a row for that record

#### Scenario: Record fails the predicate

- **WHEN** a record fails any one of a target's `match` conditions
- **THEN** the target emits no row for that record

#### Scenario: Case-insensitive condition matches regardless of case

- **WHEN** a target's `match` condition sets `ignore-case: true` (e.g. `request.method` equals `POST`)
  and a record's value differs only in case (e.g. `post`)
- **THEN** the condition holds and the target emits a row for that record

#### Scenario: No predicate emits for all records

- **WHEN** a target mapping declares no `match` block
- **THEN** the target emits a row for every parsed record

#### Scenario: Unknown operator fails startup

- **WHEN** a mapping's `match` block uses an operator that is not registered
- **THEN** the service fails fast at startup rather than loading the mapping

### Requirement: Closed named transform registry

The service SHALL support a fixed, named set of transforms referenced by columns in the mapping, and
SHALL NOT support arbitrary user-supplied expressions or code. The registry SHALL include at least
`iso-to-ch-datetime` (convert an ISO-like timestamp to a ClickHouse `DateTime64` literal),
`string-array` (coerce a JSON array to a list of strings), and `lowercase-json-keys` (lower-case the
keys of a JSON object). A column referencing a transform name that
is not in the registry SHALL cause a fatal configuration error at load time.

`lowercase-json-keys` SHALL apply to a column of `object` kind and SHALL lower-case the object's
**top-level member names** only, leaving every value byte-for-byte unchanged and leaving nested
objects untouched. Where two source members differ only in key case, which one survives SHALL be
unspecified — the mapping is for header maps, where a producer sends one spelling per request. The
`dial_usage_log` mapping SHALL apply it to `request_tags`, so that header keys are stored in a single
case going forward.

This transform applies at ingest and therefore to newly ingested rows only. Rows stored before it is
deployed keep their original key case, so `request_tags` carries mixed key case across the cutover.
Readers that must span both sides SHALL use the case-insensitive extraction function rather than
assume a single spelling (see [structured-query](../structured-query/spec.md),
`json_extract_string_ci`). Reconciling the stored history is explicitly out of scope: no re-ingest and
no in-place rewrite is implied by this transform.

#### Scenario: Known transform is applied

- **WHEN** a column declares the `iso-to-ch-datetime` transform and its source value is an ISO-like
  timestamp
- **THEN** the inserted value is the timestamp rendered as a ClickHouse `DateTime64` literal

#### Scenario: Unknown transform is rejected

- **WHEN** a column references a transform name that is not in the registry
- **THEN** the service fails to load the mapping with a configuration error

#### Scenario: Header keys are lower-cased at ingest

- **WHEN** a record's headers object carries `X-Claude-Code-Session-Id` and the `request_tags` column
  declares the `lowercase-json-keys` transform
- **THEN** the stored document carries the member under `x-claude-code-session-id`, and its value is
  unchanged

#### Scenario: Only keys are lower-cased, never values

- **WHEN** the same transform is applied to a document whose values carry upper-case characters — a
  mixed-case user agent, an e-mail address
- **THEN** every value is stored byte-for-byte as received

#### Scenario: History is unaffected

- **WHEN** the transform is deployed
- **THEN** rows ingested before the deploy keep their original key case, and no re-ingest or in-place
  rewrite is triggered

### Requirement: Closed named deriver registry

The service SHALL support a fixed, named set of **derivers** — derivation functions that compute a
column value from one or more source nodes of the parsed record — referenced by columns in the
mapping, and SHALL NOT support arbitrary user-supplied expressions or code. A derived column entry
SHALL declare a `derive` name, an ordered non-empty `sources` list of dotted JSON paths, and MAY
declare an `args` map of static parameters; it MUST NOT also declare a `source`. The registry SHALL
include derivers sufficient to populate the calculated `events` columns: a deterministic UUID hash
over its concatenated sources (for `event_id`); a request-URI classifier (for `event_kind`); JSON
sub-document field extractors that re-parse a source value that is itself a JSON string (or read it
directly when already an object) and read a dotted `args.path` as a string or as an unsigned integer
(for `response_id`, `reasoning_tokens`, `mcp_method`, `mcp_tool_call_name`, `rate_analytics.rate_raw`);
a JSON array length (for `number_request_messages`); a byte length of a source string (for
`request_body_bytes`, `response_body_bytes`); an HTTP-success predicate over `response.status`
(for `success`); and a **rate normalizer** that reads a JSON `rate` value at `args.path` and
normalizes it to a signed integer (for `rate_analytics.rate`) — a numeric value passes through as its
integer value, boolean `true` maps to `1` and `false` maps to `0`, and an absent or blank value yields
null (subject to the column's nullability fallback). A column referencing a deriver name that is not
in the registry, or a derived column missing its `sources` list, SHALL cause a fatal configuration
error at load time.

The **string extractor** reads whatever scalar the path resolves to, not only a JSON string: a boolean
member SHALL yield the text `true` or `false`, a numeric member SHALL yield its literal text, and a
string member SHALL yield its unquoted content. A member that is an object or an array SHALL yield no
value, so the column takes its absent/blank fallback rather than a serialized document. This is a
statement of shipped behavior, made explicit because a column may depend on it to preserve the
submitted form of a polymorphic field (`rate_analytics.rate_raw`) rather than merely to read text.

A JSON-extraction deriver reads only sources that parse as a JSON document; a source that does not
(such as an SSE transcript) SHALL be skipped as if absent. A value the producer also reports outside
the response payload SHALL therefore be mapped from that report rather than derived from the payload,
so that its column does not depend on the response being a parseable document.

#### Scenario: Derived column is computed by its named deriver

- **WHEN** a column declares a `derive` name with a `sources` list (and optional `args`)
- **THEN** the inserted value is computed by the named deriver from the resolved source nodes, then
  subjected to the same absent/blank fallback (default, else null if nullable, else the kind's zero
  value) as a mapped column

#### Scenario: event_id is deterministic across re-ingests

- **WHEN** two records share the same `trace_id` and `core_span_id`
- **THEN** the configured `event_id` deriver produces the identical UUID for both, so it is a stable
  proxy for the natural `(trace_id, core_span_id)` deduplication key

#### Scenario: Embedded-JSON extraction reads a nested field

- **WHEN** a JSON-extraction deriver is configured with ordered `sources` and an `args.path`, and the
  first source whose value is present parses as a JSON document
- **THEN** the deriver returns that document's value at `args.path`, falling through to the next source
  when an earlier source is absent or lacks the path

#### Scenario: Unparseable source is skipped

- **WHEN** every source of a JSON-extraction deriver holds text that is not a JSON document (such as an
  SSE event stream)
- **THEN** the deriver yields no value and the column takes its absent/blank fallback, rather than the
  ingest failing

#### Scenario: rate normalizer maps booleans and numbers

- **WHEN** the rate-normalizer deriver reads a `rate` value at its `args.path` that is `true`, `false`,
  or a number
- **THEN** it yields `1`, `0`, or the number's integer value respectively, and null when the value is
  absent or blank

#### Scenario: String extractor preserves a non-string scalar's submitted form

- **WHEN** the string-extractor deriver reads a value at its `args.path` that is `true`, `false`, or a
  number
- **THEN** it yields the text `true`, `false`, or the number's literal text respectively, so that a
  boolean submission stays distinguishable from the numeric value the rate normalizer maps it to

#### Scenario: String extractor yields no value for a non-scalar

- **WHEN** the string-extractor deriver reads a value at its `args.path` that is an object or an array
- **THEN** it yields no value and the column takes its absent/blank fallback, rather than serializing
  the document or failing the ingest

#### Scenario: Unknown or malformed deriver entry is rejected

- **WHEN** a column references a deriver name that is not in the registry, or declares `derive` without
  a non-empty `sources` list, or declares both `source` and `derive`
- **THEN** the service fails to load the mapping with a configuration error

### Requirement: Object extraction kind for JSON columns

The mapping engine SHALL provide an **object** extraction kind that resolves a source JSON object node
and carries it through unchanged as the column value, so it serializes into a ClickHouse `JSON` column.
For a column of this kind whose source is absent or blank, the service SHALL apply the standard
absent/blank fallback: the configured default if present; otherwise `null` if the column is declared
`nullable`; otherwise the kind's zero value, which for the object kind SHALL be the empty JSON object.

#### Scenario: Object source is carried through to a JSON column

- **WHEN** a column declares the object kind over a source path that resolves to a JSON object
- **THEN** the inserted value is that object, serialized as JSON into the target `JSON` column

#### Scenario: Object kind reconciles with a JSON table column

- **WHEN** a column declared with the object kind is validated against a table column of ClickHouse
  type `JSON`
- **THEN** startup schema validation treats the types as compatible

### Requirement: Fail-fast startup validation against the table schema

On startup, before any sync run can execute, the service SHALL introspect the target table's actual
columns and types (via `DESCRIBE TABLE`) and reconcile them with the configured mapping. Every
configured column MUST exist in the table, and each configured extraction kind MUST be compatible with
the table column's declared type, normalizing `Nullable(...)`, `LowCardinality(...)`, `Decimal`,
unsigned-integer, `UUID`, `Bool`, `JSON`, and timezone-qualified `DateTime` spellings before comparison.
A column declared `nullable` in the mapping MUST be backed by a `Nullable(...)` table column, and a
column not declared nullable MUST NOT be. If reconciliation fails, the service SHALL refuse to start
and SHALL report the offending column(s). Schema validation itself SHALL issue no DDL — it only
introspects via `DESCRIBE TABLE`; table provisioning, when enabled, has already run in the earlier
Flyway migration step. Table columns that the mapping does not populate — a column with a table-side
default (e.g. `_ingested_at`), or any column an operator's overridden mapping chooses to omit — MAY be
absent from the mapping.

#### Scenario: Matching mapping starts cleanly

- **WHEN** every configured column exists in the target table with a compatible type and matching
  nullability
- **THEN** the service starts and the scheduled sync job is allowed to run

#### Scenario: Mismatched mapping prevents startup

- **WHEN** a configured column is missing from the target table, its kind is incompatible with the
  table column's type, or its declared nullability disagrees with the table column
- **THEN** the service fails to start and reports the offending column(s)

#### Scenario: Unmapped table-default column is permitted

- **WHEN** the table declares a column populated by a table-side default (`_ingested_at`) and the
  mapping does not declare it
- **THEN** reconciliation succeeds, because the mapping need not populate every table column

### Requirement: Primary-key deduplication

The service SHALL rely on the target table's `ReplacingMergeTree` engine to deduplicate records by
their primary key, the combination of `trace_id` and `core_span_id` (one proxy request maps to one
span and one log record). The engine is the correctness guarantee: the service SHALL NOT assume that a
blob is ingested only once, and SHALL NOT delete or update rows to remove duplicates. To avoid writing
duplicates in the first place, every ingest path SHALL skip a blob the ingest ledger records — except an
operator backfill that asks to include already-ingested blobs; no path SHALL pre-check individual rows or
query the target tables for existing files. A blob without a matching ledger entry — and every blob such a
backfill ingests — is re-parsed and re-inserted, and the engine collapses duplicate spans on merge,
retaining the copy with the greatest `_ingested_at`. Each ingested row SHALL continue to record the
originating blob name in `_source_file` as provenance. Because engine deduplication is eventual,
documentation SHALL state that reads requiring exact, duplicate-free results use `FINAL` or
`GROUP BY`/`argMax`.

#### Scenario: Re-ingested span collapses to one row

- **WHEN** the same usage record (identical `trace_id` and `core_span_id`) is ingested more than once
- **THEN** after merge the table retains a single row for that span, keeping the latest-ingested copy

#### Scenario: A blob recorded in the ledger is not re-inserted

- **WHEN** a reconciliation run, a poll cycle or a default backfill meets a blob the ledger records
- **THEN** the service neither parses nor inserts it, without querying any target table

#### Scenario: No file or row is skipped as already-ingested

- **WHEN** a sync run processes a blob whose records were ingested earlier but which the ledger does not
  record
- **THEN** the service re-parses and re-inserts the records without an existence pre-check of rows or
  files, and the engine collapses the duplicates

#### Scenario: Provenance is still recorded

- **WHEN** a record is ingested
- **THEN** its row records the originating blob name in `_source_file`, even though that column is not
  the deduplication key

### Requirement: Records without a deduplication key are dropped

The service SHALL exclude from ingestion any record whose `trace_id` or `core_span_id` is absent or
blank, because such records would otherwise collapse together under the deduplication key and lose
data. Each excluded record SHALL be logged and counted in a dropped-rows metric, and its exclusion
MUST NOT abort ingestion of the remaining records or files.

#### Scenario: Record missing the dedup key is dropped

- **WHEN** a record has an absent or blank `trace_id` or `core_span_id`
- **THEN** the service does not insert that record, logs it, increments the dropped-rows metric, and
  continues processing the remaining records and files

### Requirement: Body columns are captured only by the payload table's mapping

The three unbounded body columns — `request_body`, `response_body`, `assembled_response` — SHALL be
persisted to their own ClickHouse table, `dial_usage_log_payload`, and SHALL NOT be persisted to
`dial_usage_log`. This is enforced at both levels: no mapping targeting `dial_usage_log` captures a
value for any of them, and `V11` has dropped the columns from that table, so there is nothing there to
write to. The bodies are stored as the producer sent them, except that `sync.inline-files-mode=drop`
removes the payloads of their inline files and `drop-truncated` cuts a body truncated inside a file back to
before it (see "Inline files are removed from payload bodies in drop mode").

The table SHALL be provisioned by a versioned ClickHouse migration (never by the `/v1/tables` API) and
SHALL declare:

- the grain column `event_id UUID`, which is also its sole `ORDER BY` column and therefore its unique
  key;
- `request_time`, which drives both `PARTITION BY toYYYYMMDD(request_time)` and the retention `TTL`;
- `trace_id` and `core_span_id`, so the deduplication-key drop rule is decidable on a payload row;
- the three body columns as `Nullable(String)` under the `AES_256_GCM_SIV` codec (which requires a
  server-side encryption key, or `CREATE TABLE` fails);
- the claim and header documents `jwt_claims` and `request_tags` as `JSON`, carried across verbatim
  from `dial_usage_log` — same type, no codec. They are necessarily NON-nullable, because ClickHouse
  has no `Nullable(JSON)`;
- the `ReplacingMergeTree` engine versioned by a table-side `_updated_at` default, so a re-ingested
  record replaces its predecessor for the same `event_id` in the same daily partition.

`_updated_at` SHALL be the only column of this table the shipped mapping leaves unpopulated.

One parsed record SHALL produce a `dial_usage_log` row **and** a `dial_usage_log_payload` row, from two
mappings evaluated over the same record under the same match-all predicate, carrying the same
`event_id`. Because both targets apply the same deduplication-key drop rule to the same record, the two
rows SHALL be either both emitted or both dropped — so an ingested `dial_usage_log` row SHALL never
lack its payload row for a reason internal to ingest.

**The payload row SHALL become visible no later than its `dial_usage_log` row.** The two targets batch
independently, so the run sink SHALL flush in dependency order: when the `dial_usage_log` sink reaches
its threshold, or at end of run, the sinks holding its enrichments (the payload sink) SHALL be written
first and the source sink after; the payload sink reaching its own threshold MAY flush alone. A failed
payload flush SHALL propagate before the source rows of the same batch are written, so a run faults
with both tables equally behind rather than with usage-log rows whose payloads never arrive. Which
targets enrich which SHALL be read from the catalog (`table_definition.type = ENRICHMENT` and
`source_table`), once per run. The reason is the read side: every consumer of the grain join — a
query, an enrichment scan, an aggregate refresh — treats an unmatched payload as absent and final, and
a scan that read a source row while its payload row was still buffered would derive a value from a
`NULL` body and never revisit the row. The freshness classification for such an enrichment is
`ingest_written` (see the pipeline-registry capability), not `no_pipeline`.

`jwt_claims` and `request_tags` SHALL move with the bodies. They are small, so storage is not the
argument; they can carry bearer tokens, cookies and forwarded client IPs, so disclosure is. Because
they are non-nullable, an unmatched grain join renders them as the empty document rather than as
`NULL` — which the `enrichment-runtime` capability's nullability rule permits for `object` columns
specifically, since no accessor can distinguish `{}` from a present document lacking the key.

#### Scenario: One record produces a row in both tables

- **WHEN** a run ingests a record carrying `request.body`, `response.body`, `assembled_response`,
  `claims` and `headers`
- **THEN** `dial_usage_log` receives a row with the analytic columns, and `dial_usage_log_payload`
  receives a row keyed on the same `event_id` carrying all five verbatim values. Under
  `sync.inline-files-mode=drop` the three bodies lack the payloads of their inline files, and under `drop` or
  `drop-truncated` a body DIAL Core cut inside a file is cut back before it; they are otherwise verbatim.

#### Scenario: The payload row keys on the parent's event id

- **WHEN** a payload row is written for a record
- **THEN** its `event_id` is the same deterministic hash of (`trace_id`, `core_span_id`) that the
  record's `dial_usage_log` row carries, so the two rows join one-to-one

#### Scenario: A re-ingested record replaces its payload row

- **WHEN** the same record is ingested more than once
- **THEN** a deduplicated read of `dial_usage_log_payload` returns a single row for that `event_id`,
  the latest-written copy

#### Scenario: The payload batch is written before the usage-log batch

- **WHEN** the `dial_usage_log` sink reaches its batch threshold while payload rows for the same records
  are buffered, or the run ends with rows buffered in both sinks
- **THEN** the payload sink is flushed first and the `dial_usage_log` sink after, so no usage-log row is
  visible before its payload row

#### Scenario: A failed payload flush withholds the usage-log rows of the same batch

- **WHEN** writing the payload batch fails
- **THEN** the run faults before the `dial_usage_log` batch is written, and neither table receives the
  batch's rows until a later run re-ingests them

#### Scenario: The payload sink filling on its own flushes alone

- **WHEN** the payload sink reaches its threshold before the `dial_usage_log` sink does
- **THEN** only the payload batch is written; the usage-log sink keeps batching to its own threshold

#### Scenario: A record without a dedup key is dropped from both tables

- **WHEN** a record's `trace_id` or `core_span_id` is absent or blank
- **THEN** neither a `dial_usage_log` row nor a `dial_usage_log_payload` row is inserted for it, and the
  drop is logged and counted for each target independently

#### Scenario: The body columns are unpopulated on dial_usage_log

- **WHEN** the shipped mapping ingests a record
- **THEN** the inserted `dial_usage_log` row carries no value for `request_body`, `response_body`,
  `assembled_response`, `jwt_claims` or `request_tags`, because the mapping for that table does not
  capture them and the physical table no longer declares them

#### Scenario: The claim and header documents move with the bodies

- **WHEN** a record carrying `claims` and `headers` is ingested
- **THEN** the `dial_usage_log_payload` row carries `jwt_claims` and `request_tags`, and the
  `dial_usage_log` row carries neither

### Requirement: The verbatim-content columns are dropped from dial_usage_log

A versioned ClickHouse migration (`V11__drop_dial_usage_log_payload_columns.sql`) SHALL drop
`jwt_claims`, `request_tags`, `request_body`, `response_body` and `assembled_response` from
`dial_usage_log`, so that the five verbatim-content columns exist on `dial_usage_log_payload` alone.
Every clause SHALL use `IF EXISTS`, so re-running the migration, or applying it to an environment that
already performed the equivalent DDL out of band, SHALL be a no-op rather than an error.

**No backfill SHALL precede the drop.** The body, claim and header content of rows ingested before the
mapping cutover is deleted with the columns and SHALL NOT be recoverable from the database. This is a
deliberate acceptance, not an oversight: since the catalog re-pointed those five column names at
`dial_usage_log_payload`, that content has resolved on no query surface the service exposes, so the
drop removes storage the service could not read. An operator who requires the history SHALL copy it out
of ClickHouse before deploying; the service SHALL NOT offer a migration that preserves it.

`request_body_bytes` and `response_body_bytes` SHALL NOT be dropped. They are four bytes each, they are
the only way to size a payload before fetching it, and they must outlive the payload they describe so
that questions about historical body sizes remain answerable past the payload table's retention.

The drop SHALL disturb nothing else about the table: none of the five columns appears in
`dial_usage_log`'s sort key, carries a `DEFAULT` expression, or backs a skip index, so deduplication
and every indexed scan SHALL behave identically after the migration.

Because the shipped mapping stopped capturing these columns before this migration existed, a rolling
deploy across it SHALL be safe in both directions for any build at or after the mapping cutover: a
replica that has not yet been replaced writes neither the dropped columns nor anything that depends on
them. Rolling back to a build from before the cutover SHALL require restoring the column shape with
`ALTER TABLE ... ADD COLUMN` first, and SHALL restore them empty.

#### Scenario: The columns are absent from dial_usage_log after provisioning

- **WHEN** the ClickHouse migrations have been applied, by Flyway at startup or by an operator out of
  band
- **THEN** `dial_usage_log`'s physical schema declares none of `jwt_claims`, `request_tags`,
  `request_body`, `response_body` or `assembled_response`, while `dial_usage_log_payload` declares all
  five

#### Scenario: The body size columns survive the drop

- **WHEN** the drop migration has been applied
- **THEN** `dial_usage_log` still declares `request_body_bytes` and `response_body_bytes`, and rows
  written before the drop retain their values for both

#### Scenario: Re-applying the drop is a no-op

- **WHEN** the migration is applied to a `dial_usage_log` that no longer has the five columns
- **THEN** it succeeds without error and changes nothing, because every clause is guarded by
  `IF EXISTS`

#### Scenario: Pre-split content is not preserved

- **WHEN** `dial_usage_log` holds rows ingested before the mapping cutover, still carrying body, claim
  and header content
- **THEN** applying the drop deletes that content permanently, and no migration in the shipped set
  copies it to `dial_usage_log_payload` first

### Requirement: The payload table's retention is shorter than its parent's

`dial_usage_log_payload` SHALL declare `TTL toDateTime(request_time) + INTERVAL 90 DAY` together with
`ttl_only_drop_parts = 1`, while `dial_usage_log` retains its one-year `TTL`. Retention SHALL be
declared in the table's own `CREATE TABLE` so that Flyway and an out-of-band operator provision it
identically.

The payload retention SHALL NOT be configurable through the service: it is neither an environment
property nor a `/v1/tables` field, and an operator changes it with
`ALTER TABLE dial_usage_log_payload MODIFY TTL …`. The catalog SHALL NOT mirror it.

Expiry SHALL be quantized to a daily part exactly as the parent's is: a part is dropped once its newest
row has expired, so a payload row MAY outlive its `TTL` by at most the rest of its day.

A payload row SHALL expire independently of the `dial_usage_log` row it belongs to. A query naming a
body column for an event older than the payload retention SHALL return the parent's columns with the
body columns `NULL` — the outcome of a grain `LEFT JOIN` that finds no row — and SHALL NOT fail. The
two document columns (`jwt_claims`, `request_tags`) are non-nullable `JSON` and SHALL read as the empty
document `{}` for such an event — indistinguishable from a request that carried no claims or headers —
so a consumer distinguishing "expired" from "empty" SHALL test a body column's nullness or bound the
query to the payload window; the query surface documents this asymmetry rather than papering over it.

#### Scenario: Provisioned payload table declares the shorter retention

- **WHEN** `dial_usage_log_payload` is provisioned (by Flyway or by an operator applying the migrations
  in version order)
- **THEN** its declaration carries `TTL toDateTime(request_time) + INTERVAL 90 DAY` and
  `ttl_only_drop_parts = 1`, while `dial_usage_log` still carries its one-year `TTL`

#### Scenario: An expired payload leaves its parent row intact

- **WHEN** a day's payload partition expires and is dropped
- **THEN** the corresponding `dial_usage_log` rows remain, with every analytic column intact —
  including `request_body_bytes` and `response_body_bytes`, which outlive the bodies they measure

#### Scenario: Querying a body column past its retention yields null

- **WHEN** a query names a body column for an event whose payload partition has been dropped
- **THEN** the row is returned with that column `NULL`, and the query does not fail

#### Scenario: Querying a document column past its retention yields the empty document

- **WHEN** a query names `jwt_claims` or `request_tags` for an event whose payload partition has been
  dropped
- **THEN** the row is returned with that column `{}`, the same value a request with no claims or headers
  carries, and the query does not fail

### Requirement: Settled ingest day partitions are force-merged so their duplicates collapse

`dial_usage_log_payload`, `dial_usage_log` and `rate_analytics` — every ingest target — SHALL each carry
`min_age_to_force_merge_seconds = 172800` (2 days) together with `min_age_to_force_merge_on_partition_only = 1`.
Once every active part of a day partition is older than that interval, ClickHouse SHALL merge the partition into
a single part, and because all three are `ReplacingMergeTree` tables that merge SHALL leave exactly one row per
key — for `dial_usage_log_payload` the row for each `event_id` with the greatest `_updated_at`, and for
`dial_usage_log` and `rate_analytics` the row for each full sort key with the greatest `_ingested_at`. For
`rate_analytics` the sort key carries the span identity, so distinct rate events stay separate rows (see
[rate-analytics](../rate-analytics/spec.md) "Keep-all-ratings with idempotent re-ingest"). A partition with any part younger than the
interval SHALL NOT be force-merged, and the settings SHALL NOT alter which merges the automatic selector
already performs on it.

The settings exist because a settled day can still hold more than one version of a row — a blob re-ingested
because the service stopped, or the ledger write failed, between writing its rows and recording it; a blob
rewritten by its producer; or an operator backfill that includes already-ingested blobs — and a settled day is
otherwise never merged into one part: the automatic selector stops picking a partition once it is down to a
few similar-sized parts, so the stale versions stay until the partition expires. `rate_analytics` shows it
most plainly: its days hold a few dozen rows, and a day written twice before the ledger existed kept both
copies as two equal parts for months. Since the ingest ledger stops
the reconciliation run from re-writing what the poller already wrote, these duplicates are the exception
rather than half the partition; the force-merge remains the backstop that collapses them. `min_age_to_force_merge_on_partition_only`
is what makes the result one part: without it a partition holding a newer part could be merged piecemeal and
still be left with several.

`dial_usage_log_payload` SHALL additionally carry `vertical_merge_algorithm_min_columns_to_activate = 1` and
`vertical_merge_algorithm_min_rows_to_activate = 1`, so every merge of it that writes a wide part, forced or
chosen by the automatic selector, runs with the vertical algorithm. ClickHouse merges vertically only into a
wide part (one above `min_bytes_for_wide_part`, 10 MiB by default), which on these rows is any merge of more
than a few dozen of them; a merge of tiny parts into a compact part stays horizontal and is cheap either
way. Its rows are heavy: on the order of a hundred KiB of bodies per row, with single bodies of several
MiB, plus two JSON documents with around a hundred paths between them.
ClickHouse picks the algorithm by column count, not width, and with 8 columns outside the sort key and version
against the default threshold of 11 every merge would otherwise be horizontal, reading every column of every
source part at once. The vertical algorithm merges the key columns first and then streams the other columns one
at a time, so a merge's memory follows its widest column. On a whole-partition merge of a day's worth of
heavy rows, the vertical merge peaks at roughly a third of the horizontal merge's memory, keeps that ratio as
the row count grows, and runs faster; the extra pass reads only the key columns, a UUID and a timestamp. `dial_usage_log` SHALL NOT carry
them: its rows are light and it already reaches the default column threshold. Neither SHALL
`rate_analytics`, whose rows are lighter still.

The interval SHALL exceed the gaps between writes that the routine writers produce into one partition — the
poller during the day, the reconciliation run early on the day after for the blobs the poller missed, and a
rare straggler a day later — so a partition is not collapsed and then reopened by them. Each write restarts the partition's age. A write into a
collapsed partition, for example an operator-triggered backfill of an older day or a reconciliation run
delayed by more than the interval, is not an error; it restarts the interval, and the partition is merged
again once it has settled.

The settings SHALL be provisioned by a ClickHouse migration, in the tables' own settings so that Flyway and an
operator applying the migrations out of band provision them identically; the migration has no dependency on
the build, so an out-of-band operator MAY apply it before or after. They SHALL NOT be configurable through the
service: neither an environment property nor a `/v1/tables` field sets them, the catalog does not mirror
them, and an operator changes or removes them with `ALTER TABLE … MODIFY SETTING …` or `RESET SETTING …`.
Applying them SHALL be metadata-only — no mutation is scheduled and no part is rewritten by the migration
itself — and re-applying the migration SHALL succeed and change nothing.

Applying the settings requires the `ALTER SETTINGS` privilege on each table. `dial_usage_log` and
`rate_analytics` already needed it for their retention setting; `dial_usage_log_payload` needs it newly, so a writer credential granted `ALTER`
sub-privileges individually fails startup at this migration until it is granted — by design, a migration
failure aborts startup rather than serving against a half-provisioned table. A wholesale `ALTER` grant is
unaffected.

The settings SHALL take effect on parts that already exist. On the deployment that first applies the
migration, every existing partition whose parts are all older than the interval becomes a merge candidate at
once. Such merges run on the shared background pool, need free disk of about twice the partition's size while
they write the new part, and are bounded by the server's merge memory share; a merge that exceeds the memory
share fails and is retried. `dial_usage_log`'s and `rate_analytics`'s partitions are small, so the cost is concentrated in
`dial_usage_log_payload`, whose partitions reach tens of GiB. A deployment short of disk or memory MAY stage
the rollout by applying a longer interval by hand, with migrations disabled, and lowering it step by step to
the shipped value.

Deduplicating on disk SHALL NOT change the result of any final read. A final read — every pipeline read, which runs under `final = 1`, and every user-facing read that is read final (see
[structured-query](../structured-query/spec.md) "Read result deduplication") — continues to deduplicate at query time,
so it returns one row per key whether or not the partition has been merged; a raw user-facing read sees
exactly the rows the parts hold, fewer once a force-merge has collapsed them. Row expiry
is unaffected: each table's `TTL` and `ttl_only_drop_parts = 1` are unchanged, and a force-merged partition is
dropped as a whole exactly as before.

#### Scenario: A settled multi-part payload partition collapses to one part

- **WHEN** a day partition of `dial_usage_log_payload` holds several parts containing several versions of the
  same `event_id`, and every part is older than 2 days
- **THEN** the partition is merged into one part holding one row per `event_id`, the row with the greatest
  `_updated_at`

#### Scenario: A settled multi-part usage-log partition collapses to one part

- **WHEN** a day partition of `dial_usage_log` holds several parts containing several versions of the same
  sort key, and every part is older than 2 days
- **THEN** the partition is merged into one part holding one row per sort key, the row with the greatest
  `_ingested_at`

#### Scenario: A settled multi-part rate partition collapses to one part

- **WHEN** a day partition of `rate_analytics` holds several parts containing several copies of the same rate
  event, and every part is older than 2 days
- **THEN** the partition is merged into one part holding one row per sort key, the row with the greatest
  `_ingested_at`, and distinct rate events of the same response remain separate rows

#### Scenario: A partition with a recent part is left to the automatic selector

- **WHEN** a day partition holds parts older than 2 days and one part younger than 2 days
- **THEN** the force-merge does not select the partition, and the partition keeps its parts until either the
  automatic selector merges them or the youngest part reaches 2 days

#### Scenario: A late write into a collapsed partition restarts the interval

- **WHEN** a backfill inserts rows into a day partition that was already collapsed to one part
- **THEN** the insert succeeds, the partition now holds the old part and the new one, and it is force-merged
  again only after both are older than 2 days

#### Scenario: The migrations provision the settings on every ingest target without rewriting data

- **WHEN** the migrations are applied to `dial_usage_log_payload`, `dial_usage_log` and `rate_analytics` holding data
- **THEN** each table's declaration lists `min_age_to_force_merge_seconds = 172800` and
  `min_age_to_force_merge_on_partition_only = 1` among its settings, `dial_usage_log_payload`'s also lists
  `vertical_merge_algorithm_min_columns_to_activate = 1` and `vertical_merge_algorithm_min_rows_to_activate = 1`
  while `dial_usage_log`'s and `rate_analytics`'s list neither, no part is rewritten by the statements themselves, and each table's
  sort key, partitioning, `TTL` and `ttl_only_drop_parts = 1` are unchanged

#### Scenario: A payload merge runs with the vertical algorithm

- **WHEN** a day partition of `dial_usage_log_payload` is merged into a wide part, by the force merge or the
  automatic selector
- **THEN** `system.part_log` records the merge with `merge_algorithm = 'Vertical'`

#### Scenario: Re-applying the migration is harmless

- **WHEN** the migration statements are run again against tables that already carry the settings
- **THEN** they succeed and the settings are unchanged

#### Scenario: Removing the settings restores the previous behaviour

- **WHEN** an operator runs `ALTER TABLE <table> RESET SETTING min_age_to_force_merge_seconds,
  min_age_to_force_merge_on_partition_only` on `dial_usage_log_payload` or on `dial_usage_log`
- **THEN** the statement succeeds, that table's declaration no longer lists either setting, and its merges
  return to the automatic selector alone

#### Scenario: A read is identical before and after the collapse

- **WHEN** the same final read naming a column of either table is run before and after a partition is
  force-merged
- **THEN** both return one row per key with the same values

#### Scenario: A writer without the settings grant fails startup at the migration

- **WHEN** migrations are enabled and the writer credential lacks the privilege to alter the settings of one of
  the two tables
- **THEN** startup fails at this migration rather than serving against a table that lacks the settings

### Requirement: Ingested blobs are recorded in a persisted ledger

The service SHALL keep a ledger of the log blobs it has fully ingested, so that the scheduled reconciliation
run, the poller and — unless the operator asks otherwise — the backfill do not ingest a blob a second time.
The ledger SHALL live in ClickHouse, in the same database as the ingest tables, so that it shares their
failure domain: a restore or re-creation of that database cannot leave the ledger claiming rows the tables no
longer hold. It SHALL be a table `_dial_internal_ingested_blob` with one row per recorded blob, keyed and ordered by
`blob_key` (the blob's full key inside the container — the same string the ingested rows carry in
`_source_file`), holding `blob_last_modified` (the blob's last-modified time as listed by the store,
millisecond precision, UTC) and `ingested_at` (the time the entry was written, a table-side default).
Recording a blob again SHALL supersede its earlier entry: the entry a reader sees for a key is the one with
the greatest `ingested_at`, whether or not older entries have been merged away.

A blob SHALL count as **recorded** only while its entry's `blob_last_modified` equals, to the millisecond, the
last-modified time the store currently lists for it. Log blobs are expected to be write-once, but that is a
property of the producer that the service cannot enforce: a blob rewritten under the same key carries a newer
last-modified time, so its entry stops matching and the next reconciliation run, backfill or poll cycle that
consults the ledger for it ingests it again, the overlap collapsed by the target table's engine. (Within its
overlap window the poller skips a blob it ingested itself by name, before consulting the ledger, so a rewrite
inside that window is caught by the next reconciliation run instead.) A blob the store lists without a
last-modified time SHALL never be recorded. The ledger does not track the ingest configuration: a change to a
target or a mapping applies to blobs ingested from then on, and applying it to earlier blobs is done by an
operator backfill that includes already-ingested blobs.

A blob SHALL be recorded as soon as every row it produced has been written to its target tables — at the
write that carries its last buffered row, or as soon as it has been read if none of its rows is still
buffered — not at the end of the poll cycle or the day, so that a run that stops part-way leaves every blob it
finished recorded, and a re-triggered backfill of the same range ingests only the rest. Rows are written in
batches that span blobs, so a blob is not recorded merely because it has been read while its rows are still
buffered. A blob whose ingest failed, or whose rows were not all written, SHALL NOT be recorded. A blob that
was read successfully but produced no rows (every record malformed, dropped, or matched by no target) SHALL
be recorded, since reading it again would produce nothing.

Recording happens after the write to the target tables, and the two are not atomic. If the service stops, or
the ledger write fails, after the rows are written but before the entry is, the blob is not recorded; a later
poll cycle, reconciliation run or backfill that lists it ingests it again and the target table's engine
collapses the copy. The ledger therefore reduces duplicates to that window rather than guaranteeing
exactly-once ingestion.

Reading and writing the ledger are ClickHouse statements on the ingest path like the inserts into the target
tables, and SHALL fail the same way: a ledger read or write that fails SHALL fail the poll cycle (the
watermark holds and the next cycle retries) or the day of a reconciliation run or backfill, exactly as a
failed insert does. A ledger write failure SHALL be treated as a fault of the cycle or day, not of the blob
being read when it occurred.

Entries SHALL be kept for 13 months after their blob's last-modified time and then expire, so the ledger does
not grow without bound; removal MAY lag the 13 months by up to one month, because entries expire a month at a
time. A blob older than that has no entry, so a backfill reaching back further ingests it as before the ledger
existed. The ledger SHALL NOT be registered in the query catalog and SHALL NOT be reachable through any query
surface.

#### Scenario: A fully ingested blob is recorded

- **WHEN** a blob has been parsed and all rows it produced have been written to their target tables
- **THEN** the ledger holds an entry for that blob with its key, its listed last-modified time and the ingest
  time

#### Scenario: A blob is recorded when its rows are written, not when it is read

- **WHEN** a day's run has read a blob whose rows are still buffered, and a later write carries the last of
  them
- **THEN** the blob is not recorded before that write and is recorded right after it, while the run goes on

#### Scenario: A run that stops part-way keeps what it wrote

- **WHEN** a reconciliation run or backfill day fails after some of its writes succeeded
- **THEN** every blob whose rows were all written by then is recorded, the blobs whose rows were not are
  not, and a backfill of that day afterwards ingests only the unrecorded ones

#### Scenario: A blob that failed to ingest is not recorded

- **WHEN** reading or parsing a blob fails, or a write of rows from the same cycle or day fails before the
  blob's rows are all written
- **THEN** the ledger holds no entry for that blob; the poller retries it while it stays within its overlap
  window, a reconciliation run that lists it ingests it, and otherwise an operator backfill recovers it

#### Scenario: An empty blob is recorded

- **WHEN** a blob is read successfully but every record in it is malformed, dropped for lacking a
  deduplication key, or matched by no target — including when it is the last blob of the cycle or day
- **THEN** the blob is recorded and is not read again by the reconciliation run, the poller or a default
  backfill

#### Scenario: A blob listed without a last-modified time is never recorded

- **WHEN** the store lists a blob without a last-modified time and a reconciliation run or backfill ingests it
- **THEN** no ledger entry is written for it, so the next reconciliation run or backfill that lists it
  ingests it again

#### Scenario: A rewritten blob is ingested again

- **WHEN** a blob has a ledger entry and the reconciliation run lists it with a different last-modified time
  than the entry recorded
- **THEN** the entry does not count as recorded, the blob is ingested again, and a new entry with the new
  last-modified time supersedes the old one

#### Scenario: A newer entry supersedes an older one before any merge

- **WHEN** a blob is recorded twice with different last-modified times and the two entries have not been
  merged
- **THEN** a reader sees only the entry recorded last

#### Scenario: A changed mapping does not re-ingest earlier blobs

- **WHEN** the contents of a target's mapping file, or the set of target tables, changes between two
  deployments
- **THEN** blobs the ledger already records are still skipped by every ingest path, the new mapping applies to
  blobs ingested from then on, and an operator backfill of a date range that includes already-ingested blobs
  applies it to that range

#### Scenario: A ledger failure fails the cycle like an insert failure

- **WHEN** a ledger read or write fails during a poll cycle
- **THEN** the cycle fails, the watermark holds, and the next cycle ingests the blobs it did not record, their
  duplicate rows collapsed by the target table's engine

#### Scenario: A ledger failure fails the day like an insert failure

- **WHEN** a ledger read or write fails during a reconciliation run or a backfill day
- **THEN** that day fails as it would on a failed insert, and the blobs recorded before the failure stay
  recorded

#### Scenario: Old entries expire

- **WHEN** an entry's blob was last modified more than 13 months ago
- **THEN** the entry is removed within one further month, and entries younger than 13 months are kept

#### Scenario: The ledger is not queryable

- **WHEN** a caller lists entities or runs a query through any query surface
- **THEN** `_dial_internal_ingested_blob` is neither listed nor readable

### Requirement: Inline files are removed from payload bodies in drop mode

The service SHALL accept an ingest setting `sync.inline-files-mode` (`SYNC_INLINE_FILES_MODE`), set to
`keep`, `drop-truncated` or `drop`, with default `drop-truncated`. Any other value SHALL fail startup.
The default removes only what no reader can use, the half file at Core's 4 MiB cut; `keep` stores every
body exactly as sent.

The setting applies only to the columns whose mapping entry declares the `inline-files` attribute (see
"Configurable column mapping"). The shipped payload mapping declares it on `request_body` as a request
body, and on `response_body` and `assembled_response` as response bodies.
- **Under `keep`,** such a column SHALL be stored exactly as it is without the attribute: the body is
  not parsed, and the value is byte for byte what the producer sent.
- **Under `drop-truncated`,** only a column value that is a truncated body (see below) SHALL be scanned. It
  SHALL be cut back to before the inline file the cut landed in, and every complete inline file SHALL be
  kept. That half file cannot be decoded, so removing it loses nothing a reader could use. Any other value
  SHALL be stored exactly as under `keep`, without being parsed.
- **Under `drop`,** the service SHALL remove the payload of every **inline file** in the column's value
  before the row is written, and leave every other character of the value unchanged.

**The API of a record** SHALL be inferred from the path of its request URI, read from the source path that
the attribute names, with the query string ignored. The whole path SHALL match one of the patterns below,
which are the fixed paths on which DIAL Core serves each API. `{id}` is one or more characters and may
contain `/`. One or more leading `/` are accepted, except on the two MCP paths, which take exactly one, as
in Core. Matching is case-sensitive and on the raw path, so percent-encoding is kept. The patterns SHALL be
tried in this order, and the first match wins:

| Path | API |
|---|---|
| `/openai/deployments/{id}/chat/completions`, `/openai/v1/chat/completions` | Chat Completions |
| `/openai/deployments/{id}/embeddings` | Embeddings |
| `/openai/v1/responses`, `/openai/responses` | Responses |
| `/anthropic/v1/messages/count_tokens`, `/anthropic/v1/messages` | Anthropic Messages |
| `/v1/toolset/{id}/mcp`, `/v1/deployments/{id}/mcp` | MCP |

The service SHALL NOT infer an API from any other path, and it never guesses one from a path's last
segments. That covers in particular:
- a route with an operator-chosen path (DIAL global routes such as `/claude_code_router/v1/messages`);
- an application route `/v1/deployments/{id}/route/...`, even one that ends with `/messages` or
  `/chat/completions`.

Either may proxy any upstream, so a location carries no meaning there. A record whose path matches none of
the patterns, or whose request URI is absent, SHALL have no inline files: its bodies are stored unchanged.

**What counts as an inline file.** A value is an inline file only when all of the following hold. The
shape of the string SHALL NOT matter: text anywhere else is stored as it is, however it is encoded.
- It is a JSON string.
- It sits at one of the locations below for the record's API and for the column's side (request or
  response).
- Its condition holds.

Locations are matched from the root of a JSON body, or the root of each event of an SSE body. `*` matches
any array index or object key.

| API | Side | Location | Condition |
|---|---|---|---|
| Chat Completions | request | `messages/*/content/*/image_url/url` | data URL |
| Chat Completions | request | `messages/*/content/*/input_audio/data` | none |
| Chat Completions | request | `messages/*/content/*/file/file_data` | data URL |
| Chat Completions | request | `messages/*/custom_content/attachments/*/data`, `messages/*/custom_content/stages/*/attachments/*/data` | DIAL binary attachment |
| Chat Completions | response | `choices/*/message/audio/data`, `choices/*/delta/audio/data` | none |
| Chat Completions | response | `choices/*/message/custom_content/attachments/*/data`, `choices/*/message/custom_content/stages/*/attachments/*/data`, and the same two under `choices/*/delta/` | DIAL binary attachment |
| Responses | request | `input/*/content/*/image_url`, `input/*/content/*/file_data`, `input/*/output/image_url`, `input/*/output/*/image_url`, `input/*/output/*/file_data` | data URL |
| Responses | request | `input/*/result` | none |
| Responses | response | `output/*/result`, `item/result`, `response/output/*/result`, `partial_image_b64` | none |
| Anthropic Messages | request | `messages/*/content/*/source/data`, `messages/*/content/*/content/*/source/data`, `messages/*/content/*/content/content/source/data` | base64 source |
| MCP | response | `result/content/*/data` | sibling `type` is `image` or `audio` |
| MCP | response | `result/content/*/resource/blob` | sibling `mimeType` is a binary type |
| Embeddings | response | `data/*/embedding` | none |

Conditions:
- **data URL:** the value starts with `data:`, and the part before its first `,` ends with `;base64`
  (case-insensitive). Parameters are allowed, for example `data:image/png;name=a.png;base64,`. A JSON
  escape inside that prefix, such as `data:image\/png;base64,`, SHALL be unescaped before this check.
- **binary type:** after removing any `;` parameters and ignoring case, the type is one of:
  - `image/*` except `image/svg+xml`;
  - `audio/*`, `video/*`;
  - `application/pdf`, `application/octet-stream`, `application/zip`, `application/msword`;
  - `application/vnd.ms-*`, `application/vnd.openxmlformats-officedocument.*`.
- **DIAL binary attachment:** the sibling `type` is a binary type; or the value is a data URL and the
  sibling `type` is missing or a binary type.
- **base64 source:** the sibling `type` is `base64`.

A sibling MAY come after the value in its object. The decision SHALL be made once the whole object has
been read. For an object the body or event ends inside, whether cut or malformed, a sibling not read by
then is unknown, not missing: a DIAL attachment there SHALL count as an inline file only when a `type`
read before the end is a binary type.

**Removal.** The removed span SHALL be the value's raw text between its quotes, as it stands in the body:
- a data URL becomes its prefix, for example `"data:image/png;base64,"`, so the media type stays
  visible;
- any other inline file becomes `""`.

The rest of the body, its JSON escapes included, SHALL stay as it was.

**Body formats.**
- A body whose first non-whitespace character is `{` or `[` SHALL be read as one JSON document.
- Any other body SHALL be read as SSE:
  - each line that starts with `data:` and holds a JSON object is one event, and an optional space after
    the colon is not part of it;
  - an event spread over several `data:` lines SHALL NOT be scanned;
  - every non-`data:` line SHALL be kept as it is.
- A body or event that does not parse SHALL keep the removals found before the parse error. Nothing
  after the error is changed. This SHALL NOT fail, skip or empty the column.

**Truncated bodies.** A value is a truncated body when it ends with `>>` and its UTF-8 length is at least
4,194,306 bytes: DIAL Core cut it at 4 MiB and appended that marker.

Under `drop` and `drop-truncated`, a truncated body SHALL be handled as follows:
- under `drop`, every inline file that ends before the cut SHALL be removed as above; under
  `drop-truncated`, every such file SHALL be kept;
- when the value that is open at the cut is an inline file, the body SHALL be cut back to just before
  that value's opening quote and the `>>` marker kept;
- when the open value is not an inline file, the body SHALL be left as it is. That includes a value
  whose condition sibling lies after the cut, and a data URL whose prefix was itself cut.

**Isolation from other columns.** The removal SHALL apply to the value written to the declaring column
only. Every other column of every target, derived columns reading the same source path included, SHALL
see the record as the producer sent it. `request_body_bytes` and `response_body_bytes` on
`dial_usage_log` therefore keep recording the original sizes.

**Determinism.** The output SHALL depend only on the value, the request URI and the mode. So ingesting
a record again under the same mode writes an identical row, while ingesting it again under a different
mode replaces the stored row with that mode's result.

#### Scenario: Keep mode stores bodies unchanged

- **WHEN** `sync.inline-files-mode` is `keep` and a record's request body holds an image data URL
- **THEN** `request_body` is stored byte for byte as the producer sent it

#### Scenario: A chat image is removed and its media type kept

- **WHEN** the mode is `drop` and a record to `/openai/deployments/m/chat/completions` carries
  `messages[0].content[1].image_url.url = "data:image/png;base64,iVBOR..."`
- **THEN** the stored `request_body` carries `"url":"data:image/png;base64,"` at that position and every
  other character of the body unchanged

#### Scenario: Base64 text outside a file location is kept

- **WHEN** the mode is `drop` and a chat request carries a 10 KB base64 string as a text `content` part
- **THEN** that string is stored unchanged

#### Scenario: A DIAL attachment of a text type is kept

- **WHEN** the mode is `drop` and a chat message carries `custom_content.attachments[0]` with
  `type: "text/markdown"` and inline `data`
- **THEN** the attachment's `data` is stored unchanged

#### Scenario: A DIAL attachment of a binary type is removed even when its type follows the data

- **WHEN** the mode is `drop` and a chat message carries an attachment object whose `data` comes before
  `"type": "application/pdf"`
- **THEN** the attachment's `data` is stored as `""`

#### Scenario: An Anthropic text document is kept

- **WHEN** the mode is `drop` and an Anthropic Messages request carries a document block whose
  `source.type` is `text`
- **THEN** its `source.data` is stored unchanged, while a block whose `source.type` is `base64` has its
  `source.data` stored as `""`

#### Scenario: An MCP image and a binary resource are removed

- **WHEN** the mode is `drop` and an MCP response carries `result.content[0]` with `type: "image"` and
  `data`, and `result.content[1].resource` with `mimeType: "application/pdf"` and `blob`
- **THEN** both values are stored as `""`

#### Scenario: An MCP text resource is kept

- **WHEN** the mode is `drop` and an MCP response carries `result.content[0].resource` with
  `mimeType: "text/plain"` and `blob`
- **THEN** the `blob` is stored unchanged

#### Scenario: A base64 embedding vector is removed

- **WHEN** the mode is `drop` and an embeddings response carries `data[0].embedding` as a string
- **THEN** that value is stored as `""`, while an embedding given as a number array is stored unchanged

#### Scenario: A route with an API-like path is never scanned

- **WHEN** the mode is `drop` and a record's request URI is `/claude_code_router/v1/messages?beta=true`,
  or `/v1/deployments/app/route/v1/messages`, and its request body is shaped like an Anthropic Messages
  request with a `base64` image source
- **THEN** its bodies are stored unchanged, because neither path is one on which DIAL Core serves an API

#### Scenario: An application's chat path is scanned whatever its name

- **WHEN** the mode is `drop` and a record's request URI is
  `/openai/deployments/public/pg/test/agent__1.0.0/chat/completions?api-version=2024-10-21`
- **THEN** its bodies are scanned against the Chat Completions locations

#### Scenario: A streamed response has its files removed event by event

- **WHEN** the mode is `drop` and a streamed chat response body holds `data:` events whose
  `choices[0].delta.custom_content.attachments[0]` carries a binary `type` and `data`
- **THEN** each such `data` value is stored as `""`, and every other line of the transcript is stored
  unchanged

#### Scenario: A body cut inside a file is cut back before it

- **WHEN** the mode is `drop` and a request body ends with `>>`, is 4,194,306 bytes long, and was cut
  inside a `messages/*/content/*/image_url/url` data URL
- **THEN** the stored `request_body` ends just before that value's opening quote followed by `>>`, and
  every complete inline file before it is removed

#### Scenario: Drop-truncated cuts back the half file and keeps the complete ones

- **WHEN** the mode is `drop-truncated` and a request body ends with `>>`, is 4,194,306 bytes long, holds
  one complete `image_url` data URL, and was cut inside a second one
- **THEN** the stored `request_body` keeps the first image byte for byte and ends just before the second
  value's opening quote followed by `>>`

#### Scenario: Drop-truncated leaves a body that was not cut untouched

- **WHEN** the mode is `drop-truncated` and a request body under 4 MiB holds an image data URL
- **THEN** `request_body` is stored byte for byte as the producer sent it, and the body is not parsed

#### Scenario: A body cut inside text is left as cut

- **WHEN** the mode is `drop` and a truncated request body was cut inside a text `content` string
- **THEN** the body keeps its cut text and the `>>` marker, with only the complete inline files before
  the cut removed

#### Scenario: A body that does not parse is stored unchanged

- **WHEN** the mode is `drop` and a response body is neither JSON nor SSE with `data:` events
- **THEN** the body is stored unchanged and the record is ingested normally

#### Scenario: Body sizes on the usage log are unaffected

- **WHEN** the mode is `drop` and a record's request body holds a 2 MB image
- **THEN** the record's `dial_usage_log.request_body_bytes` is the size of the body as the producer sent
  it, not the size stored in `request_body`

#### Scenario: Re-ingesting under the same mode gives the same row

- **WHEN** the same record is ingested twice under `drop`
- **THEN** both writes carry identical body values

