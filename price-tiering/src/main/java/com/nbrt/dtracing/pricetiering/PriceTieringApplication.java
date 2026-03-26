package com.nbrt.dtracing.pricetiering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AeronSubscriberProperties.class, SpreadMatrixProperties.class})
public class PriceTieringApplication {

    public static void main(String[] args) {
        SpringApplication.run(PriceTieringApplication.class, args);
    }

}