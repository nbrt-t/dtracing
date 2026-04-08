package com.etradingpoc.dtracing.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(
        List<FeedConfig> feeds,
        double speedMultiplier,
        List<Double> intensities,
        String aeronDir
) {
    public SimulatorProperties {
        if (feeds == null) feeds = List.of();
        if (speedMultiplier <= 0) speedMultiplier = 1.0;
        if (intensities == null || intensities.isEmpty()) intensities = List.of(speedMultiplier);
        if (aeronDir == null || aeronDir.isBlank()) aeronDir = "/dev/shm/aeron/driver";
    }

    public record FeedConfig(
            String ecn,
            String file,
            int streamId
    ) {
        public FeedConfig {
            if (streamId <= 0) streamId = 3000;
        }
    }
}