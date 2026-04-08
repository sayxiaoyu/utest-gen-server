package com.utest.gen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utest.gen.model.*;
import com.utest.gen.opencode.OpenCodeManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenCode 自动化测试生成服务
 * 通过调用自建的 test-gen-agent 一次性完成全流程：
 * 上下文提取 → LLM生成 → 编译验证 → 自动修复 → 运行测试
 *
 * 后端只需传入：源文件路径 + 待测方法列表
 * Agent 自主完成所有步骤，后端只收集最终结果
 */
@Slf4j
@Service
public class OpenCodeAutoGenService {

    @Autowired
    private OpenCodeManager openCodeManager;

    @Autowired
    private ResultSyncService resultSyncService;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 自动化生成测试 - Agent 自主完成全流程（支持增量更新）
     *
     * Agent 自主完成：
     * 1. 检查测试类文件是否存在
     * 2. 若存在，解析已有测试类，识别各方法的测试函数位置
     * 3. 增量生成：新方法插入，已有方法覆盖更新
     * 4. 编译验证
     * 5. 自动修复（最多2次）
     * 6. 运行测试
     *
     * @param sourceFile  源文件路径（绝对路径）
     * @param methodNames 待测方法名列表
     * @return 生成结果（包含测试代码、编译结果、测试结果、方法级行号信息）
     */
    public TestGenResponse autoGenerate(String sourceFile, List<String> methodNames) {
        String requestId = generateRequestId();
        log.info("开始自动化测试生成: requestId={}, sourceFile={}, methods={}",
                requestId, sourceFile, methodNames);

        long startTime = System.currentTimeMillis();

        try {
            // 构建极简 Agent 请求上下文
            // Agent 通过 Skill 自主推断测试类路径并判断是否存在
            Map<String, Object> context = new HashMap<>();
            context.put("sourceFile", sourceFile);
            context.put("methodNames", methodNames);

            // 调用 OpenCode Agent - test-gen-agent 自主完成全流程（含增量更新）
            Map<String, Object> agentResult = callOpenCodeAgent(context);

            // 解析并收集结果
            TestGenResponse response = parseAgentResult(agentResult);

            long duration = System.currentTimeMillis() - startTime;
            log.info("自动化测试生成完成: requestId={}, duration={}ms, success={}",
                    requestId, duration, response.isSuccess());

            // 异步同步结果到远程后端
            String className = extractClassNameFromSourceFile(sourceFile);
            resultSyncService.syncResult(requestId, className, methodNames, response);

            return response;

        } catch (Exception e) {
            log.error("自动化测试生成失败: requestId={}", requestId, e);
            return TestGenResponse.failure("自动化生成失败: " + e.getMessage());
        }
    }

