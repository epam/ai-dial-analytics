## ADDED Requirements

### Requirement: Google Cloud Storage blob source

The service SHALL support a Google Cloud Storage bucket as the `data-sync` blob source, selected by the blob
provider `google-cloud-storage`, alongside `azureblob` and `aws-s3`. Every path that reads the blob source
reads it the same way it reads the other two providers: the daily scheduled run, the near-real-time poller
(both listing modes), backfill, and the failure retry sweep. Ingestion semantics SHALL NOT depend on the
provider. That covers prefix listing followed to completion across pages, the last-modified time the
poller's watermark compares against (the object's update time), and per-blob streaming of the raw bytes.

The service SHALL choose the credential mode from the blob identity and blob credential, the same rule the
other providers apply:

- **both set**: the identity is a service-account client email and the credential is that account's
  private key in PEM form. The service SHALL authenticate with the service-account token flow built into
  the provider and SHALL NOT resolve any ambient credential.
- **either blank**: the service SHALL authenticate keylessly, with a short-lived access token resolved at
  runtime (see `Keyless Google Cloud Storage credentials resolve through Application Default
  Credentials`). No key material is required in configuration.

The trigger SHALL be the blank pair itself, not a new property. A `google-cloud-storage` source SHALL NOT be
handed the placeholder credentials used for providers that ignore credentials. The resulting OAuth
failure names neither what is missing nor where.

The blob endpoint SHALL be optional for this provider. When blank, the provider's public JSON API endpoint
is used. When set, it replaces that endpoint for every data-plane request, for example a private-access
endpoint for the same API. The bucket SHALL be addressed by name in the request path, so no bucket-naming
rule beyond Cloud Storage's own applies.

#### Scenario: A GCS source ingests through every read path

- **WHEN** the blob provider is `google-cloud-storage` and the bucket holds blobs under the active
  date-derived prefix
- **THEN** the poller, the daily run, backfill and the retry sweep list and read them exactly as they do for
  the other providers, and the poller selects them by the objects' update time

#### Scenario: A listing larger than one page is followed to completion

- **WHEN** a listed prefix holds more objects than the provider returns in one page
- **THEN** the service follows the continuation token until no token is returned, and every object under the
  prefix is listed exactly once

#### Scenario: A configured service-account key pair is used as given

- **WHEN** the blob provider is `google-cloud-storage` and both the blob identity (a service-account client
  email) and the blob credential (its PEM private key) are set
- **THEN** requests are authorized with tokens obtained from that key, and no Application Default
  Credentials lookup is made

#### Scenario: A blank pair on a GCS source selects keyless access

- **WHEN** the blob provider is `google-cloud-storage` and the blob identity or the blob credential is unset
  or blank
- **THEN** requests are authorized with a runtime-resolved access token, and no placeholder credential is
  sent

#### Scenario: A blank endpoint uses the public API endpoint

- **WHEN** the blob provider is `google-cloud-storage` and no blob endpoint is configured
- **THEN** data-plane requests go to the provider's public Cloud Storage JSON API endpoint, and no warning is
  raised for the blank endpoint

#### Scenario: A configured endpoint replaces the public one

- **WHEN** the blob endpoint is set for a `google-cloud-storage` source
- **THEN** every listing and read is sent to that endpoint with the same bucket path and authorization

### Requirement: Keyless Google Cloud Storage credentials resolve through Application Default Credentials

In keyless mode the service SHALL resolve an OAuth access token through Google **Application Default
Credentials**. That is the credentials file named by `GOOGLE_APPLICATION_CREDENTIALS` when set (a
service-account key, or a workload-identity-federation configuration), and otherwise the metadata server
of the environment the pod runs in, which on GKE with Workload Identity is the pod's bound service
account. The service SHALL present the token as a bearer credential on every data-plane request.

The service SHALL request a **read-only** Cloud Storage scope for any credential type that accepts a
requested scope. `data-sync` only lists and reads, so a credential with write capability SHALL NOT be
needed or requested. IAM on the bucket remains the effective boundary.

Tokens SHALL be refreshed before they expire, driven by the token's own expiry rather than a fixed
interval. Refresh SHALL happen on the calling thread when a request needs a token, so no background
thread outlives shutdown. A sync cycle SHALL never send an expired token while a refresh is possible, and
neither token expiry nor its renewal SHALL require a restart, a pod cycle or a configuration change. A
token SHALL be requested at most once per renewal window, even when several sync threads need one at the
same time.

