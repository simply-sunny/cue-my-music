# Contributing

## Prerequisites

- Java 25 (Temurin 25 recommended)
- Minecraft 26.2 via Fabric Loom
- Fabric API 0.158.0+26.2

## Build and Test

```bash
./gradlew clean test build
```

Run focused tests when possible: `./gradlew test --tests <Class>`.

## Expectations

- Keep changes focused and scoped to one concern per PR.
- Match existing style: vanilla `Screen` widgets, no Cloth Config.
- Verify docs and metadata match the source; no unverified claims.
- Do not add Queue or external import features in this pre-queue line.

## Issues First

Open an issue before large changes to align on scope and approach.

## License

Contributions are under the MIT license.
