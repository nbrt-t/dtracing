package com.nbrt.dtracing.marketdatahandler;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aeron.trace")
public record AeronTraceProperties(
        String dir,
        String channel,
        int streamId
) {
    public AeronTraceProperties {
        if (dir == null || dir.isBlank()) dir = "/dev/shm/aeron/driver";
        if (channel == null || channel.isBlank()) channel = "aeron:ipc";
        if (streamId <= 0) streamId = 2001;
    }
}