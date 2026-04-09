package com.utest.gen.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量生成请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchGenerateRequest {
    @NotEmpty(message = "类列表不能为空")
    private List<GenerateRequest> classes;
}
