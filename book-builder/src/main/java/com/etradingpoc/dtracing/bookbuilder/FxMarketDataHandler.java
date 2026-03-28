package com.etradingpoc.dtracing.bookbuilder;

import com.etradingpoc.dtracing.common.sbe.FxMarketDataDecoder;

@FunctionalInterface
public interface FxMarketDataHandler {
    void onMarketData(FxMarketDataDecoder decoder);
}