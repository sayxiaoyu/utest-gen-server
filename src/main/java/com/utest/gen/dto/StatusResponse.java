package com.utest.gen.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 状态响应
 */
@Data
@Builder
public class StatusResponse {
    private String status;
    private String openCodeServer;
    private boolean lspReady;
    private String version;
}
