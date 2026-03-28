package com.etradingpoc.dtracing.midpricer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aeron.venue-order-book")
public record AeronSubscriberProperties(
        String dir,
        String channel,
        int streamId
) {
    public AeronSubscriberProperties {
        if (dir == null || dir.isBlank()) dir = "/dev/shm/aeron/driver";
        if (channel == null || channel.isBlank()) channel = "aeron:ipc";
        if (streamId <= 0) streamId = 1002;
    }
}