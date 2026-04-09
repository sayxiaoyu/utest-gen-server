package com.utest.gen.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单个类的生成结果
 */
@Data
@Builder
public class ClassResult {
    private String sourceFile;
    private List<String> methodNames;
    private boolean success;
    private String testClassName;
    private String testFilePath;
    private String errorMessage;
    private long durationMs;
}
