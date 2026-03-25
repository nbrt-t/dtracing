package com.nbrt.dtracing.marketdatahandler;

import com.nbrt.dtracing.common.sbe.FxFeedDeltaDecoder;

/**
 * Callback for decoded FX feed deltas arriving from the UDP transport.
 * Implementations receive the decoder flyweight positioned over the current
 * datagram — the buffer must not be retained beyond the scope of the call.
 */
@FunctionalInterface
public interface MarketDataDeltaHandler {

    void onDelta(FxFeedDeltaDecoder decoder);
}