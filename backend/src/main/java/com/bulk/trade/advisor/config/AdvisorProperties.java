package com.bulk.trade.advisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI advisor configuration.
 *
 * <p>The endpoint and model live here rather than in code so the same build can
 * talk to the official Claude API or to an Anthropic-compatible gateway. That
 * keeps local development cheap while leaving the production path — and the
 * code an interviewer reads — unchanged.
 *
 * @param enabled       master switch; when false the advisor reports itself as
 *                      unavailable and no client is created
 * @param baseUrl       API endpoint
 * @param apiKey        credential, supplied through an environment variable
 * @param model         model id, e.g. {@code claude-opus-5}
 * @param maxTokens     per-response output ceiling
 * @param maxIterations hard ceiling on agent-loop turns
 */
@ConfigurationProperties(prefix = "bulk.advisor")
public record AdvisorProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        long maxTokens,
        int maxIterations
) {

    /**
     * A blank key is treated as "not configured" rather than as an error, so a
     * developer can run the platform without the advisor and only see it report
     * itself unavailable when actually used.
     */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public int effectiveMaxIterations() {
        return maxIterations <= 0 ? 8 : maxIterations;
    }

    public long effectiveMaxTokens() {
        return maxTokens <= 0 ? 8192L : maxTokens;
    }
}
