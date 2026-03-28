package com.nbrt.dtracing.simulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class FeedReplayTaskTest {

    @Test
    void decimalToMantissa_typicalFxPrice() {
        assertThat(FeedReplayTask.decimalToMantissa("1.08760")).isEqualTo(108760L);
    }

    @Test
    void decimalToMantissa_exactlyFiveDecimalPlaces() {
        assertThat(FeedReplayTask.decimalToMantissa("1.08765")).isEqualTo(108765L);
    }

    @Test
    void decimalToMantissa_fewerThanFiveDecimalPlaces_paddedWithZeros() {
        assertThat(FeedReplayTask.decimalToMantissa("1.1")).isEqualTo(110000L);
        assertThat(FeedReplayTask.decimalToMantissa("1.12")).isEqualTo(112000L);
        assertThat(FeedReplayTask.decimalToMantissa("1.123")).isEqualTo(112300L);
        assertThat(FeedReplayTask.decimalToMantissa("1.1234")).isEqualTo(112340L);
    }

    @Test
    void decimalToMantissa_moreThanFiveDecimalPlaces_truncated() {
        assertThat(FeedReplayTask.decimalToMantissa("1.123456")).isEqualTo(112345L);
        assertThat(FeedReplayTask.decimalToMantissa("1.087601234")).isEqualTo(108760L);
    }

    @Test
    void decimalToMantissa_noDecimalPoint_multipliedBy100000() {
        assertThat(FeedReplayTask.decimalToMantissa("108")).isEqualTo(10800000L);
        assertThat(FeedReplayTask.decimalToMantissa("1")).isEqualTo(100000L);
    }

    @Test
    void decimalToMantissa_zeroIntegerPart() {
        assertThat(FeedReplayTask.decimalToMantissa("0.09000")).isEqualTo(9000L);
        assertThat(FeedReplayTask.decimalToMantissa("0.00100")).isEqualTo(100L);
    }

    @Test
    void decimalToMantissa_roundNumberPrice() {
        assertThat(FeedReplayTask.decimalToMantissa("1.00000")).isEqualTo(100000L);
        assertThat(FeedReplayTask.decimalToMantissa("2.00000")).isEqualTo(200000L);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "1.08760,  108760",
        "1.30500,  130500",
        "150.000,  15000000",
        "0.75000,  75000",
        "1.23456,  123456"
    })
    void decimalToMantissa_parameterised(String price, long expectedMantissa) {
        assertThat(FeedReplayTask.decimalToMantissa(price.strip())).isEqualTo(expectedMantissa);
    }

    @Test
    void decimalToMantissa_allZeroFractionalPart() {
        assertThat(FeedReplayTask.decimalToMantissa("100.00000")).isEqualTo(10000000L);
    }
}
