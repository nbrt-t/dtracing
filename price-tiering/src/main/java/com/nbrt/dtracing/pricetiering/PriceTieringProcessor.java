package com.nbrt.dtracing.pricetiering;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.MidPriceBookDecoder;
import com.nbrt.dtracing.common.sbe.Stage;
import com.nbrt.dtracing.common.tracing.TracePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Receives {@code MidPriceBook} messages and applies a configuration-driven
 * spread matrix to produce tiered prices for each client tier.
 * <p>
 * For each tier, the spread is applied symmetrically around the mid price:
 * <ul>
 *   <li>bidPrice = mid - halfSpread</li>
 *   <li>askPrice = mid + halfSpread</li>
 * </ul>
 * <p>
 * Pre-computes the half-spread array per currency pair at startup to avoid
 * map lookups on the hot path.
 */
@Service
public class PriceTieringProcessor implements MidPriceBookHandler {

    private static final Logger log = LoggerFactory.getLogger(PriceTieringProcessor.class);

    // 12 real pairs (0..11); excludes SBE NULL_VAL sentinel (255)
    private static final int CCY_PAIR_COUNT = 12;

    private final int tierCount;

    // Pre-computed half-spreads: [ccyPair ordinal][tier index]
    private final long[][] halfSpreads = new long[CCY_PAIR_COUNT][];

    // Latest tiered prices: [ccyPair ordinal][tier index]
    private final long[][] tieredBidPrices;
    private final long[][] tieredAskPrices;
    private final int[][] tieredSizes;

    private final TracePublisher tracePublisher;
    private long messageCount;

    public PriceTieringProcessor(SpreadMatrixProperties spreadMatrix, TracePublisher tracePublisher) {
        this.tracePublisher = tracePublisher;
        this.tierCount = spreadMatrix.tierCount();
        this.tieredBidPrices = new long[CCY_PAIR_COUNT][tierCount];
        this.tieredAskPrices = new long[CCY_PAIR_COUNT][tierCount];
        this.tieredSizes = new int[CCY_PAIR_COUNT][tierCount];

        // Pre-compute half-spreads per pair (skip SBE NULL_VAL sentinel)
        for (var ccyPair : CcyPair.values()) {
            if (ccyPair == CcyPair.NULL_VAL) continue;
            halfSpreads[ccyPair.value()] = spreadMatrix.halfSpreadsFor(ccyPair);
        }

        log.info("PriceTiering initialised with {} tiers, default half-spreads={}",
                tierCount, spreadMatrix.defaultHalfSpreads());
        for (var entry : spreadMatrix.pairHalfSpreads().entrySet()) {
            log.info("  {} override: {}", entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void onMidPriceBook(MidPriceBookDecoder decoder) {
        long timestampIn = TracePublisher.epochNanosNow();
        messageCount++;

        var ccyPair = decoder.ccyPair();
        long midMantissa = decoder.midPrice().mantissa();
        int midSize = decoder.midSize();
        int ccyIdx = ccyPair.value();

        // Extract trace context
        long traceId = decoder.traceId();
        long parentSpanId = decoder.spanId();
        long sequenceNumber = decoder.sequenceNumber();
        var ecn = decoder.ecn();

        long[] pairSpreads = halfSpreads[ccyIdx];

        for (int t = 0; t < tierCount; t++) {
            long halfSpread = pairSpreads[t];
            tieredBidPrices[ccyIdx][t] = midMantissa - halfSpread;
            tieredAskPrices[ccyIdx][t] = midMantissa + halfSpread;
            tieredSizes[ccyIdx][t] = midSize;
        }

        // Transport span: Aeron transit from MID_PRICE to here
        long transportSpanId = tracePublisher.publishSpan(
                traceId, parentSpanId, Stage.TRANSPORT,
                ecn, ccyPair, sequenceNumber,
                decoder.senderTimestampOut(), timestampIn);

        // Publish terminal trace span
        long timestampOut = TracePublisher.epochNanosNow();
        tracePublisher.publishSpan(
                traceId, transportSpanId, Stage.PRICE_TIER,
                ecn, ccyPair, sequenceNumber,
                timestampIn, timestampOut);

        log.info("{} mid={} tiers: T1={}/{} T2={}/{} T3={}/{} T4={}/{}  (total={})",
                ccyPair, midMantissa,
                tieredBidPrices[ccyIdx][0], tieredAskPrices[ccyIdx][0],
                tieredBidPrices[ccyIdx][1], tieredAskPrices[ccyIdx][1],
                tieredBidPrices[ccyIdx][2], tieredAskPrices[ccyIdx][2],
                tieredBidPrices[ccyIdx][3], tieredAskPrices[ccyIdx][3],
                messageCount);
    }

    public long getTieredBidPrice(CcyPair ccyPair, int tier) {
        return tieredBidPrices[ccyPair.value()][tier];
    }

    public long getTieredAskPrice(CcyPair ccyPair, int tier) {
        return tieredAskPrices[ccyPair.value()][tier];
    }

    public int getTieredSize(CcyPair ccyPair, int tier) {
        return tieredSizes[ccyPair.value()][tier];
    }

    public int tierCount() {
        return tierCount;
    }
}