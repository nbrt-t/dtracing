package com.etradingpoc.dtracing.pricetiering;

import com.etradingpoc.dtracing.common.sbe.MidPriceBookDecoder;

@FunctionalInterface
public interface MidPriceBookHandler {
    void onMidPriceBook(MidPriceBookDecoder decoder);
}