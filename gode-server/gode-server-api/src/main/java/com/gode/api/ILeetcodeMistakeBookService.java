package com.gode.api;

import com.gode.api.dto.MistakeBookPageResponseDTO;
import com.gode.api.dto.MistakeDetailResponseDTO;
import com.gode.api.dto.MistakeReviewRequestDTO;
import com.gode.api.dto.SaveMistakeRequestDTO;
import com.gode.api.dto.AnalyzeSubmissionRequestDTO;
import com.gode.api.dto.MistakeDraftResponseDTO;
import com.gode.api.response.Response;

/** 错题本 HTTP 接口契约；业务实现位于 domain 模块。 */
public interface ILeetcodeMistakeBookService {
    /** 生成草稿，用户确认前不入库。 */
    Response<MistakeDraftResponseDTO> analyzeSubmission(AnalyzeSubmissionRequestDTO requestDTO);

    Response<Long> saveMistake(SaveMistakeRequestDTO requestDTO);

    Response<MistakeBookPageResponseDTO> queryPage(String userId, String status, String tag, String keyword, Integer page, Integer pageSize);

    Response<MistakeDetailResponseDTO> queryDetail(String userId, Long mistakeId);

    Response<Boolean> startReview(MistakeReviewRequestDTO requestDTO);

    Response<Boolean> markMastered(MistakeReviewRequestDTO requestDTO);

    /** 删除整道错题及全部关联复盘。 */
    Response<Boolean> deleteMistake(MistakeReviewRequestDTO requestDTO);
}
