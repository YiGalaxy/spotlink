package com.spotlink.advisor.entity;

import java.time.LocalDateTime;

/** 只在服务器内持久化的加密模型配置，禁止作为 API 响应。 */
public record AdvisorModelSettingsRow(boolean enabled, String baseUrl, String model, String encryptedKey,
                                      int maxTokens, int timeoutSeconds, String tokenParameter, String defaultEngine, LocalDateTime updatedAt) {
    @Override public String toString() { return "AdvisorModelSettingsRow[credentials=REDACTED]"; }
}
