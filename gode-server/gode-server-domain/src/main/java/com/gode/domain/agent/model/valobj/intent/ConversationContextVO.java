package com.gode.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedList;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationContextVO {

    /** 最近 N 条意图历史（滑动窗口） */
    private LinkedList<IntentHistoryEntryVO> recentIntents;

    /** 对话轮次 */
    private int turnCount;

    /** 会话开始时间 */
    private long sessionStartTime;

    /** 最近一次活跃时间（用于过期淘汰） */
    private long lastActiveTime;

    /** 上一次识别到的意图 */
    private IntentTypeEnumVO lastIntent;

    /** 当前任务态（支撑 CONTINUE 与多步任务自纠错） */
    private TaskStateVO taskState;

    /** 连续意图失败次数（达到阈值后强制全交主模型） */
    @Builder.Default
    private int consecutiveFailures = 0;

}
