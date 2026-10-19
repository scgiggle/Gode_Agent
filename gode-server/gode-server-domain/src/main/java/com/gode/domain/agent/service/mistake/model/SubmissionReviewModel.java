package com.gode.domain.agent.service.mistake.model;

/** 隔离模型调用，便于无需真实 API 的复盘业务测试。 */
public interface SubmissionReviewModel {
    String analyze(String agentId, String systemInstruction, String submissionJson);
}
