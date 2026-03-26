package com.nbrt.dtracing.common.tracing;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.Ecn;
import com.nbrt.dtracing.common.sbe.MessageHeaderEncoder;
import com.nbrt.dtracing.common.sbe.Stage;
import com.nbrt.dtracing.common.sbe.TraceSpanEncoder;
import io.aeron.Aeron;
import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;

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

    private static final int BUF_SIZE = MessageHeaderEncoder.ENCODED_LENGTH + TraceSpanEncoder.BLOCK_LENGTH;

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUF_SIZE));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final TraceSpanEncoder encoder = new TraceSpanEncoder();

    private final Aeron aeron;
    private final Publication publication;

    private long spanCounter;

    public TracePublisher(String aeronDir, String channel, int streamId) {
        this.aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
        this.publication = aeron.addPublication(channel, streamId);

        headerEncoder.wrap(buffer, 0)
                .blockLength(TraceSpanEncoder.BLOCK_LENGTH)
                .templateId(TraceSpanEncoder.TEMPLATE_ID)
                .schemaId(TraceSpanEncoder.SCHEMA_ID)
                .version(TraceSpanEncoder.SCHEMA_VERSION);
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
        long spanId = ++spanCounter;

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

        publication.offer(buffer, 0, BUF_SIZE);
        return spanId;
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