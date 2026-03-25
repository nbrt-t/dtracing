# Simulator Data Files

CSV files for replay-testing the market-data-handler pipeline.

## Format

```
sequence_number,ecn,ccy_pair,timestamp_ns,bid_price,bid_size,ask_price,ask_size
```

| Column | Type | Description |
|--------|------|-------------|
| sequence_number | uint64 | Monotonically increasing per file; gap in fenics = seq 71→73 |
| ecn | string | EURONEXT, EBS, or FENICS — maps to `Ecn` enum |
| ccy_pair | string | e.g. EURUSD — maps to `CcyPair` enum |
| timestamp_ns | int64 | Nanoseconds since Unix epoch (2026-03-25 10:00 UTC base) |
| bid_price | decimal | 5 d.p. display price — encode as `(long)(price * 100000)` for Decimal5 mantissa |
| bid_size | int32 | Bid notional in thousands of base currency; **0 = delete this level** |
| ask_price | decimal | 5 d.p. display price |
| ask_size | int32 | Ask notional in thousands; **0 = delete this level** |

## Conventions

- Each file begins with a **full 5-level snapshot** per currency pair (first ~15–25 rows).
- Subsequent rows are **delta updates** — only the changed level is sent.
- `bid_size=0` or `ask_size=0` means that price level should be **removed** from the book.
- Timestamps span a 5-minute window (300 seconds).
- Prices use 5 decimal places; sizes are in thousands of base currency.

## Files

| File | ECN | Pairs | Notes |
|------|-----|-------|-------|
| `euronext_feed.csv` | EURONEXT | EURUSD, GBPUSD, EURGBP | Steady book, gentle moves |
| `ebs_feed.csv` | EBS | EURUSD, USDJPY, USDCHF | Fast ticks, spread widening event |
| `fenics_feed.csv` | FENICS | GBPUSD, AUDUSD, NZDUSD | Sequence gap at seq 71→73, level deletions |

## Replay

To encode a CSV row into an SBE `FxFeedDelta` datagram and send to the market-data-handler:

1. Parse the row.
2. Map `ecn` / `ccy_pair` strings to SBE enum ordinals.
3. Encode: `MessageHeaderEncoder` + `FxFeedDeltaEncoder` onto a `UnsafeBuffer`.
4. Send the buffer as a UDP datagram to the configured port (default 9000).
5. Pace sends using `timestamp_ns` deltas for real-time replay, or send as fast as possible for stress testing.