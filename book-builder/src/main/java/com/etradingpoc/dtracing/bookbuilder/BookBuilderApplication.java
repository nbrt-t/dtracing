package com.etradingpoc.dtracing.bookbuilder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AeronSubscriberProperties.class, AeronPublisherProperties.class, AeronTraceProperties.class, ConflationProperties.class})
public class BookBuilderApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookBuilderApplication.class, args);
    }

}