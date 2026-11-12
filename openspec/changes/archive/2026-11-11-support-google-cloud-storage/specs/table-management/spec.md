## ADDED Requirements

### Requirement: The catalog can reach Cloud SQL with IAM database authentication

The system SHALL ship the Cloud SQL Java Connector for PostgreSQL
(`com.google.cloud.sql.postgres.SocketFactory`) on its runtime classpath, so the catalog `DataSource` can reach
a Cloud SQL for PostgreSQL instance with no password. No property is dedicated to it: the connector is selected
by its parameters in the JDBC URL query string (`POSTGRES_OPS`), namely `socketFactory`, `cloudSqlInstance` and
`enableIamAuth=true`. With `enableIamAuth=true` the connector signs in as the IAM principal of the
Application Default Credentials, Workload Identity on GKE or the file `GOOGLE_APPLICATION_CREDENTIALS` names,
with an OAuth token carrying the `sqlservice.login` scope. The username names that principal as an IAM database
user. The password is ignored but SHALL be non-empty, as the connector requires, so the `postgres` default
serves. The query string also carries `sslmode=disable`, because the connector already owns TLS. The host and
port the URL is composed with are ignored, because `cloudSqlInstance` alone names the target. A URL without
`socketFactory` never loads the connector and is unaffected.

The connector's transitive gRPC stack SHALL resolve to a single `io.grpc` release shared with the Arrow Flight
SQL endpoint, one that release of Arrow links against, so adding the connector never breaks Flight SQL calls.
That release is older than the one the connector's gax stack is built against. The connector reaches gRPC only
through its SQL Data Service client, used when `ipTypes` starts with `SQL_DATA`. Every other IP type opens a
direct TLS socket after HTTP calls to the Cloud SQL Admin API. So `SQL_DATA` is unsupported, and only the
direct path is verified. The connector SHALL NOT bring Apache httpclient 4 onto the classpath. It calls the
Admin API over the default `NetHttpTransport`.

The catalog `DataSource` is opened at startup, by the PostgreSQL migrations when they are enabled. So, unlike
blob access, a Cloud SQL misconfiguration (a missing credential, a principal that is not an IAM database user,
or a missing `roles/cloudsql.client` or `roles/cloudsql.instanceUser`) fails startup.

#### Scenario: An IAM-authenticated Cloud SQL URL is handled by the connector

- **WHEN** the catalog URL is `jdbc:postgresql://<host>:<port>/<database>?cloudSqlInstance=<project>:<region>:<instance>&enableIamAuth=true&socketFactory=com.google.cloud.sql.postgres.SocketFactory&sslmode=disable`,
  the username is an IAM database user and the password is a non-empty placeholder
- **THEN** the connection is opened by the connector, which calls the Cloud SQL Admin API for
  `<project>`/`<instance>` with the credential's token and sends a second token for the same principal, carrying
  the `sqlservice.login` scope, in its ephemeral-certificate request, regardless of `<host>` and `<port>`

#### Scenario: A refused Cloud SQL connection is an ordinary SQL failure

- **WHEN** the Cloud SQL Admin API refuses the connector's call
- **THEN** opening the connection fails with a `SQLException`, never a class-linkage error
