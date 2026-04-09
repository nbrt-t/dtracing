# Architecture

> **FX Pricing Pipeline** -- A low-latency, zero-allocation pricing engine built with
> Aeron IPC, SBE binary encoding, and distributed tracing via OpenTelemetry.

---

## System Overview

```
                          Shared Memory (/dev/shm/aeron/driver)
  +-----------+       +-------------------------------------------------------+
  |           |       |                  Aeron Media Driver                   |
  | Simulator |       |              (DEDICATED threading mode)               |
  |  (CSV)    |       +-------------------------------------------------------+
  +-----+-----+
        |  FxFeedDelta (IPC)
        |  streams 3001 / 3002 / 3003
        v
  +-----+-----+     +-----+-----+     +-----+-----+
  |    MDH    |     |    MDH    |     |    MDH    |
  | EURONEXT  |     |    EBS    |     |  FENICS   |
  |  :8081    |     |  :8082    |     |  :8083    |
  +-----+-----+     +-----+-----+     +-----+-----+
        |                 |                 |
        |  FxMarketData   |                 |    stream 1001
        +-----------------+-----------------+
                          |
                          v
                  +-------+--------+
                  |  Book Builder  |
                  |    :8090       |
                  +-------+--------+
                          |
                          |  CompositeBookSnapshot       stream 1002
                          v
                  +-------+--------+
                  |   Mid Pricer   |
                  |    :8091       |
                  +-------+--------+
                          |
                          |  MidPriceBook                stream 1003
                          v
                  +-------+--------+
                  | Price Tiering  |
                  |    :8092       |
                  +-------+--------+
                          |
                          v
                   TieredPrice x 4 tiers
                   (client distribution)


        All stages emit TraceSpan / SpanLink  -------->  Trace Collector
                    (stream 2001)                            |
                                                             v
                                                    Tempo (OTLP gRPC)
                                                             |
                                                             v
                                                         Grafana
```

---

## Pipeline Stages

### Stage 1 -- Market Data Handler

| Property | Value |
|----------|-------|
| **Input** | `FxFeedDelta` over Aeron IPC (one stream per ECN) |
| **Output** | `FxMarketData` on stream **1001** |
| **Instances** | 3 (one per ECN: EURONEXT, EBS, FENICS) |
| **Hot-path class** | `MarketDataDeltaProcessor` |

Each MDH instance subscribes to a single ECN feed stream from the Simulator.
On every incoming delta it:

1. Decodes the `FxFeedDelta` SBE message
2. Updates its internal `OrderBook` (fixed 5-level depth, sorted in-place)
3. Detects sequence gaps for monitoring
4. Encodes an `FxMarketData` message with best-bid/best-ask + trace context
5. Publishes to Aeron IPC stream 1001
6. Emits two trace spans: **MDH_RECEIVE** (root) and **MDH_PROCESS**

### Stage 2 -- Book Builder

| Property | Value |
|----------|-------|
| **Input** | `FxMarketData` from stream **1001** |
| **Output** | `CompositeBookSnapshot` on stream **1002** |
| **Instances** | 1 |
| **Hot-path class** | `BookBuilderProcessor` |

The Book Builder maintains a composite order book per currency pair by merging
the best-bid/best-ask from all three ECNs. On each update it:

1. Updates the `VenueBook` for the triggering ECN + pair
2. Rebuilds the `CompositeBook` (3-level depth, one per venue, sorted)
3. Checks the **conflation window** (default 10 ms)
   - If the window has elapsed: publishes the snapshot immediately
   - Otherwise: marks the pair dirty and buffers the update
4. On flush: publishes held snapshots and emits `SpanLink` messages to preserve
   lineage of conflated (suppressed) ticks

### Stage 3 -- Mid Pricer

| Property | Value |
|----------|-------|
| **Input** | `CompositeBookSnapshot` from stream **1002** |
| **Output** | `MidPriceBook` on stream **1003** |
| **Instances** | 1 |
| **Hot-path class** | `MidPricerProcessor` |

Calculates the mid price from the best available quotes across all venues:

```
mid = (bestBid + bestAsk) / 2      (integer arithmetic in Decimal5 mantissa)
```

