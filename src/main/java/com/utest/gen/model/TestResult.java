package com.utest.gen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 测试结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestResult {
    private boolean success;
    private int passed;
    private int failed;
    private String output;
}
