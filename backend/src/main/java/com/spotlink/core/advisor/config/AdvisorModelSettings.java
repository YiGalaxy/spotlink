package com.spotlink.advisor.config;

import java.net.URI;

/** 一轮调用的不可变快照；不序列化、不记录到日志。 */
public record AdvisorModelSettings(boolean enabled, String baseUrl, String model, String apiKey,
                                   int maxTokens, int timeoutSeconds, String tokenParameter) {
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey)
                && model != null && !model.isBlank() && baseUrl != null && !baseUrl.isBlank();
    }

    // 接受服务根地址及 /v1 地址，避免用户填写常见 /v1 地址后出现 /v1/v1。
    public String completionsPath() {
        String path = URI.create(baseUrl).getPath();
        return path != null && !path.isEmpty() && !"/".equals(path)
                ? "/chat/completions" : "/v1/chat/completions";
    }

    @Override public String toString() { return "AdvisorModelSettings[credentials=REDACTED]"; }
}
