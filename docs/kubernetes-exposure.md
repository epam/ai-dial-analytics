# Exposing ADAS to the internet (Kubernetes / Traefik)

This guide describes how to expose the ADAS query API — schema discovery, structured query
execution, the Swagger UI, and the optional Arrow Flight SQL endpoint — from a Kubernetes cluster
to the internet, and which egress the pod needs. Only the read-only query surface is exposed; table
management (`/v1/tables`), data-sync (`/v1/data-sync`), and actuator endpoints stay
cluster-internal.

The examples assume a reference layout: namespace `dial-adas`, deployment/service `dial-adas`
(ClusterIP, port 80 → container port `http`), with ClickHouse and PostgreSQL running in-namespace,
and the hostnames `adas.example.com` (REST) and `adas-sql.example.com` (Flight SQL). Substitute your
own names. The ingress controller is **Traefik** with two ingress classes:

- `traefik` — public LoadBalancer
- `traefik-whitelist` — IP-whitelisted LoadBalancer (recommended starting point)

TLS is terminated at Traefik's `443` entrypoint. Certificates are issued **per-ingress** by
cert-manager (the `letsencrypt-production` ClusterIssuer) and DNS records are created **per-ingress**
by external-dns — so each Ingress carries a `cert-manager.io/cluster-issuer: letsencrypt-production`
annotation and a `tls.secretName` (`dial-adas-tls` for REST, `dial-adas-sql-tls` for Flight); the
hostnames below are what drives both the certificate and the DNS record.

Environment variables are named inline below; their descriptions live in
[`configuration.md`](configuration.md).

## Traffic flow

```
client ──HTTPS/443───> Traefik LB ──HTTP──> Service dial-adas:80        ──> pod :5000  (REST)
client ──gRPC+TLS/443─>           ──h2c───> Service dial-adas-grpc:32010 ──> pod :32010 (Flight SQL)
```

One Traefik LoadBalancer serves both hostnames; routing is by Host header/SNI.

## Prerequisites

1. **Authentication enabled** — with no `SECURITY_MODE` set, ADAS runs with the default `none`
   (all endpoints open). Before any exposure set `SECURITY_MODE=oidc` with identity providers
   configured, and optionally `API_KEY_ENABLED=true` for DIAL Core API keys. The Flight endpoint
   mirrors the same `security.mode`.
2. **Flight listener enabled** — `FLIGHT_ENABLED` defaults to `false`.

## Deployment environment

If the deployment is Helm-managed (for example via the `dial-extension` chart), apply these through
the release's Helm values rather than `kubectl edit`. The container needs:

```yaml
env:
  - { name: SECURITY_MODE,  value: "oidc" }
  - { name: FLIGHT_ENABLED, value: "true" }
  - { name: FLIGHT_PORT,    value: "32010" }
ports:
  - { name: http, containerPort: 5000 }
  - { name: grpc, containerPort: 32010 }
```

## Services

The existing `dial-adas` Service (port 80 → `http`) already fronts the REST API — the REST
Ingress reuses it unchanged.

Flight SQL gets a **dedicated Service**, because Traefik's backend protocol is set per-Service via
annotation: the ADAS Flight listener is plaintext gRPC (h2c), and marking the shared service `h2c`
would break the REST port.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: dial-adas-grpc
  namespace: dial-adas
  annotations:
    traefik.ingress.kubernetes.io/service.serversscheme: h2c   # Traefik speaks plaintext HTTP/2 to the pod
spec:
  selector:                            # same pods as the dial-adas service
    app.kubernetes.io/name: dial-adas
    app.kubernetes.io/instance: dial-adas
  ports:
    - { name: grpc, port: 32010, targetPort: grpc, appProtocol: grpc }
```

## REST API Ingress

The declared paths are the exposure allowlist: anything not listed (`/v1/tables`,
`/v1/data-sync`, `/actuator`) is 404 at Traefik and never reaches the pod.

The Swagger UI (`/swagger-ui.html` + its static assets under `/swagger-ui`) and the OpenAPI
document (`/v3/api-docs`) need a credential in `oidc` mode unless `DISABLE_SWAGGER_AUTHORIZATION=true`,
and a browser sends none when it loads the UI. List them here only if external API exploration is
intended — drop those three paths to keep the docs cluster-internal.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: dial-adas
  namespace: dial-adas
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-production   # cert-manager issues the cert; external-dns creates the DNS record
spec:
  ingressClassName: traefik-whitelist   # switch to `traefik` for fully public access
  tls:
    - secretName: dial-adas-tls
      hosts:
        - adas.example.com
  rules:
    - host: adas.example.com
      http:
        paths:
          - path: /v1/queries            # entities + schema discovery + execute
            pathType: Prefix
            backend: { service: { name: dial-adas, port: { number: 80 } } }
          - path: /swagger-ui            # Swagger UI static assets (index.html, JS/CSS)
            pathType: Prefix
            backend: { service: { name: dial-adas, port: { number: 80 } } }
          - path: /swagger-ui.html       # Swagger UI entry point (redirects into /swagger-ui/)
            pathType: Prefix
            backend: { service: { name: dial-adas, port: { number: 80 } } }
          - path: /v3/api-docs           # OpenAPI JSON consumed by the UI (and API clients)
            pathType: Prefix
            backend: { service: { name: dial-adas, port: { number: 80 } } }
```