    /**
     * 调用自建的 test-gen-agent
     * Agent 配置在 .opencode/agents/test-gen-agent.md 中定义
     * 包含完整的工作流程和 Skill 调用指导
     */
    private Map<String, Object> callOpenCodeAgent(Map<String, Object> context) {
        String url = openCodeManager.getServerUrl() + "/api/agent/run";
        log.info("调用 OpenCode Agent: {}, context={}", url, context);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 极简请求：指定 Agent + 传入上下文
            // test-gen-agent 会根据自身配置自主完成所有步骤
            Map<String, Object> request = Map.of(
                    "agent", "test-gen-agent",
                    "context", context,
                    "timeout", 600000  // 10分钟超时（包含编译、运行、修复）
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return response.getBody();
            }

            throw new RuntimeException("OpenCode Agent 返回异常: " + response.getStatusCode());

        } catch (Exception e) {
            log.error("调用 OpenCode Agent 失败", e);
            throw new RuntimeException("Agent 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 Agent 返回的结果
     * Agent 已完成全流程，返回最终结果
     */
    private TestGenResponse parseAgentResult(Map<String, Object> agentResult) {
        try {
            // Agent 返回的结果在 content 或 result 字段中
            String content = (String) agentResult.getOrDefault("content",
                    agentResult.get("result"));

            if (content == null) {
                return TestGenResponse.failure("Agent 返回结果为空");
            }

            // 从内容中提取JSON
            String jsonStr = extractJsonFromContent(content);

            if (jsonStr == null) {
                return TestGenResponse.builder()
                        .success(false)
                        .errorMessage("Agent 返回格式异常: " +
                                content.substring(0, Math.min(200, content.length())))
                        .build();
            }

            // 解析JSON结果
            Map<String, Object> result = objectMapper.readValue(jsonStr, Map.class);

            // 解析方法级别结果（用于增量更新）
            List<MethodTestResult> methodResults = parseMethodResults(
                    (List<Map<String, Object>>) result.get("methodResults"));

            return TestGenResponse.builder()
                    .success((Boolean) result.getOrDefault("success", false))
                    .testCode((String) result.get("testCode"))
                    .testFilePath((String) result.get("testFilePath"))
                    .testClassName((String) result.get("testClassName"))
                    .errorMessage((String) result.get("errorMessage"))
                    .fixRounds((Integer) result.getOrDefault("fixRounds", 0))
                    .compileResult(CompileResult.builder()
                            .success((Boolean) result.getOrDefault("compileSuccess", false))
                            .errorMessage((String) result.get("compileError"))
                            .build())
                    .testResult(TestResult.builder()
                            .success((Boolean) result.getOrDefault("testPassed", false))
                            .passed((Integer) result.getOrDefault("passed", 0))
                            .failed((Integer) result.getOrDefault("failed", 0))
                            .build())
                    .methodResults(methodResults)
                    .build();

        } catch (Exception e) {
            log.error("解析 Agent 结果失败", e);
            return TestGenResponse.failure("解析结果失败: " + e.getMessage());
        }
    }

    /**
     * 从内容中提取JSON
     */
    private String extractJsonFromContent(String content) {
        // 查找JSON代码块
        int start = content.indexOf("```json");
        if (start != -1) {
            start += 7;
            int end = content.indexOf("```", start);
            if (end != -1) {
                return content.substring(start, end).trim();
            }
        }

        // 查找普通JSON对象
        start = content.indexOf("{");
        if (start != -1) {
            int end = content.lastIndexOf("}");
            if (end != -1 && end > start) {
                return content.substring(start, end + 1);
            }
        }

        return null;
    }

    /**
     * 解析方法级别结果
     */
    private List<MethodTestResult> parseMethodResults(List<Map<String, Object>> methodResults) {
        if (methodResults == null) {
            return null;
        }

        List<MethodTestResult> results = new ArrayList<>();
        for (Map<String, Object> mr : methodResults) {
            MethodTestResult methodResult = MethodTestResult.builder()
                    .methodName((String) mr.get("methodName"))
                    .startLine((Integer) mr.getOrDefault("startLine", 0))
                    .endLine((Integer) mr.getOrDefault("endLine", 0))
                    .testFunctions(parseTestFunctions(
                            (List<Map<String, Object>>) mr.get("testFunctions")))
                    .build();
            results.add(methodResult);
        }
        return results;
    }

    /**
     * 解析测试函数列表
     */
    private List<TestFunction> parseTestFunctions(List<Map<String, Object>> testFunctions) {
        if (testFunctions == null) {
            return null;
        }

        List<TestFunction> results = new ArrayList<>();
        for (Map<String, Object> tf : testFunctions) {
            TestFunction testFunction = TestFunction.builder()
                    .functionName((String) tf.get("functionName"))
                    .lineNumber((Integer) tf.getOrDefault("lineNumber", 0))
                    .scenario((String) tf.get("scenario"))
                    .code((String) tf.get("code"))
                    .build();
            results.add(testFunction);
        }
        return results;
    }

    /**
     * 从源文件路径提取类名
     */
    private String extractClassNameFromSourceFile(String sourceFile) {
        String fileName = sourceFile.substring(sourceFile.lastIndexOf("/") + 1);
        if (fileName.endsWith(".java")) {
            fileName = fileName.substring(0, fileName.length() - 5);
        }
        return fileName;
    }

    /**
     * 生成请求ID
     */
    private String generateRequestId() {
        return "auto-" + System.currentTimeMillis();
    }
}
