# AWS S3 blob access

What it takes to run `data-sync` against an S3 bucket instead of an Azure container: which credential
ADAS resolves, what the endpoint decides, and what the bucket's owner has to grant. The variables
themselves are in [`configuration.md`](configuration.md#blob-source); this doc is the operator side of
them.

Selecting the provider is `BLOB_PROVIDER=aws-s3`, with `BLOB_BUCKET` naming the bucket. Which keys
under the bucket are read, and on what schedule, is the
[data sync](configuration.md#data-sync-schedule) group, which is provider-agnostic.

## Endpoint, region and signing

Point `BLOB_ENDPOINT` at the bucket's **regional** endpoint, `https://s3.<region>.amazonaws.com`.

Requests are always signed SigV4, for the region parsed out of that host — the provider never looks the
bucket's own region up. A blank `BLOB_ENDPOINT` falls back to the global `https://s3.amazonaws.com`,
which signs for `us-east-1`; a bucket in any other region rejects that signature. Any host the parser
does not recognise also yields `us-east-1`, so a wrong endpoint fails as a signature error rather than
a configuration one.

The same host decides the **STS** region when ADAS resolves workload identity (below), so the endpoint
is load-bearing twice over: setting it wrong points both the data-plane signature and the credential
exchange at the wrong region.

## Bucket naming

Buckets are addressed **virtual-host style** — `https://<bucket>.s3.<region>.amazonaws.com` — so the
name must be DNS-compatible. Avoid dots in particular: they are legal bucket characters but break TLS
certificate matching against the wildcard `*.s3.<region>.amazonaws.com`.

## Credentials

Two modes, selected by whether the identity pair is set. No property is dedicated to the choice.

| `BLOB_IDENTITY` / `BLOB_CREDENTIAL` | Credential resolved              |
|-------------------------------------|----------------------------------|
| both set                            | Static access key pair           |
| either blank                        | AWS workload identity (EKS IRSA) |

This mirrors `azureblob`, where a blank pair likewise selects managed identity.

### Static access key pair

`BLOB_IDENTITY` is the access key id and `BLOB_CREDENTIAL` the secret access key, supplied from the
deployment's secret store:

```bash
BLOB_PROVIDER=aws-s3
BLOB_BUCKET=dial-usage-logs
BLOB_IDENTITY=<access key id>
BLOB_CREDENTIAL=<secret access key>
BLOB_ENDPOINT=https://s3.eu-central-1.amazonaws.com
```

### Workload identity (EKS IRSA)

Leave **either** of the pair blank and ADAS resolves temporary credentials instead: it exchanges the
pod's projected service-account token for a session via STS `AssumeRoleWithWebIdentity`, reading the
standard `AWS_ROLE_ARN` and `AWS_WEB_IDENTITY_TOKEN_FILE` environment variables. Those are injected by
the EKS pod-identity webhook from an annotation on the service account the pod runs as:

```yaml
serviceAccount:
  annotations:
    eks.amazonaws.com/role-arn: "arn:aws:iam::<account>:role/<role>"
```

Nothing else is configured. There is no ADAS property for the role, the token path or the STS region —
the first two come from the webhook, the third from `BLOB_ENDPOINT`.

**Web identity only, by design.** ADAS does *not* fall through to an instance profile / IMDS when the
web-identity variables are absent. A pod that lost its annotation therefore fails loudly instead of
quietly reading the bucket with the EC2 **node** role, which is a different and usually broader
identity. A missing or wrong annotation is therefore a hard failure, deliberately.

Credentials refresh for the lifetime of the process, well before they expire, and the token file is
re-read on **every** refresh — a re-projected service-account token needs no restart. Sessions last as
long as the role's own `MaxSessionDuration` allows; there is nothing to tune on the ADAS side.

Failure stays per-cycle, not at startup, like every other blob misconfiguration: the client is built on
first use, so a missing role ARN, a token path that does not exist, or a trust policy that rejects the
subject surfaces as a failed poll cycle once a minute with the watermark held, and the next cycle
retries. Note that a role **revoked or narrowed mid-run** does not look like a credential error at all:
the cached session stays valid, so it arrives as an S3 `403` per cycle until the session expires.

## IAM policy

ADAS lists prefixes and reads objects, nothing else. Two actions cover every discovery path — the daily
and backfill runs list `logs/<date-dir>/`, bounded listing sends `logs/<date-dir>/<head><digits>`, a
flat key layout sends `logs/date=<date>`, and the pagination loop only ever adds a marker — so a
`ListBucket` scoped to the `logs/` prefix is enough, and nothing that writes is needed:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AdasListLogPrefixes",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::dial-usage-logs",
      "Condition": {
        "StringLike": {
          "s3:prefix": ["logs/*"]
        }
      }
    },
    {
      "Sid": "AdasReadLogObjects",
      "Effect": "Allow",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::dial-usage-logs/logs/*"
    }
  ]
}
```

Three things to know about it:

- The `s3:prefix` condition is tied to `SYNC_PATH_PREFIX`. **Blanking that variable breaks the policy**,
  because listing prefixes then start at the date directory rather than at `logs/`. Keep the two in
  step, or drop the condition.
- `s3:GetBucketLocation` is **never called** — the provider uses a constant region and does not resolve
  the bucket's. Grant it or don't; it changes nothing.
- A bucket encrypted with an **SSE-KMS customer-managed key** additionally needs `kms:Decrypt` on that
  key. SSE-S3 needs nothing extra.

## Trust policy for the IRSA role

The role carrying that policy is assumed by the pod's service account, so its trust policy names the
cluster's OIDC provider and pins the subject to that exact service account:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::<account>:oidc-provider/oidc.eks.<region>.amazonaws.com/id/<cluster-id>"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "oidc.eks.<region>.amazonaws.com/id/<cluster-id>:aud": "sts.amazonaws.com",
          "oidc.eks.<region>.amazonaws.com/id/<cluster-id>:sub": "system:serviceaccount:<namespace>:<serviceaccount>"
        }
      }
    }
  ]
}
```

The `sub` must match the namespace and name of the service account the ADAS pod actually runs as; a
mismatch is refused by STS and shows up as a failing poll cycle, not as a permission error on the
bucket.
