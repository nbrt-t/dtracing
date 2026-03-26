package com.nbrt.dtracing.bookbuilder;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.Ecn;
import com.nbrt.dtracing.common.sbe.FxMarketDataDecoder;
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

    private static final int ECN_COUNT = Ecn.values().length;
    private static final int CCY_PAIR_COUNT = CcyPair.values().length;

    // Per-venue BBO: [ecn ordinal][ccyPair ordinal]
    private final VenueBook[][] venueBooks = new VenueBook[ECN_COUNT][CCY_PAIR_COUNT];

    // Composite books: [ccyPair ordinal] — merged across all ECNs
    private final CompositeBook[] compositeBooks = new CompositeBook[CCY_PAIR_COUNT];

    // Scratch array for passing venue books to rebuild — avoids allocation per call
    private final VenueBook[] venueSlice = new VenueBook[ECN_COUNT];

    private long messageCount;

    public BookBuilderProcessor() {
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
        messageCount++;

        var ecn = decoder.ecn();
        var ccyPair = decoder.ccyPair();
        long timestamp = decoder.timestamp();
        long bidMantissa = decoder.bidPrice().mantissa();
        int bidSize = decoder.bidSize();
        long askMantissa = decoder.askPrice().mantissa();
        int askSize = decoder.askSize();

        // Update the venue-level BBO
        int ccyIdx = ccyPair.value();
        venueBooks[ecn.value()][ccyIdx].update(timestamp, bidMantissa, bidSize, askMantissa, askSize);

        // Rebuild composite book from all venues for this pair
        for (int e = 0; e < ECN_COUNT; e++) {
            venueSlice[e] = venueBooks[e][ccyIdx];
        }
        var composite = compositeBooks[ccyIdx];
        composite.rebuild(venueSlice);

        log.info("[{}] {} composite: bids={} asks={} bestBid={}/{} bestAsk={}/{}  (total={})",
                ecn, ccyPair,
                composite.bidDepth(), composite.askDepth(),
                composite.bestBid(), composite.bidDepth() > 0 ? composite.bidEcn(0) : "-",
                composite.bestAsk(), composite.askDepth() > 0 ? composite.bestAskEcn() : "-",
                messageCount);
    }

    /**
     * Returns the composite book for a currency pair (merged across all ECNs).
     */
    public CompositeBook getCompositeBook(CcyPair ccyPair) {
        return compositeBooks[ccyPair.value()];
    }

    /**
     * Returns the venue book for a specific ECN and currency pair.
     */
    public VenueBook getVenueBook(Ecn ecn, CcyPair ccyPair) {
        return venueBooks[ecn.value()][ccyPair.value()];
    }
}