package com.utest.gen.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utest.gen.model.TestGenResponse;
import com.utest.gen.service.OpenCodeAutoGenService;
import com.utest.gen.opencode.OpenCodeManager;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 测试生成 REST API
 * 极简设计：后端只负责接收请求和收集结果，所有工作由 OpenCode Agent 自主完成
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
public class TestGenController {

    @Autowired
    private OpenCodeAutoGenService openCodeAutoGenService;

    @Autowired
    private OpenCodeManager openCodeManager;

    /**
     * 生成单元测试（单个类）
     * 
     * 后端只需传入：源文件路径 + 待测方法列表
     * Agent 自主完成：上下文提取 → LLM生成 → 编译 → 修复 → 运行测试
     * 后端只收集最终结果
     */
    @PostMapping("/generate")
    public ResponseEntity<TestGenResponse> generate(@Valid @RequestBody GenerateRequest request) {
        log.info("收到生成请求: sourceFile={}, methods={}",
                request.getSourceFile(), request.getMethodNames());
        
        TestGenResponse response = openCodeAutoGenService.autoGenerate(
                request.getSourceFile(),
                request.getMethodNames()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * 批量生成单元测试（多个类）
     * 
     * 支持同时传入多个类的待测方法，并行处理
     * 每个类独立调用 Agent 完成全流程
     */
    @PostMapping("/generate-batch")
    public ResponseEntity<BatchGenerateResponse> generateBatch(
            @Valid @RequestBody BatchGenerateRequest request) {
        log.info("收到批量生成请求: 类数={}", request.getClasses().size());
        
        long startTime = System.currentTimeMillis();
        
        // 并行处理每个类的测试生成
        List<CompletableFuture<ClassResult>> futures = request.getClasses().stream()
                .map(classRequest -> CompletableFuture.supplyAsync(() -> {
                    long classStartTime = System.currentTimeMillis();
                    TestGenResponse response = openCodeAutoGenService.autoGenerate(
                            classRequest.getSourceFile(),
                            classRequest.getMethodNames()
                    );
                    long classDuration = System.currentTimeMillis() - classStartTime;
                    
                    return ClassResult.builder()
                            .sourceFile(classRequest.getSourceFile())
                            .methodNames(classRequest.getMethodNames())
                            .success(response.isSuccess())
                            .testClassName(response.getTestClassName())
                            .testFilePath(response.getTestFilePath())
                            .errorMessage(response.getErrorMessage())
                            .durationMs(classDuration)
                            .build();
                }))
                .collect(Collectors.toList());
        
        // 收集所有结果
        List<ClassResult> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
        
        long totalDuration = System.currentTimeMillis() - startTime;
        long successCount = results.stream().filter(ClassResult::isSuccess).count();
        
        BatchGenerateResponse response = BatchGenerateResponse.builder()
                .success(successCount == results.size())
                .total(results.size())
                .successCount((int) successCount)
                .failedCount(results.size() - (int) successCount)
                .totalDurationMs(totalDuration)
                .results(results)
                .build();
        
        log.info("批量生成完成: 总数={}, 成功={}, 失败={}, 耗时={}ms",
                results.size(), successCount, results.size() - successCount, totalDuration);
        
        return ResponseEntity.ok(response);
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /**
     * 获取服务状态
     */
    @GetMapping("/status")
    public ResponseEntity<StatusResponse> status() {
        return ResponseEntity.ok(StatusResponse.builder()
                .status("running")
                .openCodeServer(openCodeManager.isRunning() ? "running" : "stopped")
                .lspReady(openCodeManager.healthCheck())
                .version("1.0.0")
                .build());
    }

    /**
     * 查询会话详情
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<SessionDetailResponse> getSession(@PathVariable String sessionId) {
        try {
            String sessionJson = openCodeManager.getSession(sessionId);
            JsonNode session = new ObjectMapper().readTree(sessionJson);
            
            return ResponseEntity.ok(SessionDetailResponse.builder()
                    .id(session.path("id").asText())
                    .title(session.path("title").asText(null))
                    .parentID(session.path("parentID").asText(null))
                    .created(session.path("created").asText(null))
                    .updated(session.path("updated").asText(null))
                    .shareID(session.path("shareID").asText(null))
                    .rawJson(sessionJson)
                    .build());
        } catch (Exception e) {
            log.error("查询会话详情失败: sessionId={}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(SessionDetailResponse.builder()
                            .id(sessionId)
                            .errorMessage("查询失败: " + e.getMessage())
                            .build());
        }
    }

    /**
     * 列出所有会话
     */
    @GetMapping("/sessions")
    public ResponseEntity<SessionListResponse> listSessions() {
        try {
            String sessionsJson = openCodeManager.listSessions();
            JsonNode sessions = new ObjectMapper().readTree(sessionsJson);
            
            List<SessionSummary> sessionList = new ArrayList<>();
            if (sessions.isArray()) {
                for (JsonNode s : sessions) {
                    sessionList.add(SessionSummary.builder()
                            .id(s.path("id").asText())
                            .title(s.path("title").asText(null))
                            .created(s.path("created").asText(null))
                            .build());
                }
            }
            
            return ResponseEntity.ok(SessionListResponse.builder()
                    .total(sessionList.size())
                    .sessions(sessionList)
                    .build());
        } catch (Exception e) {
            log.error("列出会话失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(SessionListResponse.builder()
                            .errorMessage("查询失败: " + e.getMessage())
                            .build());
        }
    }

    /**
     * 生成请求
     * 极简参数：只需源文件路径和待测方法列表
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GenerateRequest {
        @jakarta.validation.constraints.NotBlank(message = "源文件路径不能为空")
        private String sourceFile;

        @jakarta.validation.constraints.NotEmpty(message = "方法列表不能为空")
        private List<String> methodNames;
    }

    /**
     * 状态响应
     */
    @lombok.Data
    @lombok.Builder
    public static class StatusResponse {
        private String status;
        private String openCodeServer;
        private boolean lspReady;
        private String version;
    }

    /**
     * 批量生成请求
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BatchGenerateRequest {
        @jakarta.validation.constraints.NotEmpty(message = "类列表不能为空")
        private List<GenerateRequest> classes;
    }

    /**
     * 批量生成响应
     */
    @lombok.Data
    @lombok.Builder
    public static class BatchGenerateResponse {
        private boolean success;
        private int total;
        private int successCount;
        private int failedCount;
        private long totalDurationMs;
        private List<ClassResult> results;
    }

    /**
     * 单个类的生成结果
     */
    @lombok.Data
    @lombok.Builder
    public static class ClassResult {
        private String sourceFile;
        private List<String> methodNames;
        private boolean success;
        private String testClassName;
        private String testFilePath;
        private String errorMessage;
        private long durationMs;
    }

    /**
     * 会话详情响应
     */
    @lombok.Data
    @lombok.Builder
    public static class SessionDetailResponse {
        private String id;
        private String title;
        private String parentID;
        private String created;
        private String updated;
        private String shareID;
        private String errorMessage;
        private String rawJson;
    }

    /**
     * 会话列表响应
     */
    @lombok.Data
    @lombok.Builder
    public static class SessionListResponse {
        private int total;
        private List<SessionSummary> sessions;
        private String errorMessage;
    }

    /**
     * 会话摘要
     */
    @lombok.Data
    @lombok.Builder
    public static class SessionSummary {
        private String id;
        private String title;
        private String created;
    }
}
