package com.utest.gen.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 会话摘要
 */
@Data
@Builder
public class SessionSummary {
    private String id;
    private String title;
    private String created;
}