Where `bestBid` is the highest bid and `bestAsk` is the lowest ask across
the three venue slots in the composite snapshot.

### Stage 4 -- Price Tiering

| Property | Value |
|----------|-------|
| **Input** | `MidPriceBook` from stream **1003** |
| **Output** | `TieredPrice` (4 tiers) |
| **Instances** | 1 |
| **Hot-path class** | `PriceTieringProcessor` |

Applies a configurable spread matrix to the mid price:

```
tierBid = mid - halfSpread[tier]
tierAsk = mid + halfSpread[tier]
```

Tier 1 is the tightest spread (best price for premium clients).
Half-spreads are configured per currency pair with defaults:

| Tier | Default Half-Spread | Meaning |
|------|--------------------:|---------|
| 1 | 10 | 0.00010 (1.0 pip) |
| 2 | 20 | 0.00020 (2.0 pips) |
| 3 | 35 | 0.00035 (3.5 pips) |
| 4 | 50 | 0.00050 (5.0 pips) |

---

## Example: Book Update Propagation

> An EBS tick for EURUSD walks through the entire pipeline.
> All prices are in **Decimal5** format (mantissa x 10^-5).

### Initial State

The system already has quotes from two ECNs for EURUSD:

```
EURONEXT    bid 108740  (1.08740)    ask 108760  (1.08760)
EBS         bid 108735  (1.08735)    ask 108755  (1.08755)
FENICS      --          (no data)    --          (no data)
```

### Tick Arrives

A new delta arrives from EBS with an improved ask:

```
FxFeedDelta
  sequenceNumber : 47291
  ccyPair        : EURUSD
  bidPrice       : 108738   (1.08738)
  askPrice       : 108750   (1.08750)    <-- tightened by 0.5 pips
  bidSize        : 5000000
  askSize        : 3000000
```

### Step-by-step Propagation

```
  EBS Simulator                MDH (EBS)
  ============                 =========
       |                            |
  (1)  |--- FxFeedDelta ----------> |
       |    seq=47291               |
       |    bid=108738              |
       |    ask=108750              |  (2) Decode delta
       |                            |     Update OrderBook[EURUSD]
       |                            |     Detect: seq 47291 follows 47290  OK
       |                            |     Emit span: MDH_RECEIVE (root)
       |                            |     Emit span: MDH_PROCESS
       |                            |
       |                            |  (3) Encode FxMarketData
       |                            |      ecn       = EBS
       |                            |      ccyPair   = EURUSD
       |                            |      bidPrice  = 108738
       |                            |      askPrice  = 108750
       |                            |      bidSize   = 5000000
       |                            |      askSize   = 3000000
       |                            |      traceId   = 0xA3..07
       |                            |      spanId    = 0x0001..4F
       |                            |
       |                        Book Builder
       |                        ============
       |                            |
       |                            |  (4) Update VenueBook[EBS][EURUSD]
       |                            |      bid 108738, ask 108750
       |                            |
       |                            |  (5) Rebuild CompositeBook[EURUSD]
       |                            |
       |                            |      Bids (descending)       Asks (ascending)
       |                            |      +---------+-----------+ +---------+-----------+
       |                            |      | EURONXT | 108740    | | EBS     | 108750    |
       |                            |      | EBS     | 108738    | | FENICS  |   --      |
       |                            |      | FENICS  |   --      | | EURONXT | 108760    |
       |                            |      +---------+-----------+ +---------+-----------+
       |                            |      bestBid = 108740 (EURONEXT)
       |                            |      bestAsk = 108750 (EBS)        <-- new best ask!
       |                            |
       |                            |  (6) Conflation check: >10ms since last publish?
       |                            |      YES --> publish immediately
       |                            |      Emit span: BOOK_BUILD
       |                            |
       |                            |  (7) Encode CompositeBookSnapshot
       |                            |      Slot 0 (EURONEXT): bid=108740 ask=108760
       |                            |      Slot 1 (EBS):      bid=108738 ask=108750
       |                            |      Slot 2 (FENICS):   bid=0      ask=0
       |                            |
       |                        Mid Pricer
       |                        ==========
       |                            |
       |                            |  (8) Decode 3 venue slots
       |                            |      bestBid = max(108740, 108738, 0) = 108740
       |                            |      bestAsk = min(108760, 108750, _) = 108750
       |                            |
       |                            |  (9) Calculate mid price
       |                            |      mid = (108740 + 108750) / 2 = 108745
       |                            |            = 1.08745
       |                            |      Emit span: MID_PRICE
       |                            |
       |                            |  (10) Encode MidPriceBook
       |                            |       ccyPair  = EURUSD
       |                            |       midPrice = 108745
       |                            |
       |                        Price Tiering
       |                        =============
       |                            |
       |                            |  (11) Apply EURUSD spread matrix
       |                            |
       |                            |  +------+-----------+---------+---------+
       |                            |  | Tier | HalfSprd  |   Bid   |   Ask   |
       |                            |  +------+-----------+---------+---------+
       |                            |  |  1   |     5     | 108740  | 108750  |
       |                            |  |  2   |    10     | 108735  | 108755  |
       |                            |  |  3   |    20     | 108725  | 108765  |
       |                            |  |  4   |    30     | 108715  | 108775  |
       |                            |  +------+-----------+---------+---------+
       |                            |
       |                            |  In display prices:
       |                            |  Tier 1:  1.08740 / 1.08750   (1.0 pip spread)
       |                            |  Tier 2:  1.08735 / 1.08755   (2.0 pip spread)
       |                            |  Tier 3:  1.08725 / 1.08765   (4.0 pip spread)
       |                            |  Tier 4:  1.08715 / 1.08775   (6.0 pip spread)
       |                            |
       |                            |  Emit span: PRICE_TIER (terminal)
```

