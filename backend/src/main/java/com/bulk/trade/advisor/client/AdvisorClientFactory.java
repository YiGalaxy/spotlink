package com.bulk.trade.advisor.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.bulk.trade.advisor.config.AdvisorProperties;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Creates the Anthropic client on first use.
 *
 * <p>The client is built lazily rather than as an eager bean so the application
 * starts and runs normally without an advisor key. A missing key degrades one
 * feature instead of preventing the whole platform from booting — the trading
 * modules have nothing to do with the advisor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisorClientFactory {

    private final AdvisorProperties properties;

    private volatile AnthropicClient client;

    public AnthropicClient get() {
        if (!properties.isConfigured()) {
            throw BusinessException.of(ResultCode.ADVISOR_NOT_CONFIGURED);
        }

        AnthropicClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = AnthropicOkHttpClient.builder()
                            .apiKey(properties.apiKey())
                            .baseUrl(properties.baseUrl())
                            .build();
                    client = local;
                    // Never log the key itself.
                    log.info("Advisor client ready: endpoint={}, model={}",
                            properties.baseUrl(), properties.model());
                }
            }
        }
        return local;
    }

    public boolean isAvailable() {
        return properties.isConfigured();
    }

    public AdvisorProperties properties() {
        return properties;
    }
}
