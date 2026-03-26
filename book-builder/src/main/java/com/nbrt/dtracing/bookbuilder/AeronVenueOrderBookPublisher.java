package com.nbrt.dtracing.bookbuilder;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.MessageHeaderEncoder;
import com.nbrt.dtracing.common.sbe.VenueOrderBookEncoder;
import io.aeron.Aeron;
import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * Publishes {@code VenueOrderBook} messages over Aeron IPC to the MidPricer.
 * <p>
 * After each composite book rebuild, one message is published per level
 * (bid and ask sides). Pre-allocated buffer, zero allocation per publish.
 */
@Component
public class AeronVenueOrderBookPublisher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AeronVenueOrderBookPublisher.class);

    private static final int BUF_SIZE = MessageHeaderEncoder.ENCODED_LENGTH + VenueOrderBookEncoder.BLOCK_LENGTH;
    private static final long LOG_SAMPLE_INTERVAL = 10_000;

    private final AeronPublisherProperties properties;

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUF_SIZE));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final VenueOrderBookEncoder encoder = new VenueOrderBookEncoder();

    private Aeron aeron;
    private Publication publication;
    private volatile boolean running;
    private long publishCount;
    private long dropCount;

    public AeronVenueOrderBookPublisher(AeronPublisherProperties properties) {
        this.properties = properties;

        headerEncoder.wrap(buffer, 0)
                .blockLength(VenueOrderBookEncoder.BLOCK_LENGTH)
                .templateId(VenueOrderBookEncoder.TEMPLATE_ID)
                .schemaId(VenueOrderBookEncoder.SCHEMA_ID)
                .version(VenueOrderBookEncoder.SCHEMA_VERSION);
    }

    @Override
    public void start() {
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(properties.dir()));
        publication = aeron.addPublication(properties.channel(), properties.streamId());
        running = true;
        log.info("Aeron VenueOrderBook publisher started: channel={} stream={} dir={}",
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
        log.info("Aeron VenueOrderBook publisher stopped — published={} dropped={}", publishCount, dropCount);
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
     * Publish all levels of the composite book for a currency pair.
     * Each level becomes one VenueOrderBook message.
     */
    public void publishComposite(CcyPair ccyPair, CompositeBook composite) {
        if (!running) {
            return;
        }

        // Publish bid levels
        for (int i = 0; i < composite.bidDepth(); i++) {
            offer(composite.bidEcn(i), ccyPair, composite.bidPrice(i), 0, composite.bidSize(i));
        }

        // Publish ask levels
        for (int i = 0; i < composite.askDepth(); i++) {
            offer(composite.askEcn(i), ccyPair, composite.askPrice(i), composite.askSize(i), 0);
        }
    }

    private void offer(com.nbrt.dtracing.common.sbe.Ecn ecn, CcyPair ccyPair,
                       long rateMantissa, int askSize, int bidSize) {
        encoder.wrap(buffer, MessageHeaderEncoder.ENCODED_LENGTH);
        encoder.ecn(ecn);
        encoder.ccyPair(ccyPair);
        encoder.rate().mantissa(rateMantissa);
        encoder.askSize(askSize);
        encoder.bidSize(bidSize);

        long result = publication.offer(buffer, 0, BUF_SIZE);
        if (result >= 0) {
            publishCount++;
        } else {
            dropCount++;
            if (log.isDebugEnabled() && dropCount % LOG_SAMPLE_INTERVAL == 0) {
                log.debug("Aeron VenueOrderBook back-pressure: result={} dropped={}", result, dropCount);
            }
        }
    }
}