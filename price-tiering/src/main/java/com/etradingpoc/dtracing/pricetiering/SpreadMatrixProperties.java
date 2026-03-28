package com.etradingpoc.dtracing.pricetiering;

import com.etradingpoc.dtracing.common.sbe.CcyPair;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Configuration for the spread matrix applied to each tier.
 * <p>
 * Spreads are expressed as half-spread in Decimal5 mantissa (i.e. raw integer units).
 * For example, a half-spread of 10 means 0.00010 (1 pip for a 5-decimal pair like EURUSD).
 * <p>
 * Example configuration:
 * <pre>
 * price-tiering.spread.tier-count=4
 * price-tiering.spread.default-half-spreads=10,20,35,50
 * price-tiering.spread.pair-half-spreads.EURUSD=8,15,25,40
 * price-tiering.spread.pair-half-spreads.USDJPY=6,12,20,30
 * </pre>
 */
@ConfigurationProperties(prefix = "price-tiering.spread")
public record SpreadMatrixProperties(
        int tierCount,
        List<Long> defaultHalfSpreads,
        Map<String, List<Long>> pairHalfSpreads
) {
    public SpreadMatrixProperties {
        if (tierCount <= 0) tierCount = 4;
        if (defaultHalfSpreads == null || defaultHalfSpreads.isEmpty()) {
            defaultHalfSpreads = List.of(10L, 20L, 35L, 50L);
        }
        if (pairHalfSpreads == null) {
            pairHalfSpreads = Map.of();
        }
    }

    /**
     * Returns the half-spread array for a currency pair.
     * Uses pair-specific overrides if configured, otherwise the default.
     */
    public long[] halfSpreadsFor(CcyPair ccyPair) {
        var spreads = pairHalfSpreads.getOrDefault(ccyPair.name(), defaultHalfSpreads);
        long[] result = new long[tierCount];
        for (int i = 0; i < tierCount; i++) {
            result[i] = i < spreads.size() ? spreads.get(i) : spreads.getLast();
        }
        return result;
    }
}