## 1. Dependency

- [x] 1.1 Add `implementation 'com.google.auth:google-auth-library-oauth2-http:<pin>'` to the blob block in
      `build.gradle`, next to `azure-identity` and the AWS `sts` entry, in the house comment style. Say what
      it is for (Application Default Credentials → access token for the `google-cloud-storage` keyless path)
      and why it is pinned rather than BOM-managed.
- [x] 1.2 Resolve `./gradlew dependencies --configuration runtimeClasspath` and check:
      - Guava stays at the constrained `33.7.2-jre`.
      - No Netty artifact and no second AWS `SdkHttpService` arrives.
      - `google-http-client` / `google-http-client-gson` are the only HTTP additions.
      Exclude transitive modules the auth path never calls (`opencensus-*`, `grpc-context`, if the chosen
      version declares them), with a comment giving the reason.

## 2. Credential provider

- [x] 2.1 Add `src/main/java/com/epam/aidial/analytics/blob/credential/GcsCredentialProvider.java`
      implementing `CredentialProvider` and `AutoCloseable`, shaped like the Azure and AWS providers. The
      constructor decides key-pair versus keyless **once**, from the nullness of the pair. In key-pair mode
      `getCredentials()` returns the pair unchanged as `Credentials(clientEmail, pemKey)`.
- [x] 2.2 Keyless mode: obtain `GoogleCredentials.getApplicationDefault()` **lazily on first
      `getCredentials()`**, not in the constructor. Apply `createScoped("https://www.googleapis.com/auth/devstorage.read_only")`
      when `createScopedRequired()`. Then call `refreshIfExpired()` + `getAccessToken()` on the calling
      thread, returning `new Credentials("", tokenValue)`. Never use the executor-based
      `getRequestMetadata` path. Wrap `IOException` in an unchecked exception whose message names ADC and
      `GOOGLE_APPLICATION_CREDENTIALS`.
- [x] 2.3 Make first-use resolution thread-safe (double-checked, as in `AzureCredentialProvider`) so
      concurrent first callers build one credential object and make one token request.
- [x] 2.4 On first successful resolution, log once at info the principal and the source. The principal
      comes from `ServiceAccountCredentials.getClientEmail()`, `ComputeEngineCredentials.getAccount()`,
      `ExternalAccountCredentials.getServiceAccountEmail()` or `ImpersonatedCredentials.getAccount()`. The
      source is credentials file or metadata server. A failed or null principal lookup logs "principal
      unknown" and does not fail the cycle.
- [x] 2.5 Never log token values, the private key, or credential objects. Their `toString()` may include
      token state. Say so in the class javadoc.
- [x] 2.6 Add a package-private constructor that takes a credential source (a loader returning
      `GoogleCredentials`), so tests need no ADC environment and no network, and can also simulate a failing
      lookup.

## 3. Wiring into `BlobClient`

- [x] 3.1 Add `isGoogleCloudStorage()` (`"google-cloud-storage"`, case-insensitive) and a branch in
      `credentialProvider(...)` **ahead of** the constant-credentials fallback, so the placeholders stay
      confined to `filesystem`.
- [x] 3.2 In `effectiveOverrides(...)`, when the provider is GCS and `managedIdentity` is true,
      `putIfAbsent("jclouds.oauth.credential-type", "bearerTokenCredentials")`. Add nothing in key-pair mode,
      and no blank-endpoint warning for GCS.
- [x] 3.3 Confirm `shutdown()` closes the memoized provider through its existing `AutoCloseable` check. No
      new close code is needed.
- [x] 3.4 Update the `BlobClient`, `credentialProvider`, `effectiveOverrides` and `BlobProperties` javadocs
      to name `google-cloud-storage` and its two modes. `BlobProperties` gains no field.

## 4. Tests

- [x] 4.1 `GcsCredentialProviderTest` in `com.epam.aidial.analytics.blob.credential`, plain JUnit. Cover:
      - A set pair returns it unchanged and never touches ADC.
      - A stub `GoogleCredentials` with a fixed `AccessToken` maps to `Credentials("", token)`.
      - An expired stub is refreshed before use.
      - Concurrent first calls trigger one refresh.
      - Principal extraction for service-account and compute credential stubs.
      - A failing ADC lookup surfaces from `getCredentials()`, not from the constructor.
      - Captured log output never contains the token.
- [x] 4.2 `BlobClientGcsTest` in `com.epam.aidial.analytics.blob`: a JDK `com.sun.net.httpserver` endpoint,
      `blob.endpoint=http://127.0.0.1:<port>`, and a fixed bearer `CredentialProvider` substituted through
      the package-private seam. Assert:
      - `Authorization: Bearer <token>`.
      - Two `storage/v1/b/<bucket>/o` pages joined by `nextPageToken` are listed completely.
      - `updated` becomes the listing's last-modified time.
      - `openStream` returns the `alt=media` bytes for a key containing `/`.
