package com.etradingpoc.dtracing.pricetiering;

import com.etradingpoc.dtracing.common.sbe.MessageHeaderDecoder;
import com.etradingpoc.dtracing.common.sbe.MidPriceBookDecoder;
import io.aeron.Aeron;
import io.aeron.Subscription;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Subscribes to {@code MidPriceBook} messages published by the MidPricer
 * over Aeron IPC. Polls on a dedicated platform thread.
 */
@Component
public class AeronMidPriceBookSubscriber implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AeronMidPriceBookSubscriber.class);

    private final AeronSubscriberProperties properties;
    private final MidPriceBookHandler handler;

    private Aeron aeron;
    private Subscription subscription;
    private Thread pollerThread;
    private volatile boolean running;

    public AeronMidPriceBookSubscriber(AeronSubscriberProperties properties, MidPriceBookHandler handler) {
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
                .name("aeron-mid-price-book-subscriber")
                .start(this::pollLoop);

        log.info("Aeron MidPriceBook subscriber started: channel={} stream={} dir={}",
                properties.channel(), properties.streamId(), properties.dir());
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
        log.info("Aeron MidPriceBook subscriber stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        var headerDecoder = new MessageHeaderDecoder();
        var midPriceBookDecoder = new MidPriceBookDecoder();
        IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(1);

        while (running) {
            int fragmentsRead = subscription.poll((buffer, offset, length, header) ->
                    onFragment(buffer, offset, headerDecoder, midPriceBookDecoder), 10);
            idleStrategy.idle(fragmentsRead);
        }
    }

    private void onFragment(DirectBuffer buffer, int offset,
                            MessageHeaderDecoder headerDecoder,
                            MidPriceBookDecoder midPriceBookDecoder) {
        headerDecoder.wrap(buffer, offset);

        if (headerDecoder.templateId() != MidPriceBookDecoder.TEMPLATE_ID) {
            return;
        }

        midPriceBookDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
        handler.onMidPriceBook(midPriceBookDecoder);
    }
}