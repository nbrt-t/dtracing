package com.nbrt.dtracing.midpricer;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.MessageHeaderEncoder;
import com.nbrt.dtracing.common.sbe.MidPriceBookEncoder;
import io.aeron.Aeron;
import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * Publishes {@code MidPriceBook} messages over Aeron IPC to the PriceTiering engine.
 * Pre-allocated buffer, zero allocation per publish.
 */
@Component
public class AeronMidPriceBookPublisher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AeronMidPriceBookPublisher.class);

    private static final int BUF_SIZE = MessageHeaderEncoder.ENCODED_LENGTH + MidPriceBookEncoder.BLOCK_LENGTH;
    private static final long LOG_SAMPLE_INTERVAL = 10_000;

    private final AeronPublisherProperties properties;

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUF_SIZE));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MidPriceBookEncoder encoder = new MidPriceBookEncoder();

    private Aeron aeron;
    private Publication publication;
    private volatile boolean running;
    private long publishCount;
    private long dropCount;

    public AeronMidPriceBookPublisher(AeronPublisherProperties properties) {
        this.properties = properties;

        headerEncoder.wrap(buffer, 0)
                .blockLength(MidPriceBookEncoder.BLOCK_LENGTH)
                .templateId(MidPriceBookEncoder.TEMPLATE_ID)
                .schemaId(MidPriceBookEncoder.SCHEMA_ID)
                .version(MidPriceBookEncoder.SCHEMA_VERSION);
    }

    @Override
    public void start() {
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(properties.dir()));
        publication = aeron.addPublication(properties.channel(), properties.streamId());
        running = true;
        log.info("Aeron MidPriceBook publisher started: channel={} stream={} dir={}",
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
        log.info("Aeron MidPriceBook publisher stopped — published={} dropped={}", publishCount, dropCount);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return -10;
    }

    public void publish(CcyPair ccyPair, long midPriceMantissa, int midSize) {
        if (!running) {
            return;
        }

        encoder.wrap(buffer, MessageHeaderEncoder.ENCODED_LENGTH);
        encoder.ccyPair(ccyPair);
        encoder.midPrice().mantissa(midPriceMantissa);
        encoder.midSize(midSize);

        long result = publication.offer(buffer, 0, BUF_SIZE);
        if (result >= 0) {
            publishCount++;
        } else {
            dropCount++;
            if (log.isDebugEnabled() && dropCount % LOG_SAMPLE_INTERVAL == 0) {
                log.debug("Aeron MidPriceBook back-pressure: result={} dropped={}", result, dropCount);
            }
        }
    }
}