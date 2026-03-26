package com.nbrt.dtracing.tracecollector;

import com.nbrt.dtracing.common.sbe.TraceSpanDecoder;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts decoded SBE {@code TraceSpan} messages into OTel {@link SpanData}
 * and exports them via OTLP gRPC to Tempo.
 * <p>
 * Batches spans and flushes periodically or when the batch is full.
 */
public class OtelTraceExporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OtelTraceExporter.class);

    private static final AttributeKey<String> ATTR_STAGE = AttributeKey.stringKey("pipeline.stage");
    private static final AttributeKey<String> ATTR_ECN = AttributeKey.stringKey("fx.ecn");
    private static final AttributeKey<String> ATTR_CCY_PAIR = AttributeKey.stringKey("fx.ccyPair");
    private static final AttributeKey<Long> ATTR_SEQ_NUM = AttributeKey.longKey("fx.sequenceNumber");
    private static final AttributeKey<Long> ATTR_TRACE_ID = AttributeKey.longKey("pipeline.traceId");
    private static final AttributeKey<Long> ATTR_LATENCY_NS = AttributeKey.longKey("pipeline.latencyNanos");

    private static final int BATCH_SIZE = 64;

    private final OtlpGrpcSpanExporter exporter;
    private final Resource resource;
    private final InstrumentationScopeInfo scopeInfo;
    private final List<SpanData> batch = new ArrayList<>(BATCH_SIZE);

    private long exportCount;

    public OtelTraceExporter(String otlpEndpoint) {
        this.exporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .build();

        this.resource = Resource.builder()
                .put("service.name", "dtracing-pipeline")
                .put("service.namespace", "dtracing")
                .build();

        this.scopeInfo = InstrumentationScopeInfo.builder("dtracing.trace-collector")
                .setVersion("0.0.1")
                .build();

        log.info("OTel trace exporter initialised — endpoint={}", otlpEndpoint);
    }

    /**
     * Convert a decoded TraceSpan to OTel SpanData and add to the batch.
     * Flushes when the batch is full.
     */
    public void onTraceSpan(TraceSpanDecoder decoder) {
        long traceId = decoder.traceId();
        long spanId = decoder.spanId();
        long parentSpanId = decoder.parentSpanId();
        String stage = decoder.stage().name();
        String ecn = decoder.ecn().name();
        String ccyPair = decoder.ccyPair().name();
        long sequenceNumber = decoder.sequenceNumber();
        long timestampIn = decoder.timestampIn();
        long timestampOut = decoder.timestampOut();

        Attributes attrs = Attributes.builder()
                .put(ATTR_STAGE, stage)
                .put(ATTR_ECN, ecn)
                .put(ATTR_CCY_PAIR, ccyPair)
                .put(ATTR_SEQ_NUM, sequenceNumber)
                .put(ATTR_TRACE_ID, traceId)
                .put(ATTR_LATENCY_NS, timestampOut - timestampIn)
                .build();

        String spanName = stage + " " + ccyPair;

        SpanData spanData = PipelineSpanData.create(
                traceId, spanId, parentSpanId,
                spanName, timestampIn, timestampOut,
                attrs, resource, scopeInfo);

        batch.add(spanData);

        if (batch.size() >= BATCH_SIZE) {
            flush();
        }
    }

    /**
     * Flush any buffered spans to the OTLP exporter.
     */
    public void flush() {
        if (batch.isEmpty()) {
            return;
        }
        exportCount += batch.size();
        exporter.export(List.copyOf(batch));
        batch.clear();
    }

    public long exportCount() {
        return exportCount;
    }

    @Override
    public void close() {
        flush();
        exporter.close();
        log.info("OTel trace exporter closed — total exported={}", exportCount);
    }
}