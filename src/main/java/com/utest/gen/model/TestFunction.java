package com.utest.gen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 测试函数信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestFunction {
    /**
     * 测试函数名
     */
    private String functionName;

    /**
     * 测试函数在测试类中的行号
     */
    private int lineNumber;

    /**
     * 测试场景描述
     */
    private String scenario;

    /**
     * 测试函数代码
     */
    private String code;
}
