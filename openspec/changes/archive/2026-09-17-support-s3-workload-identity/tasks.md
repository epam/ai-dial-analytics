## 1. The dependency

- [x] 1.1 Add `implementation 'software.amazon.awssdk:sts:<pin>'` to the jclouds block in `build.gradle`
      (`:156-163`), in the house comment style, naming why it is pinned rather than BOM-managed.
- [x] 1.2 Exclude `netty-nio-client` on that dependency, with a comment recording the reason: `sts` declares
      both HTTP clients, 2.46.8 is managed against Netty **4.1.135.Final**, and this project pins
      `netty-bom:4.2.17.Final` (`:59`) — keeping it forces a 4.2 runtime under a 4.1-compiled client we
      never call. Note the tripwire in the same comment: a **second** `SdkHttpService` on the classpath
      fails the `StsClient` build with "Multiple HTTP implementations were found".
- [x] 1.3 Verify the resolved graph:
      `./gradlew dependencies --configuration runtimeClasspath | grep -iE "awssdk|netty-nio|httpclient5"`
      — no `netty-nio-client`, exactly one `SdkHttpService` (apache5), and Netty still at 4.2.17.Final.
      `apache5-client`'s httpclient5 5.6.1 / httpcore5 5.4.2 must stay at or below the project's resolved
      5.6.3 / 5.4.3.

## 2. The credential provider

- [x] 2.1 Add `src/main/java/com/epam/aidial/analytics/blob/credential/AwsCredentialProvider.java`
      implementing the existing `CredentialProvider` (`Credentials getCredentials()`, whose javadoc already
      promises per-request refreshables) and shaped like `AzureCredentialProvider`: the constructor decides
      shared-key versus workload identity **once**, from nullness of the pair.
- [x] 2.2 In workload-identity mode, resolve through
      `software.amazon.awssdk.services.sts.auth.StsWebIdentityTokenFileCredentialsProvider`. It has no
      `create()` and its super-constructor rejects a null `StsClient`, so build the `StsClient` here and
      give it `AnonymousCredentialsProvider` — `AssumeRoleWithWebIdentity` is `noAuth`, so it needs no
      credentials and then **cannot** reach IMDS. Leave `asyncCredentialUpdateEnabled` at its `false`
      default; do not enable async (see `design.md` — the inherited `close()` closes a cache this subclass
      never uses, so a prefetch thread would outlive shutdown).
- [x] 2.3 Resolve the STS region from the effective S3 endpoint with
      `parseRegionName(URI.create(ep).getHost(), parseServiceName(URI.create(ep)))`, `ep` being
      `BLOB_ENDPOINT` or `https://s3.amazonaws.com` (the `AWSS3ProviderMetadata` default) when blank. Do
      **not** let the SDK's region chain run — it probes IMDS before failing. `parseRegionName` never
      fails, so there is no failure branch to write.
- [x] 2.4 Map `AwsSessionCredentials` to
      `SessionCredentials.builder().accessKeyId(..).secretAccessKey(..).sessionToken(..)
      .expiration(Date.from(..)).build()`. `expiration` takes a `java.util.Date` while
      `expirationTime()` is an `Optional<Instant>`; `build()` feeds `accessKeyId` into the inherited
      `identity`, which is what SigV4 signs with.
- [x] 2.5 Implement `AutoCloseable`, closing **both** the SDK provider and the `StsClient` — the inherited
      `close()` closes neither of the things this subclass actually uses.
- [x] 2.6 Never log the resolved value, and say so in the javadoc: `SessionCredentials.toString()` prints
      `accessKeyId` and `sessionToken` verbatim; only the secret key is omitted.
- [x] 2.7 Add a package-private constructor taking an `AwsCredentialsProvider` plus a resolved region, so
      the unit test needs no AWS and no network.

## 3. Wiring it into `BlobClient`

- [x] 3.1 Add an `isAwsS3()` branch to `credentialProvider(...)` (`:179-187`), **ahead** of the
      constant-credentials fallback, so `DEFAULT_IDENTITY`/`DEFAULT_CREDENTIAL` stay confined to
      `filesystem`. A present pair still wins, so today's shared-key S3 configuration is unchanged.
