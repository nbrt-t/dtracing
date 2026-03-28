package com.nbrt.dtracing.midpricer;

import com.nbrt.dtracing.common.sbe.CompositeBookSnapshotDecoder;
import com.nbrt.dtracing.common.sbe.MessageHeaderDecoder;
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
 * Subscribes to {@code CompositeBookSnapshot} messages published by the BookBuilder
 * over Aeron IPC. Polls on a dedicated platform thread.
 */
@Component
public class AeronCompositeBookSubscriber implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AeronCompositeBookSubscriber.class);

    private final AeronSubscriberProperties properties;
    private final CompositeBookSnapshotHandler handler;

    private Aeron aeron;
    private Subscription subscription;
    private Thread pollerThread;
    private volatile boolean running;

    public AeronCompositeBookSubscriber(AeronSubscriberProperties properties, CompositeBookSnapshotHandler handler) {
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
                .name("aeron-composite-book-subscriber")
                .start(this::pollLoop);

        log.info("Aeron CompositeBookSnapshot subscriber started: channel={} stream={} dir={}",
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
        log.info("Aeron CompositeBookSnapshot subscriber stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        var headerDecoder = new MessageHeaderDecoder();
        var snapshotDecoder = new CompositeBookSnapshotDecoder();
        IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(1);

        while (running) {
            int fragmentsRead = subscription.poll((buffer, offset, length, header) ->
                    onFragment(buffer, offset, headerDecoder, snapshotDecoder), 10);
            idleStrategy.idle(fragmentsRead);
        }
    }

    private void onFragment(DirectBuffer buffer, int offset,
                            MessageHeaderDecoder headerDecoder,
                            CompositeBookSnapshotDecoder snapshotDecoder) {
        headerDecoder.wrap(buffer, offset);

        if (headerDecoder.templateId() != CompositeBookSnapshotDecoder.TEMPLATE_ID) {
            return;
        }

        snapshotDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
        handler.onCompositeBookSnapshot(snapshotDecoder);
    }
}
