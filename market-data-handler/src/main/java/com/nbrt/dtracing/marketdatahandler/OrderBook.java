package com.nbrt.dtracing.marketdatahandler;

/**
 * Fixed-depth order book for a single currency pair.
 * <p>
 * Bids are sorted descending by price (best bid at index 0).
 * Asks are sorted ascending by price (best ask at index 0).
 * <p>
 * All operations are O(MAX_DEPTH) with no heap allocation — suitable for hot-path use.
 * A size of 0 on an update removes that price level from the book.
 */
public class OrderBook {

    public static final int MAX_DEPTH = 5;

    private final long[] bidPrices = new long[MAX_DEPTH];
    private final int[] bidSizes = new int[MAX_DEPTH];
    private int bidDepth;

    private final long[] askPrices = new long[MAX_DEPTH];
    private final int[] askSizes = new int[MAX_DEPTH];
    private int askDepth;

    /**
     * Update or remove a bid level. Size 0 deletes the level.
     * Price is a Decimal5 mantissa.
     */
    public void updateBid(long priceMantissa, int size) {
        if (size == 0) {
            remove(bidPrices, bidSizes, bidDepth, priceMantissa);
            bidDepth = compactCount(bidSizes, bidDepth);
        } else {
            bidDepth = upsertDescending(bidPrices, bidSizes, bidDepth, priceMantissa, size);
        }
    }

    /**
     * Update or remove an ask level. Size 0 deletes the level.
     * Price is a Decimal5 mantissa.
     */
    public void updateAsk(long priceMantissa, int size) {
        if (size == 0) {
            remove(askPrices, askSizes, askDepth, priceMantissa);
            askDepth = compactCount(askSizes, askDepth);
        } else {
            askDepth = upsertAscending(askPrices, askSizes, askDepth, priceMantissa, size);
        }
    }

    public int bidDepth() { return bidDepth; }
    public int askDepth() { return askDepth; }

    public long bidPrice(int level) { return bidPrices[level]; }
    public int bidSize(int level)   { return bidSizes[level]; }
    public long askPrice(int level) { return askPrices[level]; }
    public int askSize(int level)   { return askSizes[level]; }

    public long bestBid() { return bidDepth > 0 ? bidPrices[0] : 0; }
    public long bestAsk() { return askDepth > 0 ? askPrices[0] : 0; }

    public void clear() {
        bidDepth = 0;
        askDepth = 0;
    }

    // ── Descending insert (bids: highest price first) ──────────────────────

    private static int upsertDescending(long[] prices, int[] sizes, int depth,
                                        long price, int size) {
        // Check for existing level
        for (int i = 0; i < depth; i++) {
            if (prices[i] == price) {
                sizes[i] = size;
                return depth;
            }
        }
        // Insert in sorted position (descending)
        int insertAt = 0;
        while (insertAt < depth && prices[insertAt] > price) {
            insertAt++;
        }
        if (depth >= MAX_DEPTH && insertAt >= MAX_DEPTH) {
            return depth; // beyond max depth, discard
        }
        int newDepth = Math.min(depth + 1, MAX_DEPTH);
        // Shift down
        for (int i = newDepth - 1; i > insertAt; i--) {
            prices[i] = prices[i - 1];
            sizes[i] = sizes[i - 1];
        }
        prices[insertAt] = price;
        sizes[insertAt] = size;
        return newDepth;
    }

    // ── Ascending insert (asks: lowest price first) ────────────────────────

    private static int upsertAscending(long[] prices, int[] sizes, int depth,
                                       long price, int size) {
        // Check for existing level
        for (int i = 0; i < depth; i++) {
            if (prices[i] == price) {
                sizes[i] = size;
                return depth;
            }
        }
        // Insert in sorted position (ascending)
        int insertAt = 0;
        while (insertAt < depth && prices[insertAt] < price) {
            insertAt++;
        }
        if (depth >= MAX_DEPTH && insertAt >= MAX_DEPTH) {
            return depth; // beyond max depth, discard
        }
        int newDepth = Math.min(depth + 1, MAX_DEPTH);
        // Shift down
        for (int i = newDepth - 1; i > insertAt; i--) {
            prices[i] = prices[i - 1];
            sizes[i] = sizes[i - 1];
        }
        prices[insertAt] = price;
        sizes[insertAt] = size;
        return newDepth;
    }

    // ── Remove by price ────────────────────────────────────────────────────

    private static void remove(long[] prices, int[] sizes, int depth, long price) {
        for (int i = 0; i < depth; i++) {
            if (prices[i] == price) {
                // Shift up
                for (int j = i; j < depth - 1; j++) {
                    prices[j] = prices[j + 1];
                    sizes[j] = sizes[j + 1];
                }
                sizes[depth - 1] = 0;
                prices[depth - 1] = 0;
                return;
            }
        }
    }

    private static int compactCount(int[] sizes, int depth) {
        while (depth > 0 && sizes[depth - 1] == 0) {
            depth--;
        }
        return depth;
    }
}