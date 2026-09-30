# Development

## Toolchain

- Java 25 (LTS); Spring Boot 4.1.x; Gradle; JUnit 5; Flyway; Testcontainers; PostgreSQL 18 in Docker.

## Java build

```sh
./gradlew build          # compiles, runs unit tests, static checks
./gradlew test           # unit tests only
./gradlew integrationTest  # Testcontainers-backed tests; needs Docker
docker compose up -d     # PostgreSQL and memcached for local runs
./gradlew bootRun --args='--spring.profiles.active=local'
```

Java 25 (LTS) with Spring Boot 4.1.x; versions are pinned in
`gradle/libs.versions.toml`. Database migrations run through Flyway on
start-up; never edit an applied migration, add a new one.

## Repository layout

```
src/main/java/<module>/   one package per module
src/test/java/
src/main/resources/db/migration/   Flyway
load/                     k6 scripts
docs/
```

## Conventions

- Tests for a component are written before its implementation; see `TESTING.md`.
- Every measurement in the documentation names the host, the command and the commit that produced it.
- Design choices are recorded in `adr/` before or with the change.
- Secrets never enter the repository; local configuration lives in an ignored `.env` file with an `.env.example` checked in.
