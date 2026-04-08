package com.etradingpoc.dtracing.marketdatahandler;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "market-data.feed")
public record AeronFeedProperties(
        String ecn,
        String dir,
        String channel,
        int streamId
) {
    public AeronFeedProperties {
        if (ecn == null || ecn.isBlank()) ecn = "UNKNOWN";
        if (dir == null || dir.isBlank()) dir = "/dev/shm/aeron/driver";
        if (channel == null || channel.isBlank()) channel = "aeron:ipc";
        if (streamId <= 0) streamId = 3000;
    }
}
