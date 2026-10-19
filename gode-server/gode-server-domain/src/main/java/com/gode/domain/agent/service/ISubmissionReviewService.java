package com.gode.domain.agent.service;

import com.gode.domain.agent.model.valobj.mistake.MistakeDraftVO;
import com.gode.domain.agent.model.valobj.mistake.SubmissionAnalysisCommand;

public interface ISubmissionReviewService {
    /** 生成草稿，不保存错题，也不修改复习状态。 */
    MistakeDraftVO analyze(SubmissionAnalysisCommand command);
}
