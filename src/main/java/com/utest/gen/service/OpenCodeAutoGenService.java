package com.utest.gen.service;

import com.fasterxml.jackson.databind.JsonNode;
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
            // 构建 Agent 提示词
            String prompt = buildAgentPrompt(sourceFile, methodNames);

            // 调用 OpenCode Agent - 通过 Session + Message API
            String messageResponse = callOpenCodeAgent(prompt);

            // 解析并收集结果
            TestGenResponse response = parseMessageResponse(messageResponse);

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
     * 调用 OpenCode Agent - 通过 Session + Message API
     * 流程：创建会话 → 发送消息 → 等待响应
     */
    private String callOpenCodeAgent(String prompt) {
        // 1. 创建会话
        String sessionId = openCodeManager.createSession(null, "test-gen-" + System.currentTimeMillis());
        log.info("创建 OpenCode 会话: sessionId={}", sessionId);

        // 2. 发送消息并等待响应
        log.info("发送消息到 Agent: sessionId={}", sessionId);
        String response = openCodeManager.sendMessage(sessionId, prompt, "test-gen-agent");
        log.info("收到 Agent 响应: sessionId={}", sessionId);

        return response;
    }

    /**
     * 解析 Message API 返回的响应
     * 响应格式: { info: {...}, parts: [{ type: "assistant", content: "..." }] }
     */
    private TestGenResponse parseMessageResponse(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode parts = root.get("parts");

            if (parts == null || !parts.isArray() || parts.isEmpty()) {
                return TestGenResponse.failure("Agent 返回结果为空");
            }

            // 查找 assistant 类型的 part 中的文本内容
            String content = null;
            for (JsonNode part : parts) {
                JsonNode type = part.get("type");
                if (type != null && "assistant".equals(type.asText())) {
                    JsonNode textNode = part.get("text");
                    if (textNode != null) {
                        content = textNode.asText();
                        break;
                    }
                }
            }

            if (content == null || content.isEmpty()) {
                return TestGenResponse.failure("Agent 返回内容为空");
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
            JsonNode result = objectMapper.readTree(jsonStr);


            return TestGenResponse.builder()
                    .success(result.path("success").asBoolean(false))
                    .testCode(result.path("testCode").asText(null))
                    .testFilePath(result.path("testFilePath").asText(null))
                    .testClassName(result.path("testClassName").asText(null))
                    .errorMessage(result.path("errorMessage").asText(null))
                    .fixRounds(result.path("fixRounds").asInt(0))
                    .compileResult(CompileResult.builder()
                            .success(result.path("compileSuccess").asBoolean(false))
                            .errorMessage(result.path("compileError").asText(null))
                            .build())
                    .testResult(TestResult.builder()
                            .success(result.path("testPassed").asBoolean(false))
                            .passed(result.path("passed").asInt(0))
                            .failed(result.path("failed").asInt(0))
                            .build())
                    .build();

        } catch (Exception e) {
            log.error("解析 Agent 结果失败", e);
            return TestGenResponse.failure("解析结果失败: " + e.getMessage());
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

    /**
     * 构建发送给 Agent 的提示词
     */
    private String buildAgentPrompt(String sourceFile, List<String> methodNames) {
        return String.format(
                "为 %s 中的以下方法生成单元测试: %s\n",
                sourceFile, String.join(", ", methodNames)
        );
    }
}
