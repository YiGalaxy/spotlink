package com.bulk.trade.advisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Advisor-specific settings.
 *
 * <p>The endpoint, key, model and token ceiling are <b>not</b> here: they live
 * under {@code spring.ai.anthropic} where Spring AI's auto-configuration reads
 * them. Duplicating them would create two sources of truth for the same
 * connection.
 *
 * @param enabled       master switch for the advisor feature
 * @param maxIterations advisory ceiling on agent-loop turns; passed to the
 *                      model as a task budget rather than enforced by a
 *                      hand-written loop
 */
@ConfigurationProperties(prefix = "bulk.advisor")
public record AdvisorProperties(
        boolean enabled,
        int maxIterations
) {

    public int effectiveMaxIterations() {
        return maxIterations <= 0 ? 8 : maxIterations;
    }
}
