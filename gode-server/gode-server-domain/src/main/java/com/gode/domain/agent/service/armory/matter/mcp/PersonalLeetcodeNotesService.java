package com.gode.domain.agent.service.armory.matter.mcp;

import com.gode.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.gode.types.exception.AppException;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.adk.tools.mcp.McpToolset;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 单人、内存态笔记配置。不是多租户凭据仓库，不允许在公网开放配置接口。 */
@Slf4j
@Service
public class PersonalLeetcodeNotesService implements BaseToolset {
    private static final Set<String> REQUIRED_TOOLS = Set.of("get_user_status", "get_note", "create_note", "update_note");
    private static final Pattern COOKIE_FIELD = Pattern.compile("(?:^|;\\s*)LEETCODE_SESSION=([^;\\s]+)");
    private final FileSystemMcpToolsetFactory factory;
    private final boolean enabled;
    private final Object configurationLock = new Object();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private volatile Generation current;
    // 只保存调用标识和版本，不保存 Cookie；同一轮执行不能在更新后换账号。
    private final Map<String, InvocationBinding> invocationBindings = new LinkedHashMap<>();
    private long nextGeneration;
    private AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters template;
    private String agentId;

    public PersonalLeetcodeNotesService(FileSystemMcpToolsetFactory factory,
            @Value("${gode.personal-notes.enabled:false}") boolean enabled) {
        this.factory = factory;
        this.enabled = enabled;
    }

    /** 在装配时挂载稳定的 Toolset 门面；Cookie 更新不需要重建父子 Agent。 */
    public BaseToolset register(String agentId,
            AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters config) {
        synchronized (configurationLock) {
            if (template != null) throw invalid("个人笔记配置仅支持装配一个笔记助手");
            this.agentId = agentId;
            this.template = config;
            String cookie = config.getServerParameters().getEnv() == null ? ""
                    : config.getServerParameters().getEnv().getOrDefault("LEETCODE_SESSION", "");
            current = new Generation(++nextGeneration, factory.create(config), cookie != null && !cookie.isBlank());
            return this;
        }
    }

    /** 只返回状态，不返回 Cookie、认证响应或笔记内容。 */
    public Status status(String agentId) {
        requireEnabledAndAgent(agentId);
        Generation value = current;
        return new Status(true, value.configured, value.verified, value.verified);
    }

    /** 新连接通过工具发现和只读笔记验证后才替换旧连接；失败保持旧配置。 */
    public Status save(String agentId, String suppliedCookie) {
        synchronized (configurationLock) {
            requireEnabledAndAgent(agentId);
            String cookie = normalizeCookie(suppliedCookie);
            Generation candidate = new Generation(++nextGeneration, factory.create(withCookie(cookie)), true);
            boolean installed = false;
            try {
                List<BaseTool> tools = candidate.tools();
                Set<String> names = tools.stream().map(BaseTool::name).collect(java.util.stream.Collectors.toSet());
                if (!names.containsAll(REQUIRED_TOOLS)) {
                    throw invalid("笔记工具未就绪，请检查 Cookie、MCP 版本和服务器网络");
                }
                BaseTool login = tools.stream().filter(tool -> "get_user_status".equals(tool.name())).findFirst().orElseThrow();
                Map<String, Object> loginResult = login.runAsync(Map.of(), null).blockingGet();
                if (containsFailure(loginResult) || !containsSignedIn(loginResult)) {
                    throw invalid("力扣登录验证失败，请检查 Cookie 是否有效及服务器能否访问力扣");
                }
                BaseTool read = tools.stream().filter(tool -> "get_note".equals(tool.name())).findFirst().orElseThrow();
                // 两数之和 questionId=1；空笔记列表也算成功，绝不自动创建笔记。
                Map<String, Object> result = read.runAsync(Map.of("questionId", "1", "limit", 1, "skip", 0), null).blockingGet();
                if (containsFailure(result) || !containsNotes(result)) {
                    throw invalid("力扣登录验证失败，请检查 Cookie 是否有效及服务器能否访问力扣");
                }
                candidate.verified = true;
                replace(candidate);
                installed = true;
                log.info("[PersonalNotes] 配置验证成功 agentId={} tools={}", agentId, names);
                return status(agentId);
            } catch (AppException exception) {
                throw exception;
            } catch (Exception exception) {
                // 异常正文可能含第三方认证信息，不能回显或记录。
                log.warn("[PersonalNotes] 配置验证失败 errorType={}", exception.getClass().getSimpleName());
                throw invalid("笔记连接验证失败，请检查 Cookie、MCP 启动和服务器网络");
            } finally {
                if (!installed) candidate.close();
            }
        }
    }

    /** 清除插件提供的认证，同时不回退到 .env 中原先的 Cookie。 */
    public Status clear(String agentId) {
        synchronized (configurationLock) {
            requireEnabledAndAgent(agentId);
            replace(new Generation(++nextGeneration, factory.create(withCookie("")), false));
            log.info("[PersonalNotes] 已清除登录配置 agentId={}", agentId);
            return status(agentId);
        }
    }