### Trace Timeline

Every stage emits trace spans to the Trace Collector (stream 2001), which
exports them to Grafana Tempo via OTLP gRPC. A single tick produces a trace
like this:

```
traceId: 0xA3..07

 MDH_RECEIVE  |====|                                                 root span
 MDH_PROCESS       |====|                                            child
 TRANSPORT              |=|                                          Aeron transit
 BOOK_BUILD               |=====|                                    child
 TRANSPORT                      |=|                                  Aeron transit
 MID_PRICE                        |===|                              child
 TRANSPORT                            |=|                            Aeron transit
 PRICE_TIER                              |====|                      terminal

              t0    t1    t2    t3    t4    t5    t6    t7    t8
              |<----- total pipeline latency (nanosecond precision) ----->|
```

---

## Example: Book Ladder

> How the order book looks at each stage of the pipeline, using the EURUSD
> example from above after the EBS tick arrives.

### Venue Books (inside MDH)

Each Market Data Handler maintains a 5-level order book per currency pair.
Only BBO (level 0) is forwarded downstream.

```
  MDH EURONEXT -- OrderBook[EURUSD]          MDH EBS -- OrderBook[EURUSD]
  ========================================   ========================================

       BID side             ASK side              BID side             ASK side
  Lvl  Price     Size       Price     Size   Lvl  Price     Size       Price     Size
  ---  --------  ---------  --------  ----   ---  --------  ---------  --------  ----
   0   1.08740   2,000,000  1.08760   1.5M    0   1.08738   5,000,000  1.08750   3.0M
   1   1.08735   4,000,000  1.08765   2.0M    1   1.08733   2,500,000  1.08758   1.5M
   2   1.08730   3,500,000  1.08770   3.0M    2   1.08728   1,800,000  1.08763   2.0M
   3   1.08720   1,000,000  1.08780   1.0M    3   1.08720   3,000,000  1.08770   4.0M
   4   1.08710   2,500,000  1.08790   2.0M    4   1.08715   1,200,000  1.08780   1.0M
       --------                                   --------
       best bid = 1.08740                         best bid = 1.08738
       best ask = 1.08760                         best ask = 1.08750  <-- new!
```

### Composite Book (inside Book Builder)

The Book Builder merges the BBO from each venue into a 3-level composite book,
sorted by price competitiveness. Best bid is the highest; best ask is the lowest.

