package com.etradingpoc.dtracing.bookbuilder;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "book-builder.conflation")
public record ConflationProperties(long windowMs) {
    public ConflationProperties {
        if (windowMs < 0) windowMs = 10;
    }
}
