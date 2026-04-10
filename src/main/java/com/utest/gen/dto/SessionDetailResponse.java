package com.utest.gen.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 会话详情响应
 */
@Data
@Builder
public class SessionDetailResponse {
    private String id;
    private String title;
    private String parentID;
    private String created;
    private String updated;
    private String shareID;
    private String errorMessage;
    private String rawJson;
}
