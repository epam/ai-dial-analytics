## Context

`BlobClient` is the only component that talks to blob storage, and it does so through jclouds.
`buildContext()` normalizes `blob.identity` / `blob.credential` into a nullable pair and derives
`managedIdentity` from it (either half blank). That one boolean drives two provider-specific branches:

- `credentialProvider(...)` returns `AzureCredentialProvider` for `azureblob`, `AwsCredentialProvider`
  for `aws-s3`, and a **constant** `Credentials` for everything else, with inert placeholders for a
  blank half.
- `effectiveOverrides(...)` adds `jclouds.azureblob.auth=azureAd` +
  `jclouds.oauth.credential-type=bearerTokenCredentials` for keyless Azure, and warns about a blank
  endpoint for S3.

`jclouds-allblobstore:2.7.3` already puts `org.apache.jclouds.provider:google-cloud-storage:2.7.3` (with
`api:oauth` and `common:googlecloud`) on the runtime classpath. So `BLOB_PROVIDER=google-cloud-storage`
can already be selected today. It then gets the constant-credentials fallback, which in keyless mode
means placeholders.

The following properties of those jars decide the shape of this change. Each was read from the 2.7.3
bytecode:

- **Listing matches what `BlobClient` asks for.** `BlobStoreListContainerOptionsToListObjectOptions`
  maps `prefix` → `prefix`, `recursive` → no `delimiter`, `getMarker()` (set by `afterMarker`) →
  `pageToken`, and `maxResults` → `maxResults`. `ObjectListToStorageMetadata` returns `nextPageToken` as
  the page's next marker. `ObjectToBlobMetadata` sets `lastModified` from the object's `updated` time,
  which is what the poller's watermark compares. Requests use the JSON API, path-style
  (`storage/v1/b/{bucket}/o`), against the default endpoint `https://www.googleapis.com`.
- **The project id is not needed.** `GoogleCloudStorageHttpApiModule.supplyProject` composes a project
  from the credential's identity (`CurrentProject.ClientEmail`) lazily. Only
  `GoogleCloudStorageBlobStore.list()` (list buckets) and `createContainerInLocation(...)` read it.
  `BlobClient` calls neither, so a keyless identity that is not a client email is harmless.
