## ADDED Requirements

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
