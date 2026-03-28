package com.etradingpoc.dtracing.marketdatahandler;

import com.etradingpoc.dtracing.common.sbe.Stage;
import com.etradingpoc.dtracing.common.tracing.TracePublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TraceConfig {

    @Bean(destroyMethod = "close")
    TracePublisher tracePublisher(AeronTraceProperties properties) {
        return new TracePublisher(properties.dir(), properties.channel(), properties.streamId(), Stage.MDH_RECEIVE);
    }
}