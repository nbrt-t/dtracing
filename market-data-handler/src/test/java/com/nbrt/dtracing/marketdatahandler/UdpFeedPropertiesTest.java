package com.nbrt.dtracing.marketdatahandler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UdpFeedPropertiesTest {

    @Test
    void defaults_appliedWhenNullInputs() {
        var props = new UdpFeedProperties(null, null, 0, 0, null);

        assertThat(props.ecn()).isEqualTo("UNKNOWN");
        assertThat(props.bindAddress()).isEqualTo("0.0.0.0");
        assertThat(props.port()).isEqualTo(9000);
        assertThat(props.bufferSize()).isEqualTo(1500);
    }

    @Test
    void defaults_appliedWhenBlankStrings() {
        var props = new UdpFeedProperties("", "  ", 0, 0, null);

        assertThat(props.ecn()).isEqualTo("UNKNOWN");
        assertThat(props.bindAddress()).isEqualTo("0.0.0.0");
    }

    @Test
    void explicitValues_arePreserved() {
        var props = new UdpFeedProperties("EURONEXT", "10.0.0.1", 9001, 2048, null);

        assertThat(props.ecn()).isEqualTo("EURONEXT");
        assertThat(props.bindAddress()).isEqualTo("10.0.0.1");
        assertThat(props.port()).isEqualTo(9001);
        assertThat(props.bufferSize()).isEqualTo(2048);
    }

    @Test
    void isMulticast_falseWhenMulticastGroupIsNull() {
        var props = new UdpFeedProperties("EBS", "0.0.0.0", 9001, 1500, null);

        assertThat(props.isMulticast()).isFalse();
    }

    @Test
    void isMulticast_falseWhenMulticastGroupIsBlank() {
        var props = new UdpFeedProperties("EBS", "0.0.0.0", 9001, 1500, "  ");

        assertThat(props.isMulticast()).isFalse();
    }

    @Test
    void isMulticast_trueWhenMulticastGroupSet() {
        var props = new UdpFeedProperties("EBS", "0.0.0.0", 9001, 1500, "239.255.0.1");

        assertThat(props.isMulticast()).isTrue();
        assertThat(props.multicastGroup()).isEqualTo("239.255.0.1");
    }
}
