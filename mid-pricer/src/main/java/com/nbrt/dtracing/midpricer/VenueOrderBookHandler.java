package com.nbrt.dtracing.midpricer;

import com.nbrt.dtracing.common.sbe.VenueOrderBookDecoder;

@FunctionalInterface
public interface VenueOrderBookHandler {
    void onVenueOrderBook(VenueOrderBookDecoder decoder);
}