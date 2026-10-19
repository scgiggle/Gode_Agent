package com.gode.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentResultVO {

    /** 意图类型 */
    private IntentTypeEnumVO intent;

    /** 置信度 0.0 ~ 1.0 */
    private double confidence;

    /** 识别出的实体（服务名、配置项等） */
    private Map<String, String> entities;

    /** LLM 原始响应（调试用） */
    private String rawResponse;

    /**
     * 候选意图（按置信度降序，不含主意图本身）
     * <p>
     * 当主意图置信度不足时，下游主模型可参考候选意图自行判断，
     * 而不是被迫接受一个低置信度的硬选结果。
     */
    @Builder.Default
    private List<IntentTypeEnumVO> candidateIntents = List.of();

    /** 是否由反馈回路触发重分类得到（调试/可观测用） */
    @Builder.Default
    private boolean reclassified = false;

}
