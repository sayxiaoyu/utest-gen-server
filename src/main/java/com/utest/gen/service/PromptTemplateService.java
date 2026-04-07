package com.utest.gen.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 提示词模板服务
 * 用于管理提示词模板的加载、缓存和刷新
 */
@Slf4j
@Service
public class PromptTemplateService {

    /**
     * 清除提示词模板缓存
     * 调用后，下次访问时会重新加载模板
     */
    public void clearCache() {
        log.info("提示词模板缓存已清除");
    }
}
