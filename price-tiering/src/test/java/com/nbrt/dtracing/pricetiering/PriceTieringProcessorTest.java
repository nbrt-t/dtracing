package com.nbrt.dtracing.pricetiering;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PriceTieringProcessorTest {

    @Mock TracePublisher tracePublisher;

    private PriceTieringProcessor processor;

    private static final int BUF_SIZE =
            MessageHeaderEncoder.ENCODED_LENGTH + MidPriceBookEncoder.BLOCK_LENGTH;

    // Default config: 4 tiers, half-spreads = [10, 20, 35, 50], EURUSD override = [8, 15, 25, 40]
    private static final SpreadMatrixProperties SPREAD_MATRIX = new SpreadMatrixProperties(
            4,
            List.of(10L, 20L, 35L, 50L),
            Map.of("EURUSD", List.of(8L, 15L, 25L, 40L))
    );

    @BeforeEach
    void setUp() {
        processor = new PriceTieringProcessor(SPREAD_MATRIX, tracePublisher);
    }

    private MidPriceBookDecoder encodeMidPrice(CcyPair ccyPair, Ecn ecn,
                                                long midMantissa, int midSize,
                                                long traceId, long spanId, long seq) {
        var buf = new UnsafeBuffer(ByteBuffer.allocate(BUF_SIZE));
        new MessageHeaderEncoder().wrap(buf, 0)
                .blockLength(MidPriceBookEncoder.BLOCK_LENGTH)
                .templateId(MidPriceBookEncoder.TEMPLATE_ID)
                .schemaId(MidPriceBookEncoder.SCHEMA_ID)
                .version(MidPriceBookEncoder.SCHEMA_VERSION);
        var enc = new MidPriceBookEncoder().wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH);
        enc.ccyPair(ccyPair);
        enc.midPrice().mantissa(midMantissa);
        enc.midSize(midSize);
        enc.ecn(ecn);
        enc.traceId(traceId);
        enc.spanId(spanId);
        enc.sequenceNumber(seq);
        enc.senderTimestampOut(System.nanoTime());
        return new MidPriceBookDecoder().wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH,
                MidPriceBookDecoder.BLOCK_LENGTH, MidPriceBookDecoder.SCHEMA_VERSION);
    }

    @Test
    void onMidPriceBook_appliesSymmetricSpread_tier0() {
        // GBPUSD uses default spread: halfSpread[0] = 10
        var decoder = encodeMidPrice(CcyPair.GBPUSD, Ecn.EBS, 130500L, 1_000_000, 1L, 1L, 1L);

        processor.onMidPriceBook(decoder);

        assertThat(processor.getTieredBidPrice(CcyPair.GBPUSD, 0)).isEqualTo(130500L - 10L);
        assertThat(processor.getTieredAskPrice(CcyPair.GBPUSD, 0)).isEqualTo(130500L + 10L);
    }

    @Test
    void onMidPriceBook_appliesAllFourTiers_defaultSpreads() {
        var decoder = encodeMidPrice(CcyPair.GBPUSD, Ecn.EBS, 130500L, 500_000, 1L, 1L, 1L);

        processor.onMidPriceBook(decoder);

        long[] halfSpreads = {10L, 20L, 35L, 50L};
        for (int t = 0; t < 4; t++) {
            assertThat(processor.getTieredBidPrice(CcyPair.GBPUSD, t))
                    .as("tier %d bid", t)
                    .isEqualTo(130500L - halfSpreads[t]);
            assertThat(processor.getTieredAskPrice(CcyPair.GBPUSD, t))
                    .as("tier %d ask", t)
                    .isEqualTo(130500L + halfSpreads[t]);
        }
    }

    @Test
    void onMidPriceBook_usesPerPairOverride_forEurusd() {
        var decoder = encodeMidPrice(CcyPair.EURUSD, Ecn.EURONEXT, 108780L, 1_000_000, 1L, 1L, 1L);

        processor.onMidPriceBook(decoder);

        // EURUSD override: [8, 15, 25, 40]
        assertThat(processor.getTieredBidPrice(CcyPair.EURUSD, 0)).isEqualTo(108780L - 8L);
        assertThat(processor.getTieredAskPrice(CcyPair.EURUSD, 0)).isEqualTo(108780L + 8L);
        assertThat(processor.getTieredBidPrice(CcyPair.EURUSD, 3)).isEqualTo(108780L - 40L);
        assertThat(processor.getTieredAskPrice(CcyPair.EURUSD, 3)).isEqualTo(108780L + 40L);
    }

    @Test
    void onMidPriceBook_tieredSizesMatchMidSize() {
        var decoder = encodeMidPrice(CcyPair.USDJPY, Ecn.FENICS, 150000L, 2_000_000, 1L, 1L, 1L);

        processor.onMidPriceBook(decoder);

        for (int t = 0; t < 4; t++) {
            assertThat(processor.getTieredSize(CcyPair.USDJPY, t)).isEqualTo(2_000_000);
        }
    }

    @Test
    void onMidPriceBook_emitsTwoTraceSpans() {
        var decoder = encodeMidPrice(CcyPair.EURUSD, Ecn.EBS, 108780L, 100, 1L, 1L, 1L);

        processor.onMidPriceBook(decoder);

        verify(tracePublisher, times(2)).publishSpan(
                anyLong(), anyLong(), any(Stage.class), any(Ecn.class),
                any(CcyPair.class), anyLong(), anyLong(), anyLong());
    }

    @Test
    void onMidPriceBook_firstSpanIsTransport_secondIsPriceTier() {
        var decoder = encodeMidPrice(CcyPair.EURUSD, Ecn.EBS, 108780L, 100, 1L, 1L, 1L);
        var stageCaptor = ArgumentCaptor.forClass(Stage.class);

        processor.onMidPriceBook(decoder);

        verify(tracePublisher, times(2)).publishSpan(
                anyLong(), anyLong(), stageCaptor.capture(), any(), any(), anyLong(), anyLong(), anyLong());
        assertThat(stageCaptor.getAllValues()).containsExactly(Stage.TRANSPORT, Stage.PRICE_TIER);
    }

    @Test
    void tierCount_reflectsConfiguration() {
        assertThat(processor.tierCount()).isEqualTo(4);
    }

    @Test
    void onMidPriceBook_doesNotAffectOtherPairs() {
        var decoder = encodeMidPrice(CcyPair.EURUSD, Ecn.EBS, 108780L, 100, 1L, 1L, 1L);

        processor.onMidPriceBook(decoder);

        assertThat(processor.getTieredBidPrice(CcyPair.GBPUSD, 0)).isZero();
        assertThat(processor.getTieredAskPrice(CcyPair.GBPUSD, 0)).isZero();
    }
}
