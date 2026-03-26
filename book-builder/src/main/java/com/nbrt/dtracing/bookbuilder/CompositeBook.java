package com.nbrt.dtracing.bookbuilder;

import com.nbrt.dtracing.common.sbe.Ecn;

/**
 * Composite order book for a single currency pair, aggregated across all ECNs.
 * <p>
 * Bids are sorted descending by price (best bid at index 0).
 * Asks are sorted ascending by price (best ask at index 0).
 * Each level is tagged with its source ECN.
 * <p>
 * Max depth equals the number of ECNs (one BBO per venue).
 * All operations are O(ECN_COUNT) with no heap allocation.
 */
public class CompositeBook {

    private static final int MAX_DEPTH = Ecn.values().length;

    private final long[] bidPrices = new long[MAX_DEPTH];
    private final int[] bidSizes = new int[MAX_DEPTH];
    private final Ecn[] bidEcns = new Ecn[MAX_DEPTH];
    private int bidDepth;

    private final long[] askPrices = new long[MAX_DEPTH];
    private final int[] askSizes = new int[MAX_DEPTH];
    private final Ecn[] askEcns = new Ecn[MAX_DEPTH];
    private int askDepth;

    /**
     * Rebuild the composite book from the venue books for this currency pair.
     * Called after every venue book update — fast for small ECN count.
     */
    public void rebuild(VenueBook[] venues) {
        bidDepth = 0;
        askDepth = 0;

        for (var venue : venues) {
            if (!venue.hasData()) {
                continue;
            }
            if (venue.bidPrice() > 0 && venue.bidSize() > 0) {
                insertBidDescending(venue.ecn(), venue.bidPrice(), venue.bidSize());
            }
            if (venue.askPrice() > 0 && venue.askSize() > 0) {
                insertAskAscending(venue.ecn(), venue.askPrice(), venue.askSize());
            }
        }
    }

    // ── Bid side (descending — highest price first) ─────────────────────

    private void insertBidDescending(Ecn ecn, long price, int size) {
        int insertAt = 0;
        while (insertAt < bidDepth && bidPrices[insertAt] > price) {
            insertAt++;
        }
        if (bidDepth < MAX_DEPTH) {
            // Shift down
            for (int i = bidDepth; i > insertAt; i--) {
                bidPrices[i] = bidPrices[i - 1];
                bidSizes[i] = bidSizes[i - 1];
                bidEcns[i] = bidEcns[i - 1];
            }
            bidDepth++;
        } else if (insertAt >= MAX_DEPTH) {
            return; // worse than all existing levels
        } else {
            // Shift down, drop last
            for (int i = MAX_DEPTH - 1; i > insertAt; i--) {
                bidPrices[i] = bidPrices[i - 1];
                bidSizes[i] = bidSizes[i - 1];
                bidEcns[i] = bidEcns[i - 1];
            }
        }
        bidPrices[insertAt] = price;
        bidSizes[insertAt] = size;
        bidEcns[insertAt] = ecn;
    }

    // ── Ask side (ascending — lowest price first) ───────────────────────

    private void insertAskAscending(Ecn ecn, long price, int size) {
        int insertAt = 0;
        while (insertAt < askDepth && askPrices[insertAt] < price) {
            insertAt++;
        }
        if (askDepth < MAX_DEPTH) {
            for (int i = askDepth; i > insertAt; i--) {
                askPrices[i] = askPrices[i - 1];
                askSizes[i] = askSizes[i - 1];
                askEcns[i] = askEcns[i - 1];
            }
            askDepth++;
        } else if (insertAt >= MAX_DEPTH) {
            return;
        } else {
            for (int i = MAX_DEPTH - 1; i > insertAt; i--) {
                askPrices[i] = askPrices[i - 1];
                askSizes[i] = askSizes[i - 1];
                askEcns[i] = askEcns[i - 1];
            }
        }
        askPrices[insertAt] = price;
        askSizes[insertAt] = size;
        askEcns[insertAt] = ecn;
    }

    // ── Accessors ───────────────────────────────────────────────────────

    public int bidDepth()              { return bidDepth; }
    public int askDepth()              { return askDepth; }

    public long bidPrice(int level)    { return bidPrices[level]; }
    public int bidSize(int level)      { return bidSizes[level]; }
    public Ecn bidEcn(int level)       { return bidEcns[level]; }

    public long askPrice(int level)    { return askPrices[level]; }
    public int askSize(int level)      { return askSizes[level]; }
    public Ecn askEcn(int level)       { return askEcns[level]; }

    public long bestBid()              { return bidDepth > 0 ? bidPrices[0] : 0; }
    public long bestAsk()              { return askDepth > 0 ? askPrices[0] : 0; }
    public Ecn bestBidEcn()            { return bidDepth > 0 ? bidEcns[0] : null; }
    public Ecn bestAskEcn()            { return askDepth > 0 ? askEcns[0] : null; }
}