package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * Token 成本估算配置视图。
 *
 * @param currency 默认币种。
 * @param rules 按模型 ID 或真实模型名配置的每百万 Token 单价。
 */
public record CostConfigView(
        String currency,
        Map<String, CostRuleView> rules
) {
}
