package com.nbrt.dtracing.marketdatahandler;

import com.nbrt.dtracing.common.sbe.FxFeedDeltaDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MarketDataDeltaProcessor implements MarketDataDeltaHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataDeltaProcessor.class);

    private static final double DECIMAL5_SCALE = 1e-5;
    private static final long LOG_SAMPLE_INTERVAL = 10_000;

    private final String ecn;
    private long messageCount;

    public MarketDataDeltaProcessor(UdpFeedProperties properties) {
        this.ecn = properties.ecn();
    }

    @Override
    public void onDelta(FxFeedDeltaDecoder decoder) {
        messageCount++;

        // Hot path — only log sampled messages to avoid per-message overhead
        if (log.isDebugEnabled() && messageCount % LOG_SAMPLE_INTERVAL == 0) {
            log.debug("[{}] DELTA seq={} {} ts={} bid={}/{} ask={}/{} (count={})",
                    ecn,
                    decoder.sequenceNumber(),
                    decoder.ccyPair(),
                    decoder.timestamp(),
                    decoder.bidPrice().mantissa(),
                    decoder.bidSize(),
                    decoder.askPrice().mantissa(),
                    decoder.askSize(),
                    messageCount);
        }
    }
}