    /** 每次模型需要工具时读取当前连接，而不是缓存启动时没有认证的工具列表。 */
    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext context) {
        return Flowable.defer(() -> {
            lifecycle.readLock().lock();
            try {
                Generation generation = current;
                if (generation == null) return Flowable.error(invalid("笔记助手尚未装配"));
                pinInvocation(context, generation);
                return Flowable.fromIterable(generation.tools().stream()
                        .map(tool -> (BaseTool) new GuardedTool(generation, tool)).toList());
            } finally {
                lifecycle.readLock().unlock();
            }
        });
    }

    private void pinInvocation(ReadonlyContext context, Generation generation) {
        if (context == null || context.invocationId() == null) return;
        synchronized (invocationBindings) {
            long now = System.nanoTime();
            long retention = java.util.concurrent.TimeUnit.MINUTES.toNanos(30);
            invocationBindings.values().removeIf(binding -> now - binding.lastUsed > retention);
            String id = context.invocationId();
            InvocationBinding binding = invocationBindings.get(id);
            if (binding == null) {
                if (invocationBindings.size() >= 512) throw invalid("笔记任务过多，请稍后重试");
                invocationBindings.put(id, new InvocationBinding(generation.version, now));
            } else {
                binding.lastUsed = now;
                if (binding.version != generation.version) {
                    throw invalid("笔记登录配置已更新，本轮任务已停止，请重新发起请求");
                }
            }
        }
    }

    /** 等待已开始的工具操作结束后替换；旧模型请求不能误用新账号继续写入。 */
    private void replace(Generation replacement) {
        lifecycle.writeLock().lock();
        try {
            Generation previous = current;
            current = replacement;
            if (previous != null) previous.close();
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (configurationLock) {
            replace(null);
        }
    }

    private void requireEnabledAndAgent(String requestedAgent) {
        if (!enabled) throw invalid("后端未启用个人笔记配置，请设置 GODE_PERSONAL_NOTES_ENABLED=true 后重启");
        if (template == null || !agentId.equals(requestedAgent)) throw invalid("当前 Agent 没有可配置的笔记助手");
    }

    private AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters withCookie(String cookie) {
        var copy = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters();
        copy.setName(template.getName());
        copy.setRequestTimeout(template.getRequestTimeout());
        List<String> toolNames = new ArrayList<>(template.getToolNames());
        if (!toolNames.contains("get_user_status")) toolNames.add("get_user_status");
        copy.setToolNames(List.copyOf(toolNames));
        var parameters = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters.ServerParameters();
        parameters.setCommand(template.getServerParameters().getCommand());
        parameters.setArgs(template.getServerParameters().getArgs());
        Map<String, String> env = new LinkedHashMap<>();
        if (template.getServerParameters().getEnv() != null) env.putAll(template.getServerParameters().getEnv());
        env.put("LEETCODE_SESSION", cookie);
        parameters.setEnv(env);
        copy.setServerParameters(parameters);
        return copy;
    }

    static String normalizeCookie(String input) {
        if (input == null || input.isBlank()) throw invalid("请填写 LEETCODE_SESSION Cookie");
        if (input.length() > 16384 || input.contains("\n") || input.contains("\r")) throw invalid("Cookie 格式或长度不正确");
        String cookie = input.trim();
        Matcher field = COOKIE_FIELD.matcher(cookie);
        if (field.find()) cookie = field.group(1);
        if (!cookie.matches("[A-Za-z0-9._~+/%=-]+")) throw invalid("请填写 LEETCODE_SESSION 的值，不要填写 JSON 或其他凭据");
        return cookie;
    }

    private static boolean containsFailure(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("error") != null || Boolean.TRUE.equals(map.get("isError"))
                    || Boolean.FALSE.equals(map.get("success"))) return true;
            return map.values().stream().anyMatch(PersonalLeetcodeNotesService::containsFailure);
        }
        return value instanceof Collection<?> list && list.stream().anyMatch(PersonalLeetcodeNotesService::containsFailure);
    }

    private static boolean containsSignedIn(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (Boolean.TRUE.equals(map.get("isSignedIn"))) return true;
            return map.values().stream().anyMatch(PersonalLeetcodeNotesService::containsSignedIn);
        }
        return value instanceof Collection<?> list && list.stream().anyMatch(PersonalLeetcodeNotesService::containsSignedIn);
    }

    private static boolean containsNotes(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("notes") instanceof Collection<?>) return true;
            return map.values().stream().anyMatch(PersonalLeetcodeNotesService::containsNotes);
        }
        return value instanceof Collection<?> list && list.stream().anyMatch(PersonalLeetcodeNotesService::containsNotes);
    }

    private static AppException invalid(String message) { return new AppException("0002", message); }

    public record Status(boolean enabled, boolean configured, boolean verified, boolean writeToolsAvailable) {}

    private static final class Generation {
        private final long version;
        private final McpToolset toolset;
        private final boolean configured;
        private boolean verified;
        private List<BaseTool> loadedTools;
        private Generation(long version, McpToolset toolset, boolean configured) {
            this.version = version;
            this.toolset = toolset;
            this.configured = configured;
        }
        private synchronized List<BaseTool> tools() {
            if (loadedTools == null) loadedTools = List.copyOf(toolset.getTools(null).toList().blockingGet());
            return loadedTools;
        }
        private void close() { toolset.close(); }
    }

    private static final class InvocationBinding {
        private final long version;
        private long lastUsed;
        private InvocationBinding(long version, long lastUsed) { this.version = version; this.lastUsed = lastUsed; }
    }

    private final class GuardedTool extends BaseTool {
        private final Generation owner;
        private final BaseTool delegate;
        private GuardedTool(Generation owner, BaseTool delegate) {
            super(delegate.name(), delegate.description(), delegate.longRunning());
            this.owner = owner;
            this.delegate = delegate;
        }
        @Override
        public Optional<FunctionDeclaration> declaration() { return delegate.declaration(); }
        @Override
        public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
            return Single.fromCallable(() -> {
                lifecycle.readLock().lock();
                try {
                    if (owner != current) return Map.of("error", "笔记登录配置已更新，请重新发起请求；本次操作未执行");
                    log.info("[PersonalNotes] 调用笔记工具 name={}", name());
                    return delegate.runAsync(args, context).blockingGet();
                } finally {
                    lifecycle.readLock().unlock();
                }
            });
        }
    }
}
