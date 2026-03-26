package com.nbrt.dtracing.bookbuilder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AeronSubscriberProperties.class, AeronPublisherProperties.class, AeronTraceProperties.class})
public class BookBuilderApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookBuilderApplication.class, args);
    }

}