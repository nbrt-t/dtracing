package com.nbrt.dtracing.pricetiering;

import com.nbrt.dtracing.common.sbe.MidPriceBookDecoder;

@FunctionalInterface
public interface MidPriceBookHandler {
    void onMidPriceBook(MidPriceBookDecoder decoder);
}