- [x] 3.2 Memoize the provider in a field, created **before** `buildContext()` and reused across retries.
      Today a `buildView` failure leaves the provider and its `StsClient` unreachable while `context()`
      retries from scratch, so every failed cycle would leak a client.
- [x] 3.3 Close that field in `shutdown()` (`:225-235`), next to the context, tolerating a close failure the
      way the context close already does.
- [x] 3.4 Drop `private` from `credentialProvider` so a test can substitute it — checkstyle enforces neither
      `DesignForExtension` nor `VisibilityModifier` here.
- [x] 3.5 Update the `credentialProvider` javadoc, which currently describes the non-Azure path as "use the
      values directly, defaulting to inert placeholders".
- [x] 3.6 Fix the `BlobClient` and `BlobProperties` class javadocs, which still say "`azureblob` in
      production, `filesystem` in tests"; name `aws-s3`. `BlobProperties` otherwise needs no change — no
      property is added, so nothing new is redacted in `toString()`.

## 4. Tests

- [x] 4.1 Add `AwsCredentialProviderTest` in `com.epam.aidial.analytics.blob.credential` — the package the
      package-private test seam lives in (plain JUnit, **not** under
      `.../integration/` — it touches neither ClickHouse nor the catalog and must not need Docker). Cover:
      a key pair set yields a plain `Credentials`; a blank pair with a stub `AwsCredentialsProvider`
      returning `AwsSessionCredentials` yields a `SessionCredentials` carrying the session token and the
      expiry; the region is derived from a regional `BLOB_ENDPOINT`, is `us-east-1` for a blank one, and is
      `us-east-1` for a host `parseRegionName` does not know.
- [x] 4.2 Add the test that **jclouds honours a `SessionCredentials`**, without AWS: a JDK
      `com.sun.net.httpserver` endpoint (`jdk.httpserver`, so no new test dependency; no LocalStack, also
      not a dependency); subclass `BlobClient` to return a fixed `SessionCredentials` from
      `credentialProvider`, which makes this an assertion about the **signer**, not about STS. Point it at
      `http://127.0.0.1:<port>` with `blob.overrides = {jclouds.s3.virtual-host-buckets=false}` (`aws-s3`
      defaults it **true** in `AWSS3ApiMetadata`), give the bucket a DNS-compatible name
      (`AWSS3BucketNameValidator` rejects otherwise), and reply with a minimal
      `<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>`. Assert the captured request
      carries `X-Amz-Security-Token` and an `AWS4-HMAC-SHA256` `Authorization`.
- [x] 4.3 Watch the checkstyle method-name rule in both: no `aFooBar` (the second character must be
      lowercase) and no two consecutive capitals. It fails `build`, not just the lint task.
- [x] 4.4 Reuse the existing seams rather than adding new ones — `TestBlobSpans.noop()`, and the
      anonymous `BlobClient` subclass pattern already used at `BlobPollingServiceTest:159`.

## 5. Docs

- [x] 5.1 Create `docs/aws-s3-access.md` and move the whole `#### AWS S3` subsection of
      `docs/configuration.md` into it — prose, the `bash` block and the IAM policy JSON are behaviour and
      operator knowledge, which the conventions keep out of a strict property reference. Cover:
      endpoint/region and SigV4, virtual-host addressing and DNS-compatible bucket names, the IAM policy,
      the workload-identity service-account annotation, and the trust-policy subject.
- [x] 5.2 In `docs/configuration.md`, leave the five `blob.*` rows plus a one-line link to the new doc, and
      **delete** the now-false sentence "The key pair is the only credential the provider accepts;
      temporary credentials — IRSA, instance profile, `AssumeRole` — are not supported".
- [x] 5.3 Give `BLOB_IDENTITY` / `BLOB_CREDENTIAL` one line each covering S3 as well as Azure — blank on
      either provider now selects a runtime-resolved identity. **No new row**: this change adds no property.
- [x] 5.4 Do **not** touch `docs/kubernetes-exposure.md` — it is scoped to exposing the query API from an
      AKS cluster through Traefik, plus egress. EKS workload identity is neither.
