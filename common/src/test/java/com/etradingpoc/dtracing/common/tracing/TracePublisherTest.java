package com.etradingpoc.dtracing.common.tracing;

import com.etradingpoc.dtracing.common.sbe.CcyPair;
import com.etradingpoc.dtracing.common.sbe.Ecn;
import com.etradingpoc.dtracing.common.sbe.MessageHeaderDecoder;
import com.etradingpoc.dtracing.common.sbe.Stage;
import com.etradingpoc.dtracing.common.sbe.TraceSpanDecoder;
import io.aeron.Aeron;
import io.aeron.Publication;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TracePublisherTest {

    @Mock Aeron aeron;
    @Mock Publication publication;

    private TracePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TracePublisher(aeron, publication, Stage.MDH_RECEIVE);
    }

    @Test
    void publishSpan_returnsIncrementingSpanIds() {
        long id1 = publisher.publishSpan(1L, 0L, Stage.MDH_RECEIVE, Ecn.EBS, CcyPair.EURUSD, 42L, 100L, 200L);
        long id2 = publisher.publishSpan(1L, id1, Stage.BOOK_BUILD, Ecn.EBS, CcyPair.EURUSD, 42L, 200L, 300L);

        assertThat(id1).isEqualTo(1L);
        assertThat(id2).isEqualTo(2L);
    }

    @Test
    void publishSpan_encodesAllFieldsCorrectly() {
        ArgumentCaptor<DirectBuffer> bufCaptor = ArgumentCaptor.forClass(DirectBuffer.class);
        ArgumentCaptor<Integer> offsetCaptor = ArgumentCaptor.forClass(Integer.class);

        publisher.publishSpan(999L, 7L, Stage.MDH_RECEIVE, Ecn.FENICS, CcyPair.GBPUSD, 12345L, 1_000_000L, 2_000_000L);

        verify(publication).offer(bufCaptor.capture(), offsetCaptor.capture(), anyInt());

        DirectBuffer buf = bufCaptor.getValue();
        int offset = offsetCaptor.getValue();

        MessageHeaderDecoder header = new MessageHeaderDecoder();
        header.wrap(buf, offset);
        assertThat(header.templateId()).isEqualTo(TraceSpanDecoder.TEMPLATE_ID);
        assertThat(header.schemaId()).isEqualTo(TraceSpanDecoder.SCHEMA_ID);

        TraceSpanDecoder decoder = new TraceSpanDecoder();
        decoder.wrap(buf, offset + MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version());

        assertThat(decoder.traceId()).isEqualTo(999L);
        assertThat(decoder.spanId()).isEqualTo(1L);
        assertThat(decoder.parentSpanId()).isEqualTo(7L);
        assertThat(decoder.stage()).isEqualTo(Stage.MDH_RECEIVE);
        assertThat(decoder.ecn()).isEqualTo(Ecn.FENICS);
        assertThat(decoder.ccyPair()).isEqualTo(CcyPair.GBPUSD);
        assertThat(decoder.sequenceNumber()).isEqualTo(12345L);
        assertThat(decoder.timestampIn()).isEqualTo(1_000_000L);
        assertThat(decoder.timestampOut()).isEqualTo(2_000_000L);
    }

    @Test
    void publishSpan_offersAtZeroOffsetWithCorrectLength() {
        int expectedLength = MessageHeaderDecoder.ENCODED_LENGTH + TraceSpanDecoder.BLOCK_LENGTH;
        ArgumentCaptor<Integer> offsetCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> lengthCaptor = ArgumentCaptor.forClass(Integer.class);

        publisher.publishSpan(1L, 0L, Stage.MID_PRICE, Ecn.EBS, CcyPair.USDJPY, 0L, 0L, 0L);
        publisher.publishSpan(2L, 0L, Stage.PRICE_TIER, Ecn.EURONEXT, CcyPair.EURUSD, 0L, 0L, 0L);

        verify(publication, times(2)).offer(any(DirectBuffer.class), offsetCaptor.capture(), lengthCaptor.capture());

        assertThat(offsetCaptor.getAllValues()).allMatch(o -> o == 0);
        assertThat(lengthCaptor.getAllValues()).allMatch(l -> l == expectedLength);
    }

    @Test
    void publishSpan_parentSpanIdZeroForRootSpan() {
        ArgumentCaptor<DirectBuffer> bufCaptor = ArgumentCaptor.forClass(DirectBuffer.class);
        ArgumentCaptor<Integer> offsetCaptor = ArgumentCaptor.forClass(Integer.class);

        publisher.publishSpan(5L, 0L, Stage.MDH_RECEIVE, Ecn.EBS, CcyPair.EURUSD, 1L, 10L, 20L);

        verify(publication).offer(bufCaptor.capture(), offsetCaptor.capture(), anyInt());

        DirectBuffer buf = bufCaptor.getValue();
        int offset = offsetCaptor.getValue();
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        header.wrap(buf, offset);
        TraceSpanDecoder decoder = new TraceSpanDecoder();
        decoder.wrap(buf, offset + MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version());

        assertThat(decoder.parentSpanId()).isEqualTo(0L);
    }

    @Test
    void publishSpan_differentStagesProduceNonCollidingSpanIds() {
        var midPricePublisher = new TracePublisher(aeron, publication, Stage.MID_PRICE);
        var priceTierPublisher = new TracePublisher(aeron, publication, Stage.PRICE_TIER);

        long midId = midPricePublisher.publishSpan(1L, 0L, Stage.MID_PRICE, Ecn.EBS, CcyPair.EURUSD, 0L, 0L, 0L);
        long tierId = priceTierPublisher.publishSpan(1L, midId, Stage.PRICE_TIER, Ecn.EBS, CcyPair.EURUSD, 0L, 0L, 0L);

        assertThat(tierId).isNotEqualTo(midId);
    }

    @Test
    void epochNanosNow_isBoundedByWallClock() {
        long before = System.currentTimeMillis() * 1_000_000L;
        long nanos = TracePublisher.epochNanosNow();
        long after = (System.currentTimeMillis() + 1) * 1_000_000L;

        assertThat(nanos).isBetween(before, after);
    }

    @Test
    void close_closesPublicationAndAeron() {
        publisher.close();

        verify(publication).close();
        verify(aeron).close();
    }
}
