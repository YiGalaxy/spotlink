package com.spotlink.advisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 平台默认模型的环境配置；密钥仅在服务端解析，不返回给浏览器。 */
@ConfigurationProperties(prefix = "bulk.advisor")
public record AdvisorProperties(boolean enabled, String baseUrl, String model, String apiKey,
                                int maxTokens, int timeoutSeconds, String tokenParameter,
                                String configSecret) {
    @Override public String toString() {
        return "AdvisorProperties[credentials=REDACTED]";
    }
}
