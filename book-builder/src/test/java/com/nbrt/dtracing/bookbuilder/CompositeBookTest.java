package com.nbrt.dtracing.bookbuilder;

import com.nbrt.dtracing.common.sbe.Ecn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeBookTest {

    private CompositeBook composite;

    @BeforeEach
    void setUp() {
        composite = new CompositeBook();
    }

    private static VenueBook venueWith(Ecn ecn, long bid, int bidSize, long ask, int askSize) {
        var v = new VenueBook(ecn);
        v.update(1L, bid, bidSize, ask, askSize);
        return v;
    }

    private static VenueBook emptyVenue(Ecn ecn) {
        return new VenueBook(ecn); // no update — hasData() = false
    }

    // ── Empty venues ───────────────────────────────────────────────────────

    @Test
    void rebuild_allEmptyVenues_zeroDepth() {
        VenueBook[] venues = {emptyVenue(Ecn.EURONEXT), emptyVenue(Ecn.EBS), emptyVenue(Ecn.FENICS)};
        composite.rebuild(venues);

        assertThat(composite.bidDepth()).isZero();
        assertThat(composite.askDepth()).isZero();
        assertThat(composite.bestBid()).isZero();
        assertThat(composite.bestAsk()).isZero();
    }

    // ── Single venue ───────────────────────────────────────────────────────

    @Test
    void rebuild_singleVenue_depthIsOne() {
        VenueBook[] venues = {
            venueWith(Ecn.EURONEXT, 108760L, 100, 108780L, 200),
            emptyVenue(Ecn.EBS),
            emptyVenue(Ecn.FENICS)
        };
        composite.rebuild(venues);

        assertThat(composite.bidDepth()).isEqualTo(1);
        assertThat(composite.askDepth()).isEqualTo(1);
        assertThat(composite.bestBid()).isEqualTo(108760L);
        assertThat(composite.bestAsk()).isEqualTo(108780L);
        assertThat(composite.bestBidEcn()).isEqualTo(Ecn.EURONEXT);
        assertThat(composite.bestAskEcn()).isEqualTo(Ecn.EURONEXT);
    }

    // ── Bid side — best (highest) price first ─────────────────────────────

    @Test
    void rebuild_bids_sortedDescending_highestFirst() {
        VenueBook[] venues = {
            venueWith(Ecn.EURONEXT, 108750L, 100, 108790L, 100),
            venueWith(Ecn.EBS,      108770L, 200, 108800L, 200),
            venueWith(Ecn.FENICS,   108760L, 300, 108785L, 300)
        };
        composite.rebuild(venues);

        assertThat(composite.bidDepth()).isEqualTo(3);
        assertThat(composite.bidPrice(0)).isEqualTo(108770L);
        assertThat(composite.bidEcn(0)).isEqualTo(Ecn.EBS);
        assertThat(composite.bidPrice(1)).isEqualTo(108760L);
        assertThat(composite.bidEcn(1)).isEqualTo(Ecn.FENICS);
        assertThat(composite.bidPrice(2)).isEqualTo(108750L);
        assertThat(composite.bidEcn(2)).isEqualTo(Ecn.EURONEXT);
    }

    // ── Ask side — best (lowest) price first ──────────────────────────────

    @Test
    void rebuild_asks_sortedAscending_lowestFirst() {
        VenueBook[] venues = {
            venueWith(Ecn.EURONEXT, 108750L, 100, 108795L, 100),
            venueWith(Ecn.EBS,      108770L, 200, 108780L, 200),
            venueWith(Ecn.FENICS,   108760L, 300, 108790L, 300)
        };
        composite.rebuild(venues);

        assertThat(composite.askDepth()).isEqualTo(3);
        assertThat(composite.askPrice(0)).isEqualTo(108780L);
        assertThat(composite.askEcn(0)).isEqualTo(Ecn.EBS);
        assertThat(composite.askPrice(1)).isEqualTo(108790L);
        assertThat(composite.askEcn(1)).isEqualTo(Ecn.FENICS);
        assertThat(composite.askPrice(2)).isEqualTo(108795L);
        assertThat(composite.askEcn(2)).isEqualTo(Ecn.EURONEXT);
    }

    // ── Partial venues (some without data) ────────────────────────────────

    @Test
    void rebuild_twoVenuesWithData_depthIsTwo() {
        VenueBook[] venues = {
            venueWith(Ecn.EURONEXT, 108760L, 100, 108780L, 100),
            emptyVenue(Ecn.EBS),
            venueWith(Ecn.FENICS, 108755L, 200, 108785L, 200)
        };
        composite.rebuild(venues);

        assertThat(composite.bidDepth()).isEqualTo(2);
        assertThat(composite.askDepth()).isEqualTo(2);
    }

    // ── Rebuild resets previous state ─────────────────────────────────────

    @Test
    void rebuild_clearsStaleDataFromPreviousBuild() {
        VenueBook[] threeVenues = {
            venueWith(Ecn.EURONEXT, 108760L, 100, 108780L, 100),
            venueWith(Ecn.EBS, 108755L, 200, 108785L, 200),
            venueWith(Ecn.FENICS, 108750L, 300, 108790L, 300)
        };
        composite.rebuild(threeVenues);
        assertThat(composite.bidDepth()).isEqualTo(3);

        VenueBook[] oneVenue = {
            venueWith(Ecn.EURONEXT, 108760L, 100, 108780L, 100),
            emptyVenue(Ecn.EBS),
            emptyVenue(Ecn.FENICS)
        };
        composite.rebuild(oneVenue);

        assertThat(composite.bidDepth()).isEqualTo(1);
        assertThat(composite.askDepth()).isEqualTo(1);
    }

    // ── Venue with bid but no ask, and vice versa ──────────────────────────

    @Test
    void rebuild_venueWithBidOnlyContributesToBidSide() {
        var bidOnly = new VenueBook(Ecn.EBS);
        bidOnly.update(1L, 108760L, 100, 0, 0); // bid > 0, ask = 0

        VenueBook[] venues = {emptyVenue(Ecn.EURONEXT), bidOnly, emptyVenue(Ecn.FENICS)};
        composite.rebuild(venues);

        assertThat(composite.bidDepth()).isEqualTo(1);
        assertThat(composite.askDepth()).isZero();
    }

    @Test
    void bestBid_returnsZeroWhenNoBids() {
        VenueBook[] venues = {
            venueWith(Ecn.EBS, 0, 0, 108780L, 100),
            emptyVenue(Ecn.EURONEXT),
            emptyVenue(Ecn.FENICS)
        };
        composite.rebuild(venues);

        assertThat(composite.bestBid()).isZero();
        assertThat(composite.bestBidEcn()).isNull();
    }
}
