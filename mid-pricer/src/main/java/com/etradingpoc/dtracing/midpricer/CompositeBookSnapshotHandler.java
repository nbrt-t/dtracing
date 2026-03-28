package com.etradingpoc.dtracing.midpricer;

import com.etradingpoc.dtracing.common.sbe.CompositeBookSnapshotDecoder;

@FunctionalInterface
public interface CompositeBookSnapshotHandler {
    void onCompositeBookSnapshot(CompositeBookSnapshotDecoder decoder);
}
