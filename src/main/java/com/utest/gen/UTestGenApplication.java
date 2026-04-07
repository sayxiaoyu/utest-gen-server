package com.utest.gen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * AI 单元测试生成服务启动类
 */
@SpringBootApplication
@EnableConfigurationProperties
public class UTestGenApplication {

    public static void main(String[] args) {
        SpringApplication.run(UTestGenApplication.class, args);
    }
}
