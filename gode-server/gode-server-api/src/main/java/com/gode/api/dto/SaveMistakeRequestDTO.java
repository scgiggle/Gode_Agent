package com.gode.api.dto;

import lombok.Data;

/** 保存用户确认后的错题草稿，不在 Controller 中调用 AI。 */
@Data
public class SaveMistakeRequestDTO {
    private String userId;
    /** 力扣题号，例如 1。 */
    private String problemId;
    /** 力扣地址标识，例如 two-sum。 */
    private String problemSlug;
    private String title;
    /** 简单 / 中等 / 困难。 */
    private String difficulty;
    private String errorSummary;
    /** 英文逗号分隔，例如 数组,哈希表。 */
    private String tags;
    private String failReason;
    private String failCase;
    private String aiAnalysis;
    private String actionPlan;
    private String sourceSessionId;
}
