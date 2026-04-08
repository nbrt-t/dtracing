package com.etradingpoc.dtracing.marketdatahandler;

import com.etradingpoc.dtracing.common.sbe.FxFeedDeltaDecoder;
import com.etradingpoc.dtracing.common.sbe.MessageHeaderDecoder;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Subscribes to {@code FxFeedDelta} messages published by the simulator over Aeron IPC.
 * Replaces the UDP receiver: same SBE message, zero network overhead, nanosecond-level latency.
 */
@Component
public class AeronFeedReceiver implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AeronFeedReceiver.class);

    private final AeronFeedProperties properties;
    private final MarketDataDeltaHandler handler;

    private Aeron aeron;
    private Subscription subscription;
    private Thread pollerThread;
    private volatile boolean running;
    private long expectedSeq = -1;

    public AeronFeedReceiver(AeronFeedProperties properties, MarketDataDeltaHandler handler) {
        this.properties = properties;
        this.handler = handler;
    }

    @Override
    public void start() {
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(properties.dir()));
        subscription = aeron.addSubscription(properties.channel(), properties.streamId());
        running = true;

        pollerThread = Thread.ofPlatform()
                .daemon(false)
                .name("aeron-feed-receiver-" + properties.ecn().toLowerCase())
                .start(this::pollLoop);

        log.info("Aeron feed receiver [{}] started: channel={} stream={} dir={}",
                properties.ecn(), properties.channel(), properties.streamId(), properties.dir());
    }

    @Override
    public void stop() {
        running = false;
        if (pollerThread != null) {
            pollerThread.interrupt();
        }
        if (subscription != null) {
            subscription.close();
        }
        if (aeron != null) {
            aeron.close();
        }
        log.info("Aeron feed receiver [{}] stopped", properties.ecn());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        var headerDecoder = new MessageHeaderDecoder();
        var deltaDecoder = new FxFeedDeltaDecoder();
        IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(1);

        while (running) {
            int fragmentsRead = subscription.poll(
                    (buffer, offset, length, header) -> onFragment(buffer, offset, headerDecoder, deltaDecoder), 10);
            idleStrategy.idle(fragmentsRead);
        }
    }

    private void onFragment(DirectBuffer buffer, int offset,
                            MessageHeaderDecoder headerDecoder,
                            FxFeedDeltaDecoder deltaDecoder) {
        headerDecoder.wrap(buffer, offset);

        if (headerDecoder.templateId() != FxFeedDeltaDecoder.TEMPLATE_ID) {
            return;
        }

        deltaDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        long seq = deltaDecoder.sequenceNumber();
        if (expectedSeq >= 0 && seq != expectedSeq && log.isWarnEnabled()) {
            log.warn("[{}] Sequence gap: expected={} received={} missed={}",
                    properties.ecn(), expectedSeq, seq, seq - expectedSeq);
        }
        expectedSeq = seq + 1;

        handler.onDelta(deltaDecoder);
    }
}
