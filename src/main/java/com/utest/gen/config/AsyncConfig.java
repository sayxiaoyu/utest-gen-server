package com.utest.gen.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.Executor;

/**
 * 异步配置
 * 启用异步方法执行
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 结果同步线程池
     */
    @Bean("syncExecutor")
    public Executor syncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("result-sync-");
        executor.initialize();
        return executor;
    }
}
