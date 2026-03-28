package com.etradingpoc.dtracing.bookbuilder;

import com.etradingpoc.dtracing.common.sbe.Ecn;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VenueBookTest {

    @Test
    void initialState_hasNoData() {
        var book = new VenueBook(Ecn.EBS);

        assertThat(book.hasData()).isFalse();
        assertThat(book.bidPrice()).isZero();
        assertThat(book.askPrice()).isZero();
        assertThat(book.ecn()).isEqualTo(Ecn.EBS);
    }

    @Test
    void update_storesAllFields() {
        var book = new VenueBook(Ecn.EURONEXT);
        book.update(1_000_000L, 108760L, 1_000_000, 108780L, 500_000);

        assertThat(book.timestamp()).isEqualTo(1_000_000L);
        assertThat(book.bidPrice()).isEqualTo(108760L);
        assertThat(book.bidSize()).isEqualTo(1_000_000);
        assertThat(book.askPrice()).isEqualTo(108780L);
        assertThat(book.askSize()).isEqualTo(500_000);
    }

    @Test
    void hasData_trueWhenBidPriceSet() {
        var book = new VenueBook(Ecn.EBS);
        book.update(0, 108760L, 0, 0, 0);

        assertThat(book.hasData()).isTrue();
    }

    @Test
    void hasData_trueWhenAskPriceSet() {
        var book = new VenueBook(Ecn.FENICS);
        book.update(0, 0, 0, 108780L, 0);

        assertThat(book.hasData()).isTrue();
    }

    @Test
    void hasData_falseWhenBothPricesZero() {
        var book = new VenueBook(Ecn.EBS);
        book.update(1_000_000L, 0, 100, 0, 200);

        assertThat(book.hasData()).isFalse();
    }

    @Test
    void ecn_unchangedAfterUpdate() {
        var book = new VenueBook(Ecn.FENICS);
        book.update(1L, 100L, 1, 200L, 1);

        assertThat(book.ecn()).isEqualTo(Ecn.FENICS);
    }

    @Test
    void update_overwritesPreviousValues() {
        var book = new VenueBook(Ecn.EBS);
        book.update(1L, 108760L, 100, 108780L, 200);
        book.update(2L, 108800L, 300, 108820L, 400);

        assertThat(book.timestamp()).isEqualTo(2L);
        assertThat(book.bidPrice()).isEqualTo(108800L);
        assertThat(book.bidSize()).isEqualTo(300);
        assertThat(book.askPrice()).isEqualTo(108820L);
        assertThat(book.askSize()).isEqualTo(400);
    }
}
