package com.gode.domain.agent.service.prompt.dynamic;

import com.gode.domain.agent.adapter.repository.IChatHistoryRepository;
import com.gode.domain.agent.model.valobj.prompt.MilestoneVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 里程碑追踪器，基于正则规则自动识别用户纠偏、任务完成、任务切换、工具报错等关键事件，并按会话维度缓存。
 */

@Slf4j
@Component
public class MilestoneTracker {

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    private static final int MAX_MILESTONES = 50;
    private final Map<String, LinkedList<MilestoneVO>> milestones = new ConcurrentHashMap<>();

    public void detectAndRecord(String sessionId, String role, String content) {
        if (sessionId == null || content == null || content.isEmpty()) return;

        MilestoneVO.Type type = null;

        if ("user".equals(role)) {
            if (matches(content, "不对|不是这样|改一下|换个思路|换种方式|错了")) {
                type = MilestoneVO.Type.TASK_CHANGE;
            } else if (matches(content, "完成了|搞定|结束|好了")) {
                type = MilestoneVO.Type.TASK_COMPLETE;
            } else if (matches(content, "不要|停|别")) {
                type = MilestoneVO.Type.USER_CORRECTION;
            }
        }

        // 如果实际测试中还发现有兜不住的，可以继续完善。
        if ("tool".equals(role)) {
            if (matches(content, "(?i)error|failed|exception|permission denied|not found|refused")) {
                type = MilestoneVO.Type.ERROR;
            }
        }

        if (type != null) {
            push(sessionId, MilestoneVO.builder()
                    .type(type)
                    .content(truncate(content, 200))
                    .timestamp(System.currentTimeMillis())
                    .build());
            log.info("里程碑记录: sessionId={}, type={}, content={}", sessionId, type, truncate(content, 100));
        }
    }

    private void push(String sessionId, MilestoneVO milestoneVO) {
        // 1. 内存缓存（ConcurrentHashMap + LinkedList，滑动窗口 50 条）
        LinkedList<MilestoneVO> list = milestones.computeIfAbsent(sessionId, k -> new LinkedList<>());
        synchronized (list) {
            list.addLast(milestoneVO);
            while (list.size() > MAX_MILESTONES) {
                list.removeFirst();
            }
        }

        // 2. 数据库持久化（2-8 新增）：try-catch 旁路写入，失败不影响主流程。
        // 旁路写入原则：记忆持久化是"锦上添花"而非"生死攸关"，不能因为 DB 异常导致 Agent 不可用。
        try {
            chatHistoryRepository.saveMilestone(sessionId, milestoneVO);
        } catch (Exception e) {
            log.error("保存里程碑失败 sessionId={}", sessionId, e);
        }
    }

    public List<MilestoneVO> getRecent(String sessionId, int limit) {
        // DB 优先查询（2-8 新增）：重启后从 DB 恢复里程碑数据，保证持久化不丢失。
        try {
            List<MilestoneVO> recentMilestones = chatHistoryRepository.getRecentMilestones(sessionId, limit);
            if (recentMilestones != null && !recentMilestones.isEmpty()) {
                // DB 查询返回的是倒序，这里转为正序返回
                List<MilestoneVO> reversed = new ArrayList<>(recentMilestones);
                Collections.reverse(reversed);
                return reversed;
            }
        } catch (Exception e) {
            log.error("获取近期里程碑失败 sessionId={}", sessionId, e);
        }

        // DB 查询失败或无数据时降级到内存缓存
        LinkedList<MilestoneVO> list = milestones.getOrDefault(sessionId, new LinkedList<>());
        synchronized (list) {
            int from = Math.max(0, list.size() - limit);
            return new ArrayList<>(list.subList(from, list.size()));
        }
    }

    public void clear(String sessionId) {
        milestones.remove(sessionId);
    }

    private boolean matches(String content, String regex) {
        return Pattern.compile(".*(" + regex + ").*").matcher(content).matches();
    }

    /**
     * 截断字符串到指定长度，超出部分用 "..." 表示。
     *
     * @param s   原始字符串，为 null 时返回空串
     * @param max 最大保留长度
     * @return 截断后的字符串
     */
    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

}
