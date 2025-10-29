# Contributing to Holarki

Welcome! Holarki is a small but opinionated core for Fabric servers. This document summarises the
process for proposing changes and ensures they stay aligned with the v1.0.0 master specification.

## Getting Started

1. Fork the repository and clone it locally.
2. Install the toolchain: JDK 21, Gradle 8.1.4+, Fabric Loom 1.11.x, and a MariaDB 10.6+ instance
   for integration tests.
3. Run the formatter and checks once before you begin:
   ```sh
   ./gradlew spotlessApply check
   ```
4. Configure a local `config/holarki.json5` (the loader writes a commented default on first run).

## Development Workflow

* Create feature branches from `development` (or the default working branch) and keep pull requests
  focused.
* Follow Conventional Commits for messages, for example `feat: add cron audit logging`.
* Provide JavaDoc on all new public APIs and keep error handling mapped to `ErrorCode` values.
* Never block the Minecraft main thread with JDBC or other I/O—hop to async executors via the
  service container.
* Prefer idempotent wallet operations and include reason strings suitable for operators.
* When touching scheduled jobs or migrations, use advisory locks and document failure behaviour.

## Coding Standards

* Source is formatted using Spotless with Google Java Style. Run `./gradlew spotlessApply` before
  committing.
* Keep timestamps in UTC seconds and store UUIDs as `BINARY(16)` when writing migrations.
* New localization keys belong in `assets/holarki/lang/en_us.json`; update translations and run
  `./gradlew validateI18n`.
* Surface operator/player messages through the translation files and include appropriate placeholders
  for dynamic data.

## Testing Checklist

Before submitting a pull request:

1. `./gradlew build` – compiles sources and runs unit tests.
2. `./gradlew validateI18n` – ensures locale files stay in sync.
3. If your change touches the database, run the smoke test workflow in `docs/SMOKE_TEST.md` against a
   local MariaDB instance.
4. Capture manual verification notes (commands run, results observed) in the pull request body.

## Reporting Issues

* Include server logs around the failure with `(holarki)` tags.
* Provide schema version, Minecraft/Fabric versions, and MariaDB server version.
* Describe the steps to reproduce, including relevant module toggles or other
  mods interacting with Holarki APIs.

Thank you for helping us keep Holarki reliable and operator-friendly!
