package com.gode.trigger.http;

import com.gode.api.dto.LeetcodeMistakeDTO;
import com.gode.api.dto.LeetcodeMistakeReviewDTO;
import com.gode.api.dto.MistakeBookPageResponseDTO;
import com.gode.api.dto.MistakeDetailResponseDTO;
import com.gode.api.dto.MistakeReviewRequestDTO;
import com.gode.api.dto.SaveMistakeRequestDTO;
import com.gode.api.dto.AnalyzeSubmissionRequestDTO;
import com.gode.api.dto.MistakeDraftResponseDTO;
import com.gode.api.response.Response;
import com.gode.domain.agent.model.entity.LeetcodeMistakeEntity;
import com.gode.domain.agent.model.entity.LeetcodeMistakeReviewEntity;
import com.gode.domain.agent.model.valobj.mistake.MistakeBookPageVO;
import com.gode.domain.agent.model.valobj.mistake.MistakeDetailVO;
import com.gode.domain.agent.model.valobj.mistake.SaveMistakeCommand;
import com.gode.domain.agent.service.ILeetcodeMistakeBookService;
import com.gode.domain.agent.model.valobj.mistake.SubmissionAnalysisCommand;
import com.gode.domain.agent.model.valobj.mistake.MistakeDraftVO;
import com.gode.domain.agent.service.ISubmissionReviewService;
import com.gode.types.enums.ResponseCode;
import com.gode.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.function.Supplier;

