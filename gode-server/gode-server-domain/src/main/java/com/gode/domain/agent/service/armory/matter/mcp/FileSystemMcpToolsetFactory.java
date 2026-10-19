package com.gode.domain.agent.service.armory.matter.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.google.adk.tools.mcp.McpSessionManager;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StdioConnectionParameters;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@AllArgsConstructor
public class FileSystemMcpToolsetFactory {

    private final ObjectMapper objectMapper;

    public McpToolset create(
            AiAgentConfigTableVO.Module.ChatModel.ToolMcp
                    .StdioServerParameters config) {

        if (config == null) {
            throw new IllegalArgumentException(
                    "Filesystem MCP配置不能为空"
            );
        }

        AiAgentConfigTableVO.Module.ChatModel.ToolMcp
                .StdioServerParameters.ServerParameters
                serverConfig = config.getServerParameters();

        if (serverConfig == null) {
            throw new IllegalArgumentException(
                    "Filesystem MCP serverParameters不能为空"
            );
        }

        com.google.adk.tools.mcp.StdioServerParameters
                serverParameters =
                com.google.adk.tools.mcp.StdioServerParameters
                        .builder()
                        .command(serverConfig.getCommand())
                        .args(serverConfig.getArgs())
                        .env(serverConfig.getEnv())
                        .build();

        int timeoutMillis =
                config.getRequestTimeout() == null
                        ? 30000
                        : config.getRequestTimeout();

        StdioConnectionParameters connectionParameters =
                StdioConnectionParameters.builder()
                        .serverParams(serverParameters)
                        // Google ADK这里使用秒
                        .timeout(timeoutMillis / 1000.0f)
                        .build();

        List<String> toolNames = config.getToolNames();

        if (toolNames == null || toolNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "Filesystem MCP必须配置允许使用的toolNames"
            );
        }

        log.info(
                "[FilesystemMCP] 创建Toolset name={} command={} args={} toolNames={}",
                config.getName(),
                serverConfig.getCommand(),
                serverConfig.getArgs(),
                toolNames
        );

        McpSessionManager sessionManager =
                new McpSessionManager(connectionParameters);

        return new McpToolset(
                sessionManager,
                objectMapper,
                toolNames
        );
    }
}