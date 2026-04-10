package com.utest.gen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 生成请求
 * 极简参数：只需源文件路径和待测方法列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateRequest {
    @NotBlank(message = "源文件路径不能为空")
    private String sourceFile;

    @NotEmpty(message = "方法列表不能为空")
    private List<String> methodNames;
}
