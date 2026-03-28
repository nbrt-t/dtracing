package com.nbrt.dtracing.midpricer;

import com.nbrt.dtracing.common.sbe.*;
import com.nbrt.dtracing.common.tracing.TracePublisher;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MidPricerProcessorTest {

    @Mock AeronMidPriceBookPublisher publisher;
    @Mock TracePublisher tracePublisher;

    private MidPricerProcessor processor;

    private static final int BUF_SIZE =
            MessageHeaderEncoder.ENCODED_LENGTH + CompositeBookSnapshotEncoder.BLOCK_LENGTH;

    @BeforeEach
    void setUp() {
        processor = new MidPricerProcessor(publisher, tracePublisher);
    }

    /**
     * Encodes a CompositeBookSnapshot with 3 venue slots.
     * Pass 0 for bid/ask prices to indicate "no data" for that venue.
     */
    private CompositeBookSnapshotDecoder encodeSnapshot(
            CcyPair ccyPair, Ecn triggeringEcn,
            long bid0, int bidSz0, long ask0, int askSz0,
            long bid1, int bidSz1, long ask1, int askSz1,
            long bid2, int bidSz2, long ask2, int askSz2,
            long traceId, long spanId, long seq) {
        var buf = new UnsafeBuffer(ByteBuffer.allocate(BUF_SIZE));
        new MessageHeaderEncoder().wrap(buf, 0)
                .blockLength(CompositeBookSnapshotEncoder.BLOCK_LENGTH)
                .templateId(CompositeBookSnapshotEncoder.TEMPLATE_ID)
                .schemaId(CompositeBookSnapshotEncoder.SCHEMA_ID)
                .version(CompositeBookSnapshotEncoder.SCHEMA_VERSION);
        var enc = new CompositeBookSnapshotEncoder().wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH);
        enc.ccyPair(ccyPair);
        enc.triggeringEcn(triggeringEcn);
        enc.venue0BidPrice().mantissa(bid0); enc.venue0BidSize(bidSz0);
        enc.venue0AskPrice().mantissa(ask0); enc.venue0AskSize(askSz0);
        enc.venue1BidPrice().mantissa(bid1); enc.venue1BidSize(bidSz1);
        enc.venue1AskPrice().mantissa(ask1); enc.venue1AskSize(askSz1);
        enc.venue2BidPrice().mantissa(bid2); enc.venue2BidSize(bidSz2);
        enc.venue2AskPrice().mantissa(ask2); enc.venue2AskSize(askSz2);
        enc.traceId(traceId);
        enc.spanId(spanId);
        enc.sequenceNumber(seq);
        enc.senderTimestampOut(System.nanoTime());
        return new CompositeBookSnapshotDecoder().wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH,
                CompositeBookSnapshotDecoder.BLOCK_LENGTH, CompositeBookSnapshotDecoder.SCHEMA_VERSION);
    }

    @Test
    void onCompositeBookSnapshot_calculatesMidPrice() {
        // bestBid = 108770, bestAsk = 108790 → mid = (108770 + 108790) / 2 = 108780
        var decoder = encodeSnapshot(CcyPair.EURUSD, Ecn.EBS,
                108770L, 1_000_000, 108790L, 500_000,
                0, 0, 0, 0,
                0, 0, 0, 0,
                1L, 1L, 1L);

        processor.onCompositeBookSnapshot(decoder);

        assertThat(processor.getMidPrice(CcyPair.EURUSD)).isEqualTo(108780L);
    }

    @Test
    void onCompositeBookSnapshot_midSizeIsMinOfBidAndAskSizes() {
        var decoder = encodeSnapshot(CcyPair.EURUSD, Ecn.EBS,
                108770L, 2_000_000, 108790L, 500_000,
                0, 0, 0, 0,
                0, 0, 0, 0,
                1L, 1L, 1L);

        processor.onCompositeBookSnapshot(decoder);

        assertThat(processor.getMidSize(CcyPair.EURUSD)).isEqualTo(500_000); // min(2M, 500K)
    }

    @Test
    void onCompositeBookSnapshot_selectsBestBidAcrossVenues() {
        // Venue0 bid=108750, Venue1 bid=108770 (best), Venue2 bid=108760
        var decoder = encodeSnapshot(CcyPair.GBPUSD, Ecn.EURONEXT,
                108750L, 100, 108795L, 100,
                108770L, 200, 108785L, 200,
                108760L, 300, 108780L, 300,
                2L, 2L, 2L);

        processor.onCompositeBookSnapshot(decoder);

        // bestBid=108770, bestAsk=108780 → mid=(108770+108780)/2=108775
        assertThat(processor.getMidPrice(CcyPair.GBPUSD)).isEqualTo(108775L);
    }

    @Test
    void onCompositeBookSnapshot_selectsBestAskAcrossVenues() {
        // Venue0 ask=108795, Venue1 ask=108800, Venue2 ask=108780 (best)
        var decoder = encodeSnapshot(CcyPair.USDJPY, Ecn.FENICS,
                150000L, 100, 150020L, 100,
                150005L, 100, 150025L, 100,
                150010L, 100, 150015L, 100,  // Venue2 has lowest ask
                3L, 3L, 3L);

        processor.onCompositeBookSnapshot(decoder);

        // bestBid=150010, bestAsk=150015 → mid=150012 (integer division)
        assertThat(processor.getMidPrice(CcyPair.USDJPY)).isEqualTo(150012L);
    }

    @Test
    void onCompositeBookSnapshot_publishesMidPrice() {
        var decoder = encodeSnapshot(CcyPair.EURUSD, Ecn.EBS,
                108770L, 100, 108790L, 100,
                0, 0, 0, 0,
                0, 0, 0, 0,
                99L, 5L, 42L);

        processor.onCompositeBookSnapshot(decoder);

        verify(publisher).publish(eq(CcyPair.EURUSD), eq(108780L), eq(100),
                eq(99L), anyLong(), eq(42L), eq(Ecn.EBS), anyLong());
    }

    @Test
    void onCompositeBookSnapshot_noPublishWhenNoBidOrAsk() {
        // All venue prices are zero
        var decoder = encodeSnapshot(CcyPair.EURUSD, Ecn.EBS,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                1L, 1L, 1L);

        processor.onCompositeBookSnapshot(decoder);

        verify(publisher, never()).publish(any(), anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), any(), anyLong());
        assertThat(processor.getMidPrice(CcyPair.EURUSD)).isZero();
    }

    @Test
    void onCompositeBookSnapshot_emitsTwoTraceSpans() {
        var decoder = encodeSnapshot(CcyPair.EURUSD, Ecn.EBS,
                108770L, 100, 108790L, 100,
                0, 0, 0, 0,
                0, 0, 0, 0,
                1L, 1L, 1L);

        processor.onCompositeBookSnapshot(decoder);

        verify(tracePublisher, times(2)).publishSpan(
                anyLong(), anyLong(), any(Stage.class), any(Ecn.class),
                any(CcyPair.class), anyLong(), anyLong(), anyLong());
    }

    @Test
    void onCompositeBookSnapshot_firstSpanIsTransport_secondIsMidPrice() {
        var decoder = encodeSnapshot(CcyPair.EURUSD, Ecn.EBS,
                108770L, 100, 108790L, 100,
                0, 0, 0, 0,
                0, 0, 0, 0,
                1L, 1L, 1L);
        var stageCaptor = ArgumentCaptor.forClass(Stage.class);

        processor.onCompositeBookSnapshot(decoder);

        verify(tracePublisher, times(2)).publishSpan(
                anyLong(), anyLong(), stageCaptor.capture(), any(), any(), anyLong(), anyLong(), anyLong());
        assertThat(stageCaptor.getAllValues()).containsExactly(Stage.TRANSPORT, Stage.MID_PRICE);
    }

    @Test
    void onCompositeBookSnapshot_doesNotAffectOtherPairs() {
        var decoder = encodeSnapshot(CcyPair.EURUSD, Ecn.EBS,
                108770L, 100, 108790L, 100,
                0, 0, 0, 0,
                0, 0, 0, 0,
                1L, 1L, 1L);

        processor.onCompositeBookSnapshot(decoder);

        assertThat(processor.getMidPrice(CcyPair.GBPUSD)).isZero();
    }
}
