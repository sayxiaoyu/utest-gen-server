package com.utest.gen.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 提示词模板配置属性
 * 用于管理测试生成时使用的提示词模板
 */
@Data
@Component
@ConfigurationProperties(prefix = "prompt-template")
public class PromptTemplateProperties {

    /**
     * 是否启用外部提示词模板
     */
    private boolean externalEnabled = false;

    /**
     * 提示词模板文件路径
     */
    private String templatePath = "classpath:templates/";

    /**
     * 类级别提示词模板
     */
    private String classLevelTemplate;

    /**
     * 修复提示词模板
     */
    private String fixTemplate;

    /**
     * 是否启用热重载
     */
    private boolean hotReload = false;

    /**
     * 缓存有效期（毫秒）
     */
    private long cacheMillis = 300000;
}
