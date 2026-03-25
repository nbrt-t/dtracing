# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
./mvnw.cmd clean install          # full build (Windows)
./mvnw.cmd compile -pl common     # regenerate SBE codecs only
./mvnw.cmd package -pl market-data-handler -am   # single module + deps
```

SBE codecs are generated during `generate-sources` phase in the `common` module via `exec-maven-plugin` invoking `uk.co.real_logic.sbe.SbeTool`. Generated sources land in `common/target/generated-sources/java/com/nbrt/dtracing/common/sbe/`.

## Architecture

This is an FX pricing pipeline built as a Maven multi-module Spring Boot 4.0.4 project (Java 26). Data flows through 4 stages connected by Aeron messaging, with SBE (Simple Binary Encoding) as the wire format throughout.

### Data flow

```
UDP Feed → MarketDataHandler → BookBuilder → MidPricer → PriceTiering
              (FxFeedDelta)    (FxMarketData) (VenueOrderBook) (MidPriceBook) → (TieredPrice)
```

### Modules

| Module | Purpose | Status |
|--------|---------|--------|
| **common** | SBE schema + generated codecs, shared by all others. Library jar (Spring Boot repackage skipped). | Active |
| **market-data-handler** | Receives SBE-encoded `FxFeedDelta` datagrams over UDP (unicast or multicast), decodes via `UdpFeedReceiver` on a virtual thread, delegates to `MarketDataDeltaHandler`. Tracks sequence numbers for gap detection. | Active |
| **book-builder** | Builds venue-aggregated order books from market data. | Stub |
| **mid-pricer** | Calculates mid prices from the aggregated book. | Stub |
| **price-tiering** | Applies tiered spreads for client distribution. | Stub |

### SBE schema

Single schema at `common/src/main/resources/sbe/dtracing-schema.xml`, package `com.nbrt.dtracing.common.sbe`.

Key shared types:
- `Decimal5` — fixed-point price (int64 mantissa, exponent=-5). Encode 1.08765 as mantissa=108765.
- `NanoTimestamp` — int64 nanoseconds since Unix epoch.
- `Ecn` enum — EURONEXT, EBS, FENICS.
- `CcyPair` enum — 12 major FX pairs.

When adding a new SBE message: add it to the schema XML, rebuild `common`, and all downstream modules pick up the new encoder/decoder.

### Key conventions

- All inter-service messages use SBE encoding over Agrona `DirectBuffer` / `UnsafeBuffer`. Do not use Java serialization or JSON for hot-path data.
- Prices are always `Decimal5` (5 decimal places). Convert with `mantissa * 1e-5` for display.
- UDP receiver config is under `market-data.udp.*` in `application.properties` (bind-address, port, buffer-size, multicast-group).
- The `common` module has no Spring Boot main class. Its POM skips the `spring-boot-maven-plugin` repackage goal.
- All app modules depend on `common` (version managed in parent POM's `dependencyManagement`).

### Dependency versions (managed in root POM)

- Aeron: `${aeron.version}` — messaging transport
- Agrona: `${agrona.version}` — off-heap buffers for SBE codecs
- SBE Tool: `${sbe.version}` — codec generation