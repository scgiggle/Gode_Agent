package com.gode.domain.agent.service;

import com.gode.domain.agent.model.valobj.SimilarProblemVO;
import com.gode.domain.agent.model.valobj.recommend.SimilarProblemCandidatesVO;

import java.util.List;

public interface ISimilarProblemService {

    List<SimilarProblemVO> recommend(String currentProblemSlug, int count);

    /** 扩大候选池后再个性化排序；默认实现兼容原有提供者。 */
    default SimilarProblemCandidatesVO candidates(String currentProblemSlug, int limit) {
        return new SimilarProblemCandidatesVO(null, List.of(), recommend(currentProblemSlug, Math.min(limit, 5)), false);
    }

}