#### Scenario: GKE Workload Identity supplies the token

- **WHEN** a keyless `google-cloud-storage` source runs in a pod whose Kubernetes service account is bound
  to a Google service account through Workload Identity, and `GOOGLE_APPLICATION_CREDENTIALS` is unset
- **THEN** the token issued for the bound service account authorizes the bucket requests

#### Scenario: A credentials file supplies the token

- **WHEN** `GOOGLE_APPLICATION_CREDENTIALS` names a readable service-account key or workload-identity
  federation configuration
- **THEN** the token is obtained from that file's credential, and the metadata server is not consulted

#### Scenario: The requested scope is read-only

- **WHEN** a token is requested in keyless mode from a credential type that accepts a scope
- **THEN** the only scope requested is Cloud Storage read-only

#### Scenario: An expiring token is renewed in place

- **WHEN** a sync cycle runs after the current token has reached its renewal margin
- **THEN** a new token is obtained and the cycle proceeds, with no restart and no operator action

#### Scenario: Concurrent first use requests one token

- **WHEN** several sync threads need a token at the same moment and none is cached
- **THEN** a single token request is made and every thread uses its result

### Requirement: The resolved Google principal is reported, its credentials never are

When a keyless `google-cloud-storage` source first resolves a token, the service SHALL log, once at info
level, the principal the token was issued for (the service-account email) when the credential type
exposes it, and the credential source it came from (credentials file or metadata server). When the
principal cannot be determined, the service SHALL log the credential source and state that the principal
is unknown.

This is how a misconfiguration becomes visible. On GCP the metadata server answers whether or not
Workload Identity is in effect. A pod scheduled where it is not enabled therefore gets the **node's**
service account, not the bound one. If that account can read the bucket, ingest succeeds under an
identity nobody granted for this purpose. Naming the principal at first resolution lets an operator see
the substitution without a new property or an extra API call.

The service SHALL NOT log an access token, a refresh token, a private key, or the contents of a credentials
file, whether resolution succeeds, renews or fails.

#### Scenario: The principal is named at first resolution

- **WHEN** a keyless GCS source resolves its first token from a credential that exposes its account email
- **THEN** one info-level log record names that email and the credential source

#### Scenario: A node service account is visible as such

- **WHEN** the metadata server resolves the node's default service account instead of a bound one
- **THEN** the logged principal is the node's service account, distinguishable from the intended one

#### Scenario: Token material is never logged

- **WHEN** credentials are resolved, renewed, or fail to resolve, in either credential mode
- **THEN** no log record contains an access token, a refresh token, a private key or credentials-file
  contents

### Requirement: A Google credential failure fails the sync cycle, not startup

The service SHALL treat a failure to obtain Google Cloud Storage credentials as a **failure of the sync
cycle that needed them**, and SHALL NOT refuse to start because of one. Such failures include no
Application Default Credentials available, an unreadable or malformed credentials file, a metadata server
that refuses or times out, and a key pair the token endpoint rejects. Each SHALL fail that cycle whole,
leave the ingest watermark where it was, and be retried by the next scheduled cycle with no operator
action. The failure SHALL be visible through the existing sync failure reporting: the cycle's failure
outcome, the held watermark and its growing lag, and failure records for the daily run and backfill.

The credential resolver SHALL be created **once** and reused across cycles, including cycles that failed
after it was created, and SHALL be released when the service shuts down.

#### Scenario: No available credential fails the cycle and is retried

- **WHEN** a sync cycle needs a GCS token and Application Default Credentials cannot resolve one
- **THEN** that cycle fails as a whole with the ingest watermark held, the failure is reported through the
  existing sync failure outcome, and the next scheduled cycle attempts it again

#### Scenario: Startup is unaffected by a credential fault

- **WHEN** the service starts with a `google-cloud-storage` blob source whose credentials cannot be resolved
- **THEN** startup completes and the query surface serves normally; only the sync cycles fail

#### Scenario: A failing cycle does not rebuild the resolver

- **WHEN** many consecutive sync cycles fail to obtain a token
- **THEN** the credential resolver is created once and reused, and it is released at shutdown

## MODIFIED Requirements

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
The same confinement applies to `google-cloud-storage` (see `Google Cloud Storage blob source`).

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

- **WHEN** the blob provider is anything other than `aws-s3`, `azureblob` or `google-cloud-storage`
- **THEN** its credential handling is unchanged, including the placeholder defaults used when no pair is
  configured
