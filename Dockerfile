# syntax=docker/dockerfile:1
FROM node:24.21.0-trixie-slim@sha256:8ec5d7557396cfe32d21c3f9c13072355ceab22b584578ca4bb28af31120cffe AS web
WORKDIR /web
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm test && npm run build

FROM eclipse-temurin:25-jdk-noble@sha256:f6366ccac38ceae180280ad7012d18a15e8031548a430dc2bae06631d9e88ed0 AS build
WORKDIR /source
COPY gradlew settings.gradle settings-gradle.lockfile build.gradle gradle.properties gradle.lockfile ./
COPY gradle/ gradle/
COPY src/ src/
COPY --from=web /web/dist/ src/main/resources/static/
RUN chmod +x gradlew && ./gradlew --no-daemon --no-watch-fs test bootJar
RUN mkdir /unpacked && cd /unpacked && jar xf /source/build/libs/auctionhouse.jar
COPY ops/java/ /ops/
RUN javac -cp '/unpacked/BOOT-INF/lib/*' -d /unpacked/BOOT-INF/classes /ops/io/auctionhouse/ops/Migrate.java

FROM eclipse-temurin:25-jre-noble@sha256:693fdaf83831eeeefd9709eae44c8b8706622652f972cf5903bd0e481bbf6ad3
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.title="auctionhouse" org.opencontainers.image.revision=$SOURCE_REVISION
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade perl-base=5.38.2-3.2ubuntu0.6 libssl3t64=3.0.13-0ubuntu3.16 openssl=3.0.13-0ubuntu3.16 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build --chown=10001:10001 /unpacked/BOOT-INF/classes/ classes/
COPY --from=build --chown=10001:10001 /unpacked/BOOT-INF/lib/ lib/
COPY --chmod=0555 ops/container-entrypoint.sh /app/entrypoint.sh
COPY --chown=10001:10001 observability/agent.properties /app/observability/agent.properties
ADD --checksum=sha256:bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba --chmod=0444 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.1/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
RUN chmod 0555 /opt/otel
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC" \
    SPRING_FLYWAY_ENABLED=false
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["app"]
