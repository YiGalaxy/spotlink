package com.bulk.trade.admin.service;

import com.bulk.trade.admin.dto.AdminViews;
import com.bulk.trade.admin.mapper.AdminStatsMapper;
import com.bulk.trade.advisor.agent.AdvisorAgent;
import com.bulk.trade.publicapi.dto.PublicStats;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * The console's landing figures.
 *
 * <p>Carries the advisor's readiness too, which used to live on the member
 * workbench. That page described the project; this one describes the platform,
 * and an operator is the reader for whom "which tools can the assistant call"
 * is a question worth answering.
 */
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    /** Placeholder from application.yml, meaning no key was configured. */
    private static final String UNCONFIGURED_KEY = "not-configured";

    private final AdminStatsMapper stats;
    private final AdvisorAgent advisorAgent;

    @Value("${spring.ai.anthropic.api-key:}")
    private String apiKey;

    @Value("${spring.ai.anthropic.chat.model:}")
    private String model;

    public AdminViews.Overview load() {
        BigDecimal traded = stats.tradedAmount();
        boolean configured = apiKey != null && !apiKey.isBlank() && !UNCONFIGURED_KEY.equals(apiKey);

        return new AdminViews.Overview(
                stats.enterpriseCount(),
                stats.pendingEnterpriseCount(),
                stats.frozenEnterpriseCount(),
                stats.userCount(),
                stats.orderCount(),
                stats.activeOrderCount(),
                stats.openListingCount(),
                traded,
                PublicStats.formatAmount(traded),
                stats.auditCount(),
                model == null ? "" : model,
                configured,
                toolNames());
    }

    /**
     * What the assistant can currently do.
     *
     * <p>Scanned from the beans the agent hands to Spring AI rather than from a
     * list kept here. A hand-maintained copy already drifted once — the
     * readiness endpoint advertised nine tools while thirteen were registered —
     * and a report people trust instead of checking is the worst place to keep a
     * second source of truth.
     */
    private List<String> toolNames() {
        return advisorAgent.toolBeans().stream()
                // CGLIB proxies: the recording aspect matches on the annotation,
                // so Spring wraps these beans and getDeclaredMethods on the proxy
                // would not see the inherited tool methods.
                .map(AopUtils::getTargetClass)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(method -> {
                    Tool tool = method.getAnnotation(Tool.class);
                    return tool.name().isBlank() ? method.getName() : tool.name();
                })
                .sorted()
                .toList();
    }
}
