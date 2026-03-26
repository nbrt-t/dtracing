package com.nbrt.dtracing.marketdatahandler;

import com.nbrt.dtracing.common.sbe.FxFeedDeltaDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MarketDataDeltaProcessor implements MarketDataDeltaHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataDeltaProcessor.class);

    private static final double DECIMAL5_SCALE = 1e-5;

    private final String ecn;

    public MarketDataDeltaProcessor(UdpFeedProperties properties) {
        this.ecn = properties.ecn();
    }

    @Override
    public void onDelta(FxFeedDeltaDecoder decoder) {
        var ccyPair  = decoder.ccyPair();
        var ts       = decoder.timestamp();
        var bidPrice = decoder.bidPrice().mantissa() * DECIMAL5_SCALE;
        var bidSize  = decoder.bidSize();
        var askPrice = decoder.askPrice().mantissa() * DECIMAL5_SCALE;
        var askSize  = decoder.askSize();
        var seq      = decoder.sequenceNumber();

        log.info("[{}] DELTA seq={} {} ts={} bid={}/{} ask={}/{}",
                ecn, seq, ccyPair, ts,
                String.format("%.5f", bidPrice), bidSize,
                String.format("%.5f", askPrice), askSize);
    }
}