package com.github.clawagent.toolkit.websearch;

import com.github.clawagent.toolkit.websearch.bocha.BochaWebSearchProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebSearchProviderFactoryTest {
    @Test
    void createsBochaProviderByDefault() {
        assertInstanceOf(BochaWebSearchProvider.class, WebSearchProviderFactory.create(Map.of()));
    }

    @Test
    void rejectsUnknownProvider() {
        assertThrows(IllegalArgumentException.class,
                () -> WebSearchProviderFactory.create(Map.of("PROVIDER", "unknown")));
    }
}