```
  CompositeBook[EURUSD]
  ============================================================

         BID side (descending)           ASK side (ascending)
  Lvl    Venue      Price     Size       Venue      Price     Size
  ----   --------   --------  --------   --------   --------  --------
    0    EURONEXT   1.08740   2,000,000  EBS        1.08750   3,000,000   <-- best
    1    EBS        1.08738   5,000,000  EURONEXT   1.08760   1,500,000
    2    FENICS       --          --     FENICS       --          --
  ----   --------   --------  --------   --------   --------  --------
         best bid = 1.08740              best ask = 1.08750

                    spread = 1.08750 - 1.08740 = 0.00010 (1.0 pip)
```

### Mid Price (inside Mid Pricer)

```
  MidPriceBook[EURUSD]
  ============================================================

    bestBid (EURONEXT)  =  1.08740
    bestAsk (EBS)       =  1.08750
                           --------
    midPrice            =  1.08745      (108740 + 108750) / 2 = 108745
```

### Tiered Prices (inside Price Tiering)

The spread matrix fans the mid price into client-facing tiers.
Each tier widens symmetrically around the mid.

```
  TieredPrice[EURUSD]
  ============================================================

              Bid                  Mid                  Ask
              |                     |                     |
  Tier 1      |     1.08740         |         1.08750     |      0.10 pip spread
              |         |-----------+-----------+         |
              |                     |                     |
  Tier 2      | 1.08735             |             1.08755 |      0.20 pip spread
              |     |---------------+---------------+     |
              |                     |                     |
  Tier 3      1.08725               |               1.08765      0.40 pip spread
              |--+------------------+------------------+--|
              |                     |                     |
  Tier 4  1.08715                   |                   1.08775  0.60 pip spread
          |----+--------------------+--------------------+----|
                                    |
                                 1.08745


  +--------+-----------+-----------+-----------+-----------+
  |  Tier  |    Bid    |    Ask    |  Spread   |  Client   |
  +--------+-----------+-----------+-----------+-----------+
  |    1   |  1.08740  |  1.08750  |  1.0 pip  |  Platinum |
  |    2   |  1.08735  |  1.08755  |  2.0 pip  |  Gold     |
  |    3   |  1.08725  |  1.08765  |  4.0 pip  |  Silver   |
  |    4   |  1.08715  |  1.08775  |  6.0 pip  |  Standard |
  +--------+-----------+-----------+-----------+-----------+
```

---

## Conflation

The Book Builder applies a **conflation window** (default 10 ms) to avoid
flooding downstream stages with redundant updates when markets are volatile.

```
                    10ms window
              |<-------------------->|

  tick A  ----X                                    published immediately
  tick B  ---------X                               suppressed (dirty flag)
  tick C  --------------X                          suppressed (dirty flag)
  tick D  -------------------X                     suppressed (dirty flag)
                              |
                              +-- flush: publish latest state
                                         emit SpanLinks: B->D, C->D
                                         emit CONFLATION_WAIT span
                                           (heldTicks = 3)

  tick E  --------------------------X              published immediately
                                                   (new window starts)
```

**SpanLinks** preserve the full lineage of suppressed ticks so that every
original market data event can be traced through the pipeline in Grafana,
even if its update was conflated into a later snapshot.

---

## SBE Messages

All inter-service communication uses **Simple Binary Encoding** (SBE) over
Agrona `DirectBuffer`. No Java serialization or JSON on the hot path.

### Message Catalog

| ID | Message | Direction | Fields |
|----|---------|-----------|--------|
| 1 | `FxMarketData` | MDH -> BookBuilder | ecn, ccyPair, bid/ask price+size, trace context |
| 2 | `CompositeBookSnapshot` | BookBuilder -> MidPricer | ccyPair, 3 venue slots (bid/ask price+size each), trace context |
| 3 | `MidPriceBook` | MidPricer -> PriceTiering | ccyPair, midPrice, midSize, trace context |
| 4 | `TieredPrice` | PriceTiering -> Clients | ccyPair, tierNo, bid/ask price+size |
| 5 | `FxFeedDelta` | Simulator -> MDH | sequenceNumber, ccyPair, bid/ask price+size |
| 6 | `TraceSpan` | All stages -> TraceCollector | traceId, spanId, parentSpanId, stage, timestamps, heldTicks |
| 7 | `SpanLink` | BookBuilder -> TraceCollector | traceId, spanId, linkedTraceId, linkedSpanId |

