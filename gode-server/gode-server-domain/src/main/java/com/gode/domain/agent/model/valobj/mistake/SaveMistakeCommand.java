package com.gode.domain.agent.model.valobj.mistake;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户确认后的错题草稿；AI 生成草稿不在数据库事务内执行。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveMistakeCommand {
    private String problemId;
    private String problemSlug;
    private String title;
    private String difficulty;
    private String errorSummary;
    /** 英文逗号分隔的标签。 */
    private String tags;
    private String failReason;
    private String failCase;
    private String aiAnalysis;
    private String actionPlan;
    private String sourceSessionId;
}