- [x] 5.5 Keep the markdown table padding aligned in `docs/configuration.md`; fix an oversized cell by
      re-padding the table, never by unpadding it.

## 6. Endpoint robustness

- [x] 6.1 Make `stsRegion` total, as its javadoc and the spec both promise: trim the endpoint, and fall
      back to the default region when `URI.create` throws or the parsed host is `null`. Verified against
      the real classpath that without this, a scheme-less endpoint, a host with an underscore, a
      `host:port` value or one with surrounding whitespace throws `NullPointerException` /
      `IllegalArgumentException` out of the provider's constructor and surfaces as a per-cycle
      `BlobAccessException` naming neither `BLOB_ENDPOINT` nor a region. `BlobProperties` has no compact
      constructor, so nothing trims the value earlier.
- [x] 6.2 Log a warning in that fallback naming `BLOB_ENDPOINT` and the region fallen back to — jclouds
      derives the data-plane region the same way, so whatever failed here fails there too.
- [x] 6.3 Warn at context build when an `aws-s3` source has no endpoint, mirroring the `azureblob`
      keyless branch: the default is the global endpoint, so both SigV4 and STS sign for `us-east-1` and
      a bucket anywhere else rejects every request with nothing pointing at the setting.
- [x] 6.4 Cover all of it in `AwsCredentialProviderTest`: the four unreadable endpoint forms and the
      whitespace case, each resolving to the default region rather than throwing.

## 7. Verification

- [x] 7.1 `./gradlew clean build` (Docker available for the integration tests) — green, including
      the two new tests. This is the completion gate.
- [x] 7.2 Locally, with no AWS environment set, start with `BLOB_PROVIDER=aws-s3` and no key pair. Expect
      `Poll cycle failed; watermark held at …` once a minute wrapping `BlobAccessException` →
      `IllegalStateException` (**not** `SdkClientException`: `SystemSetting.getStringValueOrThrow` throws
      "Either the environment variable AWS_WEB_IDENTITY_TOKEN_FILE or the javaproperty … must be set"),
      with `poll.cycles{outcome=failure}` incrementing and `poll.watermark.lag.seconds` climbing — **not** a
      placeholder-credential 403, which is what this configuration produces today.
      *Verified at the provider level rather than through a running poll cycle: constructing
      `AwsCredentialProvider` with no AWS environment succeeds, and `getCredentials()` throws
      `java.lang.IllegalStateException: Either the environment variable AWS_WEB_IDENTITY_TOKEN_FILE or the
      javaproperty aws.webIdentityTokenFile must be set.` — so the failure is per-resolution, as specified.*
- [x] 7.3 Then point `AWS_WEB_IDENTITY_TOKEN_FILE` at a path that does not exist: the same per-cycle shape,
      but an `UncheckedIOException` — that case is not caught in the SDK provider's constructor.
      *Verified the same way: `java.io.UncheckedIOException: java.nio.file.NoSuchFileException: <path>`.*
- [x] 7.4 Confirm the shared-key path is untouched: an `aws-s3` source with both values set still builds a
      plain `Credentials` and resolves no workload identity.
- [ ] 7.5 Against a running deployment: the annotation present on the pod, one poll cycle ingesting
      rows, and `sync.blobs.band.shape.mismatch` flat at zero. The first cycle after every restart pays one
      STS round trip inside `buildContext()`. Note that a role revoked or narrowed mid-run will **not** look
      like a credential error — the cached session stays valid, so it surfaces as an S3 403
      (`AWSResponseException`) per cycle until expiry.
- [ ] 7.6 Ingestion on the target bucket also needs the paired `allow-flat-blob-key-layout` change; this one
      authenticates but discovers nothing against a bucket with no date directory. Roll them out together.

## 8. Spec

- [x] 8.1 Confirm `openspec/changes/2026-09-17-support-s3-workload-identity/specs/data-sync/spec.md` still
      matches the shipped behaviour before archiving — it is **ADDED only**, so no existing requirement of
      the 33 in `openspec/specs/data-sync/spec.md` may need restating.
- [x] 8.2 `openspec validate 2026-09-17-support-s3-workload-identity --strict` passes.
