package com.bulk.trade.advisor.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-turn tool set, which is where data egress is actually decided.
 *
 * <p>Every tool result enters the prompt and the prompt leaves for an external
 * model provider, so "which tools are callable this turn" is the switch that
 * decides what leaves the platform. {@code ContractReviewTriggerTest} covers
 * the rule; this covers the thing the rule controls — that a contract's full
 * text is genuinely absent from the toolset of an ordinary question, rather
 * than present and merely discouraged.
 *
 * <p>The two are separate assertions on purpose. A rule that classifies
 * correctly and a tool list that ignores it would each pass the other's test.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("顾问每轮的工具集")
class AdvisorToolScopeTest {

    @Autowired
    private AdvisorAgent advisorAgent;

    @Test
    @DisplayName("普通问题拿不到合同正文工具")
    void ordinaryQuestionsCannotReadContracts() {
        Set<String> tools = toolNamesFor("我还有多少订单没处理");

        assertThat(tools).doesNotContain("get_contract_detail");
        // The index stays available — "我有哪些合同" is an ordinary question and
        // carries no clauses.
        assertThat(tools).contains("list_my_contracts");
    }

    @Test
    @DisplayName("要求审查合同时才解锁")
    void reviewRequestsUnlockIt() {
        assertThat(toolNamesFor("帮我审查一下合同 CT202609202025074559"))
                .contains("get_contract_detail");
    }

    @Test
    @DisplayName("收窄只影响这一个工具，其余一切照旧")
    void nothingElseIsAffected() {
        Set<String> ordinary = toolNamesFor("今天铜价多少");
        Set<String> review = toolNamesFor("帮我看看这份合同有没有问题");

        assertThat(review).containsExactlyInAnyOrderElementsOf(
                new ArrayList<>(ordinary) {{ add("get_contract_detail"); }});
    }

    @Test
    @DisplayName("顾问始终没有资金工具，与提问无关")
    void noFundToolEver() {
        for (String question : List.of("我账上还有多少钱", "资金流水给我看看", "余额是多少")) {
            assertThat(toolNamesFor(question))
                    .as("提问：%s", question)
                    .noneMatch(name -> name.contains("fund") || name.contains("balance"));
        }
    }

    private Set<String> toolNamesFor(String message) {
        Set<String> names = new java.util.HashSet<>();
        for (Object bean : advisorAgent.toolsFor(message)) {
            // Beans are CGLIB proxies; getDeclaredMethods on the proxy would not
            // include the inherited tool methods.
            for (var method : AopUtils.getTargetClass(bean).getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null) {
                    names.add(tool.name().isBlank() ? method.getName() : tool.name());
                }
            }
        }
        return names;
    }
}
