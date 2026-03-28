package com.etradingpoc.dtracing.pricetiering;

import com.etradingpoc.dtracing.common.sbe.CcyPair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpreadMatrixPropertiesTest {

    @Test
    void defaults_usedWhenNullInputs() {
        var props = new SpreadMatrixProperties(0, null, null);

        assertThat(props.tierCount()).isEqualTo(4);
        assertThat(props.defaultHalfSpreads()).containsExactly(10L, 20L, 35L, 50L);
        assertThat(props.pairHalfSpreads()).isEmpty();
    }

    @Test
    void defaults_usedWhenEmptyHalfSpreads() {
        var props = new SpreadMatrixProperties(4, List.of(), null);

        assertThat(props.defaultHalfSpreads()).containsExactly(10L, 20L, 35L, 50L);
    }

    @Test
    void tierCount_fixedToFour_whenZeroOrNegative() {
        assertThat(new SpreadMatrixProperties(0, null, null).tierCount()).isEqualTo(4);
        assertThat(new SpreadMatrixProperties(-1, null, null).tierCount()).isEqualTo(4);
    }

    @Test
    void halfSpreadsFor_usesDefaultWhenNoPairOverride() {
        var props = new SpreadMatrixProperties(4, List.of(10L, 20L, 35L, 50L), Map.of());

        long[] spreads = props.halfSpreadsFor(CcyPair.GBPJPY);

        assertThat(spreads).containsExactly(10L, 20L, 35L, 50L);
    }

    @Test
    void halfSpreadsFor_usesOverrideWhenPairOverrideExists() {
        var overrides = Map.of("EURUSD", List.of(8L, 15L, 25L, 40L));
        var props = new SpreadMatrixProperties(4, List.of(10L, 20L, 35L, 50L), overrides);

        long[] eurusd = props.halfSpreadsFor(CcyPair.EURUSD);
        long[] gbpusd = props.halfSpreadsFor(CcyPair.GBPUSD);

        assertThat(eurusd).containsExactly(8L, 15L, 25L, 40L);
        assertThat(gbpusd).containsExactly(10L, 20L, 35L, 50L); // falls back to default
    }

    @Test
    void halfSpreadsFor_paddedWithLastValue_whenFewerOverridesThanTiers() {
        // Only 2 values for 4 tiers — last value should fill remaining tiers
        var overrides = Map.of("USDJPY", List.of(6L, 12L));
        var props = new SpreadMatrixProperties(4, List.of(10L, 20L, 35L, 50L), overrides);

        long[] spreads = props.halfSpreadsFor(CcyPair.USDJPY);

        assertThat(spreads).containsExactly(6L, 12L, 12L, 12L);
    }

    @Test
    void halfSpreadsFor_truncatesWhenMoreOverridesThanTiers() {
        var overrides = Map.of("EURGBP", List.of(5L, 10L, 15L, 20L, 25L, 30L));
        var props = new SpreadMatrixProperties(4, List.of(10L, 20L, 35L, 50L), overrides);

        long[] spreads = props.halfSpreadsFor(CcyPair.EURGBP);

        assertThat(spreads).hasSize(4);
        assertThat(spreads).containsExactly(5L, 10L, 15L, 20L);
    }

    @Test
    void halfSpreadsFor_returnedArrayHasCorrectLength() {
        var props = new SpreadMatrixProperties(4, List.of(10L, 20L, 35L, 50L), Map.of());

        for (var ccyPair : CcyPair.values()) {
            if (ccyPair == CcyPair.NULL_VAL) continue;
            assertThat(props.halfSpreadsFor(ccyPair)).hasSize(4);
        }
    }
}
