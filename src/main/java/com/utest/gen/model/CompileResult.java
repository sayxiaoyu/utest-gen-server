package com.utest.gen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 编译结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompileResult {
    private boolean success;
    private String errorMessage;
}
