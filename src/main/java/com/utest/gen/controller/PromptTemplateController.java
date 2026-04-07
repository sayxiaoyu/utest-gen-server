package com.utest.gen.controller;

import com.utest.gen.config.PromptTemplateProperties;
import com.utest.gen.service.PromptTemplateService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 提示词模板管理接口
 * 用于动态查看和刷新提示词模板
 */
@Slf4j
@RestController
@RequestMapping("/api/prompt")
public class PromptTemplateController {

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private PromptTemplateProperties promptTemplateProperties;

    /**
     * 获取当前提示词模板配置
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("externalEnabled", promptTemplateProperties.isExternalEnabled());
        config.put("templatePath", promptTemplateProperties.getTemplatePath());
        config.put("classLevelTemplate", promptTemplateProperties.getClassLevelTemplate());
        config.put("fixTemplate", promptTemplateProperties.getFixTemplate());
        config.put("hotReload", promptTemplateProperties.isHotReload());
        config.put("cacheMillis", promptTemplateProperties.getCacheMillis());
        return ResponseEntity.ok(config);
    }

    /**
     * 刷新提示词模板缓存
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh() {
        promptTemplateService.clearCache();
        log.info("提示词模板缓存已手动刷新");

        Map<String, String> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "提示词模板缓存已刷新");
        return ResponseEntity.ok(result);
    }

    /**
     * 更新提示词模板配置（部分更新）
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody PromptConfigUpdateRequest request) {
        // 注意：这里只是更新内存中的配置，重启后会恢复
        // 如需持久化，需要写入配置文件

        if (request.getExternalEnabled() != null) {
            promptTemplateProperties.setExternalEnabled(request.getExternalEnabled());
        }
        if (request.getHotReload() != null) {
            promptTemplateProperties.setHotReload(request.getHotReload());
        }
        if (request.getCacheMillis() != null) {
            promptTemplateProperties.setCacheMillis(request.getCacheMillis());
        }

        // 清除缓存使新配置生效
        promptTemplateService.clearCache();

        log.info("提示词模板配置已更新");
        return getConfig();
    }

    /**
     * 配置更新请求
     */
    @Data
    public static class PromptConfigUpdateRequest {
        private Boolean externalEnabled;
        private Boolean hotReload;
        private Long cacheMillis;
    }
}
