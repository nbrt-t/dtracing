package com.etradingpoc.dtracing.marketdatahandler;

import com.etradingpoc.dtracing.common.sbe.*;
import com.etradingpoc.dtracing.common.tracing.TracePublisher;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketDataDeltaProcessorTest {

    @Mock AeronFxMarketDataPublisher publisher;
    @Mock TracePublisher tracePublisher;

    private MarketDataDeltaProcessor processor;

    private static final int BUF_SIZE =
            MessageHeaderEncoder.ENCODED_LENGTH + FxFeedDeltaEncoder.BLOCK_LENGTH;

    @BeforeEach
    void setUp() {
        var props = new UdpFeedProperties("EURONEXT", "0.0.0.0", 9001, 1500, null);
        processor = new MarketDataDeltaProcessor(props, publisher, tracePublisher);
    }

    private FxFeedDeltaDecoder encodeDelta(CcyPair ccyPair, long bidMantissa, int bidSize,
                                           long askMantissa, int askSize, long sequenceNumber) {
        var buf = new UnsafeBuffer(ByteBuffer.allocate(BUF_SIZE));
        new MessageHeaderEncoder().wrap(buf, 0)
                .blockLength(FxFeedDeltaEncoder.BLOCK_LENGTH)
                .templateId(FxFeedDeltaEncoder.TEMPLATE_ID)
                .schemaId(FxFeedDeltaEncoder.SCHEMA_ID)
                .version(FxFeedDeltaEncoder.SCHEMA_VERSION);
        var enc = new FxFeedDeltaEncoder().wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH);
        enc.sequenceNumber(sequenceNumber);
        enc.ccyPair(ccyPair);
        enc.timestamp(System.nanoTime());
        enc.bidPrice().mantissa(bidMantissa);
        enc.bidSize(bidSize);
        enc.askPrice().mantissa(askMantissa);
        enc.askSize(askSize);
        return new FxFeedDeltaDecoder().wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH,
                FxFeedDeltaDecoder.BLOCK_LENGTH, FxFeedDeltaDecoder.SCHEMA_VERSION);
    }

    @Test
    void onDelta_updatesOrderBook() {
        var decoder = encodeDelta(CcyPair.EURUSD, 108760L, 1_000_000, 108780L, 500_000, 1L);

        processor.onDelta(decoder);

        var book = processor.getBook(CcyPair.EURUSD);
        assertThat(book.bestBid()).isEqualTo(108760L);
        assertThat(book.bestAsk()).isEqualTo(108780L);
    }

    @Test
    void onDelta_publishesBbo() {
        var decoder = encodeDelta(CcyPair.GBPUSD, 130500L, 2_000_000, 130520L, 1_000_000, 42L);

        processor.onDelta(decoder);

        verify(publisher).publish(eq(CcyPair.GBPUSD), anyLong(),
                eq(130500L), eq(2_000_000),
                eq(130520L), eq(1_000_000),
                anyLong(), anyLong(), eq(42L), anyLong());
    }

    @Test
    void onDelta_emitsTwoTraceSpans() {
        var decoder = encodeDelta(CcyPair.EURUSD, 108760L, 100, 108780L, 100, 1L);

        processor.onDelta(decoder);

        verify(tracePublisher, times(2)).publishSpan(
                anyLong(), anyLong(), any(Stage.class), any(Ecn.class),
                any(CcyPair.class), anyLong(), anyLong(), anyLong());
    }

    @Test
    void onDelta_firstSpanIsReceive_secondIsProcess() {
        var decoder = encodeDelta(CcyPair.EURUSD, 108760L, 100, 108780L, 100, 1L);
        var stageCaptor = org.mockito.ArgumentCaptor.forClass(Stage.class);

        processor.onDelta(decoder);

        verify(tracePublisher, times(2)).publishSpan(
                anyLong(), anyLong(), stageCaptor.capture(), any(), any(), anyLong(), anyLong(), anyLong());

        assertThat(stageCaptor.getAllValues()).containsExactly(Stage.MDH_RECEIVE, Stage.MDH_PROCESS);
    }

    @Test
    void onDelta_multipleDeltasSamePair_bookAccumulates() {
        processor.onDelta(encodeDelta(CcyPair.EURUSD, 108760L, 100, 108780L, 100, 1L));
        processor.onDelta(encodeDelta(CcyPair.EURUSD, 108750L, 200, 108790L, 200, 2L));

        var book = processor.getBook(CcyPair.EURUSD);
        assertThat(book.bidDepth()).isEqualTo(2);
        assertThat(book.bestBid()).isEqualTo(108760L);
    }

    @Test
    void onDelta_doesNotModifyOtherPair() {
        processor.onDelta(encodeDelta(CcyPair.EURUSD, 108760L, 100, 108780L, 100, 1L));

        var gbpBook = processor.getBook(CcyPair.GBPUSD);
        assertThat(gbpBook.bestBid()).isZero();
        assertThat(gbpBook.bestAsk()).isZero();
    }

    @Test
    void onDelta_publishedWithEcnFromProperties() {
        var decoder = encodeDelta(CcyPair.EURUSD, 108760L, 100, 108780L, 100, 1L);
        var ecnCaptor = org.mockito.ArgumentCaptor.forClass(Ecn.class);

        processor.onDelta(decoder);

        verify(tracePublisher, times(2)).publishSpan(
                anyLong(), anyLong(), any(), ecnCaptor.capture(), any(), anyLong(), anyLong(), anyLong());
        assertThat(ecnCaptor.getAllValues()).allMatch(e -> e == Ecn.EURONEXT);
    }
}
