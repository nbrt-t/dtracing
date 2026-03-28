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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookBuilderProcessorTest {

    @Mock AeronCompositeBookPublisher publisher;
    @Mock TracePublisher tracePublisher;

    private BookBuilderProcessor processor;

    private static final int BUF_SIZE =
            MessageHeaderEncoder.ENCODED_LENGTH + FxMarketDataEncoder.BLOCK_LENGTH;

    @BeforeEach
    void setUp() {
        processor = new BookBuilderProcessor(publisher, tracePublisher);
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
        processor.onMarketData(encodeMarketData(Ecn.EBS, CcyPair.EURUSD,
                108770L, 200, 108780L, 200, 2L, 2L, 2L));
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
}
