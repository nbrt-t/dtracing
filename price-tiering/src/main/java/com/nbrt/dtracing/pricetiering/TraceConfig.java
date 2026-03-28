package com.nbrt.dtracing.pricetiering;

import com.nbrt.dtracing.common.sbe.Stage;
import com.nbrt.dtracing.common.tracing.TracePublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TraceConfig {

    @Bean(destroyMethod = "close")
    TracePublisher tracePublisher(AeronTraceProperties properties) {
        return new TracePublisher(properties.dir(), properties.channel(), properties.streamId(), Stage.PRICE_TIER);
    }
}