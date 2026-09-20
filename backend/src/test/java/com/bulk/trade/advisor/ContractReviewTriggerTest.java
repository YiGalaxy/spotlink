package com.bulk.trade.advisor;

import com.bulk.trade.advisor.agent.ContractReviewTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides whether a contract's full text may be read this turn.
 *
 * <p>Worth testing rather than trusting because it is the one place where what
 * the assistant may see is narrowed per request, and because the failure is
 * silent in both directions: too loose and a contract goes to an external model
 * provider without anyone asking, too tight and a legitimate review cannot be
 * performed. Both are cheap individually — this is minimisation, not access
 * control — but "cheap when it happens occasionally" is not the same as "cheap
 * when it happens every time", and only a test distinguishes the two.
 */
class ContractReviewTriggerTest {

    @ParameterizedTest
    @DisplayName("请人审查合同的说法都能触发")
    @ValueSource(strings = {
            "帮我审查一下合同 CT202609202025074559",
            "这份合同有什么问题吗",
            "审阅一下这份合同的条款",
            "帮我看看合同有没有坑",
            "合同的条款我需要你把把关",
            "合约风险大不大",
            "CT202609202025074559 帮我看看",
            "review 合同 CT202609202025074559",
    })
    void reviewRequestsTrigger(String message) {
        assertThat(ContractReviewTrigger.requested(message))
                .as("应触发：%s", message)
                .isTrue();
    }

    @ParameterizedTest
    @DisplayName("只是问有哪些合同、或问别的，都不触发")
    @ValueSource(strings = {
            "我有哪些合同",
            "合同列表给我看看",          // 有「看看」但没有「帮我看看」，不构成审查请求
            "我的库存还有多少",
            "库存有没有问题",
            "电解铜现在什么价",
            "我还有多少订单没处理",
            "磅差容差默认是多少",
            "企业的联系电话是多少",
    })
    void otherQuestionsDoNot(String message) {
        assertThat(ContractReviewTrigger.requested(message))
                .as("不应触发：%s", message)
                .isFalse();
    }

    @Test
    @DisplayName("空的、null 的、空白的一律不触发")
    void blankNeverTriggers() {
        assertThat(ContractReviewTrigger.requested(null)).isFalse();
        assertThat(ContractReviewTrigger.requested("")).isFalse();
        assertThat(ContractReviewTrigger.requested("   ")).isFalse();
    }

    @Test
    @DisplayName("只提到「条款」但没有合同，不算")
    void clauseWordAloneIsNotEnough() {
        // 平台规则里也有"条款"这个词，问规则不该解锁合同正文。
        assertThat(ContractReviewTrigger.requested("平台的结算条款是怎么规定的")).isFalse();
    }

    @Test
    @DisplayName("只提到合同但没有要审的意思，不算")
    void contractWordAloneIsNotEnough() {
        assertThat(ContractReviewTrigger.requested("合同签了之后多久交收")).isFalse();
        assertThat(ContractReviewTrigger.requested("我要起草合同")).isFalse();
    }
}