@Slf4j
@RestController
@RequestMapping(value = "/api/v1/mistake", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
public class LeetcodeMistakeBookController implements com.gode.api.ILeetcodeMistakeBookService {

    @Resource
    private ILeetcodeMistakeBookService mistakeBookService;

    @Resource
    private ISubmissionReviewService submissionReviewService;

    /** 分析失败提交，只返回草稿；确认保存仍走 save 接口。 */
    @Override
    @PostMapping(value = "/analyze_submission", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response<MistakeDraftResponseDTO> analyzeSubmission(@RequestBody AnalyzeSubmissionRequestDTO requestDTO) {
        return execute("生成提交复盘", () -> {
            requireRequest(requestDTO);
            SubmissionAnalysisCommand command = new SubmissionAnalysisCommand();
            command.setAgentId(requestDTO.getAgentId());
            command.setUserId(requestDTO.getUserId());
            command.setProblemId(requestDTO.getProblemId());
            command.setProblemSlug(requestDTO.getProblemSlug());
            command.setTitle(requestDTO.getTitle());
            command.setDifficulty(requestDTO.getDifficulty());
            command.setTags(requestDTO.getTags());
            command.setProblemDescription(requestDTO.getProblemDescription());
            command.setSubmittedCode(requestDTO.getSubmittedCode());
            command.setLanguage(requestDTO.getLanguage());
            command.setVerdict(requestDTO.getVerdict());
            command.setFailCase(requestDTO.getFailCase());
            command.setSourceSessionId(requestDTO.getSourceSessionId());
            MistakeDraftVO draft = submissionReviewService.analyze(command);
            MistakeDraftResponseDTO response = new MistakeDraftResponseDTO();
            response.setProblemId(draft.getProblemId());
            response.setProblemSlug(draft.getProblemSlug());
            response.setTitle(draft.getTitle());
            response.setDifficulty(draft.getDifficulty());
            response.setTags(draft.getTags());
            response.setErrorSummary(draft.getErrorSummary());
            response.setFailReason(draft.getFailReason());
            response.setFailCase(draft.getFailCase());
            response.setAiAnalysis(draft.getAiAnalysis());
            response.setActionPlan(draft.getActionPlan());
            response.setSourceSessionId(draft.getSourceSessionId());
            return response;
        });
    }

    /** 保存错题及本次复盘，返回错题主记录 ID。 */
    @Override
    @PostMapping(value = "/save", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response<Long> saveMistake(@RequestBody SaveMistakeRequestDTO requestDTO) {
        return execute("保存错题", () -> {
            requireRequest(requestDTO);
            SaveMistakeCommand command = SaveMistakeCommand.builder()
                    .problemId(requestDTO.getProblemId())
                    .problemSlug(requestDTO.getProblemSlug())
                    .title(requestDTO.getTitle())
                    .difficulty(requestDTO.getDifficulty())
                    .errorSummary(requestDTO.getErrorSummary())
                    .tags(requestDTO.getTags())
                    .failReason(requestDTO.getFailReason())
                    .failCase(requestDTO.getFailCase())
                    .aiAnalysis(requestDTO.getAiAnalysis())
                    .actionPlan(requestDTO.getActionPlan())
                    .sourceSessionId(requestDTO.getSourceSessionId())
                    .build();
            return mistakeBookService.saveMistake(requestDTO.getUserId(), command);
        });
    }

    /** 分页查询当前用户的错题；页码从 1 开始，默认每页 10 条。 */
    @Override
    @GetMapping("/page")
    public Response<MistakeBookPageResponseDTO> queryPage(
            @RequestParam("userId") String userId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {
        return execute("分页查询错题", () -> {
            MistakeBookPageVO pageVO = mistakeBookService.queryPage(
                    userId, status, tag, keyword, page, pageSize);
            MistakeBookPageResponseDTO responseDTO = new MistakeBookPageResponseDTO();
            responseDTO.setRecords(pageVO.getRecords().stream().map(this::toMistakeDTO).toList());
            responseDTO.setTotal(pageVO.getTotal());
            responseDTO.setPage(pageVO.getPage());
            responseDTO.setPageSize(pageVO.getPageSize());
            responseDTO.setTotalPages(pageVO.getTotalPages());
            return responseDTO;
        });
    }

    /** 查询错题及完整复盘历史，保留最新复盘字段；归属校验由 Service 执行。 */
    @Override
    @GetMapping("/detail")
    public Response<MistakeDetailResponseDTO> queryDetail(
            @RequestParam("userId") String userId,
            @RequestParam("mistakeId") Long mistakeId) {
        return execute("查询错题详情", () -> {
            MistakeDetailVO detail = mistakeBookService.queryDetail(userId, mistakeId);
            MistakeDetailResponseDTO responseDTO = new MistakeDetailResponseDTO();
            responseDTO.setMistake(toMistakeDTO(detail.getMistake()));
            responseDTO.setLatestReview(toReviewDTO(detail.getLatestReview()));
            responseDTO.setReviews(detail.getReviews().stream().map(this::toReviewDTO).toList());
            return responseDTO;
        });
    }

    /** 将当前用户的错题标记为复习中，不增加完成次数。 */
    @Override
    @PostMapping(value = "/start_review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response<Boolean> startReview(@RequestBody MistakeReviewRequestDTO requestDTO) {
        return execute("开始复习错题", () -> {
            requireRequest(requestDTO);
            mistakeBookService.startReview(requestDTO.getUserId(), requestDTO.getMistakeId());
            return true;
        });
    }

    /** 用户确认掌握后更新状态及完成次数，不进行自动判题。 */
    @Override
    @PostMapping(value = "/mark_mastered", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response<Boolean> markMastered(@RequestBody MistakeReviewRequestDTO requestDTO) {
        return execute("标记错题掌握", () -> {
            requireRequest(requestDTO);
            mistakeBookService.markMastered(requestDTO.getUserId(), requestDTO.getMistakeId());
            return true;
        });
    }

    /** 删除整道错题及其全部复盘；归属校验和事务由 Service 执行。 */
    @Override
    @PostMapping(value = "/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response<Boolean> deleteMistake(@RequestBody MistakeReviewRequestDTO requestDTO) {
        return execute("删除错题", () -> {
            requireRequest(requestDTO);
            mistakeBookService.deleteMistake(requestDTO.getUserId(), requestDTO.getMistakeId());
            return true;
        });
    }

    /** 统一包装成功、业务异常和未知异常，避免向前端暴露内部异常细节。 */
    private <T> Response<T> execute(String operation, Supplier<T> action) {
        try {
            T data = action.get();
            log.info("错题本接口完成 operation={}", operation);
            return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
        } catch (AppException e) {
            log.warn("错题本接口业务异常 operation={} code={} info={}", operation, e.getCode(), e.getInfo());
            return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("错题本接口失败 operation={}", operation, e);
            return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    /** 拒绝空请求体；具体字段的校验复用 Service。 */
    private void requireRequest(Object requestDTO) {
        if (requestDTO == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "请求内容不能为空");
        }
    }

    /** 将领域主记录转换为 API 对象，避免接口层直接返回领域实体。 */
    private LeetcodeMistakeDTO toMistakeDTO(LeetcodeMistakeEntity entity) {
        LeetcodeMistakeDTO dto = new LeetcodeMistakeDTO();
        dto.setId(entity.getId());
        dto.setUserId(entity.getUserId());
        dto.setProblemId(entity.getProblemId());
        dto.setProblemSlug(entity.getProblemSlug());
        dto.setTitle(entity.getTitle());
        dto.setDifficulty(entity.getDifficulty());
        dto.setErrorSummary(entity.getErrorSummary());
        dto.setTags(entity.getTags());
        dto.setStatus(entity.getStatus());
        dto.setNextReviewTime(entity.getNextReviewTime());
        dto.setReviewCount(entity.getReviewCount());
        dto.setSourceSessionId(entity.getSourceSessionId());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    /** 将复盘转换为 API 对象；尚无复盘时返回 null。 */
    private LeetcodeMistakeReviewDTO toReviewDTO(LeetcodeMistakeReviewEntity entity) {
        if (entity == null) return null;
        LeetcodeMistakeReviewDTO dto = new LeetcodeMistakeReviewDTO();
        dto.setId(entity.getId());
        dto.setMistakeId(entity.getMistakeId());
        dto.setFailReason(entity.getFailReason());
        dto.setFailCase(entity.getFailCase());
        dto.setAiAnalysis(entity.getAiAnalysis());
        dto.setActionPlan(entity.getActionPlan());
        dto.setSourceSessionId(entity.getSourceSessionId());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
