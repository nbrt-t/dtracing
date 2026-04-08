package com.etradingpoc.dtracing.marketdatahandler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AeronFeedPropertiesTest {

    @Test
    void defaults_appliedWhenNullInputs() {
        var props = new AeronFeedProperties(null, null, null, 0);

        assertThat(props.ecn()).isEqualTo("UNKNOWN");
        assertThat(props.dir()).isEqualTo("/dev/shm/aeron/driver");
        assertThat(props.channel()).isEqualTo("aeron:ipc");
        assertThat(props.streamId()).isEqualTo(3000);
    }

    @Test
    void defaults_appliedWhenBlankStrings() {
        var props = new AeronFeedProperties("", "  ", "  ", 0);

        assertThat(props.ecn()).isEqualTo("UNKNOWN");
        assertThat(props.dir()).isEqualTo("/dev/shm/aeron/driver");
        assertThat(props.channel()).isEqualTo("aeron:ipc");
    }

    @Test
    void explicitValues_arePreserved() {
        var props = new AeronFeedProperties("EURONEXT", "/tmp/aeron", "aeron:ipc", 3001);

        assertThat(props.ecn()).isEqualTo("EURONEXT");
        assertThat(props.dir()).isEqualTo("/tmp/aeron");
        assertThat(props.channel()).isEqualTo("aeron:ipc");
        assertThat(props.streamId()).isEqualTo(3001);
    }
}
