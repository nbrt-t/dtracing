package com.etradingpoc.dtracing.bookbuilder;

import com.etradingpoc.dtracing.common.sbe.Ecn;

/**
 * Holds the best bid/ask from a single ECN for a single currency pair.
 * Updated on every FxMarketData message from that ECN.
 */
public class VenueBook {

    private final Ecn ecn;

    private long bidPrice;
    private int bidSize;
    private long askPrice;
    private int askSize;
    private long timestamp;

    public VenueBook(Ecn ecn) {
        this.ecn = ecn;
    }

    public void update(long timestamp, long bidPrice, int bidSize, long askPrice, int askSize) {
        this.timestamp = timestamp;
        this.bidPrice = bidPrice;
        this.bidSize = bidSize;
        this.askPrice = askPrice;
        this.askSize = askSize;
    }

    public Ecn ecn()       { return ecn; }
    public long bidPrice() { return bidPrice; }
    public int bidSize()   { return bidSize; }
    public long askPrice() { return askPrice; }
    public int askSize()   { return askSize; }
    public long timestamp(){ return timestamp; }

    public boolean hasData() {
        return bidPrice > 0 || askPrice > 0;
    }
}