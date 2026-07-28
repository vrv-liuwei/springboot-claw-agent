package com.github.clawagent.intent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统意图目录加载器。
 * <p>
 * 默认从模块资源目录读取 system-intents.yml，并转换为运行期可用的 IntentDefinition。
 */
public class IntentCatalogLoader {
    private static final Logger log = LoggerFactory.getLogger(IntentCatalogLoader.class);
    private static final String DEFAULT_CATALOG = "clawagent/intents/system-intents.yml";
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    /**
     * 加载内置系统意图目录，并为没有单独配置 threshold 的意图填充全局默认阈值。
     */
    public List<IntentDefinition> loadDefaultCatalog(double defaultThreshold) {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(DEFAULT_CATALOG)) {
            if (input == null) {
                log.warn("intent catalog not found path={}", DEFAULT_CATALOG);
                return List.of();
            }
            Map<String, Object> root = mapper.readValue(input, new TypeReference<Map<String, Object>>() {});
            Object rawIntents = root.get("intents");
            if (!(rawIntents instanceof Map<?, ?> map)) {
                return List.of();
            }
            List<IntentDefinition> definitions = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String id = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof Map<?, ?> value) {
                    definitions.add(toDefinition(id, value).withDefaults(defaultThreshold));
                }
            }
            return definitions;
        } catch (Exception e) {
            throw new IllegalStateException("加载系统意图目录失败：" + e.getMessage(), e);
        }
    }

    /**
     * 将 YAML 中的一条意图配置转换成强类型定义。
     */
    private IntentDefinition toDefinition(String id, Map<?, ?> value) {
        Map<String, String> metadata = new LinkedHashMap<>();
        Object rawMetadata = value.get("metadata");
        if (rawMetadata instanceof Map<?, ?> map) {
            map.forEach((key, val) -> metadata.put(String.valueOf(key), val == null ? "" : String.valueOf(val)));
        }
        return new IntentDefinition(
                id,
                string(value.get("name"), id),
                IntentRisk.from(string(value.get("risk"), "low")),
                IntentRouteMode.from(string(value.get("route"), "handler")),
                string(value.get("handler"), ""),
                doubleValue(value.get("threshold")),
                metadata,
                stringList(value.get("examples")),
                stringList(value.get("negativeExamples")));
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }
}
