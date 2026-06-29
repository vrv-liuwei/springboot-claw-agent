package com.github.clawagent.toolkit.execute;

/**
 * 命令风险评估结果。
 *
 * @param riskLevel 风险等级：low / medium / high。
 * @param category 风险分类，用于管理台和审计展示。
 * @param approvalRequired 是否必须走审批。
 * @param reason 触发该分类的简要原因。
 */
public record CommandRiskAssessment(String riskLevel, String category, boolean approvalRequired, String reason) {
    public static CommandRiskAssessment low(String category, String reason) {
        return new CommandRiskAssessment("low", category, false, reason);
    }

    public static CommandRiskAssessment medium(String category, String reason) {
        return new CommandRiskAssessment("medium", category, false, reason);
    }

    public static CommandRiskAssessment high(String category, String reason) {
        return new CommandRiskAssessment("high", category, true, reason);
    }
}
