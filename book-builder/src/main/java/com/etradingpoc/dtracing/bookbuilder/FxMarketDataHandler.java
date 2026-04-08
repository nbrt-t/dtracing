package com.etradingpoc.dtracing.bookbuilder;

import com.etradingpoc.dtracing.common.sbe.FxMarketDataDecoder;

public interface FxMarketDataHandler {
    void onMarketData(FxMarketDataDecoder decoder);

    default void flushDirtyPairs() {}
}