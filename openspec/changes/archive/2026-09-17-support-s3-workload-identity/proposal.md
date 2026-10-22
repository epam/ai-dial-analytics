## Why

A DIAL deployment may write its usage logs to an **S3 bucket** rather than to Azure Blob, with the ADAS pod granted
read access the way EKS grants it — an IAM role associated with the pod's service account, no key material
anywhere. ADAS already reaches S3 for the data plane (`aws-s3` is on the runtime classpath and `BlobClient`
drives it), but `BlobClient.credentialProvider` (`:179-187`) hands every non-`azureblob` provider a
**constant** `Credentials` built from `BLOB_IDENTITY` / `BLOB_CREDENTIAL`. With a role association there is
no pair to supply, so the blank pair falls through to the inert placeholder defaults and every listing is a
`403` that names neither what is missing nor where. **ADAS cannot ingest from such a bucket at all.**

The gap is narrow and the classpath makes it cheap to close rather than a fork — each of the following was
verified against the jars, not inferred:

- jclouds' S3 SigV4 signer already reads its credentials through a `Supplier` **per request**, tests for
  session credentials and emits the session-token header. Temporary credentials need **no** jclouds patch,
  and the session-credential type ships in the S3 provider's own dependency.
- `ContextBuilder` leaves our supplier **unmemoized**, so rotation is ours to own — exactly as
  `AzureCredentialProvider` already owns it for Azure AD. The one snapshot jclouds bakes into its
  properties at build time is the identity, never the session token, so it goes stale harmlessly.
- The signing region is parsed out of the request host, with a documented fallback and no failure branch,
  by a utility that is public on the compile classpath — so the credential exchange can be pointed at the
  *same* region the signer uses, by construction.

What is missing is only the exchange itself: jclouds' own STS API exposes `assumeRole` but **not**
`assumeRoleWithWebIdentity`, so this needs the AWS SDK's STS module, which is not currently a dependency at
any scope.

## What Changes

- **A blank `BLOB_IDENTITY`/`BLOB_CREDENTIAL` pair on an `aws-s3` source now means workload identity**,
  mirroring the rule `azureblob` already uses to choose managed identity over a shared key. A new
  `AwsCredentialProvider` resolves temporary credentials from the projected web-identity token, maps them
  to jclouds session credentials, and lets the existing signer carry the session token.
- **Web identity only — no fallback chain.** Not the SDK's default provider chain, which reaches the
  instance metadata service: a pod that lost its role association would then read the bucket with the
  **node's** identity and succeed, which is worse than failing.
- **The credential exchange is pointed at the region derived from the effective S3 endpoint**, through the
  same host-parsing rule the signer uses, so the session's region and the signature's region cannot
  disagree. No new region property.
- **Failure stays per sync cycle.** A missing role ARN or token file fails that cycle whole, holds the
  watermark, and is retried on the next cadence; startup is unaffected and the query surface keeps serving.
- **No new configuration property**, no migration, no new contract version, and no change for a deployment
  that supplies an S3 key pair today — a present pair still wins.
- **Incidental fixes in the same lines**: the credential provider is memoized in a field and closed in
  `shutdown()` (today a failed `buildView` leaves the provider unreachable while `context()` retries from
  scratch, so a repeatedly failing cycle would leak one client per cycle), and the `BlobClient` /
  `BlobProperties` javadocs stop claiming the provider is "`azureblob` in production, `filesystem` in
  tests" — `aws-s3` is now a supported deployment provider and is named.

**This change alone does not make every such bucket ingestible.** A bucket whose keys carry no date
directory is refused by `BlobPrefixResolver`; the layout half is a separate change
(`allow-flat-blob-key-layout`) meant to ship alongside it. This one is the authentication half and is
independently useful to any S3 deployment using a role.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`: **ADDED only.** The capability has 33 requirements and mentions blob authentication nowhere
  — every `credential` it names is ClickHouse's writer credential — so nothing loosens or is restated.
  Five requirements are added: the blank-pair trigger, web-identity-only resolution, session-token
  credentials refreshed for the process lifetime, the region derived from the effective endpoint, and
  per-cycle rather than startup failure.

## Impact

- **Surfaces**: none. No endpoint, request shape, response shape or error code changes; the ingest path
  authenticates differently to one provider.
- **Code**: new `blob/credential/AwsCredentialProvider.java`; `blob/BlobClient.java` gains the `aws-s3`
  branch, the memoized provider field and its close. `BlobProperties.java` is unchanged apart from its
  class javadoc — no property is added, so nothing new is redacted in `toString()`.
- **Build**: one new dependency, `software.amazon.awssdk:sts`, excluding `netty-nio-client`. The exclusion
  is load-bearing, not tidiness: the STS module is managed against Netty 4.1 while this project pins a
  Netty 4.2 BOM, so keeping a client we never use would force a 4.2 runtime under a 4.1-compiled client.
  The sync path already has its HTTP implementation. A **second** HTTP implementation arriving later fails
  the STS client build outright with "Multiple HTTP implementations were found" — the tripwire to watch.
- **Tests**: two new plain-JUnit tests in `com.epam.aidial.analytics.blob`, neither touching ClickHouse or
  the catalog and neither needing Docker or AWS — one over the credential mapping and region derivation,
  one proving jclouds signs a session credential correctly against a local HTTP endpoint.
- **Docs**: `docs/configuration.md`'s `#### AWS S3` subsection currently carries prose, a shell block and an
  IAM policy — behaviour and operator knowledge in what the conventions define as a strict property
  reference. It moves to a new `docs/aws-s3-access.md`, leaving the `blob.*` rows plus a one-line link, and
  the now-false sentence "temporary credentials — IRSA, instance profile, `AssumeRole` — are not supported"
  is deleted. **No new row**: this change adds no property.
- **Cross-service**: none. `RunnerContract.VERSION` is not touched and no runner behaviour changes.
- **Operational**: the IAM policy ADAS needs is unchanged by this change — `s3:GetObject` on the log prefix
  and `s3:ListBucket` scoped to it. `s3:GetBucketLocation` is never called. The deployment is Helm-managed
  outside this repo, so the service-account annotation is a hand-off, not an edit here.
