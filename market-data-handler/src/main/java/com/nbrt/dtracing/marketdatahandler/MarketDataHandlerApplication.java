package com.nbrt.dtracing.marketdatahandler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({UdpFeedProperties.class, AeronPublisherProperties.class, AeronTraceProperties.class})
public class MarketDataHandlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketDataHandlerApplication.class, args);
    }

}