## Flight SQL Ingress

Flight SQL is gRPC, so it gets its own hostname and routes to the h2c-annotated Service. Traefik
terminates TLS at the entrypoint (gRPC requires HTTP/2, which Traefik negotiates over TLS) and
forwards plaintext HTTP/2 to the pod. Unlike nginx, Traefik has no default per-request read
timeout, so long `DoGet` streams need no timeout annotations.

No address rewriting is needed: ADAS builds `FlightInfo` endpoints without a `Location`
(`AdasFlightSqlProducer`), so clients call `doGet` back on the same connection through the proxy.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: dial-adas-grpc
  namespace: dial-adas
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-production   # cert-manager issues the cert; external-dns creates the DNS record
spec:
  ingressClassName: traefik-whitelist   # switch to `traefik` for fully public access
  tls:
    - secretName: dial-adas-sql-tls
      hosts:
        - adas-sql.example.com
  rules:
    - host: adas-sql.example.com
      http:
        paths:
          - path: /                 # entire host is dedicated to gRPC — no path filtering
            pathType: Prefix
            backend: { service: { name: dial-adas-grpc, port: { number: 32010 } } }
```

### Client connection

```
ADBC / Python:  grpc+tls://adas-sql.example.com:443
JDBC:           jdbc:arrow-flight-sql://adas-sql.example.com:443?useEncryption=true
```

Credentials ride in gRPC metadata (`authorization: Bearer <token>`) and pass through Traefik
unchanged.

## Egress NetworkPolicy (optional hardening)

The `dial-extension` chart defaults to `networkPolicy.allowExternalEgress: true`, so all outbound
traffic is allowed. The policy below is optional hardening you can layer on to lock egress down to
only what the pod needs.

Everything the pod dials out to. ClickHouse and PostgreSQL run in the same namespace, so those
rules are pod selectors; the rest is external HTTPS.

| Destination | Port | When active |
|---|---|---|
| ClickHouse (`dial-adas-clickhouse-headless`) | 8123 TCP | always |
| PostgreSQL (`dial-adas-postgresql`) | 5432 TCP | always |
| Azure Blob (`<account>.blob.core.windows.net`) | 443 TCP | when data-sync polling/backfill runs |
| DIAL Core `/v1/user/info` | 443 TCP | `oidc` mode with API keys enabled (incl. startup probe) |
| Identity providers (JWKS/userinfo) | 443 TCP | `oidc` mode |
| DNS | 53 UDP/TCP | always |

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: dial-adas-egress
  namespace: dial-adas
spec:
  podSelector:
    matchLabels: { app.kubernetes.io/name: dial-adas, app.kubernetes.io/instance: dial-adas }
  policyTypes: [Egress]
  egress:
    - to: [{ podSelector: { matchLabels: { app: dial-adas-clickhouse } } }]
      ports: [{ port: 8123, protocol: TCP }]
    - to: [{ podSelector: { matchLabels: { app.kubernetes.io/instance: dial-adas-postgresql } } }]
      ports: [{ port: 5432, protocol: TCP }]
    - to: [{ ipBlock: { cidr: 0.0.0.0/0 } }]                                  # Azure Blob + DIAL Core + IdP JWKS
      ports: [{ port: 443, protocol: TCP }]
    - to: [{ namespaceSelector: {}, podSelector: { matchLabels: { k8s-app: kube-dns } } }]
      ports: [{ port: 53, protocol: UDP }, { port: 53, protocol: TCP }]
```

Tightening options: replace the open 443 CIDR with the storage account's service-tag ranges (or a
Private Endpoint), and if DIAL Core runs in-cluster, move it to a pod/namespace selector rule.

## Verification

```bash
# exposed path works, blocked path 404s at Traefik (no ADAS ErrorView body)
curl https://adas.example.com/v1/queries/entities
curl https://adas.example.com/v1/tables

# Swagger UI + OpenAPI doc reachable (only if you listed those paths above)
curl -sI https://adas.example.com/swagger-ui.html   # 200/302 into /swagger-ui/index.html
curl -s  https://adas.example.com/v3/api-docs        # OpenAPI JSON

# Flight SQL end-to-end (pip install adbc-driver-flightsql)
python -c "
import adbc_driver_flightsql.dbapi as flight
conn = flight.connect('grpc+tls://adas-sql.example.com:443',
    db_kwargs={'adbc.flight.sql.authorization_header': 'Bearer <token>'})
print(conn.cursor().execute('SELECT count(*) FROM dial_usage_log').fetchall())
"
```

A 404 on a blocked path that shows ADAS's `{status, error, message, path, method}` JSON body means
the path leaked through the ingress — Traefik's own 404 is the expected response.
