package com.nbrt.dtracing.midpricer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AeronSubscriberProperties.class, AeronPublisherProperties.class})
public class MidPricerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MidPricerApplication.class, args);
    }

}