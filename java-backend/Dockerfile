# syntax=docker/dockerfile:1
#
# java-backend (Spring Boot 3.3.4 / Java 21).
#
# Multi-stage: a full Maven + JDK image builds the fat jar, then only the jar is
# copied into a slim JRE image so the runtime carries no Maven, no build tools,
# and no compiler.

# ---------------------------------------------------------------------------
# Build stage
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first, keyed only on pom.xml, so the (slow) dependency
# download layer is cached and reused whenever only source files change.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are the CI job's responsibility (.github/workflows/ci.yml runs `mvn
# test`); skip them here so the image build is deterministic and fast and does
# not need a database.
RUN mvn -B -q clean package -DskipTests

# ---------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# curl is only here so the compose healthcheck can hit a real endpoint.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Spring Boot repackage produces exactly one executable jar in target/ (plus a
# non-matching *.jar.original, which this glob does not pick up).
COPY --from=build /build/target/*.jar app.jar

RUN useradd --create-home --uid 10001 appuser
USER appuser

EXPOSE 8080

# All connection details and API keys come from the environment (see
# application.yml's ${...} placeholders and docker-compose.yml) — nothing is
# baked into the image.
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=10 \
    CMD curl -fsS http://localhost:8080/api/inventory || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
