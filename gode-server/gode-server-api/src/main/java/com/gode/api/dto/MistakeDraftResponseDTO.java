package com.gode.api.dto;

import lombok.Data;

/** AI 复盘草稿，用户确认后调用已有 save 接口。 */
@Data
public class MistakeDraftResponseDTO {
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
