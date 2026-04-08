package com.etradingpoc.dtracing.bookbuilder;

import com.etradingpoc.dtracing.common.sbe.*;
import com.etradingpoc.dtracing.common.tracing.TracePublisher;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookBuilderProcessorTest {

    @Mock AeronCompositeBookPublisher publisher;
    @Mock TracePublisher tracePublisher;

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private BookBuilderProcessor processor;

    private static final int BUF_SIZE =
            MessageHeaderEncoder.ENCODED_LENGTH + FxMarketDataEncoder.BLOCK_LENGTH;

    private static final long WINDOW_NANOS = 10_000_000L; // 10ms

    @BeforeEach
    void setUp() {
        // Return distinct spanIds so we can verify span link arguments
        when(tracePublisher.publishSpan(anyLong(), anyLong(), any(Stage.class), any(Ecn.class),
                any(CcyPair.class), anyLong(), anyLong(), anyLong()))
                .thenAnswer(inv -> clock.incrementAndGet());
        when(tracePublisher.publishSpan(anyLong(), anyLong(), any(Stage.class), any(Ecn.class),
                any(CcyPair.class), anyLong(), anyLong(), anyLong(), anyInt()))
                .thenAnswer(inv -> clock.incrementAndGet());
        processor = new BookBuilderProcessor(publisher, tracePublisher,
                new ConflationProperties(10), clock::get);
    }

    private FxMarketDataDecoder encodeMarketData(Ecn ecn, CcyPair ccyPair,
                                                  long bidMantissa, int bidSize,
                                                  long askMantissa, int askSize,
                                                  long traceId, long spanId, long seq) {
        var buf = new UnsafeBuffer(ByteBuffer.allocate(BUF_SIZE));
        new MessageHeaderEncoder().wrap(buf, 0)
                .blockLength(FxMarketDataEncoder.BLOCK_LENGTH)
                .templateId(FxMarketDataEncoder.TEMPLATE_ID)
                .schemaId(FxMarketDataEncoder.SCHEMA_ID)
                .version(FxMarketDataEncoder.SCHEMA_VERSION);
        var enc = new FxMarketDataEncoder().wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH);
        enc.ecn(ecn);
        enc.ccyPair(ccyPair);
        enc.timestamp(System.nanoTime());
        enc.bidPrice().mantissa(bidMantissa);
        enc.bidSize(bidSize);
        enc.askPrice().mantissa(askMantissa);
        enc.askSize(askSize);
        enc.traceId(traceId);
        enc.spanId(spanId);
        enc.sequenceNumber(seq);
        enc.senderTimestampOut(System.nanoTime());
        return new FxMarketDataDecoder().wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH,
                FxMarketDataDecoder.BLOCK_LENGTH, FxMarketDataDecoder.SCHEMA_VERSION);
    }

    @Test
    void onMarketData_updatesVenueBook() {
        var decoder = encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 1_000_000, 108780L, 500_000, 1L, 1L, 1L);

        processor.onMarketData(decoder);

        var venueBook = processor.getVenueBook(Ecn.EBS, CcyPair.EURUSD);
        assertThat(venueBook.bidPrice()).isEqualTo(108760L);
        assertThat(venueBook.bidSize()).isEqualTo(1_000_000);
        assertThat(venueBook.askPrice()).isEqualTo(108780L);
        assertThat(venueBook.askSize()).isEqualTo(500_000);
    }

    @Test
    void onMarketData_rebuildsCompositeBook() {
        var decoder = encodeMarketData(Ecn.EURONEXT, CcyPair.GBPUSD,
                130500L, 2_000_000, 130520L, 1_000_000, 1L, 1L, 1L);

        processor.onMarketData(decoder);

        var composite = processor.getCompositeBook(CcyPair.GBPUSD);
        assertThat(composite.bestBid()).isEqualTo(130500L);
        assertThat(composite.bestAsk()).isEqualTo(130520L);
        assertThat(composite.bestBidEcn()).isEqualTo(Ecn.EURONEXT);
    }

    @Test
    void onMarketData_publishesSnapshot() {
        var decoder = encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 200, 99L, 5L, 42L);

        processor.onMarketData(decoder);

        verify(publisher).publishSnapshot(eq(CcyPair.EURUSD), any(), eq(Ecn.EBS),
                eq(99L), anyLong(), eq(42L), anyLong());
    }

    @Test
    void onMarketData_emitsTwoTraceSpans() {
        var decoder = encodeMarketData(Ecn.FENICS, CcyPair.USDJPY,
                150000L, 100, 150100L, 100, 1L, 1L, 1L);

        processor.onMarketData(decoder);

        verify(tracePublisher, times(2)).publishSpan(
                anyLong(), anyLong(), any(Stage.class), any(Ecn.class),
                any(CcyPair.class), anyLong(), anyLong(), anyLong());
    }

    @Test
    void onMarketData_firstSpanIsTransport_secondIsBookBuild() {
        var decoder = encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L);
        var stageCaptor = ArgumentCaptor.forClass(Stage.class);

        processor.onMarketData(decoder);

        verify(tracePublisher, times(2)).publishSpan(
                anyLong(), anyLong(), stageCaptor.capture(), any(), any(), anyLong(), anyLong(), anyLong());
        assertThat(stageCaptor.getAllValues()).containsExactly(Stage.TRANSPORT, Stage.BOOK_BUILD);
    }

    @Test
    void onMarketData_threeDifferentEcns_compositeHasThreeLevels() {
        processor.onMarketData(encodeMarketData(Ecn.EURONEXT, CcyPair.EURUSD,
                108750L, 100, 108790L, 100, 1L, 1L, 1L));
        clock.addAndGet(WINDOW_NANOS); // advance past window for second tick
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108780L, 200, 2L, 2L, 2L));
        clock.addAndGet(WINDOW_NANOS); // advance past window for third tick
        processor.onMarketData(encodeMarketData(Ecn.FENICS, CcyPair.EURUSD,
                108760L, 300, 108785L, 300, 3L, 3L, 3L));

        var composite = processor.getCompositeBook(CcyPair.EURUSD);
        assertThat(composite.bidDepth()).isEqualTo(3);
        assertThat(composite.bestBid()).isEqualTo(108770L); // EBS has best bid
        assertThat(composite.bestAsk()).isEqualTo(108780L); // EBS has best ask
    }

    @Test
    void onMarketData_doesNotAffectOtherPairs() {
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));

        var composite = processor.getCompositeBook(CcyPair.GBPUSD);
        assertThat(composite.bidDepth()).isZero();
    }

    // ── Conflation tests ──────────────────────────────────────────

    @Test
    void conflation_secondTickWithinWindowIsSuppressed() {
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));

        clock.addAndGet(1_000_000L); // 1ms — within 10ms window
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108790L, 200, 2L, 2L, 2L));

        verify(publisher, times(1)).publishSnapshot(eq(CcyPair.EURUSD), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void conflation_tickAfterWindowPublishes() {
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));

        clock.addAndGet(WINDOW_NANOS); // advance past window
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108790L, 200, 2L, 2L, 2L));

        verify(publisher, times(2)).publishSnapshot(eq(CcyPair.EURUSD), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void conflation_flushPublishesDirtyPairAfterWindow() {
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));

        clock.addAndGet(1_000_000L); // 1ms — within window
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108790L, 200, 2L, 2L, 2L));

        // Only 1 publish so far (second was suppressed)
        verify(publisher, times(1)).publishSnapshot(eq(CcyPair.EURUSD), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());

        clock.addAndGet(WINDOW_NANOS); // advance past window
        processor.flushDirtyPairs();

        // Now the dirty pair should have been flushed
        verify(publisher, times(2)).publishSnapshot(eq(CcyPair.EURUSD), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void conflation_differentPairsAreIndependent() {
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));

        clock.addAndGet(1_000_000L); // 1ms — within window for EURUSD
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.GBPUSD,
                130500L, 100, 130520L, 100, 2L, 2L, 2L));

        // Both pairs should publish (independent windows)
        verify(publisher).publishSnapshot(eq(CcyPair.EURUSD), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
        verify(publisher).publishSnapshot(eq(CcyPair.GBPUSD), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void conflation_traceSpansEmittedForEveryTickEvenWhenSuppressed() {
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));

        clock.addAndGet(1_000_000L); // within window
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108790L, 200, 2L, 2L, 2L));

        // 2 ticks × 2 spans each = 4 trace spans, even though only 1 publish
        verify(tracePublisher, times(4)).publishSpan(
                anyLong(), anyLong(), any(Stage.class), any(Ecn.class),
                any(CcyPair.class), anyLong(), anyLong(), anyLong());
        verify(publisher, times(1)).publishSnapshot(eq(CcyPair.EURUSD), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void conflation_zeroWindowDisablesConflation() {
        var noConflation = new BookBuilderProcessor(publisher, tracePublisher,
                new ConflationProperties(0), clock::get);

        noConflation.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));
        noConflation.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108790L, 200, 2L, 2L, 2L));
        noConflation.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108780L, 300, 108800L, 300, 3L, 3L, 3L));

        // All 3 ticks should publish
        verify(publisher, times(3)).publishSnapshot(eq(CcyPair.EURUSD), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void conflation_flushDoesNothingWhenNoDirtyPairs() {
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));

        clock.addAndGet(WINDOW_NANOS);
        processor.flushDirtyPairs();

        // Only the original publish, no extra from flush
        verify(publisher, times(1)).publishSnapshot(eq(CcyPair.EURUSD), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void conflation_flushDoesNothingWhenWindowNotElapsed() {
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));

        clock.addAndGet(1_000_000L); // within window
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108790L, 200, 2L, 2L, 2L));

        // Flush while still within window — should not publish
        processor.flushDirtyPairs();

        verify(publisher, times(1)).publishSnapshot(eq(CcyPair.EURUSD), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    // ── Span link tests ───────────────────────────────────────────

    @Test
    void spanLinks_noLinksEmittedWhenNoConflation() {
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));

        verify(tracePublisher, never()).publishSpanLink(
                anyLong(), anyLong(), anyLong(), anyLong(), any(CcyPair.class));
    }

    @Test
    void spanLinks_emittedOnPublishAfterConflation() {
        // Tick 1: publishes immediately
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 10L, 1L, 1L));

        // Tick 2: suppressed (within window)
        clock.addAndGet(1_000_000L);
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108790L, 200, 20L, 2L, 2L));

        // No span links yet — tick 2 is still pending
        verify(tracePublisher, never()).publishSpanLink(
                anyLong(), anyLong(), anyLong(), anyLong(), any(CcyPair.class));

        // Tick 3: window elapsed, publishes and emits link to tick 2's span
        clock.addAndGet(WINDOW_NANOS);
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108780L, 300, 108800L, 300, 30L, 3L, 3L));

        verify(tracePublisher, times(1)).publishSpanLink(
                anyLong(), anyLong(), anyLong(), anyLong(), eq(CcyPair.EURUSD));
    }

    @Test
    void spanLinks_multipleConflatedTicksProduceMultipleLinks() {
        // Tick 1: publishes
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 10L, 1L, 1L));

        // Ticks 2-4: all suppressed (within window)
        clock.addAndGet(1_000_000L);
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108790L, 200, 20L, 2L, 2L));
        clock.addAndGet(1_000_000L);
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108775L, 250, 108795L, 250, 30L, 3L, 3L));
        clock.addAndGet(1_000_000L);
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108780L, 300, 108800L, 300, 40L, 4L, 4L));

        // Tick 5: window elapsed, publishes and emits links to ticks 2, 3 (not 4 — it's the pending/publishing tick's context)
        clock.addAndGet(WINDOW_NANOS);
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108785L, 350, 108805L, 350, 50L, 5L, 5L));

        // Ticks 2, 3, 4 were suppressed — ticks 2 and 3 become links, tick 4 was the last pending
        // which gets moved to link buffer when tick 5's pending overwrites it,
        // so all 3 suppressed ticks (2, 3, 4) become links
        verify(tracePublisher, times(3)).publishSpanLink(
                anyLong(), anyLong(), anyLong(), anyLong(), eq(CcyPair.EURUSD));
    }

    @Test
    void spanLinks_emittedOnFlushAfterConflation() {
        // Tick 1: publishes
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 10L, 1L, 1L));

        // Tick 2: suppressed
        clock.addAndGet(1_000_000L);
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108790L, 200, 20L, 2L, 2L));

        // Tick 3: also suppressed
        clock.addAndGet(1_000_000L);
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108775L, 250, 108795L, 250, 30L, 3L, 3L));

        // Flush after window — should emit link from tick 3 (pending) to tick 2 (accumulated)
        clock.addAndGet(WINDOW_NANOS);
        processor.flushDirtyPairs();

        verify(tracePublisher, times(1)).publishSpanLink(
                anyLong(), anyLong(), anyLong(), anyLong(), eq(CcyPair.EURUSD));
    }

    // ── CONFLATION_WAIT span tests ────────────────────────────────

    @Test
    void flushDirtyPairs_emitsConflationWaitSpan() {
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));
        clock.addAndGet(1_000_000L); // within window — suppressed
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108790L, 200, 2L, 2L, 2L));

        clock.addAndGet(WINDOW_NANOS);
        var stageCaptor = ArgumentCaptor.forClass(Stage.class);
        processor.flushDirtyPairs();

        verify(tracePublisher, atLeastOnce()).publishSpan(
                anyLong(), anyLong(), stageCaptor.capture(), any(), any(), anyLong(), anyLong(), anyLong(), anyInt());
        assertThat(stageCaptor.getAllValues()).containsExactly(Stage.CONFLATION_WAIT);
    }

    @Test
    void flushDirtyPairs_heldTicksCountIsCorrect() {
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));
        // 3 ticks suppressed
        clock.addAndGet(1_000_000L);
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108790L, 200, 2L, 2L, 2L));
        clock.addAndGet(1_000_000L);
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108775L, 250, 108795L, 250, 3L, 3L, 3L));
        clock.addAndGet(1_000_000L);
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108780L, 300, 108800L, 300, 4L, 4L, 4L));

        clock.addAndGet(WINDOW_NANOS);
        var heldCaptor = ArgumentCaptor.forClass(Integer.class);
        processor.flushDirtyPairs();

        verify(tracePublisher).publishSpan(
                anyLong(), anyLong(), eq(Stage.CONFLATION_WAIT), any(), any(), anyLong(), anyLong(), anyLong(),
                heldCaptor.capture());
        assertThat(heldCaptor.getValue()).isEqualTo(3); // 2 in link buffer + 1 pending
    }

    @Test
    void spanLinks_zeroWindowNeverEmitsLinks() {
        var noConflation = new BookBuilderProcessor(publisher, tracePublisher,
                new ConflationProperties(0), clock::get);

        noConflation.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108760L, 100, 108780L, 100, 1L, 1L, 1L));
        noConflation.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108790L, 200, 2L, 2L, 2L));

        verify(tracePublisher, never()).publishSpanLink(
                anyLong(), anyLong(), anyLong(), anyLong(), any(CcyPair.class));
    }
}
