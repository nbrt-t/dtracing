package com.etradingpoc.dtracing.bookbuilder;

import com.etradingpoc.dtracing.common.sbe.CcyPair;
import com.etradingpoc.dtracing.common.sbe.Ecn;
import com.etradingpoc.dtracing.common.sbe.FxMarketDataDecoder;
import com.etradingpoc.dtracing.common.sbe.Stage;
import com.etradingpoc.dtracing.common.tracing.TracePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.LongSupplier;

/**
 * Aggregates BBO ticks from all ECNs into composite order books per currency pair.
 * <p>
 * On every incoming {@code FxMarketData}, the per-venue BBO is updated and the
 * composite book for that currency pair is rebuilt from all venue contributions.
 * Output is conflated: at most one {@code CompositeBookSnapshot} is published
 * per currency pair per configurable time window.
 * <p>
 * When ticks are conflated, span links are emitted connecting the published
 * snapshot's BOOK_BUILD span to each suppressed tick's BOOK_BUILD span,
 * preserving full trace lineage.
 * <p>
 * Flat arrays throughout — zero allocation on the hot path.
 */
@Service
public class BookBuilderProcessor implements FxMarketDataHandler {

    private static final Logger log = LoggerFactory.getLogger(BookBuilderProcessor.class);

    // Excludes SBE NULL_VAL sentinels (value 255)
    private static final int ECN_COUNT = 3;
    private static final int CCY_PAIR_COUNT = 12;
    private static final int MAX_CONFLATED_LINKS = 64;

    // Per-venue BBO: [ecn ordinal][ccyPair ordinal]
    private final VenueBook[][] venueBooks = new VenueBook[ECN_COUNT][CCY_PAIR_COUNT];

    // Composite books: [ccyPair ordinal] — merged across all ECNs
    private final CompositeBook[] compositeBooks = new CompositeBook[CCY_PAIR_COUNT];

    // Scratch array for passing venue books to rebuild — avoids allocation per call
    private final VenueBook[] venueSlice = new VenueBook[ECN_COUNT];

    // Conflation state — all indexed by ccyPair ordinal
    private final long[] lastPublishNanos = new long[CCY_PAIR_COUNT];
    private final boolean[] dirty = new boolean[CCY_PAIR_COUNT];
    private final long[] pendingTraceId = new long[CCY_PAIR_COUNT];
    private final long[] pendingSpanId = new long[CCY_PAIR_COUNT];
    private final long[] pendingSeqNo = new long[CCY_PAIR_COUNT];
    private final long[] pendingTimestampOut = new long[CCY_PAIR_COUNT];
    private final Ecn[] pendingEcn = new Ecn[CCY_PAIR_COUNT];

    // Span links for conflated ticks: [ccyPair][link index]
    private final long[][] linkTraceIds = new long[CCY_PAIR_COUNT][MAX_CONFLATED_LINKS];
    private final long[][] linkSpanIds = new long[CCY_PAIR_COUNT][MAX_CONFLATED_LINKS];
    private final int[] linkCount = new int[CCY_PAIR_COUNT];

    private final AeronCompositeBookPublisher publisher;
    private final TracePublisher tracePublisher;
    private final long conflationWindowNanos;
    private final LongSupplier nanoClock;
    private long messageCount;

    @Autowired
    public BookBuilderProcessor(AeronCompositeBookPublisher publisher, TracePublisher tracePublisher,
                                ConflationProperties conflationProperties) {
        this(publisher, tracePublisher, conflationProperties, System::nanoTime);
    }