- **Two OAuth flows are relevant.** `OAuthModule` selects a filter by `jclouds.oauth.credential-type`.
  The default, `P12_PRIVATE_KEY_CREDENTIALS`, is `JWTBearerTokenFlow`: it signs an RS256 JWT
  with identity = client email and credential = a **PEM** RSA key (`PrivateKeySupplier`, "credential in
  PEM format"), exchanges it at `oauth.endpoint` (default `https://accounts.google.com/o/oauth2/token`),
  and caches the token. `bearerTokenCredentials` is `BearerTokenFromCredentials`. It calls our
  `Supplier<Credentials>.get()` **per request** and sends `Authorization: Bearer <credential>`. This is the
  exact seam keyless Azure uses on the EPAM fork, here in upstream code.
- **Scopes.** `GoogleCloudStorageOAuthScopes` uses `devstorage.read_only` for GET/HEAD and `read_write`
  otherwise. ADAS issues only reads, so key-mode tokens are read-only scoped already.

The one missing piece is turning ambient Google credentials into an access token, which needs
Google's auth library.

## Goals / Non-Goals

**Goals:**

- A GKE pod bound to a Google service account through Workload Identity ingests from a bucket with **no
  key material** in configuration, and keeps doing so for the process lifetime.
- A deployment that has only a service-account key can use it, either as a mounted key file or as the
  identity/credential pair.
- The principal ADAS actually authenticated as is visible, so a node-identity substitution can be seen.
- A credential fault costs a sync cycle, not the service.
- No change for `azureblob` or `aws-s3` deployments; no new property.

**Non-Goals:**

- Google's own `google-cloud-storage` Java client. See the first decision.
- The S3-compatible XML API with HMAC keys (`aws-s3`/`s3` against `storage.googleapis.com`). It has no
  keyless mode, and it inherits SigV4 region rules that mean nothing on GCS.
- Requester-pays buckets (`userProject`), Pub/Sub notifications (the poller is notification-free by
  spec), and any Helm chart change.
- Writing to the bucket. ADAS only lists and reads.

## Decisions

**Use jclouds' `google-cloud-storage` provider, not Google's storage client.** `BlobClient` is a single
jclouds seam shared by four ingest paths. Adding a second blob abstraction for one provider would fork
`list` / `openStream` semantics, the span instrumentation in `BlobSpans`, and the error mapping to
`BlobAccessException`. Google's client also brings gRPC and its own Netty line, while this project
already pins a Netty 4.2 BOM. The provider is already on the classpath and its listing semantics were
verified to match. Rejected: `com.google.cloud:google-cloud-storage`.

**Keyless tokens come from Application Default Credentials (`GoogleCredentials.getApplicationDefault()`),
scoped `devstorage.read_only`.** This is a deliberate departure from the S3 design, which forbids any
fallback chain. The reason is a real difference between the platforms, not convenience:

- On EKS the instance metadata service is the **node's** identity and the projected token is the
  workload's, so excluding IMDS is what separates the two.
- On GKE the metadata server **is** the Workload Identity mechanism: the GKE metadata server answers for
  the pod's bound service account. A metadata-only resolver would therefore not be any narrower. It
  would just drop the credentials-file route, which is how a pod on another cloud federates into GCP
  (workload-identity-federation config) and how a key file is mounted without putting a PEM in an env
  var.

ADC's remaining source is the gcloud user credential well-known file. It is absent in a container
image and useful in local development. Rejected alternatives:

- `ComputeEngineCredentials` only. Not narrower on GKE, and it loses federation and key-file use.
- A hand-rolled metadata-server call. More code in the auth path, and caching, expiry and federation all
  rewritten rather than inherited.

The residual risk is real: on a node pool **without** Workload Identity, the metadata server hands out
the node's service account. It is covered by the next decision rather than by narrowing the chain,
which cannot distinguish the two cases.

**Log the resolved principal once, never the token.** On first successful resolution, `GcsCredentialProvider`
logs the account email and the source:

- `ServiceAccountCredentials.getClientEmail()` for a key file.
- `ComputeEngineCredentials.getAccount()` for the metadata server. This is one extra metadata call, made
  once, and its failure is downgraded to "principal unknown".
- `ExternalAccountCredentials.getServiceAccountEmail()` when federation impersonates an account, which
  may be null.
- `ImpersonatedCredentials.getAccount()` for impersonated credentials.

An email is not a secret. It is the cheapest way to make "authenticated as the node" visible.
Rejected: an `expected principal` property that fails the cycle on mismatch. It is a stronger guarantee,
but it is a new config key with a new failure mode, and it is left as an open question.

**Hand the token to jclouds through the existing bearer seam, keyless mode only.** In keyless mode
`effectiveOverrides` adds `jclouds.oauth.credential-type=bearerTokenCredentials` with `putIfAbsent`, so
an operator `blob.overrides.*` still wins, exactly as for Azure. `GcsCredentialProvider.getCredentials()`
returns `new Credentials("", <access token>)`. The empty identity matches the Azure path and is safe
because nothing on the read path derives a project from it. With a present pair no override is added:
jclouds' default JWT flow handles the key itself. Rejected: routing key mode through Google's library as
well. It would duplicate a working, cached flow, and it would make key mode depend on a library it does
not need.

**Refresh lives in Google's credential object, synchronously.** `getCredentials()` calls
`refreshIfExpired()` and then `getAccessToken()`. `OAuth2Credentials` refreshes ahead of expiry using the
library's own margins and serializes refresh internally, so concurrent sync threads cause one token
request per window. No executor is passed, so no background refresh thread exists. Don't use the
`getRequestMetadata(uri, executor, callback)` async path: it would start work that nothing here stops at
shutdown. The provider still implements `AutoCloseable` and is closed from `BlobClient.shutdown()`,
which already closes any `AutoCloseable` provider. Today that close is a no-op, but keeping it means a
future owned transport cannot leak.

**Failure stays per-cycle, through the existing lazy build.** `ContextBuilder` calls the supplier once
inside `buildInjector()`, so the first token request happens inside `buildContext()`. An ADC lookup
failure (`IOException` from `getApplicationDefault()`, or a refresh failure) is wrapped and thrown from
`getCredentials()`, so the cycle fails whole and `context` stays `null`. The provider is created
**lazily on first `getCredentials()`**, not in its constructor. That way a missing ADC never escapes as
a construction failure, and the memoized provider field in `BlobClient` (added by the S3 change) is
reused across failing cycles.

**No endpoint warning.** Unlike S3, a blank endpoint is correct for GCS: one global JSON API with no
region in the signature. A set endpoint (Private Service Connect, or a local emulator in tests) is passed
to `ContextBuilder.endpoint` as for the other providers.

**Dependency: `com.google.auth:google-auth-library-oauth2-http`, pinned.** Its HTTP transport is
`google-http-client`'s `NetHttpTransport` over `HttpURLConnection`: no Netty, and no second HTTP
implementation to collide with the AWS SDK's single `SdkHttpService`. It brings `google-http-client`,
`google-http-client-gson`, `google-auth-library-credentials`, Guava and `auto-value-annotations`. Guava is
already constrained to `33.7.2-jre` for a CVE, and the constraint wins. Anything the auth path never
calls (`opencensus-*`, `grpc-context`, if present in the chosen version) is excluded with a comment, as
the AWS block does.

**Tests are plain JUnit with a JDK `HttpServer`, no emulator.** This mirrors `BlobClientAwsSigningTest`.
Point `blob.endpoint` at `http://127.0.0.1:<port>`. Then:

- Keyless: substitute a fixed `CredentialProvider` through the package-private `credentialProvider`
  seam and assert `Authorization: Bearer <token>`. Serve two `storage/v1/b/<bucket>/o` pages joined by
  `nextPageToken` and assert both are listed with `updated` mapped to the last-modified time. Serve
  `?alt=media` and assert the streamed bytes, including a key containing `/`, to pin object-name
  encoding.
- Key mode: generate an RSA key, set `blob.overrides.oauth.endpoint` to the same server, and assert a
  `jwt-bearer` grant is posted and its token used. This proves the native flow is reached with no
  placeholder in between.

`GcsCredentialProviderTest` covers mode selection, mapping a stub `GoogleCredentials` to `Credentials`,
principal extraction per credential type, and that no token appears in captured log output.

## Risks / Trade-offs

- **[Node identity substitution]** A pod on a node pool without Workload Identity authenticates as the
  node's service account and may succeed. → The principal is logged at first resolution, the docs state the
  Workload Identity prerequisite, and an enforcing property is an open question.
- **[Upstream jclouds is retired]** Apache jclouds is in the Apache Attic, so provider bugs are not fixed
  upstream. → ADAS already depends on the EPAM fork for Azure, which can carry a GCS fix. The two
  `HttpServer` tests pin the behaviour this change relies on.
- **[ADC breadth]** ADC reads whatever `GOOGLE_APPLICATION_CREDENTIALS` names, including a long-lived key
  file. → That file is operator-provided, so this is a deliberate configuration rather than an ambient
  identity. The docs recommend Workload Identity over keys.
- **[A revoked binding is not a credential error]** A cached token stays valid until it expires, so a
  removed IAM grant surfaces as a `403` per cycle rather than as a resolution failure. → Accepted, as for
  S3. The token life is short.
- **[PEM in an environment variable]** Key mode through the pair needs a multi-line PEM in
  `BLOB_CREDENTIAL`. → The docs prefer a mounted key file named by `GOOGLE_APPLICATION_CREDENTIALS` with
  the pair left blank, which keeps the key out of the environment.
- **[Dependency weight]** The auth library adds a few small jars for one token request per hour. →
  Bounded, Netty-free, and less security-critical code than hand-rolling the metadata and federation
  exchanges.

## Migration Plan

No migration, no new property, no contract-version bump, no ClickHouse grant.

Rollout is deployment-side configuration:

- Set `BLOB_PROVIDER=google-cloud-storage` and `BLOB_BUCKET`.
- Leave `BLOB_IDENTITY` / `BLOB_CREDENTIAL` unset.
- Bind the Kubernetes service account to a Google service account through Workload Identity.
- Grant that account `roles/storage.objectViewer` on the bucket (or a custom role with
  `storage.objects.list` + `storage.objects.get`).

Rollback is reverting `BLOB_PROVIDER`. Existing `azureblob` and `aws-s3` deployments need no action.

Before shipping, verify offline:

1. Resolve the runtime classpath. Confirm Guava is still `33.7.2-jre`, no Netty or second HTTP
   implementation arrived, and exactly one `google-auth-library-oauth2-http`.
2. Start with `BLOB_PROVIDER=google-cloud-storage`, no pair and no Google environment. Confirm the
   per-cycle "poll cycle failed; watermark held" shape naming the ADC lookup, not an OAuth error about a
   placeholder credential.

## Open Questions

- **Should an expected principal be enforceable?** For example, a blank-by-default
  `blob.expected-principal` that fails the cycle when the resolved email differs. It would turn the
  node-identity risk from visible into impossible, at the cost of a property and a metadata call per
  context build.
- **Is the identity/credential key pair worth documenting at all**, given that a mounted key file through
  ADC is safer and works with the pair blank? It costs no code, so this change keeps it for parity and
  documents the file route as preferred.
- **Requester-pays buckets** need `userProject` on every request, which the jclouds provider does not
  expose. They stay out of scope until a deployment needs them.
