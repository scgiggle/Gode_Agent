package com.gode.domain.agent.model.valobj.mistake;

import lombok.Data;

/** 结构化复盘草稿，真实用例由后端保留，不能被模型替换。 */
@Data
public class MistakeDraftVO {
    private String problemId;
    private String problemSlug;
    private String title;
    private String difficulty;
    private String tags;
    private String errorSummary;
    private String failReason;
    private String failCase;
    private String aiAnalysis;
    private String actionPlan;
    private String sourceSessionId;
}
