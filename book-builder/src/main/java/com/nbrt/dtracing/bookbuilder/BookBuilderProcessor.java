package com.nbrt.dtracing.bookbuilder;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.Ecn;
import com.nbrt.dtracing.common.sbe.FxMarketDataDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Aggregates BBO ticks from all ECNs into a venue-level view per currency pair.
 * <p>
 * Maintains a flat {@code VenueBook[ecnCount][ccyPairCount]} array for
 * zero-allocation lookup on every incoming message.
 */
@Service
public class BookBuilderProcessor implements FxMarketDataHandler {

    private static final Logger log = LoggerFactory.getLogger(BookBuilderProcessor.class);
    private static final long LOG_SAMPLE_INTERVAL = 10_000;

    private static final int ECN_COUNT = Ecn.values().length;
    private static final int CCY_PAIR_COUNT = CcyPair.values().length;

    // Flat 2D array: [ecn ordinal][ccyPair ordinal]
    private final VenueBook[][] venueBooks = new VenueBook[ECN_COUNT][CCY_PAIR_COUNT];

    private long messageCount;

    public BookBuilderProcessor() {
        for (int e = 0; e < ECN_COUNT; e++) {
            for (int c = 0; c < CCY_PAIR_COUNT; c++) {
                venueBooks[e][c] = new VenueBook(Ecn.values()[e]);
            }
        }
    }

    @Override
    public void onMarketData(FxMarketDataDecoder decoder) {
        messageCount++;

        var ecn = decoder.ecn();
        var ccyPair = decoder.ccyPair();
        long timestamp = decoder.timestamp();
        long bidMantissa = decoder.bidPrice().mantissa();
        int bidSize = decoder.bidSize();
        long askMantissa = decoder.askPrice().mantissa();
        int askSize = decoder.askSize();

        var venueBook = venueBooks[ecn.value()][ccyPair.value()];
        venueBook.update(timestamp, bidMantissa, bidSize, askMantissa, askSize);

        if (log.isDebugEnabled() && messageCount % LOG_SAMPLE_INTERVAL == 0) {
            log.debug("[{}] {} venue book: bid={}/{} ask={}/{}  (total={})",
                    ecn, ccyPair,
                    bidMantissa, bidSize,
                    askMantissa, askSize,
                    messageCount);
        }
    }

    /**
     * Returns the venue book for a specific ECN and currency pair.
     */
    public VenueBook getVenueBook(Ecn ecn, CcyPair ccyPair) {
        return venueBooks[ecn.value()][ccyPair.value()];
    }

    /**
     * Returns all venue books for a currency pair (one per ECN).
     * Useful for cross-venue aggregation.
     */
    public VenueBook[] getVenueBooksForPair(CcyPair ccyPair) {
        var result = new VenueBook[ECN_COUNT];
        for (int e = 0; e < ECN_COUNT; e++) {
            result[e] = venueBooks[e][ccyPair.value()];
        }
        return result;
    }
}