package com.utest.gen.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 会话列表响应
 */
@Data
@Builder
public class SessionListResponse {
    private int total;
    private List<SessionSummary> sessions;
    private String errorMessage;
}
