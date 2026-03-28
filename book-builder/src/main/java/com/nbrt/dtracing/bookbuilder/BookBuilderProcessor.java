package com.nbrt.dtracing.bookbuilder;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.Ecn;
import com.nbrt.dtracing.common.sbe.FxMarketDataDecoder;
import com.nbrt.dtracing.common.sbe.Stage;
import com.nbrt.dtracing.common.tracing.TracePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Aggregates BBO ticks from all ECNs into composite order books per currency pair.
 * <p>
 * On every incoming {@code FxMarketData}, the per-venue BBO is updated and the
 * composite book for that currency pair is rebuilt from all venue contributions.
 * <p>
 * Flat arrays throughout — zero allocation on the hot path.
 */
@Service
public class BookBuilderProcessor implements FxMarketDataHandler {

    private static final Logger log = LoggerFactory.getLogger(BookBuilderProcessor.class);

    // Excludes SBE NULL_VAL sentinels (value 255)
    private static final int ECN_COUNT = 3;
    private static final int CCY_PAIR_COUNT = 12;

    // Per-venue BBO: [ecn ordinal][ccyPair ordinal]
    private final VenueBook[][] venueBooks = new VenueBook[ECN_COUNT][CCY_PAIR_COUNT];

    // Composite books: [ccyPair ordinal] — merged across all ECNs
    private final CompositeBook[] compositeBooks = new CompositeBook[CCY_PAIR_COUNT];

    // Scratch array for passing venue books to rebuild — avoids allocation per call
    private final VenueBook[] venueSlice = new VenueBook[ECN_COUNT];

    private final AeronCompositeBookPublisher publisher;
    private final TracePublisher tracePublisher;
    private long messageCount;

    public BookBuilderProcessor(AeronCompositeBookPublisher publisher, TracePublisher tracePublisher) {
        this.publisher = publisher;
        this.tracePublisher = tracePublisher;
        for (int e = 0; e < ECN_COUNT; e++) {
            for (int c = 0; c < CCY_PAIR_COUNT; c++) {
                venueBooks[e][c] = new VenueBook(Ecn.values()[e]);
            }
        }
        for (int c = 0; c < CCY_PAIR_COUNT; c++) {
            compositeBooks[c] = new CompositeBook();
        }
    }

    @Override
    public void onMarketData(FxMarketDataDecoder decoder) {
        long timestampIn = TracePublisher.epochNanosNow();
        messageCount++;

        var ecn = decoder.ecn();
        var ccyPair = decoder.ccyPair();
        long timestamp = decoder.timestamp();
        long bidMantissa = decoder.bidPrice().mantissa();
        int bidSize = decoder.bidSize();
        long askMantissa = decoder.askPrice().mantissa();
        int askSize = decoder.askSize();

        // Extract trace context from incoming message
        long traceId = decoder.traceId();
        long parentSpanId = decoder.spanId();
        long sequenceNumber = decoder.sequenceNumber();

        // Update the venue-level BBO
        int ccyIdx = ccyPair.value();
        venueBooks[ecn.value()][ccyIdx].update(timestamp, bidMantissa, bidSize, askMantissa, askSize);

        // Rebuild composite book from all venues for this pair
        for (int e = 0; e < ECN_COUNT; e++) {
            venueSlice[e] = venueBooks[e][ccyIdx];
        }
        var composite = compositeBooks[ccyIdx];
        composite.rebuild(venueSlice);

        // Transport span: Aeron transit from MDH_PROCESS to here
        long transportSpanId = tracePublisher.publishSpan(
                traceId, parentSpanId, Stage.TRANSPORT,
                ecn, ccyPair, sequenceNumber,
                decoder.senderTimestampOut(), timestampIn);

        // Publish trace span
        long timestampOut = TracePublisher.epochNanosNow();
        long spanId = tracePublisher.publishSpan(
                traceId, transportSpanId, Stage.BOOK_BUILD,
                ecn, ccyPair, sequenceNumber,
                timestampIn, timestampOut);

        // Publish venue-level snapshot to MidPricer with trace context
        publisher.publishSnapshot(ccyPair, venueSlice, ecn, traceId, spanId, sequenceNumber, timestampOut);

        if (log.isDebugEnabled()) {
            log.debug("[{}] {} composite: bids={} asks={} bestBid={}/{} bestAsk={}/{}  (total={})",
                    ecn, ccyPair,
                    composite.bidDepth(), composite.askDepth(),
                    composite.bestBid(), composite.bidDepth() > 0 ? composite.bidEcn(0) : "-",
                    composite.bestAsk(), composite.askDepth() > 0 ? composite.bestAskEcn() : "-",
                    messageCount);
        }
    }

    public CompositeBook getCompositeBook(CcyPair ccyPair) {
        return compositeBooks[ccyPair.value()];
    }

    public VenueBook getVenueBook(Ecn ecn, CcyPair ccyPair) {
        return venueBooks[ecn.value()][ccyPair.value()];
    }
}