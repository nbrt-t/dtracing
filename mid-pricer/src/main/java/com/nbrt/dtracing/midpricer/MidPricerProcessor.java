package com.nbrt.dtracing.midpricer;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.Ecn;
import com.nbrt.dtracing.common.sbe.VenueOrderBookDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Receives {@code VenueOrderBook} levels from the BookBuilder and calculates
 * the mid price for each currency pair.
 * <p>
 * Tracks the best bid (highest across all ECNs) and best ask (lowest across
 * all ECNs) per currency pair. Mid = (bestBid + bestAsk) / 2 in Decimal5 mantissa.
 * <p>
 * Flat arrays indexed by CcyPair ordinal — zero allocation on the hot path.
 */
@Service
public class MidPricerProcessor implements VenueOrderBookHandler {

    private static final Logger log = LoggerFactory.getLogger(MidPricerProcessor.class);

    // Excludes SBE NULL_VAL sentinels (value 255)
    private static final int ECN_COUNT = 3;
    private static final int CCY_PAIR_COUNT = 12;

    // Per-venue best bid/ask: [ecn ordinal][ccyPair ordinal]
    private final long[][] venueBidPrices = new long[ECN_COUNT][CCY_PAIR_COUNT];
    private final int[][] venueBidSizes = new int[ECN_COUNT][CCY_PAIR_COUNT];
    private final long[][] venueAskPrices = new long[ECN_COUNT][CCY_PAIR_COUNT];
    private final int[][] venueAskSizes = new int[ECN_COUNT][CCY_PAIR_COUNT];

    // Computed mid prices: [ccyPair ordinal]
    private final long[] midPrices = new long[CCY_PAIR_COUNT];
    private final int[] midSizes = new int[CCY_PAIR_COUNT];

    private final AeronMidPriceBookPublisher publisher;
    private long messageCount;

    public MidPricerProcessor(AeronMidPriceBookPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void onVenueOrderBook(VenueOrderBookDecoder decoder) {
        messageCount++;

        var ecn = decoder.ecn();
        var ccyPair = decoder.ccyPair();
        long rateMantissa = decoder.rate().mantissa();
        int bidSize = decoder.bidSize();
        int askSize = decoder.askSize();

        int ecnIdx = ecn.value();
        int ccyIdx = ccyPair.value();

        // Update per-venue state based on which side this level represents
        if (bidSize > 0) {
            venueBidPrices[ecnIdx][ccyIdx] = rateMantissa;
            venueBidSizes[ecnIdx][ccyIdx] = bidSize;
        }
        if (askSize > 0) {
            venueAskPrices[ecnIdx][ccyIdx] = rateMantissa;
            venueAskSizes[ecnIdx][ccyIdx] = askSize;
        }

        // Recalculate best bid (highest) and best ask (lowest) across all ECNs
        long bestBid = 0;
        int bestBidSize = 0;
        long bestAsk = Long.MAX_VALUE;
        int bestAskSize = 0;

        for (int e = 0; e < ECN_COUNT; e++) {
            long bp = venueBidPrices[e][ccyIdx];
            if (bp > bestBid) {
                bestBid = bp;
                bestBidSize = venueBidSizes[e][ccyIdx];
            }
            long ap = venueAskPrices[e][ccyIdx];
            if (ap > 0 && ap < bestAsk) {
                bestAsk = ap;
                bestAskSize = venueAskSizes[e][ccyIdx];
            }
        }

        if (bestAsk == Long.MAX_VALUE) {
            bestAsk = 0;
        }

        // Calculate mid price: (bestBid + bestAsk) / 2 — integer division in Decimal5 mantissa
        if (bestBid > 0 && bestAsk > 0) {
            midPrices[ccyIdx] = (bestBid + bestAsk) / 2;
            midSizes[ccyIdx] = Math.min(bestBidSize, bestAskSize);

            // Publish to PriceTiering via Aeron IPC
            publisher.publish(ccyPair, midPrices[ccyIdx], midSizes[ccyIdx]);
        }

        log.info("{} mid={} size={} (bestBid={} bestAsk={})  (total={})",
                ccyPair,
                midPrices[ccyIdx], midSizes[ccyIdx],
                bestBid, bestAsk,
                messageCount);
    }

    public long getMidPrice(CcyPair ccyPair) {
        return midPrices[ccyPair.value()];
    }

    public int getMidSize(CcyPair ccyPair) {
        return midSizes[ccyPair.value()];
    }
}