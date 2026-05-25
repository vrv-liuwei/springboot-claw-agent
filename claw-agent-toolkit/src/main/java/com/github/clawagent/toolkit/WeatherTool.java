package com.github.clawagent.toolkit;

import cn.hutool.core.net.URLEncodeUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.core.http.AgentHttpClient;
import com.github.clawagent.spi.AgentTool;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WeatherTool implements AgentTool {
    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("city", ToolDefinition.stringProperty("城市名称，例如 北京、上海、深圳"));
        return ToolDefinition.low(
                "builtin.weather",
                "Weather",
                "通过免费天气接口查询城市当前天气。参数：city。",
                ToolDefinition.objectSchema(properties, false, List.of("city")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String city = call.arguments().getOrDefault("city", "").trim();
        if (city.isBlank()) {
            return ToolResult.error("缺少参数：city");
        }
        try {
            // wttr.in 是免 key 的公开天气接口。这里使用 JSON 格式，避免解析自然语言文本。
            String encodedCity = URLEncodeUtil.encode(city, StandardCharsets.UTF_8);
            String body = AgentHttpClient.get("https://wttr.in/" + encodedCity + "?format=j1", Map.of(), 15_000).body();
            JSONObject root = JSONUtil.parseObj(body);
            JSONArray current = root.getJSONArray("current_condition");
            if (current == null || current.isEmpty()) {
                return ToolResult.error("天气接口没有返回当前天气：" + city);
            }
            JSONObject item = current.getJSONObject(0);
            String description = "";
            JSONArray descriptions = item.getJSONArray("weatherDesc");
            if (descriptions != null && !descriptions.isEmpty()) {
                description = descriptions.getJSONObject(0).getStr("value", "");
            }
            // 输出保持简洁，方便最终回答直接引用，也便于日志审计。
            return ToolResult.success("城市：" + city
                    + "\n天气：" + description
                    + "\n温度：" + item.getStr("temp_C") + "℃"
                    + "\n体感：" + item.getStr("FeelsLikeC") + "℃"
                    + "\n湿度：" + item.getStr("humidity") + "%"
                    + "\n风速：" + item.getStr("windspeedKmph") + "km/h");
        } catch (Exception e) {
            return ToolResult.error("天气查询失败：" + e.getMessage());
        }
    }
}
