package com.etradingpoc.dtracing.bookbuilder;

import com.etradingpoc.dtracing.common.sbe.CcyPair;
import com.etradingpoc.dtracing.common.sbe.CompositeBookSnapshotEncoder;
import com.etradingpoc.dtracing.common.sbe.Ecn;
import com.etradingpoc.dtracing.common.sbe.MessageHeaderEncoder;
import io.aeron.Aeron;
import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * Publishes a single {@code CompositeBookSnapshot} message over Aeron IPC
 * to the MidPricer on every composite book rebuild.
 * <p>
 * The snapshot carries all 3 venue-level BBOs (positional by ECN ordinal),
 * replacing the previous per-level fan-out.
 * Pre-allocated buffer, zero allocation per publish.
 */
@Component
public class AeronCompositeBookPublisher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AeronCompositeBookPublisher.class);

    private static final int BUF_SIZE = MessageHeaderEncoder.ENCODED_LENGTH + CompositeBookSnapshotEncoder.BLOCK_LENGTH;
    private static final long LOG_SAMPLE_INTERVAL = 10_000;

    private final AeronPublisherProperties properties;

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUF_SIZE));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final CompositeBookSnapshotEncoder encoder = new CompositeBookSnapshotEncoder();

    private Aeron aeron;
    private Publication publication;
    private volatile boolean running;
    private long publishCount;
    private long dropCount;

    public AeronCompositeBookPublisher(AeronPublisherProperties properties) {
        this.properties = properties;

        headerEncoder.wrap(buffer, 0)
                .blockLength(CompositeBookSnapshotEncoder.BLOCK_LENGTH)
                .templateId(CompositeBookSnapshotEncoder.TEMPLATE_ID)
                .schemaId(CompositeBookSnapshotEncoder.SCHEMA_ID)
                .version(CompositeBookSnapshotEncoder.SCHEMA_VERSION);
    }

    @Override
    public void start() {
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(properties.dir()));
        publication = aeron.addPublication(properties.channel(), properties.streamId());
        running = true;
        log.info("Aeron CompositeBookSnapshot publisher started: channel={} stream={} dir={}",
                properties.channel(), properties.streamId(), properties.dir());
    }

    @Override
    public void stop() {
        running = false;
        if (publication != null) {
            publication.close();
        }
        if (aeron != null) {
            aeron.close();
        }
        log.info("Aeron CompositeBookSnapshot publisher stopped — published={} dropped={}", publishCount, dropCount);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return -10;
    }

    /**
     * Publish a single snapshot carrying all venue BBOs for a currency pair.
     * Each venue slot is positional: index 0 = EURONEXT, 1 = EBS, 2 = FENICS.
     */
    public void publishSnapshot(CcyPair ccyPair, VenueBook[] venues, Ecn triggeringEcn,
                                long traceId, long spanId, long sequenceNumber, long senderTimestampOut) {
        if (!running) {
            return;
        }

        encoder.wrap(buffer, MessageHeaderEncoder.ENCODED_LENGTH);
        encoder.ccyPair(ccyPair);
        encoder.triggeringEcn(triggeringEcn);

        // Slot 0 — EURONEXT
        encoder.venue0BidPrice().mantissa(venues[0].bidPrice());
        encoder.venue0BidSize(venues[0].bidSize());
        encoder.venue0AskPrice().mantissa(venues[0].askPrice());
        encoder.venue0AskSize(venues[0].askSize());

        // Slot 1 — EBS
        encoder.venue1BidPrice().mantissa(venues[1].bidPrice());
        encoder.venue1BidSize(venues[1].bidSize());
        encoder.venue1AskPrice().mantissa(venues[1].askPrice());
        encoder.venue1AskSize(venues[1].askSize());

        // Slot 2 — FENICS
        encoder.venue2BidPrice().mantissa(venues[2].bidPrice());
        encoder.venue2BidSize(venues[2].bidSize());
        encoder.venue2AskPrice().mantissa(venues[2].askPrice());
        encoder.venue2AskSize(venues[2].askSize());

        encoder.traceId(traceId);
        encoder.spanId(spanId);
        encoder.sequenceNumber(sequenceNumber);
        encoder.senderTimestampOut(senderTimestampOut);

        long result = publication.offer(buffer, 0, BUF_SIZE);
        if (result >= 0) {
            publishCount++;
        } else {
            dropCount++;
            if (log.isDebugEnabled() && dropCount % LOG_SAMPLE_INTERVAL == 0) {
                log.debug("Aeron CompositeBookSnapshot back-pressure: result={} dropped={}", result, dropCount);
            }
        }
    }
}
