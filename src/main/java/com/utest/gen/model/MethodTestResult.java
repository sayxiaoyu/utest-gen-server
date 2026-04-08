package com.utest.gen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 方法的测试结果
 * 包含该方法生成的所有测试函数信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodTestResult {
    /**
     * 被测试的方法名
     */
    private String methodName;

    /**
     * 该方法生成的测试函数列表
     */
    private List<TestFunction> testFunctions;

    /**
     * 该方法在测试类中的起始行号
     */
    private int startLine;

    /**
     * 该方法在测试类中的结束行号
     */
    private int endLine;
}
