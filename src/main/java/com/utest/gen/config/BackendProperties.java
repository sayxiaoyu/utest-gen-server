package com.utest.gen.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 远程后端服务配置属性
 * 用于调用远程 Java 后端的测试生成接口
 */
@Data
@Component
@ConfigurationProperties(prefix = "backend")
public class BackendProperties {

    /**
     * 远程后端服务地址
     */
    private String apiUrl = "http://127.0.0.1:8080";

    /**
     * API Key（可选）
     */
    private String apiKey;

    /**
     * 请求超时时间（毫秒）
     */
    private int timeout = 60000;

    /**
     * 是否启用结果同步
     */
    private boolean syncEnabled = true;
}
