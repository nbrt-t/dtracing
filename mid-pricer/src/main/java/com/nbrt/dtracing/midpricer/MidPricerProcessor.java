package com.nbrt.dtracing.midpricer;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.CompositeBookSnapshotDecoder;
import com.nbrt.dtracing.common.sbe.Stage;
import com.nbrt.dtracing.common.tracing.TracePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Receives {@code CompositeBookSnapshot} messages from the BookBuilder and
 * calculates the mid price for each currency pair.
 * <p>
 * Each snapshot carries all 3 venue-level BBOs (positional by ECN ordinal).
 * The processor finds the best bid (highest) and best ask (lowest) across
 * venues and computes mid = (bestBid + bestAsk) / 2 in Decimal5 mantissa.
 * <p>
 * Stateless with respect to venue data — each snapshot is self-contained.
 * Flat scratch arrays, zero allocation on the hot path.
 */
@Service
public class MidPricerProcessor implements CompositeBookSnapshotHandler {

    private static final Logger log = LoggerFactory.getLogger(MidPricerProcessor.class);

    private static final int ECN_COUNT = 3;
    private static final int CCY_PAIR_COUNT = 12;

    // Scratch arrays for decoding venue slots — reused per message
    private final long[] bidPrices = new long[ECN_COUNT];
    private final int[] bidSizes = new int[ECN_COUNT];
    private final long[] askPrices = new long[ECN_COUNT];
    private final int[] askSizes = new int[ECN_COUNT];

    // Latest mid prices: [ccyPair ordinal]
    private final long[] midPrices = new long[CCY_PAIR_COUNT];
    private final int[] midSizes = new int[CCY_PAIR_COUNT];

    private final AeronMidPriceBookPublisher publisher;
    private final TracePublisher tracePublisher;
    private long messageCount;

    public MidPricerProcessor(AeronMidPriceBookPublisher publisher, TracePublisher tracePublisher) {
        this.publisher = publisher;
        this.tracePublisher = tracePublisher;
    }

    @Override
    public void onCompositeBookSnapshot(CompositeBookSnapshotDecoder decoder) {
        long timestampIn = TracePublisher.epochNanosNow();
        messageCount++;

        var ccyPair = decoder.ccyPair();
        var triggeringEcn = decoder.triggeringEcn();
        int ccyIdx = ccyPair.value();

        // Extract trace context
        long traceId = decoder.traceId();
        long parentSpanId = decoder.spanId();
        long sequenceNumber = decoder.sequenceNumber();

        // Decode all 3 venue slots (positional by ECN ordinal)
        bidPrices[0] = decoder.venue0BidPrice().mantissa();
        bidSizes[0]  = decoder.venue0BidSize();
        askPrices[0] = decoder.venue0AskPrice().mantissa();
        askSizes[0]  = decoder.venue0AskSize();

        bidPrices[1] = decoder.venue1BidPrice().mantissa();
        bidSizes[1]  = decoder.venue1BidSize();
        askPrices[1] = decoder.venue1AskPrice().mantissa();
        askSizes[1]  = decoder.venue1AskSize();

        bidPrices[2] = decoder.venue2BidPrice().mantissa();
        bidSizes[2]  = decoder.venue2BidSize();
        askPrices[2] = decoder.venue2AskPrice().mantissa();
        askSizes[2]  = decoder.venue2AskSize();

        // Find best bid (highest) and best ask (lowest) across all venues
        long bestBid = 0;
        int bestBidSize = 0;
        long bestAsk = Long.MAX_VALUE;
        int bestAskSize = 0;

        for (int e = 0; e < ECN_COUNT; e++) {
            long bp = bidPrices[e];
            if (bp > bestBid) {
                bestBid = bp;
                bestBidSize = bidSizes[e];
            }
            long ap = askPrices[e];
            if (ap > 0 && ap < bestAsk) {
                bestAsk = ap;
                bestAskSize = askSizes[e];
            }
        }

        if (bestAsk == Long.MAX_VALUE) {
            bestAsk = 0;
        }

        // Calculate mid price: (bestBid + bestAsk) / 2 — integer division in Decimal5 mantissa
        if (bestBid > 0 && bestAsk > 0) {
            midPrices[ccyIdx] = (bestBid + bestAsk) / 2;
            midSizes[ccyIdx] = Math.min(bestBidSize, bestAskSize);

            // Transport span: Aeron transit from BOOK_BUILD to here
            long transportSpanId = tracePublisher.publishSpan(
                    traceId, parentSpanId, Stage.TRANSPORT,
                    triggeringEcn, ccyPair, sequenceNumber,
                    decoder.senderTimestampOut(), timestampIn);

            // Publish trace span — ecn is the triggering ECN
            long timestampOut = TracePublisher.epochNanosNow();
            long spanId = tracePublisher.publishSpan(
                    traceId, transportSpanId, Stage.MID_PRICE,
                    triggeringEcn, ccyPair, sequenceNumber,
                    timestampIn, timestampOut);

            // Publish to PriceTiering with trace context
            publisher.publish(ccyPair, midPrices[ccyIdx], midSizes[ccyIdx],
                    traceId, spanId, sequenceNumber, triggeringEcn, timestampOut);
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
