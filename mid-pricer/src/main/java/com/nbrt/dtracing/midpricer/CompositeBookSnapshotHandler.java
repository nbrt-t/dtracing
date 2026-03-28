package com.nbrt.dtracing.midpricer;

import com.nbrt.dtracing.common.sbe.CompositeBookSnapshotDecoder;

@FunctionalInterface
public interface CompositeBookSnapshotHandler {
    void onCompositeBookSnapshot(CompositeBookSnapshotDecoder decoder);
}
