package com.utest.gen.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * DB Agent MCP Server 配置属性
 * <p>
 * 数据源由 OpenCode 启动后的 bootstrap 任务自动配置：
 * 读取被测项目的 application.yml 并调用 configure_datasource MCP 工具。
 * 此处仅配置 MCP Server 自身的开关和安全限制。
 */
@Data
@Component
@ConfigurationProperties(prefix = "db-mcp")
public class DbMcpProperties {

    /**
     * 是否启用 DB MCP Server
     */
    private boolean enabled = false;

    /**
     * 查询限制配置
     */
    private QueryLimitConfig queryLimit = new QueryLimitConfig();

    /**
     * 安全配置
     */
    private SecurityConfig security = new SecurityConfig();

    @Data
    public static class QueryLimitConfig {
        /** 每表最大返回行数 */
        private int maxRows = 10;
        /** 查询超时（秒） */
        private int timeoutSeconds = 30;
        /** 最大列数 */
        private int maxColumns = 50;
    }

    @Data
    public static class SecurityConfig {
        /** 允许的 SQL 操作 */
        private List<String> allowedOperations = List.of("SELECT");
        /** 禁止的 SQL 关键字 */
        private List<String> forbiddenKeywords = Arrays.asList(
                "DELETE", "DROP", "TRUNCATE", "UPDATE", "INSERT",
                "CREATE", "ALTER", "GRANT", "REVOKE"
        );
    }
}
