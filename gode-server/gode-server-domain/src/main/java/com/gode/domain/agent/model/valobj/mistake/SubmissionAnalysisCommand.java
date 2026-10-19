package com.gode.domain.agent.model.valobj.mistake;

import lombok.Data;

/** 生成复盘草稿的输入，不进行数据库写入。 */
@Data
public class SubmissionAnalysisCommand {
    private String agentId;
    private String userId;
    private String problemId;
    private String problemSlug;
    private String title;
    private String difficulty;
    private String tags;
    private String problemDescription;
    private String submittedCode;
    private String language;
    private String verdict;
    private String failCase;
    private String sourceSessionId;
}
