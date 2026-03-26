package com.nbrt.dtracing.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(
        List<FeedConfig> feeds,
        double speedMultiplier
) {
    public SimulatorProperties {
        if (feeds == null) feeds = List.of();
        if (speedMultiplier <= 0) speedMultiplier = 1.0;
    }

    public record FeedConfig(
            String ecn,
            String file,
            String targetHost,
            int targetPort
    ) {
        public FeedConfig {
            if (targetHost == null || targetHost.isBlank()) targetHost = "localhost";
        }
    }
}