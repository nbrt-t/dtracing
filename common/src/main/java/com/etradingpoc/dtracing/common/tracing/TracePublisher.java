package com.etradingpoc.dtracing.common.tracing;

import com.etradingpoc.dtracing.common.sbe.CcyPair;
import com.etradingpoc.dtracing.common.sbe.Ecn;
import com.etradingpoc.dtracing.common.sbe.MessageHeaderEncoder;
import com.etradingpoc.dtracing.common.sbe.SpanLinkEncoder;
import com.etradingpoc.dtracing.common.sbe.Stage;
import com.etradingpoc.dtracing.common.sbe.TraceSpanEncoder;
import io.aeron.Aeron;
import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * Publishes {@code TraceSpan} messages to a dedicated Aeron trace channel.
 * <p>
 * Each pipeline stage creates one instance. The publisher pre-allocates a single
 * direct buffer and encodes in-place — zero allocation per span.
 * <p>
 * Not a Spring component — services create it as a bean and manage its lifecycle.
 */
public class TracePublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TracePublisher.class);
    private static final int SPAN_BUF_SIZE = MessageHeaderEncoder.ENCODED_LENGTH + TraceSpanEncoder.BLOCK_LENGTH;
    private static final int LINK_BUF_SIZE = MessageHeaderEncoder.ENCODED_LENGTH + SpanLinkEncoder.BLOCK_LENGTH;

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(SPAN_BUF_SIZE));
    private final UnsafeBuffer linkBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(LINK_BUF_SIZE));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final TraceSpanEncoder encoder = new TraceSpanEncoder();
    private final SpanLinkEncoder linkEncoder = new SpanLinkEncoder();

    private final Aeron aeron;
    private final Publication publication;

    /** High bits encode the stage ordinal so span IDs are unique across services. */
    private final long spanIdPrefix;
    private long spanCounter;

    public TracePublisher(String aeronDir, String channel, int streamId, Stage stage) {
        this.aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
        this.publication = aeron.addPublication(channel, streamId);
        this.spanIdPrefix = (long) stage.value() << 48;
        initHeaders();
    }

    /** Package-private for testing — bypasses Aeron connection. */
    TracePublisher(Aeron aeron, Publication publication, Stage stage) {
        this.aeron = aeron;
        this.publication = publication;
        this.spanIdPrefix = (long) stage.value() << 48;
        initHeaders();
    }

    private void initHeaders() {
        headerEncoder.wrap(buffer, 0)
                .blockLength(TraceSpanEncoder.BLOCK_LENGTH)
                .templateId(TraceSpanEncoder.TEMPLATE_ID)
                .schemaId(TraceSpanEncoder.SCHEMA_ID)
                .version(TraceSpanEncoder.SCHEMA_VERSION);

        headerEncoder.wrap(linkBuffer, 0)
                .blockLength(SpanLinkEncoder.BLOCK_LENGTH)
                .templateId(SpanLinkEncoder.TEMPLATE_ID)
                .schemaId(SpanLinkEncoder.SCHEMA_ID)
                .version(SpanLinkEncoder.SCHEMA_VERSION);
    }

    /**
     * Publish a trace span. Call after processing is complete.
     *
     * @param traceId        trace correlation ID (flows through the pipeline)
     * @param parentSpanId   span ID from the upstream stage (0 for root)
     * @param stage          pipeline stage enum
     * @param ecn            source ECN
     * @param ccyPair        currency pair
     * @param sequenceNumber original feed sequence number
     * @param timestampIn    nanosecond wall-clock time when message was received
     * @param timestampOut   nanosecond wall-clock time when processing completed
     * @return the spanId generated for this span
     */
    public long publishSpan(long traceId, long parentSpanId, Stage stage,
                            Ecn ecn, CcyPair ccyPair, long sequenceNumber,
                            long timestampIn, long timestampOut) {
        return publishSpan(traceId, parentSpanId, stage, ecn, ccyPair, sequenceNumber,
                timestampIn, timestampOut, 0);
    }

    /**
     * Publish a trace span with an explicit held-tick count (CONFLATION_WAIT spans).
     *
     * @param heldTicks number of ticks suppressed during this conflation window
     */
    public long publishSpan(long traceId, long parentSpanId, Stage stage,
                            Ecn ecn, CcyPair ccyPair, long sequenceNumber,
                            long timestampIn, long timestampOut, int heldTicks) {
        long spanId = spanIdPrefix | ++spanCounter;

        encoder.wrap(buffer, MessageHeaderEncoder.ENCODED_LENGTH);
        encoder.traceId(traceId);
        encoder.spanId(spanId);
        encoder.parentSpanId(parentSpanId);
        encoder.stage(stage);
        encoder.ecn(ecn);
        encoder.ccyPair(ccyPair);
        encoder.sequenceNumber(sequenceNumber);
        encoder.timestampIn(timestampIn);
        encoder.timestampOut(timestampOut);
        encoder.heldTicks(heldTicks);

        if (log.isDebugEnabled()) {
            log.debug("traceId={} spanId={} parentSpanId={} stage={} ecn={} ccyPair={} seq={} in={} out={} heldTicks={}",
                    traceId, spanId, parentSpanId, stage, ecn, ccyPair, sequenceNumber, timestampIn, timestampOut, heldTicks);
        }

        publication.offer(buffer, 0, SPAN_BUF_SIZE);
        return spanId;
    }

    /**
     * Publish a span link connecting a span to a causally-related span.
     * Used by conflation to link the published snapshot's span to each
     * suppressed tick's span.
     *
     * @param traceId       trace ID of the span that owns this link
     * @param spanId        span ID that owns this link (the published span)
     * @param linkedTraceId trace ID of the linked (conflated) span
     * @param linkedSpanId  span ID of the linked (conflated) span
     * @param ccyPair       currency pair
     */
    public void publishSpanLink(long traceId, long spanId,
                                long linkedTraceId, long linkedSpanId,
                                CcyPair ccyPair) {
        linkEncoder.wrap(linkBuffer, MessageHeaderEncoder.ENCODED_LENGTH);
        linkEncoder.traceId(traceId);
        linkEncoder.spanId(spanId);
        linkEncoder.linkedTraceId(linkedTraceId);
        linkEncoder.linkedSpanId(linkedSpanId);
        linkEncoder.ccyPair(ccyPair);

        if (log.isDebugEnabled()) {
            log.debug("spanLink traceId={} spanId={} → linkedTraceId={} linkedSpanId={} ccyPair={}",
                    traceId, spanId, linkedTraceId, linkedSpanId, ccyPair);
        }

        publication.offer(linkBuffer, 0, LINK_BUF_SIZE);
    }

    /**
     * Returns current wall-clock time as nanoseconds since Unix epoch.
     */
    public static long epochNanosNow() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    @Override
    public void close() {
        publication.close();
        aeron.close();
    }
}