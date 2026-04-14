package com.utest.gen.dbagent;

import com.utest.gen.config.DbMcpProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DB MCP Server 配置
 * <p>
 * 当 db-mcp.enabled=true 时，将 {@link DbMcpServer} 中的 @Tool 注解方法注册为 MCP Tools，
 * 由 Spring AI MCP Server 在 /mcp 端点暴露。
 * <p>
 * 核心：通过 {@link MethodToolCallbackProvider} 将 @Tool 方法转换为 MCP ToolCallback，
 * Spring AI MCP Server 自动配置会扫描 ToolCallbackProvider Bean 并注册工具。
 *
 * @see DbMcpServer
 * @see DbMcpProperties
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "db-mcp.enabled", havingValue = "true")
public class DbMcpServerConfig {

    /**
     * 将 DbMcpServer 中的 @Tool 注解方法注册为 MCP Tools。
     * <p>
     * Spring AI MCP Server 自动配置会扫描所有 ToolCallbackProvider Bean，
     * 将其中的工具注册到 /mcp 端点，供 OpenCode 调用。
     */
    @Bean
    public ToolCallbackProvider dbMcpToolProvider(DbMcpServer dbMcpServer) {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(dbMcpServer)
                .build();
        log.info("DB MCP Server 工具已注册: configure_datasource, data_query, data_query_batch, tables_list, schema_get, health_check");
        return provider;
    }

    public DbMcpServerConfig(DbMcpProperties properties) {
        log.info("DB MCP Server 配置加载完成:");
        log.info("  enabled: true");
        log.info("  queryLimit.maxRows: {}", properties.getQueryLimit().getMaxRows());
        log.info("  queryLimit.timeoutSeconds: {}", properties.getQueryLimit().getTimeoutSeconds());
        log.info("  MCP Streamable HTTP 端点: /mcp");
        log.info("  数据源: 等待 Agent 通过 configure_datasource 工具动态配置");
    }
}
