## Context

`BlobClient.buildContext` normalizes the configured pair into `identity`/`credential` (null when blank) and
derives one boolean, `managedIdentity`, from their nullness. That boolean already drives the `azureblob`
decision in two places — `credentialProvider(...)` (`:179-187`) picks `AzureCredentialProvider`, and
`effectiveOverrides(...)` (`:196-210`) adds the Azure AD overrides. Every other provider falls through to a
**constant** `Credentials`, defaulting each half to an inert placeholder. So the machinery for "no pair
means a runtime-resolved identity" exists; it is wired to exactly one provider.

Four properties of the jars on the classpath decide the shape of this change, and each was checked
first-hand rather than assumed:

- **The signer re-reads credentials per request and already understands a session.**
  `Aws4SignerForAuthorizationHeader` reads `Aws4SignerBase.creds`, a `Supplier<Credentials>`, on every
  request; it tests `instanceof org.jclouds.aws.domain.SessionCredentials` and, when true, emits
  `X-Amz-Security-Token`. `SessionCredentials` ships in `sts-2.7.3.jar`, which is on the compile *and*
  runtime classpath. No jclouds fork, no signer patch.
- **`ContextBuilder` does not memoize our supplier**, so refresh is ours — the same seam
  `AzureCredentialProvider` already uses. It *does* call `supplier.get()` once inside `buildInjector()`
  (via `currentStateToUnexpandedProperties()`) and bake the result into `jclouds.identity` /
  `jclouds.credential`. Two consequences: the first exchange happens synchronously inside `buildContext()`,
  and that baked snapshot goes stale **harmlessly**, because the session token is never baked at all — the
  signer reads it only from the supplier.
- **`aws-s3` signs SigV4 unconditionally and never asks S3 where the bucket is.**
  `AWSS3HttpApiModule.providesRequestAuthorizeSignature` returns `AWSRequestAuthorizeSignatureV4` and
  ignores the `signer-version` handed to it; `defaultRegionForBucket` is the constant `us-standard`, so no
  `GetBucketLocation` is ever issued. That is why the IAM policy needs no such permission.
- **The signing region is parsed from the request host.** `AWSServiceAndRegion` computes
  service = `parseServiceName(URI.create(<effective endpoint>))` and
  region = `AwsHostNameUtils.parseRegionName(<request host>, service)`. Probed:
  `s3.us-east-1.amazonaws.com` and `example-bucket.s3.us-east-1.amazonaws.com` both yield
  `us-east-1`; `s3.amazonaws.com` (the `AWSS3ProviderMetadata` default) yields `us-east-1`; an unknown host
  yields `us-east-1` and an unknown endpoint yields service `s3`. **`parseRegionName` never fails.**

The one thing missing is the exchange. `org.jclouds.sts.STSApi` exposes `createTemporaryCredentials`,
`assumeRole` and `createFederatedUser` — **not** `assumeRoleWithWebIdentity` — so jclouds' own STS API
cannot perform the exchange the projected token requires. That is the whole reason a new dependency enters.

## Goals / Non-Goals

**Goals:**

- A pod carrying a workload-identity role association reads the bucket with **no secret in its
  configuration**, and keeps reading for the process's lifetime.
- A pod **without** that association fails visibly rather than reading the bucket as somebody else.
- The region the session is issued for and the region the request is signed for cannot diverge.
- A credential fault costs a sync cycle, not the service.
- Nothing changes for a deployment that supplies an S3 key pair today.

**Non-Goals:**

- EKS **Pod Identity**. It is a different mechanism with a different injected surface; adding it later is
  one more resolver in an ordered set, which this design leaves open.
- Instance-profile or environment-variable credentials. Deliberately excluded — see the first decision.
- S3-compatible stores (MinIO and path-style addressing), multiple file-name patterns in one run, and any
  Helm chart change; the deployment lives in another repo.
- The bucket's **key layout**. A flat bucket (no date directory) is refused by `BlobPrefixResolver`; that is
  the separate `allow-flat-blob-key-layout` change. This one authenticates and would then list nothing there —
  stated plainly so the pair is rolled out together.

## Decisions

**Credential source: web identity only, never a fallback chain.** The provider is
`StsWebIdentityTokenFileCredentialsProvider`, which reads `AWS_ROLE_ARN` + `AWS_WEB_IDENTITY_TOKEN_FILE` —
precisely what the pod-identity webhook injects. Rotation is already covered inside it: the inner
`AssumeRoleWithWebIdentityRequestSupplier.get()` does `Files.newInputStream(tokenFile)` on **every**
refresh, so a re-projected token needs no restart and we write no file-watching code.

