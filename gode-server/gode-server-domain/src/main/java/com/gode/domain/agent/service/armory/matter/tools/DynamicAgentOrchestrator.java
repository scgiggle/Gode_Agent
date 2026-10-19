package com.gode.domain.agent.service.armory.matter.tools;

import com.gode.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import com.gode.domain.agent.model.valobj.dynamic.DynamicTask;
import com.gode.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import com.gode.domain.agent.model.valobj.dynamic.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
public class DynamicAgentOrchestrator {
    private final SubAgentDispatchService dispatchService;
    private final Executor executor;

    public DynamicAgentOrchestrator(SubAgentDispatchService dispatchService, Executor executor) {
        this.dispatchService = dispatchService;
        this.executor = executor;
    }

    /**
     * 执行整个任务计划（阻塞直至全部任务到达终态）。
     *
     * @param context 子 Agent 执行上下文（携带终端会话等）
     * @param plan    经校验的任务计划
     * @return {plan, tasks(含状态/结果), allSucceeded}
     */
    public Map<String, Object> execute(AgentExecutionContext context, DynamicTaskPlan plan) {
        // taskId -> task 索引，便于依赖查询与状态更新
        Map<String, DynamicTask> tasks = new HashMap<>();
        plan.getTasks().forEach(task -> tasks.put(task.getTaskId(), task));

        // 并发额度：同一时刻最多 maxConcurrency 个任务在执行
        Semaphore concurrency = new Semaphore(Math.max(1, plan.getMaxConcurrency()));

        // 调度循环：每轮执行一批"依赖已全部满足"的就绪任务
        while (tasks.values().stream().anyMatch(task -> task.getStatus() == TaskStatus.PENDING)) {
            // failFast：已有任务失败，不再调度新任务，剩余 PENDING 全部置 SKIPPED 后退出
            if (Boolean.TRUE.equals(plan.getFailFast())
                    && tasks.values().stream().anyMatch(task -> task.getStatus() == TaskStatus.FAILED)) {
                tasks.values().stream()
                        .filter(task -> task.getStatus() == TaskStatus.PENDING)
                        .forEach(task -> {
                            task.setStatus(TaskStatus.SKIPPED);
                            task.setError("skipped by failFast: another task failed");
                        });
                break;
            }

            // 级联跳过：依赖了 FAILED/SKIPPED 任务的 PENDING 任务显式置 SKIPPED（沿依赖链逐轮传播）
            tasks.values().stream()
                    .filter(task -> task.getStatus() == TaskStatus.PENDING)
                    .filter(task -> task.getDependsOn().stream()
                            .map(tasks::get)
                            .anyMatch(parent -> parent == null
                                    || parent.getStatus() == TaskStatus.FAILED
                                    || parent.getStatus() == TaskStatus.SKIPPED))
                    .forEach(task -> {
                        task.setStatus(TaskStatus.SKIPPED);
                        task.setError("skipped: dependency failed or skipped");
                    });

            // 就绪任务：PENDING 且所有依赖任务均已 COMPLETED
            List<DynamicTask> readyTasks = tasks.values().stream()
                    .filter(task -> task.getStatus() == TaskStatus.PENDING)
                    .filter(task -> task.getDependsOn().stream()
                            .map(tasks::get)
                            .allMatch(parent -> parent != null && parent.getStatus() == TaskStatus.COMPLETED))
                    .toList();

            // 无就绪任务：剩余 PENDING 任务依赖关系无法满足（校验器已排除环，此处兜底），提前退出
            if (readyTasks.isEmpty()) {
                break;
            }

            // 并发执行本轮就绪任务：信号量限流，异常任务置 FAILED 不中断整体
            List<CompletableFuture<Void>> futures = readyTasks.stream()
                    .map(task -> CompletableFuture.runAsync(() -> {
                        try {
                            concurrency.acquire();
                            dispatchService.execute(context, task);
                        } catch (Exception exception) {
                            task.setStatus(TaskStatus.FAILED);
                            task.setError(exception.getMessage());
                        } finally {
                            concurrency.release();
                        }
                    }, executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }

        // 汇总返回：计划、各任务最终状态（含成功/失败/跳过数）、是否全部成功
        Map<String, Object> response = new HashMap<>();

        response.put("plan", plan);
        response.put("tasks", new ArrayList<>(tasks.values()));
        response.put("allSucceeded", tasks.values().stream().allMatch(task -> task.getStatus() == TaskStatus.COMPLETED));
        response.put("failedCount", tasks.values().stream().filter(task -> task.getStatus() == TaskStatus.FAILED).count());
        response.put("skippedCount", tasks.values().stream().filter(task -> task.getStatus() == TaskStatus.SKIPPED).count());

        return response;
    }

}
