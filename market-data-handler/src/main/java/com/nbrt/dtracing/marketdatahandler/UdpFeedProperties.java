package com.nbrt.dtracing.marketdatahandler;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "market-data.udp")
public record UdpFeedProperties(
        String ecn,
        String bindAddress,
        int port,
        int bufferSize,
        String multicastGroup
) {
    public UdpFeedProperties {
        if (ecn == null || ecn.isBlank()) ecn = "UNKNOWN";
        if (bindAddress == null || bindAddress.isBlank()) bindAddress = "0.0.0.0";
        if (port <= 0) port = 9000;
        if (bufferSize <= 0) bufferSize = 1500;
        // multicastGroup may be null — unicast mode
    }

    public boolean isMulticast() {
        return multicastGroup != null && !multicastGroup.isBlank();
    }
}