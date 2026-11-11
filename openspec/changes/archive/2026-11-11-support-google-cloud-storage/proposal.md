## Why

A DIAL deployment on Google Cloud writes its usage logs to a **Cloud Storage bucket**, but `data-sync` can
only authenticate to Azure Blob and AWS S3. jclouds' `google-cloud-storage` provider is already on the
runtime classpath (it ships in `jclouds-allblobstore`), yet nothing in ADAS selects, documents or tests
it. `BlobClient.credentialProvider` gives every provider other than `azureblob` / `aws-s3` a **constant**
`Credentials`. Keyless access, the way GKE grants it (Workload Identity Federation for GKE, no key
material in the pod), is therefore impossible. A blank pair falls through to the inert placeholder
values, and every listing then fails with an OAuth error that names neither what is missing nor where.

The gap is narrow. Each of the following was checked against the 2.7.3 jars, not inferred:

- **The data plane works unchanged.** The provider maps `ListContainerOptions.prefix` / `recursive` /
  `afterMarker` to the JSON API's `prefix` / no delimiter / `pageToken`, returns `nextPageToken` as the
  next marker, and maps the object's `updated` time to `lastModified`. Those are exactly the calls
  `BlobClient.listWithMetadata` and `openStream` make. The project id it derives from the identity is
  read only by container-level operations (`list()`, `createContainerInLocation`), which ADAS never calls.
- **Key mode needs no new code.** The default OAuth credential type
  (`P12_PRIVATE_KEY_CREDENTIALS`, despite its name) signs a JWT from identity = service-account
  `client_email` and credential = its **PEM** private key, then caches the exchanged token.
- **Keyless mode needs no jclouds patch.** With `jclouds.oauth.credential-type=bearerTokenCredentials`,
  `BearerTokenFromCredentials` reads our credentials supplier **on every request** and sends
  `Authorization: Bearer <credential>`. This is the same seam the keyless Azure path already uses.

The only missing piece is obtaining that token, which needs Google's auth library. It is not a dependency
today at any scope.

## What Changes

- **`google-cloud-storage` becomes a supported `BLOB_PROVIDER`** alongside `azureblob` and `aws-s3`, for
  the `data-sync` ingest path only (daily run, poller, backfill and retry sweep all go through the same
  `BlobClient`).
- **A blank `BLOB_IDENTITY` / `BLOB_CREDENTIAL` pair on a GCS source means keyless access.** This is the
  same rule the other two providers use. A new `GcsCredentialProvider` resolves a short-lived OAuth access
  token through Google **Application Default Credentials**, scoped read-only to Cloud Storage, and hands it
  to jclouds as a bearer token. On GKE that resolves through Workload Identity. Outside GKE it resolves
  through a credentials file named by `GOOGLE_APPLICATION_CREDENTIALS`: a service-account key, or a
  workload-identity-federation config for a pod running on another cloud.
- **A present pair keeps jclouds' native service-account flow**: identity is the `client_email`,
  credential is the PEM private key. No token library is involved.
- **The resolved principal is logged once.** Only the service-account email, never the token. On GCP the
  keyless chain can resolve to a node's default service account when Workload Identity is not enabled.
  The log line makes that visible without a new property (see `design.md`).
- **Failure stays per sync cycle**, as for the other keyless paths. A missing or unusable credential fails
  that cycle, holds the watermark, and is retried on the next cadence. Startup is unaffected.
- **No new configuration property**, no migration, no contract-version bump. Nothing changes for
  `azureblob` or `aws-s3` deployments.
- **Docs**: a new `docs/gcs-access.md` (credential modes, Workload Identity binding, the IAM role to
  grant, endpoint override), plus one-line updates to the five `blob.*` rows in `docs/configuration.md`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`:
  - **ADDED**: a GCS blob source with the blank-pair keyless trigger; token resolution through
    Application Default Credentials with read-only scope and per-request refresh; principal visibility
    without credential leakage; per-cycle rather than startup failure.
  - **MODIFIED**: `Workload-identity credentials for an S3 blob source`. Its "other providers keep their
    existing credential handling" scenario names the providers that fall through to the placeholder
    defaults, and `google-cloud-storage` stops being one of them.

## Impact

- **Surfaces**: no `/v1` endpoint, request or response shape, error code, or catalog schema changes. Config
  keys are unchanged. `blob.provider` accepts one more documented value, and `blob.identity` /
  `blob.credential` / `blob.endpoint` gain a GCS meaning.
- **Code**: new `blob/credential/GcsCredentialProvider.java`. `blob/BlobClient.java` gains an
  `isGoogleCloudStorage()` branch in `credentialProvider(...)` and in `effectiveOverrides(...)`, the
  bearer-token override applied in keyless mode only. Javadocs in `BlobClient` and `BlobProperties`
  name the new provider. `BlobProperties` itself is unchanged.
- **Build**: one new pinned dependency, `com.google.auth:google-auth-library-oauth2-http`. Its
  transitive `google-http-client`, Gson and Guava must align with the project's existing pins (Guava is
  constrained to 33.7.2-jre).
- **Tests**: plain JUnit in `com.epam.aidial.analytics.blob`, no Docker or GCP. One test covers the
  credential mapping and mode selection. One proves against a JDK `HttpServer` that jclouds sends the
  supplied bearer token and that list, pagination and get behave as `BlobClient` expects.
- **Docs**: `docs/gcs-access.md` (new), `docs/configuration.md` (row text plus a link, **no new row**),
  and the README's one-line list of blob stores.
- **Cross-service**: none. `RunnerContract.VERSION` is not touched.
- **Operational**: the bucket owner grants `roles/storage.objectViewer` (or the narrower
  `storage.objects.list` + `storage.objects.get`) to the ADAS service account. For keyless access on GKE,
  the Kubernetes service account is bound to it through Workload Identity. Both are deployment-side
  hand-offs; no chart lives in this repo.