The rejected alternative is `DefaultCredentialsProvider`, and the reason is the failure mode rather than
the cost. Its chain falls through to IMDS, so a pod that lost its annotation — a Helm values typo, a
service account swapped, a role deleted — would silently authenticate as the **EC2 node role** and, on a
node that happens to have bucket access, *succeed*. The ingest would keep working under an identity nobody
granted for this purpose, the CloudTrail principal would be wrong, and nothing in ADAS would report
anything. A narrow provider makes that case a per-cycle failure with the missing variable named in the
message. This is the same instinct as the project's "unrecognised stays a rejection" rule: an ambient
credential is exactly an unrecognised one being treated as authorization.

**We own the `StsClient`, and we give it anonymous credentials.** Not a preference — the API leaves no
choice. `StsWebIdentityTokenFileCredentialsProvider` has no `create()`, only `builder()`, and the
`StsCredentialsProvider` super-constructor runs
`Validate.notNull(builder.stsClient, "STS client must not be null.")`. There is no path that lets the SDK
construct the client, and therefore none that lets it pick the region.

Since we build it, we choose its credentials, and the choice is `AnonymousCredentialsProvider`:
`DefaultStsAuthSchemeProvider` maps `AssumeRoleWithWebIdentity` to `smithy.api#noAuth`, so the call needs
no caller credentials at all. Handing it anything else — including the SDK default — would attach a
resolver that *can* reach IMDS, reintroducing the fallback the previous decision exists to remove, through
a back door nobody would look at. Anonymous credentials make the no-fallback rule structural instead of a
convention.

**STS region is always derived from the effective S3 endpoint**, via the same utility the signer uses:
`parseRegionName(URI.create(ep).getHost(), parseServiceName(URI.create(ep)))`, where `ep` is `BLOB_ENDPOINT`
or `https://s3.amazonaws.com` (the `AWSS3ProviderMetadata` default) when blank. Three things follow, and
they are why this is a decision rather than plumbing:

- The STS region is **by construction** the region SigV4 signs for. A mismatch is a `403` naming neither
  setting, and it is the kind of misconfiguration that survives review because both values look right in
  isolation.
- **There is no failure branch.** `parseRegionName` returns `us-east-1` for anything unrecognised, so an
  unusual endpoint degrades to "signed for the default region" rather than to a startup failure.
- The SDK's own region chain is **never consulted**. That chain probes IMDS before giving up with
  "Unable to load region", so leaving it in play would be a second route to the node's environment and a
  second thing to reason about.

Set the endpoint anyway: blank makes *both* regions `us-east-1`, which a bucket elsewhere rejects. That
constraint already exists for the key-pair path; workload identity neither adds nor removes it.

**Trigger: the blank pair, mirroring `azureblob`.** No new property. The `isAwsS3()` branch goes **ahead**
of the constant-credentials fallback, which keeps `DEFAULT_IDENTITY`/`DEFAULT_CREDENTIAL` confined to
`filesystem`, where a placeholder is harmless. Nothing Azure-specific leaks into the S3 path:
`managedIdentity` is read only under `isAzureBlob()` in `effectiveOverrides`. A present pair still wins, so
the existing shared-key S3 configuration is bit-for-bit unchanged — which is what makes this additive
rather than a migration.

**Caching lives in the SDK provider, synchronously — and async is not merely unnecessary, it is unsafe
here.** `asyncCredentialUpdateEnabled` defaults to `false` in `StsCredentialsProvider.BaseBuilder`, so
there is **no refresh thread**: a stale `CachedSupplier` refreshes on the calling thread. The defaults —
`prefetchTime` 5 min, `staleTime` 1 min before expiry — mean one call per session pays the round trip, and
an STS blip inside the prefetch window keeps serving the cached value rather than failing a cycle.

Do **not** enable async. The inherited `close()` closes `StsCredentialsProvider`'s own `sessionCache`,
which *this subclass never uses* — `resolveCredentials()` delegates to an inner
`StsAssumeRoleWithWebIdentityCredentialsProvider` that nothing ever closes — so a `NonBlocking` prefetch
thread would outlive shutdown with nothing holding a reference to stop it. The same reading shows
`close()` never closes the `StsClient` either, which is why the provider we write implements
`AutoCloseable` and closes **both**.

**Refresh cannot thrash, whatever the role's session duration.** The provider sets no `DurationSeconds`
anywhere in the STS module's sources, so STS issues the role's default and IAM's `MaxSessionDuration` floor
is 3600s; the SDK renews at `expiration − 5min` and hard-expires at `expiration − 1min`. There is nothing
to ask the cluster's owners and nothing to tune — worth recording because "what session duration do you
want" is the obvious question to send, and it has no answer that matters.

