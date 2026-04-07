package com.utest.gen.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OpenCode Server 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "opencode")
public class OpenCodeProperties {

    /**
     * 是否启用 OpenCode 服务
     */
    private boolean enabled = true;

    /**
     * OpenCode Server 端口
     */
    private int port = 3000;

    /**
     * OpenCode Server 启动超时时间（秒）
     */
    private int startupTimeout = 30;

    /**
     * OpenCode 二进制文件目录（相对于 classpath）
     */
    private String binaryDir = "opencode";

    /**
     * 项目根目录（OpenCode LSP 需要知道项目路径）
     */
    private String projectRoot;

    /**
     * 请求超时时间（毫秒）
     */
    private int requestTimeout = 120000;

    /**
     * 生成配置
     */
    private GenerationConfig generation = new GenerationConfig();

    /**
     * 修复配置
     */
    private FixConfig fix = new FixConfig();

    @Data
    public static class GenerationConfig {
        private int timeout = 120000;
        private int maxTokens = 4096;
        private double temperature = 0.7;
    }

    @Data
    public static class FixConfig {
        private int maxRounds = 2;
        private int compileTimeout = 30000;
        private int testTimeout = 60000;
    }
}
