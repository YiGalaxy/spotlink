package com.bulk.trade.advisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 顾问专属的配置项。
 *
 * <p>端点、密钥、模型和令牌上限**不在这里**：它们位于 {@code spring.ai.anthropic}
 * 之下，Spring AI 的自动配置从那里读取。把它们复制一份，会让同一个连接出现两个真相来源。
 *
 * @param enabled 顾问功能的总开关
 */
@ConfigurationProperties(prefix = "bulk.advisor")
public record AdvisorProperties(boolean enabled) {
}
