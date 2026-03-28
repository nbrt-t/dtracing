# dtracing

A low-latency FX pricing pipeline with end-to-end distributed tracing. Market data ticks from multiple ECNs flow through book building, mid-price calculation, and spread tiering — every tick traced with nanosecond precision via OpenTelemetry and Grafana Tempo.

## Pipeline

```
Simulator (CSV)
    │  UDP (FxFeedDelta)
    ▼
MarketDataHandler ×3      — one per ECN (EURONEXT, EBS, FENICS)
    │  Aeron IPC (FxMarketData)
    ▼
BookBuilder               — aggregates venue BBOs into CompositeBook
    │  Aeron IPC (CompositeBookSnapshot)
    ▼
MidPricer                 — mid = (bestBid + bestAsk) / 2
    │  Aeron IPC (MidPriceBook)
    ▼
PriceTiering              — applies per-tier spreads → TieredPrice

All stages ──► TraceCollector ──► Tempo (OTLP/gRPC)
```

Each tick produces 8 spans (one distributed trace). See [ARCHITECTURE.md](ARCHITECTURE.md) for message layouts, span encoding, and a Grafana waterfall example.

## Modules

| Module | Role |
|--------|------|
| `common` | SBE schema, generated codecs, `TracePublisher` |
| `market-data-handler` | UDP receiver, per-pair order book, Aeron publisher |
| `book-builder` | Per-venue BBOs → depth-3 composite book |
| `mid-pricer` | Best bid/ask across venues → mid price |
| `price-tiering` | Mid price + spread matrix → tiered client prices |
| `simulator` | Replays CSV feed files over UDP at configurable speed |
| `aeron-media-driver` | Shared Aeron media driver (start first) |
| `trace-collector` | Aeron trace subscriber → OTLP gRPC exporter |

## Tech

- **Java 21**, Spring Boot 4.0.4, Maven multi-module
- **Aeron** (IPC) for inter-stage messaging
- **SBE** (Simple Binary Encoding) for zero-copy wire format
- **Agrona** `UnsafeBuffer` for off-heap, allocation-free encoding
- **OpenTelemetry SDK** + OTLP gRPC export to **Grafana Tempo**
- All hot-path code is zero-allocation: pre-allocated buffers, fixed arrays, no GC pressure

## Build

```bash
./mvnw clean install                                   # full build
./mvnw compile -pl common                              # regenerate SBE codecs only
./mvnw package -pl market-data-handler -am             # single module + deps
```

SBE codecs are generated into `common/target/generated-sources/java/` during the `generate-sources` phase.

## Running with Docker Compose

The full environment — pipeline services, observability stack, and simulator — is managed via `docker compose` and a `Makefile`.

```bash
make up                # build images and start everything (pipeline + Tempo/Grafana/Prometheus)
make simulate          # start the simulator (replays feed data into the running pipeline)
make down              # stop all services
make logs              # tail logs from all containers
make logs-euronext     # tail logs for a single MDH instance
make status            # show running containers and ports
```

### Observability only

```bash
make infra             # start only Tempo, Grafana, and Prometheus
make infra-down        # stop only the observability stack
```

### Services and ports

| Service | Port | Description |
|---------|------|-------------|
| Grafana | [localhost:3001](http://localhost:3001) | Dashboards and trace explorer (admin/admin) |
| Tempo | localhost:3200 | Tempo HTTP API |
| Tempo | localhost:4317 | OTLP gRPC receiver (used by trace-collector) |
| Prometheus | localhost:9090 | Metrics and Tempo-generated span metrics |
| mdh-euronext | 8081, 9001/udp | MarketDataHandler — EURONEXT |
| mdh-ebs | 8082, 9002/udp | MarketDataHandler — EBS |
| mdh-fenics | 8083, 9003/udp | MarketDataHandler — FENICS |
| book-builder | 8090 | BookBuilder |
| mid-pricer | 8091 | MidPricer |
| price-tiering | 8092 | PriceTiering |

Observability config files live in `grafana-tempo/` (tempo.yaml, prometheus.yml).

## Running locally (without Docker)

Start components in this order:

```bash
# 1. Shared Aeron media driver
java -jar aeron-media-driver/target/aeron-media-driver-*.jar

# 2. Pipeline stages (each in a separate process)
java -jar price-tiering/target/price-tiering-*.jar
java -jar mid-pricer/target/mid-pricer-*.jar
java -jar book-builder/target/book-builder-*.jar

# 3. Market data handlers (one per ECN, using Spring profiles)
java -jar market-data-handler/target/market-data-handler-*.jar --spring.profiles.active=euronext
java -jar market-data-handler/target/market-data-handler-*.jar --spring.profiles.active=ebs
java -jar market-data-handler/target/market-data-handler-*.jar --spring.profiles.active=fenics

# 4. Trace collector sidecar
java -jar trace-collector/target/trace-collector-*.jar

# 5. Simulator (feed replay)
java -jar simulator/target/simulator-*.jar
```

## Configuration

Key properties in each module's `application.properties`:

| Property | Default | Description |
|----------|---------|-------------|
| `market-data.udp.bind-address` | `0.0.0.0` | UDP listen address |
| `market-data.udp.port` | per-ECN profile | UDP port (9001/9002/9003) |
| `aeron.*.dir` | `/dev/shm/aeron/driver` | Aeron driver directory |
| `aeron.*.channel` | `aeron:ipc` | Aeron channel URI |
| `simulator.speed-multiplier` | `1.0` | Replay speed (e.g. `10.0` = 10× faster) |

## Simulator feed format

CSV files under `simulator/data/` with columns:

```
sequence_number, ecn, ccy_pair, timestamp_ns, bid_price, bid_size, ask_price, ask_size
1, EBS, EURUSD, 1774712585000000000, 1.08760, 5000, 1.08765, 4000
```

Timestamps are rebased to current wall-clock time on startup. See [simulator/data/README.md](simulator/data/README.md) for the full format spec.

## Further reading

- [ARCHITECTURE.md](ARCHITECTURE.md) — detailed message structures, order book algorithms, span encoding, latency profile
