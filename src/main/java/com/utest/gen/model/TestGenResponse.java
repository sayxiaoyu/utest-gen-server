package com.utest.gen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 测试生成响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestGenResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 测试文件路径
     */
    private String testFilePath;

    /**
     * 测试类名
     */
    private String testClassName;

    /**
     * 测试代码
     */
    private String testCode;

    /**
     * 测试场景列表
     */
    private List<String> testScenarios;

    /**
     * 编译结果
     */
    private CompileResult compileResult;

    /**
     * 测试运行结果
     */
    private TestResult testResult;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 修复轮数
     */
    @Builder.Default
    private int fixRounds = 0;

    /**
     * 修复历史
     */
    @Builder.Default
    private List<String> fixHistory = new java.util.ArrayList<>();

    /**
     * 方法级别的测试结果列表
     */
    private List<MethodTestResult> methodResults;

    /**
     * 创建成功响应
     */
    public static TestGenResponse success(String testFilePath, String testClassName,
                                          String testCode, List<String> scenarios) {
        return TestGenResponse.builder()
                .success(true)
                .testFilePath(testFilePath)
                .testClassName(testClassName)
                .testCode(testCode)
                .testScenarios(scenarios)
                .build();
    }

    /**
     * 创建失败响应
     */
    public static TestGenResponse failure(String errorMessage) {
        return TestGenResponse.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
