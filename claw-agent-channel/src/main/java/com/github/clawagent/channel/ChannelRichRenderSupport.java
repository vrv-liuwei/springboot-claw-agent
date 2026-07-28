package com.github.clawagent.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ChannelRichRenderSupport 把不同 IM 平台的卡片/富文本压成统一可审查摘要。
 * 这里不还原平台专有 UI，只输出稳定的 Markdown 文本、动作列表和原始结构预览。
 */
public final class ChannelRichRenderSupport {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ChannelRichRenderSupport() {
    }

    public static Map<String, String> richAttachment(String source, String type, String messageType,
                                                     String explicitTitle, Object rawContent) {
        String title = firstNonBlank(explicitTitle, extractTitle(rawContent));
        String plainText = extractPlainText(rawContent);
        String actions = extractActions(rawContent);
        String renderText = renderMarkdown(title, plainText, actions);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("messageType", safeValue(messageType, type));
        values.put("title", title);
        values.put("summary", firstNonBlank(title, truncate(plainText, 160)));
        values.put("contentPreview", truncate(plainText, 1000));
        values.put("actions", actions);
        values.put("renderFormat", "markdown");
        values.put("renderText", truncate(renderText, 2000));
        values.put("renderStatus", renderText.isBlank() ? "metadata-only" : "rendered");
        values.put("rawContentPreview", truncate(compactJson(rawContent), 2000));
        return ChannelMediaSupport.attachment(source, type, values);
    }

    private static String renderMarkdown(String title, String plainText, String actions) {
        List<String> lines = new ArrayList<>();
        if (!title.isBlank()) {
            lines.add("### " + title);
        }
        if (!plainText.isBlank()) {
            lines.add(plainText);
        }
        if (!actions.isBlank()) {
            lines.add("操作：" + actions);
        }
        return String.join("\n\n", lines).trim();
    }

    private static String extractTitle(Object value) {
        if (value instanceof Map<?, ?> map) {
            String direct = firstNonBlank(textField(map, "title"), textField(map, "name"), textField(map, "headline"));
            if (!direct.isBlank()) {
                return direct;
            }
            return firstNonBlank(
                    extractTitle(map.get("header")),
                    extractTitle(map.get("card")),
                    extractTitle(map.get("markdown")),
                    extractTitle(map.get("actionCard")));
        }
        if (value instanceof JsonNode node) {
            return firstNonBlank(
                    textAt(node, "title"),
                    textAt(node, "name"),
                    textAt(node, "headline"),
                    extractTitle(node.path("header")),
                    extractTitle(node.path("card")),
                    extractTitle(node.path("markdown")),
                    extractTitle(node.path("actionCard")));
        }
        return "";
    }

    private static String extractPlainText(Object value) {
        StringBuilder builder = new StringBuilder();
        appendPlainText(value, builder, 0);
        return builder.toString().replaceAll("\\s+", " ").trim();
    }

    @SuppressWarnings("unchecked")
    private static void appendPlainText(Object value, StringBuilder builder, int depth) {
        if (value == null || builder.length() >= 1200 || depth > 8) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            // 只抽取用户可见字段，避免把平台内部 id、样式和签名字段塞进模型上下文。
            for (String key : List.of("text", "title", "content", "value", "summary", "description", "elements", "actions", "btns")) {
                if (map.containsKey(key)) {
                    appendPlainText(map.get(key), builder, depth + 1);
                }
            }
            return;
        }
        if (value instanceof JsonNode node) {
            if (node.isObject()) {
                for (String key : List.of("text", "title", "content", "value", "summary", "description", "elements", "actions", "btns")) {
                    appendPlainText(node.path(key), builder, depth + 1);
                }
                return;
            }
            if (node.isArray()) {
                node.forEach(item -> appendPlainText(item, builder, depth + 1));
                return;
            }
            appendText(builder, node.asText(""));
            return;
        }
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                appendPlainText(item, builder, depth + 1);
            }
            return;
        }
        appendText(builder, safeValue(value, ""));
    }

    private static String extractActions(Object value) {
        List<String> actions = new ArrayList<>();
        collectActions(value, actions, 0);
        return String.join(" | ", actions);
    }

    private static void collectActions(Object value, List<String> actions, int depth) {
        if (value == null || actions.size() >= 8 || depth > 8) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            String label = firstNonBlank(textField(map, "text"), textField(map, "title"), textField(map, "singleTitle"), textField(map, "name"));
            String url = firstNonBlank(textField(map, "url"), textField(map, "actionURL"),
                    textField(map, "singleURL"), textField(map, "multi_url"), textField(map, "pc_url"));
            if (!label.isBlank() && (!url.isBlank() || hasAnyKey(map, "value", "action", "tag"))) {
                actions.add(url.isBlank() ? label : label + " -> " + url);
            }
            for (String key : List.of("actions", "btns", "buttons", "elements")) {
                Object nested = map.get(key);
                if (nested != null) {
                    collectActions(nested, actions, depth + 1);
                }
            }
            return;
        }
        if (value instanceof JsonNode node) {
            if (node.isObject()) {
                String label = firstNonBlank(textAt(node, "text"), textAt(node, "title"), textAt(node, "singleTitle"), textAt(node, "name"));
                String url = firstNonBlank(textAt(node, "url"), textAt(node, "actionURL"),
                        textAt(node, "singleURL"), textAt(node, "multi_url"), textAt(node, "pc_url"));
                if (!label.isBlank() && (!url.isBlank() || node.has("value") || node.has("action") || node.has("tag"))) {
                    actions.add(url.isBlank() ? label : label + " -> " + url);
                }
                for (String key : List.of("actions", "btns", "buttons", "elements")) {
                    collectActions(node.path(key), actions, depth + 1);
                }
                return;
            }
            if (node.isArray()) {
                Iterator<JsonNode> iterator = node.elements();
                while (iterator.hasNext()) {
                    collectActions(iterator.next(), actions, depth + 1);
                }
            }
            return;
        }
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                collectActions(item, actions, depth + 1);
            }
        }
    }

    private static boolean hasAnyKey(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static String textField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map<?, ?> nested) {
            return firstNonBlank(textField(nested, "content"), textField(nested, "text"), textField(nested, "title"));
        }
        if (value instanceof JsonNode node) {
            return firstNonBlank(textAt(node, "content"), textAt(node, "text"), textAt(node, "title"), node.asText(""));
        }
        return safeValue(value, "");
    }

    private static String textAt(JsonNode root, String field) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return "";
        }
        JsonNode value = root.path(field);
        if (value.isObject()) {
            return firstNonBlank(textAt(value, "content"), textAt(value, "text"), textAt(value, "title"));
        }
        return value.isValueNode() ? value.asText("") : "";
    }

    private static void appendText(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(text.trim());
    }

    private static String compactJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ignored) {
            return safeValue(value, "");
        }
    }

    private static String truncate(String value, int maxChars) {
        String text = safeValue(value, "");
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String safeValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }
}