    BookBuilderProcessor(AeronCompositeBookPublisher publisher, TracePublisher tracePublisher,
                         ConflationProperties conflationProperties, LongSupplier nanoClock) {
        this.publisher = publisher;
        this.tracePublisher = tracePublisher;
        this.conflationWindowNanos = conflationProperties.windowMs() * 1_000_000L;
        this.nanoClock = nanoClock;
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

        // Conflation gate: publish only if window has elapsed for this pair
        long now = nanoClock.getAsLong();
        if (now - lastPublishNanos[ccyIdx] >= conflationWindowNanos) {
            // Move the last pending tick into the link buffer before emitting
            if (dirty[ccyIdx]) {
                addLink(ccyIdx, pendingTraceId[ccyIdx], pendingSpanId[ccyIdx]);
            }
            // Emit span links from this tick's span to all suppressed ticks' spans
            emitSpanLinks(ccyIdx, ccyPair, traceId, spanId);
            publisher.publishSnapshot(ccyPair, venueSlice, ecn, traceId, spanId, sequenceNumber, timestampOut);
            lastPublishNanos[ccyIdx] = now;
            dirty[ccyIdx] = false;
        } else {
            // Save current pending to link buffer before overwriting
            if (dirty[ccyIdx]) {
                addLink(ccyIdx, pendingTraceId[ccyIdx], pendingSpanId[ccyIdx]);
            }
            dirty[ccyIdx] = true;
            pendingTraceId[ccyIdx] = traceId;
            pendingSpanId[ccyIdx] = spanId;
            pendingSeqNo[ccyIdx] = sequenceNumber;
            pendingEcn[ccyIdx] = ecn;
            pendingTimestampOut[ccyIdx] = timestampOut;
        }

        if (log.isDebugEnabled()) {
            log.debug("[{}] {} composite: bids={} asks={} bestBid={}/{} bestAsk={}/{}  (total={})",
                    ecn, ccyPair,
                    composite.bidDepth(), composite.askDepth(),
                    composite.bestBid(), composite.bidDepth() > 0 ? composite.bidEcn(0) : "-",
                    composite.bestAsk(), composite.askDepth() > 0 ? composite.bestAskEcn() : "-",
                    messageCount);
        }
    }

    @Override
    public void flushDirtyPairs() {
        long now = nanoClock.getAsLong();
        for (int c = 0; c < CCY_PAIR_COUNT; c++) {
            if (dirty[c] && (now - lastPublishNanos[c] >= conflationWindowNanos)) {
                CcyPair ccyPair = CcyPair.values()[c];

                // Emit span links from the pending span to all earlier suppressed spans
                emitSpanLinks(c, ccyPair, pendingTraceId[c], pendingSpanId[c]);

                for (int e = 0; e < ECN_COUNT; e++) {
                    venueSlice[e] = venueBooks[e][c];
                }
                publisher.publishSnapshot(ccyPair, venueSlice, pendingEcn[c],
                        pendingTraceId[c], pendingSpanId[c], pendingSeqNo[c], pendingTimestampOut[c]);
                lastPublishNanos[c] = now;
                dirty[c] = false;
            }
        }
    }

    private void addLink(int ccyIdx, long traceId, long spanId) {
        int idx = linkCount[ccyIdx];
        if (idx < MAX_CONFLATED_LINKS) {
            linkTraceIds[ccyIdx][idx] = traceId;
            linkSpanIds[ccyIdx][idx] = spanId;
            linkCount[ccyIdx] = idx + 1;
        }
    }

    private void emitSpanLinks(int ccyIdx, CcyPair ccyPair, long publishTraceId, long publishSpanId) {
        int count = linkCount[ccyIdx];
        for (int i = 0; i < count; i++) {
            tracePublisher.publishSpanLink(
                    publishTraceId, publishSpanId,
                    linkTraceIds[ccyIdx][i], linkSpanIds[ccyIdx][i],
                    ccyPair);
        }
        linkCount[ccyIdx] = 0;
    }

    public CompositeBook getCompositeBook(CcyPair ccyPair) {
        return compositeBooks[ccyPair.value()];
    }

    public VenueBook getVenueBook(Ecn ecn, CcyPair ccyPair) {
        return venueBooks[ecn.value()][ccyPair.value()];
    }
}
