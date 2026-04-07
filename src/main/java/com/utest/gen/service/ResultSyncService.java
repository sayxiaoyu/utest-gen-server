package com.utest.gen.service;

import com.utest.gen.config.BackendProperties;
import com.utest.gen.model.TestGenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 结果同步服务
 * 负责异步同步生成结果到远程后端
 * 远程后端仅负责记录，不参与生成流程
 */
@Slf4j
@Service
public class ResultSyncService {

    @Autowired
    private BackendProperties backendProperties;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 异步同步生成结果到远程后端
     *
     * @param requestId  请求ID
     * @param className  类名
     * @param methodNames 方法名列表
     * @param response   生成结果
     */
    @Async
    public void syncResult(String requestId, String className, java.util.List<String> methodNames, TestGenResponse response) {
        if (!backendProperties.isSyncEnabled()) {
            log.debug("结果同步已禁用，跳过同步");
            return;
        }

        String apiUrl = backendProperties.getApiUrl();
        log.info("同步生成结果到远程后端: requestId={}, className={}", requestId, className);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (backendProperties.getApiKey() != null && !backendProperties.getApiKey().isEmpty()) {
                headers.setBearerAuth(backendProperties.getApiKey());
            }

            // 构建同步请求体
            Map<String, Object> request = new HashMap<>();
            request.put("requestId", requestId);
            request.put("className", className);
            request.put("methodNames", methodNames);
            request.put("timestamp", Instant.now().toString());

            // 结果信息
            Map<String, Object> result = new HashMap<>();
            result.put("success", response.isSuccess());
            result.put("testFilePath", response.getTestFilePath());
            result.put("testClassName", response.getTestClassName());
            result.put("testCode", response.getTestCode());
            result.put("scenarios", response.getTestScenarios());
            result.put("fixRounds", response.getFixRounds());
            result.put("fixHistory", response.getFixHistory());

            // 编译结果
            if (response.getCompileResult() != null) {
                Map<String, Object> compileResult = new HashMap<>();
                compileResult.put("success", response.getCompileResult().isSuccess());
                compileResult.put("errorMessage", response.getCompileResult().getErrorMessage());
                result.put("compileResult", compileResult);
            }

            // 测试结果
            if (response.getTestResult() != null) {
                Map<String, Object> testResult = new HashMap<>();
                testResult.put("success", response.getTestResult().isSuccess());
                testResult.put("passed", response.getTestResult().getPassed());
                testResult.put("failed", response.getTestResult().getFailed());
                testResult.put("output", response.getTestResult().getOutput());
                result.put("testResult", testResult);
            }

            // 错误信息
            if (response.getErrorMessage() != null) {
                result.put("errorMessage", response.getErrorMessage());
            }

            request.put("result", result);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> syncResponse = restTemplate.exchange(
                    apiUrl + "/api/test/sync-result",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (syncResponse.getStatusCode() == HttpStatus.OK && syncResponse.getBody() != null) {
                Map<String, Object> body = syncResponse.getBody();
                if (Boolean.TRUE.equals(body.get("success"))) {
                    log.info("结果同步成功: requestId={}", requestId);
                } else {
                    log.warn("结果同步失败: requestId={}, message={}", requestId, body.get("message"));
                }
            } else {
                log.warn("结果同步返回异常: requestId={}, status={}", requestId, syncResponse.getStatusCode());
            }

        } catch (Exception e) {
            log.error("同步结果到远程后端失败: requestId={}", requestId, e);
            // 同步失败不影响主流程，记录日志即可
        }
    }

    /**
     * 同步单个方法生成结果（兼容旧接口）
     */
    @Async
    public void syncSingleResult(String requestId, String className, String methodName, TestGenResponse response) {
        syncResult(requestId, className, java.util.Collections.singletonList(methodName), response);
    }
}
