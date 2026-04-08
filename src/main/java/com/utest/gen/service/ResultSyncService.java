package com.utest.gen.service;

import com.utest.gen.config.BackendProperties;
import com.utest.gen.model.*;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;

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
     * @param requestId   请求ID
     * @param className   类名
     * @param methodNames 方法名列表
     * @param response    生成结果
     */
    @Async
    public void syncResult(String requestId, String className, List<String> methodNames, TestGenResponse response) {
        if (!backendProperties.isSyncEnabled()) {
            log.debug("结果同步已禁用，跳过同步");
            return;
        }

        String apiUrl = backendProperties.getApiUrl();
        log.info("同步生成结果到远程后端: requestId={}, className={}", requestId, className);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 构建同步请求对象
            SyncRequest request = buildSyncRequest(requestId, className, methodNames, response);

            HttpEntity<SyncRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<SyncResponse> syncResponse = restTemplate.exchange(
                    apiUrl + "/api/test/sync-result",
                    HttpMethod.POST,
                    entity,
                    SyncResponse.class
            );

            if (syncResponse.getStatusCode() == HttpStatus.OK && syncResponse.getBody() != null) {
                SyncResponse body = syncResponse.getBody();
                if (body.isSuccess()) {
                    log.info("结果同步成功: requestId={}", requestId);
                } else {
                    log.warn("结果同步失败: requestId={}, message={}", requestId, body.getMessage());
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
     * 构建同步请求对象
     */
    private SyncRequest buildSyncRequest(String requestId, String className,
                                         List<String> methodNames, TestGenResponse response) {
        return SyncRequest.builder()
                .requestId(requestId)
                .className(className)
                .methodNames(methodNames)
                .timestamp(Instant.now().toString())
                .result(buildResultDetail(response))
                .build();
    }

    /**
     * 构建结果详情
     */
    private ResultDetail buildResultDetail(TestGenResponse response) {
        return ResultDetail.builder()
                .success(response.isSuccess())
                .testFilePath(response.getTestFilePath())
                .testClassName(response.getTestClassName())
                .testCode(response.getTestCode())
                .scenarios(response.getTestScenarios())
                .fixRounds(response.getFixRounds())
                .fixHistory(response.getFixHistory())
                .methodResults(response.getMethodResults())
                .compileResult(response.getCompileResult())
                .testResult(response.getTestResult())
                .errorMessage(response.getErrorMessage())
                .build();
    }

    /**
     * 同步单个方法生成结果（兼容旧接口）
     */
    @Async
    public void syncSingleResult(String requestId, String className, String methodName, TestGenResponse response) {
        syncResult(requestId, className, java.util.Collections.singletonList(methodName), response);
    }

    // ==================== DTO 类定义 ====================

    /**
     * 同步请求对象
     */
    @Data
    @Builder
    public static class SyncRequest {
        private String requestId;
        private String className;
        private List<String> methodNames;
        private String timestamp;
        private ResultDetail result;
    }

    /**
     * 同步响应对象
     */
    @Data
    @Builder
    public static class SyncResponse {
        private boolean success;
        private String message;
    }

    /**
     * 结果详情
     */
    @Data
    @Builder
    public static class ResultDetail {
        private boolean success;
        private String testFilePath;
        private String testClassName;
        private String testCode;
        private List<String> scenarios;
        private int fixRounds;
        private List<String> fixHistory;
        private List<MethodTestResult> methodResults;
        private CompileResult compileResult;
        private TestResult testResult;
        private String errorMessage;
    }
}
