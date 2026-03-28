package com.nbrt.dtracing.marketdatahandler;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.Ecn;
import com.nbrt.dtracing.common.sbe.FxFeedDeltaDecoder;
import com.nbrt.dtracing.common.sbe.Stage;
import com.nbrt.dtracing.common.tracing.TracePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MarketDataDeltaProcessor implements MarketDataDeltaHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataDeltaProcessor.class);

    private static final long LOG_SAMPLE_INTERVAL = 10_000;

    // 12 real pairs (0..11); excludes SBE NULL_VAL sentinel (255)
    private static final int CCY_PAIR_COUNT = 12;
    private final OrderBook[] books = new OrderBook[CCY_PAIR_COUNT];

    private final String ecn;
    private final Ecn ecnEnum;
    private final AeronFxMarketDataPublisher publisher;
    private final TracePublisher tracePublisher;
    private long messageCount;
    private long traceIdCounter;
    private final long traceIdSeed = System.nanoTime();

    public MarketDataDeltaProcessor(UdpFeedProperties properties,
                                    AeronFxMarketDataPublisher publisher,
                                    TracePublisher tracePublisher) {
        this.ecn = properties.ecn();
        this.ecnEnum = Ecn.valueOf(properties.ecn());
        this.publisher = publisher;
        this.tracePublisher = tracePublisher;
        for (int i = 0; i < books.length; i++) {
            books[i] = new OrderBook();
        }
    }

    @Override
    public void onDelta(FxFeedDeltaDecoder decoder) {
        long timestampIn = TracePublisher.epochNanosNow();
        messageCount++;

        var ccyPair = decoder.ccyPair();
        long feedTimestamp = decoder.timestamp();
        long sequenceNumber = decoder.sequenceNumber();
        long bidMantissa = decoder.bidPrice().mantissa();
        int bidSize = decoder.bidSize();
        long askMantissa = decoder.askPrice().mantissa();
        int askSize = decoder.askSize();

        var book = books[ccyPair.value()];
        book.updateBid(bidMantissa, bidSize);
        book.updateAsk(askMantissa, askSize);

        // Span 1: exchange publish → UDP receive (root span)
        long traceId = traceIdSeed ^ (++traceIdCounter);
        long receiveSpanId = tracePublisher.publishSpan(
                traceId, 0, Stage.MDH_RECEIVE,
                ecnEnum, ccyPair, sequenceNumber,
                feedTimestamp, timestampIn);

        // Span 2: internal processing — decode, book update, Aeron publish
        long timestampOut = TracePublisher.epochNanosNow();
        long spanId = tracePublisher.publishSpan(
                traceId, receiveSpanId, Stage.MDH_PROCESS,
                ecnEnum, ccyPair, sequenceNumber,
                timestampIn, timestampOut);

        // Publish current BBO to BookBuilder with trace context
        publisher.publish(ccyPair, feedTimestamp, book.bestBid(),
                book.bidDepth() > 0 ? book.bidSize(0) : 0,
                book.bestAsk(),
                book.askDepth() > 0 ? book.askSize(0) : 0,
                traceId, spanId, sequenceNumber, timestampOut);

        //if (log.isDebugEnabled() && messageCount % LOG_SAMPLE_INTERVAL == 0) {
            log.info("[{}] {} book: bid={}/{} ask={}/{} depth={}/{} sequence={} (count={})",
                    ecn, ccyPair,
                    book.bestBid(), book.bidDepth() > 0 ? book.bidSize(0) : 0,
                    book.bestAsk(), book.askDepth() > 0 ? book.askSize(0) : 0,
                    book.bidDepth(), book.askDepth(),
                    sequenceNumber,
                    messageCount);
        //}
    }

    public OrderBook getBook(CcyPair ccyPair) {
        return books[ccyPair.value()];
    }
}