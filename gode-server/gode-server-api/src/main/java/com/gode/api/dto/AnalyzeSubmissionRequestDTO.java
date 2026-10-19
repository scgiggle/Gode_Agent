package com.gode.api.dto;

import lombok.Data;

/** 失败提交的代码快照和真实判题结果；不包含登录 Cookie。 */
@Data
public class AnalyzeSubmissionRequestDTO {
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
