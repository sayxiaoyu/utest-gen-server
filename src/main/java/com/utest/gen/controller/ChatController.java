package com.utest.gen.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * OpenCode 对话 API
 * 支持直接与 OpenCode Agent 进行项目级问答
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private com.utest.gen.opencode.OpenCodeManager openCodeManager;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 与 OpenCode Agent 对话
     * 
     * 用户可以向 Agent 询问关于项目的问题，Agent 会根据项目上下文回答
     */
    @PostMapping("/message")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        log.info("收到对话请求: {}", request.getMessage());

        if (!openCodeManager.isRunning()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "OpenCode Server 未运行"));
        }

        try {
            String url = openCodeManager.getServerUrl() + "/api/agent/run";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> context = Map.of(
                    "userMessage", request.getMessage(),
                    "mode", "chat"  // 告诉 Agent 这是一个对话模式
            );

            Map<String, Object> agentRequest = Map.of(
                    "agent", "test-gen-agent",
                    "context", context,
                    "timeout", 300000  // 5分钟超时
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(agentRequest, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> result = response.getBody();
                return ResponseEntity.ok(result);
            }

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Agent 返回异常"));

        } catch (Exception e) {
            log.error("对话请求失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "对话失败: " + e.getMessage()));
        }
    }

    /**
     * 对话请求
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ChatRequest {
        private String message;
    }
}