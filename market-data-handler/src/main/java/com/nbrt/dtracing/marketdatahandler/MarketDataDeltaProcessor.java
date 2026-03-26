package com.nbrt.dtracing.marketdatahandler;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.FxFeedDeltaDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MarketDataDeltaProcessor implements MarketDataDeltaHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataDeltaProcessor.class);

    private static final long LOG_SAMPLE_INTERVAL = 10_000;

    // One book per CcyPair ordinal — zero-allocation lookup, no map needed
    // 12 real pairs (0..11); excludes SBE NULL_VAL sentinel (255)
    private static final int CCY_PAIR_COUNT = 12;
    private final OrderBook[] books = new OrderBook[CCY_PAIR_COUNT];

    private final String ecn;
    private final AeronFxMarketDataPublisher publisher;
    private long messageCount;

    public MarketDataDeltaProcessor(UdpFeedProperties properties, AeronFxMarketDataPublisher publisher) {
        this.ecn = properties.ecn();
        this.publisher = publisher;
        for (int i = 0; i < books.length; i++) {
            books[i] = new OrderBook();
        }
    }

    @Override
    public void onDelta(FxFeedDeltaDecoder decoder) {
        messageCount++;

        var ccyPair = decoder.ccyPair();
        long timestamp = decoder.timestamp();
        long bidMantissa = decoder.bidPrice().mantissa();
        int bidSize = decoder.bidSize();
        long askMantissa = decoder.askPrice().mantissa();
        int askSize = decoder.askSize();

        var book = books[ccyPair.value()];
        book.updateBid(bidMantissa, bidSize);
        book.updateAsk(askMantissa, askSize);

        // Publish current BBO to BookBuilder via Aeron IPC
        publisher.publish(ccyPair, timestamp, book.bestBid(),
                book.bidDepth() > 0 ? book.bidSize(0) : 0,
                book.bestAsk(),
                book.askDepth() > 0 ? book.askSize(0) : 0);

        if (log.isDebugEnabled() && messageCount % LOG_SAMPLE_INTERVAL == 0) {
            log.debug("[{}] {} book: bid={}/{} ask={}/{} depth={}/{}  (count={})",
                    ecn, ccyPair,
                    book.bestBid(), book.bidDepth() > 0 ? book.bidSize(0) : 0,
                    book.bestAsk(), book.askDepth() > 0 ? book.askSize(0) : 0,
                    book.bidDepth(), book.askDepth(),
                    messageCount);
        }
    }

    /**
     * Returns the order book for the given currency pair.
     */
    public OrderBook getBook(CcyPair ccyPair) {
        return books[ccyPair.value()];
    }
}