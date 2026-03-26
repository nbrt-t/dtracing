package com.nbrt.dtracing.bookbuilder;

import com.nbrt.dtracing.common.sbe.FxMarketDataDecoder;

@FunctionalInterface
public interface FxMarketDataHandler {
    void onMarketData(FxMarketDataDecoder decoder);
}