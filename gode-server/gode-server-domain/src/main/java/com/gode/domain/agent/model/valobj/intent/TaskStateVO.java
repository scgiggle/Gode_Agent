package com.gode.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStateVO {

    /** 当前任务描述（由主模型或意图层归纳） */
    private String taskDescription;

    /** 任务对应的根意图（如 COMPOUND / PROBLEM_ANALYSIS / CODE_REVIEW） */
    private IntentTypeEnumVO rootIntent;

    /** 子步骤列表（按执行顺序） */
    @Builder.Default
    private List<String> steps = new LinkedList<>();

    /** 当前执行到的步骤下标（从 0 开始，-1 表示尚未开始） */
    @Builder.Default
    private int currentStepIndex = -1;

    /** 任务是否已完成 */
    @Builder.Default
    private boolean completed = false;

    /** 最近一次反馈是否失败（用于触发重分类） */
    @Builder.Default
    private boolean lastFeedbackFailed = false;

}
