package com.github.clawagent.server.dto;

/**
 * 单个模型的成本规则。
 *
 * @param inputPerMillion 输入 Token 每百万单价。
 * @param outputPerMillion 输出 Token 每百万单价。
 * @param currency 可选币种覆盖。
 */
public record CostRuleView(
        double inputPerMillion,
        double outputPerMillion,
        String currency
) {
}