**Failure stays per-cycle, not at startup.** A missing `AWS_ROLE_ARN` / `AWS_WEB_IDENTITY_TOKEN_FILE` is
caught in the SDK provider's constructor, stored as `loadException` and rethrown from every
`resolveCredentials()`. A *present* variable pointing at a *missing* file is not caught there at all — it
surfaces as an `UncheckedIOException` on each refresh. Either way the first resolution happens inside
`buildContext()`, so cycle one fails whole, `context` stays `null`, and the next cycle retries from
scratch. This falls out of the existing lazy build; no new error handling is written to obtain it.

It is also the right behaviour rather than merely the cheap one: the fault clears when the cluster
re-associates the role, with no deploy, and a service that refused to start would stay down after the fault
had cleared — taking the query surface with it for a reason that has nothing to do with queries. The
existing sync reporting is sufficient to see it (failure outcome, held watermark, growing lag); no health
indicator is added, and there is none for the blob path today.

**Memoize the provider in a field — a bug fix riding along, not a refactor.** `context()` retries
`buildContext()` on every cycle after a failure. Today the provider is constructed inside it, so a
`buildView` failure leaves the provider — and, once this change lands, its `StsClient` — unreachable while
the next cycle builds another. A repeatedly failing cycle would leak one client per cycle, a slow failure
introduced by the error path. Creating the provider **before** `buildContext()` and holding it in a field
fixes the leak and the repeated construction at once, and `shutdown()` then closes the one field next to
the context. `credentialProvider` drops `private` so a test can substitute it; checkstyle enforces neither
`DesignForExtension` nor `VisibilityModifier` here, so this is clean.

**The `netty-nio-client` exclusion is load-bearing.** `sts:2.46.8` declares **both** `apache5-client` and
`netty-nio-client` (alongside `aws-core`/`sdk-core`/`auth`/`regions`/`profiles`/`retries`/`http-auth-aws`),
and 2.46.8 is managed against Netty **4.1.135.Final** while this project pins `netty-bom:4.2.17.Final`.
Keeping the async client would force a 4.2 runtime under a client compiled against 4.1 — for a client we
never call. The sync path survives the exclusion: `apache5-client` registers
`software.amazon.awssdk.http.apache5.Apache5SdkHttpService` as the **only** `SdkHttpService`, and its
httpclient5 5.6.1 / httpcore5 5.4.2 are already exceeded by the project's resolved 5.6.3 / 5.4.3.

The tripwire to watch is the opposite failure: a **second** `SdkHttpService` on the classpath fails the
`StsClient` build outright with "Multiple HTTP implementations were found". That is a loud, immediate,
startup-adjacent failure rather than a silent one, which is the reason this is acceptable to leave to a
dependency check rather than pinning an explicit http client on the builder. If the apache5 alignment ever
misbehaves, `url-connection-client` (no third-party dependencies) is the drop-in — one STS POST per hour
needs no connection pooling.

**Mapping the credentials.** Workload-identity mode maps `AwsSessionCredentials` to
`SessionCredentials.builder().accessKeyId(..).secretAccessKey(..).sessionToken(..).expiration(Date.from(..))
.build()`. Two details that do not survive a careless edit: `expiration` takes a `java.util.Date` while
`AwsSessionCredentials.expirationTime()` is an `Optional<Instant>`; and `build()` feeds `accessKeyId` into
the inherited `identity`, which is what SigV4 actually signs with. Shape the class like
`AzureCredentialProvider` — the constructor decides shared-key versus workload identity **once**, from
nullness — and add a package-private constructor taking an `AwsCredentialsProvider` plus a resolved region
so the test needs no AWS.

**Never log the resolved credentials.** `SessionCredentials.toString()` prints `accessKeyId` and
`sessionToken` verbatim — only the secret key is omitted (read from bytecode, not assumed). Those two plus
the secret are enough to impersonate the role for the session's life, and a log line is the one place they
would outlive the session's expiry.

**Concurrency is already handled, and worth stating so nobody adds a lock.**
`spring.task.scheduling.pool.size` is 4 and the backfill owns a `data-backfill` thread, so up to three
callers can race the first build. `context()` is already `synchronized` for the build, and
`StsCredentialsProvider` is `@ThreadSafe` over a `CachedSupplier`, so exactly one caller pays a refresh.