### Shared Types

| Type | Encoding | Example |
|------|----------|---------|
| `Decimal5` | int64 mantissa, exponent = -5 | 1.08765 -> mantissa `108765` |
| `NanoTimestamp` | int64 nanos since epoch | `1712678400000000000` |
| `CcyPair` | uint8 enum | EURUSD(0) .. AUDJPY(11) |
| `Ecn` | uint8 enum | EURONEXT(0), EBS(1), FENICS(2) |
| `Stage` | uint8 enum | MDH_RECEIVE .. CONFLATION_WAIT |

---

## Aeron Transport

All messaging uses **Aeron IPC** (shared memory) through a single dedicated
Media Driver process.

### Stream Map

```
  Stream 3001  ----  Simulator (EURONEXT)  -->  MDH EURONEXT     FxFeedDelta
  Stream 3002  ----  Simulator (EBS)       -->  MDH EBS          FxFeedDelta
  Stream 3003  ----  Simulator (FENICS)    -->  MDH FENICS       FxFeedDelta

  Stream 1001  ----  MDH (all 3)           -->  Book Builder     FxMarketData
  Stream 1002  ----  Book Builder          -->  Mid Pricer       CompositeBookSnapshot
  Stream 1003  ----  Mid Pricer            -->  Price Tiering    MidPriceBook

  Stream 2001  ----  All stages            -->  Trace Collector  TraceSpan / SpanLink
```

### Back-Pressure Policy

All publishers use **drop-on-back-pressure**: if `publication.offer()` returns
`BACK_PRESSURED`, the message is silently discarded. This is safe because every
subsequent tick carries the full current state (BBO or composite snapshot), so
a dropped message is immediately superseded.

---

## Zero-Allocation Design

The hot path is designed for **zero garbage collection pressure**:

| Technique | Where |
|-----------|-------|
| Pre-allocated `ByteBuffer.allocateDirect` + `UnsafeBuffer` | Every publisher |
| Fixed-size arrays (no collections) | OrderBook, VenueBook, CompositeBook, scratch arrays |
| SBE flyweight pattern (wrap, don't copy) | All decoders |
| Reusable encoder/decoder instances | All codecs per thread |
| Integer arithmetic (`Decimal5` mantissa) | All price calculations |
| Enum ordinals as array indices | ECN -> venue slot, CcyPair -> book index |
| Drop on back-pressure (no queue allocation) | All Aeron publishers |

---

## Observability

```
  +-------------------+       +------------------+       +---------+
  |   All Pipeline    | SBE   |  Trace Collector | OTLP  |  Tempo  |
  |     Stages        |------>|   (sidecar)      |------>| (trace  |
  | TraceSpan stream  | 2001  |  decodes SBE     | gRPC  |  store) |
  | SpanLink  stream  |       |  exports OTLP    |       |         |
  +-------------------+       +------------------+       +----+----+
                                                              |
                                                              v
                                                        +-----------+
                                                        |  Grafana  |
                                                        | (traces + |
                                                        |  metrics) |
                                                        +-----------+
```

The Trace Collector is a standalone sidecar that:
- Subscribes to Aeron IPC stream 2001
- Decodes `TraceSpan` and `SpanLink` SBE messages
- Buffers and flushes to Tempo every 1 second via OTLP gRPC
- Converts `SpanLink` messages into OTLP span links for lineage

---

## Deployment

All services run as Docker containers sharing a single Aeron Media Driver
through an IPC volume mount:

```
  docker-compose.yml
  ==================

  aeron-media-driver        shared IPC driver (/dev/shm/aeron)
       |
       +-- mdh-euronext     :8081    profile=euronext
       +-- mdh-ebs          :8082    profile=ebs
       +-- mdh-fenics       :8083    profile=fenics
       +-- book-builder     :8090
       +-- mid-pricer       :8091
       +-- price-tiering    :8092
       +-- trace-collector
       +-- simulator
       +-- tempo            :3200    (Grafana Tempo)
       +-- grafana          :3000
```

All application containers use `ipc: service:aeron-media-driver` to share
the IPC namespace, and mount the `aeron-dir` volume for shared memory access.
