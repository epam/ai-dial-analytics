# Build stage (runs on the build host's platform: the jar is platform-independent)
FROM --platform=$BUILDPLATFORM gradle:9.6.0-jdk25-alpine AS builder
WORKDIR /build-workspace

COPY build.gradle .
COPY settings.gradle .
COPY gradle.properties .
COPY checkstyle/ checkstyle/
COPY src/ src/

# GitHub Packages credentials for the jclouds fork arrive as BuildKit secrets, never as image layers
RUN --mount=type=secret,id=GPR_USERNAME,env=GPR_USERNAME \
    --mount=type=secret,id=GPR_PASSWORD,env=GPR_PASSWORD \
    gradle --no-daemon clean bootJar

# Runtime stage
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

RUN adduser -u 1001 --disabled-password --gecos "" appuser && \
    mkdir -p /app/data && \
    chown -R appuser:appuser /app

RUN apk upgrade --no-cache && apk add --no-cache bash

COPY --from=builder --chown=appuser:appuser /build-workspace/build/libs/analytics-data-access-service*.jar ./app.jar
COPY --chown=appuser:appuser docker-entrypoint.sh /usr/local/bin/

RUN chmod +x /usr/local/bin/docker-entrypoint.sh

USER appuser

EXPOSE 5000 32010

HEALTHCHECK --start-period=30s --interval=1m --timeout=3s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:5000/actuator/health || exit 1

ENTRYPOINT ["docker-entrypoint.sh"]