**Tests are plain JUnit, not integration tests.** Both live in `com.epam.aidial.analytics.blob` and **not**
under `.../integration/`: neither touches ClickHouse or the catalog, so neither should require Docker, and
same-package placement keeps the package-private seam package-private. The seams already exist —
`TestBlobSpans.noop()`, and `BlobPollingServiceTest:159` already anonymous-subclasses `BlobClient`.

The second test is the interesting one: it proves **jclouds honours a session credential**, which is the
assumption this whole change rests on, and it does so without AWS and without a new test dependency. A JDK
`com.sun.net.httpserver` endpoint (`jdk.httpserver` — no LocalStack, which is not a dependency either)
stands in for S3; the `BlobClient` subclass returns a fixed `SessionCredentials`, so the assertion is about
the **signer**, not about STS. Three configuration details make it work: point it at
`http://127.0.0.1:<port>`, set `blob.overrides = {jclouds.s3.virtual-host-buckets=false}` because `aws-s3`
defaults that **true** in `AWSS3ApiMetadata`, and give the bucket a DNS-compatible name or
`AWSS3BucketNameValidator` rejects it. It is stable against host parsing because `parseServiceName` /
`parseRegionName` fall back to `s3` / `us-east-1` for a `127.0.0.1` endpoint — probed, not assumed.

## Risks / Trade-offs

- **A revoked or narrowed role will not look like a credential error.** The cached session stays valid
  until it expires, so the failure surfaces as an S3 `403` (`AWSResponseException`) per cycle rather than as
  a credential fault, for up to the remaining session life. Accepted: the alternative is re-validating the
  role on every cycle, which buys a better message for a rare event at the cost of an STS call per cycle.
- **No health indicator and no credential metric.** A sustained credential fault is visible as sync-cycle
  failures, a held watermark and a climbing lag — the same signals as any other ingest outage. Adding a
  blob-credential health indicator would be the first one in `src/main` for this path, and an indicator that
  can report DOWN interacts with the service's probe topology; out of scope here, but it is the obvious
  follow-up if S3 becomes the primary source.
- **Only the projected-token mechanism is supported.** A cluster that adopts Pod Identity instead gets a
  per-cycle failure, not a fallback. That is the intended trade (see the first decision), and the fix is
  additive.
- **The SDK enters the dependency graph for one call per hour.** The STS module drags `sdk-core`, `auth`,
  `regions`, `profiles`, `retries` and an HTTP client. Justified by jclouds' STS API not exposing
  `assumeRoleWithWebIdentity`, and bounded by excluding the async client; the alternative — hand-rolling a
  signed `AssumeRoleWithWebIdentity` POST and its XML parsing — is more code in the security-critical path,
  with the refresh, caching and expiry logic rewritten rather than inherited.
- **Ships without the layout half.** Alone, this change authenticates to a flat bucket and then discovers
  nothing, because that bucket has no date directory. The mitigation is rollout sequencing, not code: the
  two changes go out together, and the proposal says so rather than leaving it to be discovered after deploy.

## Migration Plan

No migration, no configuration property, no contract-version bump, no new grant on the ClickHouse side.
The IAM policy ADAS needs is unchanged — `s3:GetObject` on the log prefix and `s3:ListBucket` scoped to it,
with `kms:Decrypt` only for an SSE-KMS CMK — because the *permissions* were never the gap; only the way the
identity is obtained was. `s3:GetBucketLocation` is never called and may be kept or dropped.

Rollout is a configuration change on the deployment side (the service-account annotation, `BLOB_PROVIDER`,
`BLOB_BUCKET`, `BLOB_ENDPOINT` at the bucket's **regional** endpoint, and `BLOB_IDENTITY`/`BLOB_CREDENTIAL`
left unset) plus the paired layout change. A deployment that supplies a key pair is unaffected and needs no
action, so there is no ordering constraint and nothing to roll back in lockstep.

Verification before the deploy is offline and cheap: resolve the runtime classpath and confirm no
`netty-nio-client`, exactly one `SdkHttpService`, and Netty still at the pinned 4.2 line; then run with
`BLOB_PROVIDER=aws-s3`, no key pair and no AWS environment, and confirm the failure is the per-cycle
"poll cycle failed; watermark held" shape naming the missing variable — **not** a placeholder-credential
`403`, which is what the same configuration produces today.

## Open Questions

- **Should the blob path grow a health indicator, or a credential-age metric?** Left out here because the
  ingest signals already cover a sustained fault and because a DOWN-capable indicator has consequences for
  the pod's probes. Worth revisiting if S3 becomes the primary source rather than one deployment's.
- **Does any deployment want an ordered set of workload-identity sources** (projected token, then Pod
  Identity), or is one-per-deployment enough? Nothing here forecloses it; the design only forbids a *host*
  identity from being in that set.
