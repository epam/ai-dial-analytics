# Cloud SQL catalog access

This doc covers pointing the PostgreSQL table catalog at a Cloud SQL for PostgreSQL instance with IAM
database authentication: which variables carry the connection, which identity signs in, and what the
instance's owner has to grant. No real password exists anywhere. The variables themselves are in
[`configuration.md`](configuration.md#postgresql-table-catalog). This doc is the operator side of them.

ADAS ships the [Cloud SQL Java Connector](https://github.com/GoogleCloudPlatform/cloud-sql-jdbc-socket-factory),
a socket factory for the PostgreSQL JDBC driver. No property is dedicated to it. It is selected by its own
parameters in the JDBC URL, and a URL without them never loads it.

## Connection

The catalog URL is composed as
`jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DATABASE}?${POSTGRES_OPS}`. The connector's
parameters go in `POSTGRES_OPS`:

```bash
POSTGRES_DATABASE=adas
POSTGRES_OPS=cloudSqlInstance=<project>:<region>:<instance>&enableIamAuth=true&socketFactory=com.google.cloud.sql.postgres.SocketFactory&sslmode=disable
POSTGRES_DATASOURCE_USERNAME=<name>@<project>.iam
```

- `cloudSqlInstance` is the instance connection name, as `gcloud sql instances describe` prints it. It alone
  names the target, so `POSTGRES_HOST` and `POSTGRES_PORT` are ignored and can keep their defaults.
- `POSTGRES_DATASOURCE_USERNAME` is the IAM database user: the service account's email **without** the
  `.gserviceaccount.com` suffix.
- `sslmode=disable` turns off the driver's own TLS negotiation. The connector already encrypts the
  connection with its ephemeral certificate, so this does not send anything in clear text.
- `POSTGRES_DATASOURCE_PASSWORD` is ignored under IAM authentication but must not be empty: the connector
  documents that an empty value fails driver-level validation. Leave it unset so the `postgres` default
  applies, or set any placeholder.
- Add `&ipTypes=PRIVATE` when the instance has a private IP that the cluster reaches over its VPC. By default the
  connector prefers the public IP when the instance has both.
- Don't set `ipTypes=SQL_DATA`. That one IP type routes the connection through the connector's gRPC data
  client, which runs here on an older gRPC release than the connector was built against (Arrow Flight
  requires it, see the `table-management` spec). No other path uses gRPC.

A JDBC URL kept whole, such as `jdbc:postgresql:///<database>?cloudSqlInstance=...`, can be given instead
through Spring's own `SPRING_DATASOURCE_URL`. It replaces the composed URL, and the `POSTGRES_HOST`, `POSTGRES_PORT`,
`POSTGRES_DATABASE` and `POSTGRES_OPS` parts no longer apply.

## Credentials

With `enableIamAuth=true` the connector signs in as the principal of Application Default Credentials. It draws
on the same sources as the `google-cloud-storage` blob provider, so one Google service account can serve both.
The modes are described in [`gcs-access.md`](gcs-access.md#credentials):

- **Workload Identity (GKE):** annotate the pod's Kubernetes service account with
  `iam.gke.io/gcp-service-account` and grant `roles/iam.workloadIdentityUser`.
- **Mounted key file or federation from another cloud:** point `GOOGLE_APPLICATION_CREDENTIALS` at it.

The connector refreshes its token and ephemeral client certificate in the background. Pooled connections
survive a refresh, and no restart is needed.

## IAM and database grants

The service account needs:

- `roles/cloudsql.client` on the project, to fetch the instance's connection settings and an ephemeral
  certificate.
- `roles/cloudsql.instanceUser` on the project, to sign in with IAM.
- An IAM database user on the instance:

  ```bash
  gcloud sql users create <name>@<project>.iam --instance=<instance> --type=cloud_iam_service_account
  ```

- Inside the database, read/write on the catalog tables, plus DDL while migrations run. On PostgreSQL 15 and
  later, `public` no longer grants `CREATE` to everyone, so grant it explicitly. Run as a user holding
  `cloudsqlsuperuser`:

  ```sql
  GRANT ALL PRIVILEGES ON DATABASE adas TO "<name>@<project>.iam";
  GRANT ALL ON SCHEMA public TO "<name>@<project>.iam";
  ```

## Failure

The catalog is opened at startup when `POSTGRES_MIGRATIONS_ENABLED` is `true`, the default, because the
migrations run then. So any of the following aborts startup, unlike a blob misconfiguration, which only fails
a poll cycle:

- no credential available
- a principal that is not an IAM database user
- a missing role
- a wrong instance connection name

## Cloud SQL Auth Proxy

Running the [Cloud SQL Auth Proxy](https://cloud.google.com/sql/docs/postgres/sql-proxy) as a sidecar with
`--auto-iam-authn` works too and needs none of the above parameters. Point `POSTGRES_HOST` at `localhost` and
keep the same IAM database user. The connector does the same job in-process, with no sidecar.
