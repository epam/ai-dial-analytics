# Google Cloud Storage blob access

This doc covers running `data-sync` against a Google Cloud Storage bucket instead of an Azure container:
which credential ADAS resolves, what the endpoint decides, and what the bucket's owner has to grant. The
variables themselves are in [`configuration.md`](configuration.md#blob-source). This doc is the operator
side of them.

To select the provider, set `BLOB_PROVIDER=google-cloud-storage` and name the bucket in `BLOB_BUCKET`.
Which keys under the bucket are read, and on what schedule, is the [data sync](configuration.md#data-sync)
group, which is provider-agnostic.

## Endpoint

Leave `BLOB_ENDPOINT` blank. Requests then go to the public Cloud Storage JSON API at
`https://www.googleapis.com`. The bucket is addressed by name in the request path, so there is no region to
match and no bucket-naming rule beyond Cloud Storage's own. Set the endpoint only to reach the same API
another way, for example a Private Service Connect endpoint.

## Credentials

There are two modes, selected by whether the identity pair is set. No property is dedicated to the choice.

| `BLOB_IDENTITY` / `BLOB_CREDENTIAL` | Credential resolved                                           |
|-------------------------------------|---------------------------------------------------------------|
| both set                            | Service-account key: client email + PEM private key           |
| either blank                        | Application Default Credentials, scoped read-only to Storage |

This mirrors `azureblob` and `aws-s3`, where a blank pair likewise selects the keyless mode. Prefer the
options in this order: Workload Identity, then a mounted key file, then the key pair.

### Workload Identity (GKE)

Leave the pair blank and run the pod under a Kubernetes service account that Workload Identity Federation
for GKE maps to a Google identity. ADAS resolves a short-lived access token from the GKE metadata server.
No key exists anywhere. The usual binding impersonates a Google service account. Annotate the Kubernetes
service account:

```yaml
serviceAccount:
  annotations:
    iam.gke.io/gcp-service-account: "<name>@<project>.iam.gserviceaccount.com"
```

Then grant that Google service account `roles/iam.workloadIdentityUser` for the member
`serviceAccount:<project>.svc.id.goog[<namespace>/<kubernetes-service-account>]`. Granting the bucket role
to the Kubernetes service account's federated principal directly, with no Google service account, also
works.

The cluster and the node pool must both have Workload Identity enabled. On a node pool where it is not, the
metadata server answers with the **node's** service account instead. If that account can read the bucket,
ingestion succeeds under an identity nobody granted for this purpose. To catch that, ADAS logs the principal
once, when it first resolves a token:

```text
google-cloud-storage blob access is keyless; principal <name>@<project>.iam.gserviceaccount.com from the metadata server
```

Check that the logged principal is the bound account, not `<number>-compute@developer.gserviceaccount.com`.
Tokens are never logged.

### Mounted key file, or federation from another cloud

Leave the pair blank and point `GOOGLE_APPLICATION_CREDENTIALS` at a file mounted from the deployment's
secret store. A service-account key file (JSON) works. So does a workload-identity-federation
configuration, which lets a pod on AKS or EKS exchange its own cluster token for Google credentials. The
variable is read by Google's credential library, not by an ADAS property, and a file it names takes
precedence over the metadata server.

### Key pair

`BLOB_IDENTITY` is the service account's `client_email` and `BLOB_CREDENTIAL` its `private_key`, the
multi-line PEM block, both from the key file. jclouds signs a token request with the key itself. This keeps
a multi-line secret in the environment, so the mounted key file above is usually the better shape for the
same key.

```bash
BLOB_PROVIDER=google-cloud-storage
BLOB_BUCKET=dial-usage-logs
BLOB_IDENTITY=<name>@<project>.iam.gserviceaccount.com
BLOB_CREDENTIAL="$(jq -r .private_key key.json)"
```

### Refresh and failure

Tokens are refreshed ahead of expiry on the calling thread and need no restart. Failure stays per-cycle,
not at startup, like every other blob misconfiguration. The client is built on first use, so any of the
following surfaces as a failed poll cycle once a minute, with the watermark held, and the next cycle
retries:

- no credential available
- an unreadable credentials file
- a metadata server that refuses
- a key the token endpoint rejects

A grant **revoked mid-run** does not look like a credential error. Cloud Storage checks IAM on every
request, so the token in hand keeps refreshing and the revocation arrives as a `403` every cycle until the grant
is restored.

## IAM

ADAS lists objects and reads them, nothing else. Grant the identity `roles/storage.objectViewer` **on the
bucket**, or a custom role holding exactly `storage.objects.list` and `storage.objects.get`. Nothing that
writes is needed, and the token ADAS requests is read-only scoped regardless.

Listing is a bucket-level permission, so a prefix condition does not narrow what can be listed. To keep
ADAS away from other data, give the logs a bucket of their own. A bucket encrypted with a customer-managed
Cloud KMS key additionally needs the Cloud Storage service agent to hold `roles/cloudkms.cryptoKeyEncrypterDecrypter`
on that key. That grant is part of setting up the bucket, not a role for ADAS.
