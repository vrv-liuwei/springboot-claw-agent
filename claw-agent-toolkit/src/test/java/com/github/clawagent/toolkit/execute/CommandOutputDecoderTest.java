package com.github.clawagent.toolkit.execute;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandOutputDecoderTest {
    @Test
    void keepsUtf8OutputWhenBytesAreValidUtf8() {
        String value = "显示协议统计信息";

        String decoded = CommandOutputDecoder.decode(value.getBytes(StandardCharsets.UTF_8), "stderr");

        assertEquals(value, decoded);
    }

    @Test
    void fallsBackToWindowsChineseConsoleEncodingWhenUtf8IsInvalid() {
        String value = "显示协议统计信息";

        String decoded = CommandOutputDecoder.decode(value.getBytes(Charset.forName("GBK")), "stderr");

        assertEquals(value, decoded);
    }
}
