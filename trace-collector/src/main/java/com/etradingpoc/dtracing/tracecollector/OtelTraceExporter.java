package com.etradingpoc.dtracing.tracecollector;

import com.etradingpoc.dtracing.common.sbe.SpanLinkDecoder;
import com.etradingpoc.dtracing.common.sbe.TraceSpanDecoder;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts decoded SBE {@code TraceSpan} messages into OTel {@link SpanData}
 * and exports them via OTLP gRPC to Tempo.
 * <p>
 * Batches spans and flushes periodically or when the batch is full.
 * {@code SpanLink} messages received before flush are resolved and attached
 * to their owning span at flush time.
 */
public class OtelTraceExporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OtelTraceExporter.class);

    private static final AttributeKey<String> ATTR_STAGE = AttributeKey.stringKey("pipeline.stage");
    private static final AttributeKey<String> ATTR_ECN = AttributeKey.stringKey("fx.ecn");
    private static final AttributeKey<String> ATTR_CCY_PAIR = AttributeKey.stringKey("fx.ccyPair");
    private static final AttributeKey<String> ATTR_SEQ_NUM = AttributeKey.stringKey("fx.sequenceNumber");
    private static final AttributeKey<Long> ATTR_TRACE_ID = AttributeKey.longKey("pipeline.traceId");
    private static final AttributeKey<Long> ATTR_LATENCY_NS = AttributeKey.longKey("pipeline.latencyNanos");

    private static final int BATCH_SIZE = 64;

    private final OtlpGrpcSpanExporter exporter;
    private final Resource resource;
    private final InstrumentationScopeInfo scopeInfo;

    /** Raw span data before link resolution — keyed by spanId for link attachment. */
    private final List<RawSpan> batch = new ArrayList<>(BATCH_SIZE);

    /** Buffered span links — keyed by owning spanId. */
    private final Map<Long, List<LinkData>> pendingLinks = new HashMap<>();

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
     * Convert a decoded TraceSpan to a raw span record and add to the batch.
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
                .put(ATTR_SEQ_NUM, Long.toUnsignedString(sequenceNumber))
                .put(ATTR_TRACE_ID, traceId)
                .put(ATTR_LATENCY_NS, timestampOut - timestampIn)
                .build();

        String spanName = stage + " " + ccyPair;

        batch.add(new RawSpan(traceId, spanId, parentSpanId, spanName,
                timestampIn, timestampOut, attrs));

        if (batch.size() >= BATCH_SIZE) {
            flush();
        }
    }

    /**
     * Buffer a span link. At flush time, links are attached to their owning span.
     */
    public void onSpanLink(SpanLinkDecoder decoder) {
        long spanId = decoder.spanId();
        long linkedTraceId = decoder.linkedTraceId();
        long linkedSpanId = decoder.linkedSpanId();

        String linkedTraceIdHex = String.format("%032x", linkedTraceId);
        String linkedSpanIdHex = String.format("%016x", linkedSpanId);

        SpanContext linkedCtx = SpanContext.create(
                linkedTraceIdHex, linkedSpanIdHex,
                TraceFlags.getSampled(), TraceState.getDefault());

        LinkData link = LinkData.create(linkedCtx);

        pendingLinks.computeIfAbsent(spanId, _ -> new ArrayList<>()).add(link);

        if (log.isDebugEnabled()) {
            log.debug("buffered spanLink ownerSpanId={} → linkedTraceId={} linkedSpanId={}",
                    String.format("%016x", spanId), linkedTraceIdHex, linkedSpanIdHex);
        }
    }

    /**
     * Flush any buffered spans to the OTLP exporter, resolving span links.
     */
    public void flush() {
        if (batch.isEmpty()) {
            pendingLinks.clear();
            return;
        }

        List<SpanData> resolved = new ArrayList<>(batch.size());
        for (RawSpan raw : batch) {
            List<LinkData> links = pendingLinks.getOrDefault(raw.spanId(), List.of());
            resolved.add(PipelineSpanData.create(
                    raw.traceId(), raw.spanId(), raw.parentSpanId(),
                    raw.name(), raw.startEpochNanos(), raw.endEpochNanos(),
                    raw.attributes(), resource, scopeInfo, links));
        }

        if (log.isDebugEnabled()) {
            for (SpanData span : resolved) {
                log.debug("exporting span traceId={} spanId={} name={} links={}",
                        span.getSpanContext().getTraceId(),
                        span.getSpanContext().getSpanId(),
                        span.getName(),
                        span.getLinks().size());
                for (LinkData link : span.getLinks()) {
                    log.debug("  link → traceId={} spanId={}",
                            link.getSpanContext().getTraceId(),
                            link.getSpanContext().getSpanId());
                }
            }
        }

        exportCount += resolved.size();
        exporter.export(resolved);
        batch.clear();
        pendingLinks.clear();
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

    private record RawSpan(
            long traceId, long spanId, long parentSpanId,
            String name, long startEpochNanos, long endEpochNanos,
            Attributes attributes) {}
}
