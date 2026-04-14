package com.utest.gen.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 配置属性（供 OpenCode 使用）
 * OpenCode 启动时会读取此配置连接内网大模型
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** provider.<id> 的键名 */
    private String providerId = "custom";
    /**
     * LLM API 地址
     */
    private String apiUrl = "http://127.0.0.1:8080/v1";

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 模型名称
     */
    private String model = "gpt-4";

    /**
     * 最大 Token 数
     */
    private int maxTokens = 8192;

    /** limit.context（可选，建议配置） */
    private Integer context = 200000;

    /** limit.output（可选，建议配置） */
    private Integer output = 8192;

    /**
     * 温度参数
     */
    private double temperature = 0.7;
}