- [x] 4.3 Key-pair path in the same test class: generate an RSA key, set `blob.overrides` `oauth.endpoint` to
      the local server, and assert a JWT-bearer token request is posted and its returned token authorizes
      the listing.
- [x] 4.4 Reuse `TestBlobSpans.noop()` and the existing anonymous-`BlobClient` pattern. Watch the checkstyle
      method-name rule (no `aFoo` names, no two consecutive capitals).

## 5. Docs

- [x] 5.1 Create `docs/gcs-access.md`, modelled on `docs/aws-s3-access.md`. Cover:
      - Selecting the provider.
      - The endpoint (blank is correct; override for private access).
      - The credential-mode table: pair set → service-account key; blank → ADC.
      - Preferring Workload Identity, then a mounted key file via `GOOGLE_APPLICATION_CREDENTIALS`, over a
        PEM in `BLOB_CREDENTIAL`.
      - The Workload Identity binding (`iam.gke.io/gcp-service-account` annotation +
        `roles/iam.workloadIdentityUser`).
      - The bucket grant (`roles/storage.objectViewer`, or `storage.objects.list` + `storage.objects.get`).
      - The node-service-account caveat and the principal log line to check it against.
- [x] 5.2 In `docs/configuration.md` § Blob source, name `google-cloud-storage` as the third supported
      provider and link `gcs-access.md`. Update the existing `BLOB_PROVIDER`, `BLOB_IDENTITY`,
      `BLOB_CREDENTIAL` and `BLOB_ENDPOINT` descriptions to cover GCS, one line each, keeping the table
      padding aligned. **No new row**: no property is added.
- [x] 5.3 README: extend the "from Azure Blob Storage or AWS S3" sentence and the docs table with Google
      Cloud Storage / `gcs-access.md`.
- [x] 5.4 Leave `application.yml` untouched. No key or default changes.

## 6. Verification

- [x] 6.1 Locally, with no Google environment, run with `BLOB_PROVIDER=google-cloud-storage` and no pair.
      Expect `Poll cycle failed; watermark held` once per poll naming the ADC lookup, with startup and
      `/actuator/health` unaffected, and no OAuth error about a placeholder credential.
      *Verified at the provider level rather than through a running poll cycle: with no
      `GOOGLE_APPLICATION_CREDENTIALS` and no gcloud config, constructing `GcsCredentialProvider` does no
      lookup, and `getCredentials()` throws `UncheckedIOException` ("Cannot resolve Google Application Default
      Credentials … GKE Workload Identity, or point GOOGLE_APPLICATION_CREDENTIALS at a credentials file")
      wrapping the library's "default credentials were not found", in about half a second. The cycle-level
      shape follows from the existing lazy build, which `BlobClientGcsTest` exercises.*
- [x] 6.2 Confirm `azureblob` and `aws-s3` behaviour is unchanged: the existing blob and credential tests pass
      untouched.
- [x] 6.3 Hygiene sweep over the touched files with `git grep -nIP` per CLAUDE.md. No environment, project
      or bucket names; fixtures use invented values.
- [x] 6.4 Confirm `specs/data-sync/spec.md` matches the shipped behaviour, and
      `openspec validate support-google-cloud-storage --strict` passes.
- [x] 6.5 `./gradlew clean build` passes. This is the completion gate.
- [x] 6.6 End to end through a running service on the keyless path: one poll cycle ingests rows, and the
      principal log line names the bound service account.
      *Verified locally against stand-ins, not against GCP. ADAS ran via `bootRun` on the local ClickHouse +
      PostgreSQL stack with `BLOB_PROVIDER=google-cloud-storage`, no key pair, no `GOOGLE_APPLICATION_CREDENTIALS`
      and no gcloud config. `GCE_METADATA_HOST` pointed at a fake GKE metadata server (token + email endpoints,
      `Metadata-Flavor` enforced). `BLOB_ENDPOINT` pointed at a minimal Cloud Storage JSON API stand-in that
      serves one object per page and rejects requests without the expected bearer token. The first poll cycle
      did the following:*
      - *resolved ADC to the metadata server, which got one token request*
      - *logged `principal adas-reader@example-project.iam.gserviceaccount.com from the metadata server`*
      - *followed `pageToken` across two pages*
      - *fetched both blobs with `alt=media`, every request carrying `Authorization: Bearer <token>`*
      - *ingested 2 blobs / 3 records into `dial_usage_log`, plus their payload rows*
      *The next cycle discovered nothing new, wrote no duplicates and requested no new token. The token never
      appeared in the service log. Real Workload Identity and IAM evaluation are GCP-side and are left to the
      rollout check in `docs/gcs-access.md` (compare the logged principal with the bound account).*
