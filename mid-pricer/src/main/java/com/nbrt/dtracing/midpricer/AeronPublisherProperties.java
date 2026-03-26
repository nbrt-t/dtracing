package com.nbrt.dtracing.midpricer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aeron.mid-price-book")
public record AeronPublisherProperties(
        String dir,
        String channel,
        int streamId
) {
    public AeronPublisherProperties {
        if (dir == null || dir.isBlank()) dir = "/dev/shm/aeron/driver";
        if (channel == null || channel.isBlank()) channel = "aeron:ipc";
        if (streamId <= 0) streamId = 1003;
    }
}