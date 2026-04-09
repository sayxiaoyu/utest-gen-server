package com.utest.gen.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 批量生成响应
 */
@Data
@Builder
public class BatchGenerateResponse {
    private boolean success;
    private int total;
    private int successCount;
    private int failedCount;
    private long totalDurationMs;
    private List<ClassResult> results;
}
