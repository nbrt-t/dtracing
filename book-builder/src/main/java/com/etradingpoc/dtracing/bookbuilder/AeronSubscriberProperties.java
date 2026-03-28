package com.etradingpoc.dtracing.bookbuilder;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aeron.market-data")
public record AeronSubscriberProperties(
        String dir,
        String channel,
        int streamId
) {
    public AeronSubscriberProperties {
        if (dir == null || dir.isBlank()) dir = "/dev/shm/aeron/driver";
        if (channel == null || channel.isBlank()) channel = "aeron:ipc";
        if (streamId <= 0) streamId = 1001;
    }
}