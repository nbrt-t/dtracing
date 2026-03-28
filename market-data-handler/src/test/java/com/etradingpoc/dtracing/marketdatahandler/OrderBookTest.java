package com.etradingpoc.dtracing.marketdatahandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookTest {

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook();
    }

    // ── Empty book ─────────────────────────────────────────────────────────

    @Test
    void emptyBook_bestBidAndAskAreZero() {
        assertThat(book.bestBid()).isZero();
        assertThat(book.bestAsk()).isZero();
        assertThat(book.bidDepth()).isZero();
        assertThat(book.askDepth()).isZero();
    }

    // ── Single level inserts ───────────────────────────────────────────────

    @Test
    void singleBid_isAtLevel0() {
        book.updateBid(108760L, 1_000_000);

        assertThat(book.bidDepth()).isEqualTo(1);
        assertThat(book.bestBid()).isEqualTo(108760L);
        assertThat(book.bidSize(0)).isEqualTo(1_000_000);
    }

    @Test
    void singleAsk_isAtLevel0() {
        book.updateAsk(108780L, 500_000);

        assertThat(book.askDepth()).isEqualTo(1);
        assertThat(book.bestAsk()).isEqualTo(108780L);
        assertThat(book.askSize(0)).isEqualTo(500_000);
    }

    // ── Bid sorting (descending — highest first) ───────────────────────────

    @Test
    void bids_sortedDescending_bestBidFirst() {
        book.updateBid(108750L, 100);
        book.updateBid(108770L, 200);
        book.updateBid(108760L, 300);

        assertThat(book.bidDepth()).isEqualTo(3);
        assertThat(book.bidPrice(0)).isEqualTo(108770L);
        assertThat(book.bidPrice(1)).isEqualTo(108760L);
        assertThat(book.bidPrice(2)).isEqualTo(108750L);
    }

    // ── Ask sorting (ascending — lowest first) ────────────────────────────

    @Test
    void asks_sortedAscending_bestAskFirst() {
        book.updateAsk(108790L, 100);
        book.updateAsk(108780L, 200);
        book.updateAsk(108800L, 300);

        assertThat(book.askDepth()).isEqualTo(3);
        assertThat(book.askPrice(0)).isEqualTo(108780L);
        assertThat(book.askPrice(1)).isEqualTo(108790L);
        assertThat(book.askPrice(2)).isEqualTo(108800L);
    }

    // ── Update existing level ──────────────────────────────────────────────

    @Test
    void updateBid_existingPrice_updatesSize() {
        book.updateBid(108760L, 1_000_000);
        book.updateBid(108760L, 2_000_000);

        assertThat(book.bidDepth()).isEqualTo(1);
        assertThat(book.bidSize(0)).isEqualTo(2_000_000);
    }

    @Test
    void updateAsk_existingPrice_updatesSize() {
        book.updateAsk(108780L, 500_000);
        book.updateAsk(108780L, 750_000);

        assertThat(book.askDepth()).isEqualTo(1);
        assertThat(book.askSize(0)).isEqualTo(750_000);
    }

    // ── Remove levels (size = 0) ───────────────────────────────────────────

    @Test
    void removeBid_sizeZero_decreasesDepth() {
        book.updateBid(108770L, 200);
        book.updateBid(108760L, 100);
        book.updateBid(108770L, 0); // remove top level

        assertThat(book.bidDepth()).isEqualTo(1);
        assertThat(book.bestBid()).isEqualTo(108760L);
    }

    @Test
    void removeAsk_sizeZero_decreasesDepth() {
        book.updateAsk(108780L, 200);
        book.updateAsk(108790L, 100);
        book.updateAsk(108780L, 0); // remove best ask

        assertThat(book.askDepth()).isEqualTo(1);
        assertThat(book.bestAsk()).isEqualTo(108790L);
    }

    @Test
    void removeBid_nonExistentPrice_depthUnchanged() {
        book.updateBid(108760L, 100);
        book.updateBid(999999L, 0); // doesn't exist

        assertThat(book.bidDepth()).isEqualTo(1);
    }

    // ── MAX_DEPTH enforcement ──────────────────────────────────────────────

    @Test
    void bids_atMaxDepth_discardsBeyondDepth() {
        for (int i = 0; i < OrderBook.MAX_DEPTH + 2; i++) {
            book.updateBid(100000L - i, 100 + i); // descending prices
        }

        assertThat(book.bidDepth()).isEqualTo(OrderBook.MAX_DEPTH);
        assertThat(book.bestBid()).isEqualTo(100000L);
    }

    @Test
    void asks_atMaxDepth_discardsBeyondDepth() {
        for (int i = 0; i < OrderBook.MAX_DEPTH + 2; i++) {
            book.updateAsk(100000L + i, 100 + i); // ascending prices
        }

        assertThat(book.askDepth()).isEqualTo(OrderBook.MAX_DEPTH);
        assertThat(book.bestAsk()).isEqualTo(100000L);
    }

    @Test
    void bids_worseThanMaxDepth_discarded() {
        // Fill 5 levels at high prices
        for (int i = 0; i < OrderBook.MAX_DEPTH; i++) {
            book.updateBid(200000L - i, 100); // 200000, 199999, ... 199996
        }
        // Try to insert a worse (lower) bid
        book.updateBid(100000L, 999);

        assertThat(book.bidDepth()).isEqualTo(OrderBook.MAX_DEPTH);
        // Worst level should still be 199996, not 100000
        assertThat(book.bidPrice(OrderBook.MAX_DEPTH - 1)).isEqualTo(200000L - (OrderBook.MAX_DEPTH - 1));
    }

    // ── Clear ──────────────────────────────────────────────────────────────

    @Test
    void clear_resetsDepthToZero() {
        book.updateBid(108760L, 100);
        book.updateAsk(108780L, 200);
        book.clear();

        assertThat(book.bidDepth()).isZero();
        assertThat(book.askDepth()).isZero();
        assertThat(book.bestBid()).isZero();
        assertThat(book.bestAsk()).isZero();
    }

    // ── Mixed bid/ask independence ─────────────────────────────────────────

    @Test
    void bidAndAskSides_areIndependent() {
        book.updateBid(108760L, 1_000_000);
        book.updateAsk(108780L, 500_000);

        assertThat(book.bidDepth()).isEqualTo(1);
        assertThat(book.askDepth()).isEqualTo(1);
        assertThat(book.bestBid()).isEqualTo(108760L);
        assertThat(book.bestAsk()).isEqualTo(108780L);
    }

    // ── Multiple updates maintain correct order ────────────────────────────

    @Test
    void bids_multipleUpdates_maintainCorrectOrder() {
        book.updateBid(108760L, 100);
        book.updateBid(108770L, 200);
        book.updateBid(108750L, 50);
        book.updateBid(108760L, 150); // update middle level

        assertThat(book.bidDepth()).isEqualTo(3);
        assertThat(book.bidPrice(0)).isEqualTo(108770L);
        assertThat(book.bidPrice(1)).isEqualTo(108760L);
        assertThat(book.bidSize(1)).isEqualTo(150);
        assertThat(book.bidPrice(2)).isEqualTo(108750L);
    }
}
