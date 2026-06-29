package com.github.clawagent.toolkit.execute;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 子进程输出解码器。
 * Windows 控制台命令可能输出 GBK/OEM 字节，而 Git、Node 等工具常输出 UTF-8。
 */
public final class CommandOutputDecoder {
    private CommandOutputDecoder() {
    }

    public static String decode(byte[] bytes, String streamName) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        // 先严格判断 UTF-8。合法 UTF-8 直接返回，避免被本地 GBK 默认编码误解码。
        String utf8 = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        for (Charset charset : fallbackCharsets(streamName)) {
            String decoded = decodeStrict(bytes, charset);
            if (decoded != null) {
                return decoded;
            }
        }
        return new String(bytes, Charset.defaultCharset());
    }

    private static String decodeStrict(byte[] bytes, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static List<Charset> fallbackCharsets(String streamName) {
        Set<Charset> charsets = new LinkedHashSet<>();
        addPropertyCharset(charsets, "stderr".equalsIgnoreCase(streamName) ? "sun.stderr.encoding" : "sun.stdout.encoding");
        charsets.add(Charset.defaultCharset());
        if (isWindows()) {
            addNamedCharset(charsets, "GBK");
            addNamedCharset(charsets, "GB18030");
            addNamedCharset(charsets, "MS936");
        }
        charsets.add(StandardCharsets.UTF_8);
        return new ArrayList<>(charsets);
    }

    private static void addPropertyCharset(Set<Charset> charsets, String propertyName) {
        String value = System.getProperty(propertyName);
        if (value != null && !value.isBlank()) {
            addNamedCharset(charsets, value.trim());
        }
    }

    private static void addNamedCharset(Set<Charset> charsets, String name) {
        try {
            charsets.add(Charset.forName(name));
        } catch (RuntimeException ignored) {
            // 某些精简 JRE 可能没有对应别名，跳过后继续尝试其他编码。
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
