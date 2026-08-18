# MINIX

MINIX is a local-first Android control-panel project. It contains the Compose UI, permission and overlay flows, Binder control service, typed target-session gates, native probe adapter, and the fixture/unit tests used to validate the current implementation.

## Repository boundary

This repository tracks source, Gradle configuration, tests, tools, and design/review documents. Device captures, reverse-engineering workspaces, APKs, native build output, local SDK paths, and signing keys stay outside Git and are covered by the repository `.gitignore`.

The control path is certificate/shared-UID/Binder based. All control components use the `me.dartcv.minix.control` namespace and the ordinary `:control` Binder service process.

## Build

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

On a clean checkout, `assembleDebug` uses the normal Android debug signer. `assembleRelease` produces an unsigned release artifact unless an external signing configuration is supplied.

## External signing (optional)

Copy `app/signing.properties.example` to `app/signing.properties` and fill in local values, or provide the equivalent `MINIX_DEBUG_*` / `MINIX_RELEASE_*` environment variables. The properties file and key material are ignored by Git. Set `MINIX_RELEASE_SIGNING_REQUIRED=true` in CI when a release key must be present.

## Documentation

- [`codex.md`](codex.md): migration baseline and verification commands.
- [`docs/README.md`](docs/README.md): report index and architecture notes.
- [`tools/`](tools/): static contract and ELF verification helpers.

## Current verification snapshot

The latest local run completed the unit-test, lint, native build, and debug/release assembly checks. Target-side runtime validation remains a separate device step and is intentionally kept out of the source-only repository snapshot.
