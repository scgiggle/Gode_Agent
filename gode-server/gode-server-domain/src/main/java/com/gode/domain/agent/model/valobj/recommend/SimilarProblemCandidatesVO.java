package com.gode.domain.agent.model.valobj.recommend;

import com.gode.domain.agent.model.valobj.SimilarProblemVO;
import java.util.List;

/** 相似题候选池，以及力扣真实返回的当前题难度和标签。 */
public record SimilarProblemCandidatesVO(String currentDifficulty, List<String> currentTopicTags,
                                        List<SimilarProblemVO> candidates, boolean metadataAvailable) { }
