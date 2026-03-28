package com.nbrt.dtracing.marketdatahandler;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.Ecn;
import com.nbrt.dtracing.common.sbe.FxMarketDataEncoder;
import com.nbrt.dtracing.common.sbe.MessageHeaderEncoder;
import io.aeron.Aeron;
import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * Publishes {@code FxMarketData} messages over Aeron IPC to the BookBuilder.
 * <p>
 * Hot-path: encode directly into a pre-allocated buffer, no allocation per publish.
 * Back-pressure is handled by dropping the message (the next delta will carry fresh state).
 */
@Component
public class AeronFxMarketDataPublisher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AeronFxMarketDataPublisher.class);

    private static final int BUF_SIZE = MessageHeaderEncoder.ENCODED_LENGTH + FxMarketDataEncoder.BLOCK_LENGTH;
    private static final long LOG_SAMPLE_INTERVAL = 10_000;

    private final AeronPublisherProperties properties;
    private final Ecn ecn;

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUF_SIZE));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final FxMarketDataEncoder encoder = new FxMarketDataEncoder();

    private Aeron aeron;
    private Publication publication;
    private volatile boolean running;
    private long publishCount;
    private long dropCount;

    public AeronFxMarketDataPublisher(AeronPublisherProperties properties, UdpFeedProperties udpProperties) {
        this.properties = properties;
        this.ecn = Ecn.valueOf(udpProperties.ecn());

        // Pre-encode the SBE header — it never changes
        headerEncoder.wrap(buffer, 0)
                .blockLength(FxMarketDataEncoder.BLOCK_LENGTH)
                .templateId(FxMarketDataEncoder.TEMPLATE_ID)
                .schemaId(FxMarketDataEncoder.SCHEMA_ID)
                .version(FxMarketDataEncoder.SCHEMA_VERSION);
    }

    @Override
    public void start() {
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(properties.dir()));
        publication = aeron.addPublication(properties.channel(), properties.streamId());
        running = true;
        log.info("Aeron FxMarketData publisher started: channel={} stream={} dir={}",
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
        log.info("Aeron FxMarketData publisher stopped — published={} dropped={}", publishCount, dropCount);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Start before UDP receiver (default phase 0), so publication is ready when deltas arrive
        return -10;
    }

    /**
     * Publish the current best bid/ask for a currency pair as an FxMarketData message.
     * Called on the hot path — zero allocation, pre-encoded header.
     */
    public void publish(CcyPair ccyPair, long timestamp,
                        long bidMantissa, int bidSize,
                        long askMantissa, int askSize,
                        long traceId, long spanId, long sequenceNumber, long senderTimestampOut) {
        if (!running) {
            return;
        }

        encoder.wrap(buffer, MessageHeaderEncoder.ENCODED_LENGTH);
        encoder.ecn(ecn);
        encoder.ccyPair(ccyPair);
        encoder.timestamp(timestamp);
        encoder.bidPrice().mantissa(bidMantissa);
        encoder.bidSize(bidSize);
        encoder.askPrice().mantissa(askMantissa);
        encoder.askSize(askSize);
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
                log.debug("Aeron publish back-pressure: result={} dropped={}", result, dropCount);
            }
        }
    }
}