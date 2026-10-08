package com.spotlink.admin.service;

import com.spotlink.admin.dto.AdminViews;
import com.spotlink.advisor.config.AdvisorModelSettingsService;
import com.spotlink.admin.mapper.AdminStatsMapper;
import com.spotlink.advisor.agent.AdvisorAgent;
import com.spotlink.publicapi.dto.PublicStats;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 运营后台首页的数字。
 *
 * <p>这里也一并带上顾问的就绪状态，它原先放在会员工作台上。那个页面描述的是项目，
 * 而这个页面描述的是平台；而对运营人员这样的读者来说，"助手能调用哪些工具"正是一个
 * 值得回答的问题。
 */
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    private final AdminStatsMapper stats;
    private final AdvisorAgent advisorAgent;
    private final AdvisorModelSettingsService modelSettings;

    public AdminViews.Overview load() {
        BigDecimal traded = stats.tradedAmount();
        var config = modelSettings.view();

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
                config.model(),
                config.available(),
                toolNames());
    }

    /**
     * 助手当前能做的事情。
     *
     * <p>这些是从 Agent 交给 Spring AI 的那些 Bean 上扫描出来的，而不是取自这里
     * 维护的一份清单。手工维护的那份副本已经漂移过一次——就绪状态接口宣称有 9 个
     * 工具，而实际注册了 13 个——而一份人们会直接相信、不去核对的报告，恰恰是最不
     * 该存放第二份真相的地方。
     */
    private List<String> toolNames() {
        return advisorAgent.toolBeans().stream()
                // CGLIB 代理：录制切面是按注解匹配的，所以 Spring 会把这些 Bean
                // 包装起来，而在代理对象上调用 getDeclaredMethods 是看不到继承来的
                // 那些工具方法的。
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
