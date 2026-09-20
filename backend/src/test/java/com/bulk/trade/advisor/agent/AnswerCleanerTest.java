package com.bulk.trade.advisor.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Strips a model's leaked working notes, without ever eating a real answer.
 *
 * <p>The samples are verbatim from a live run: asked to review a contract, the
 * model prefixed its reply with a plan written in English. Everything below
 * checks one of the two ways this can go wrong — leaving the notes in, or
 * taking part of the answer out with them.
 */
class AnswerCleanerTest {

    /** Copied from a real response, including the mixed-language planning line. */
    private static final String LEAKED = """
            The caller is 华东金属材料有限公司, the seller in this contract. Good.

            Now compose the review.

            Contract review structure:
            1. Basic info
            2. Key terms and compliance check

            Points:
            - 合同编号 CT202609202219011901
            - 数量 20 吨，单价 68000 元/吨

            ## 合同审查结论

            这份合同整体**没有明显问题**，可以签署。以下是逐条说明。
            """;

    @Test
    @DisplayName("推理草稿被剥掉，答案从第一行中文开始")
    void stripsLeakedPlanning() {
        String cleaned = AnswerCleaner.clean(LEAKED);

        assertThat(cleaned).startsWith("## 合同审查结论");
        assertThat(cleaned).doesNotContain("Now compose");
        assertThat(cleaned).doesNotContain("Contract review structure");
        assertThat(cleaned).contains("这份合同整体");
    }

    @Test
    @DisplayName("推理里的中文片段也不能被当成答案起点")
    void planningContainsChineseButStillGetsDropped() {
        // The trap: the notes quote Chinese — a contract number, a commodity — so
        // "first Chinese line" would stop inside the notes and keep the rest.
        String cleaned = AnswerCleaner.clean(LEAKED);

        assertThat(cleaned).doesNotContain("合同编号 CT202609202219011901");
        assertThat(cleaned).doesNotContain("单价 68000");
    }

    @Test
    @DisplayName("本来干净的答案一个字都不动")
    void cleanAnswerIsUntouched() {
        String answer = """
                您名下共有 **3 份合同**（最多显示 20 份）：

                | 合同号 | 标的 | 金额 | 状态 |
                |---|---|---|---|
                | CT202609202228553760 | 云铝铝锭 10 吨 | 680,000 元 | 待签署 |
                """;

        assertThat(AnswerCleaner.clean(answer)).isEqualTo(answer);
    }

    @Test
    @DisplayName("以英文专有名词开头的正常回答不会被误伤")
    void aLeadingCodeIsNotMistakenForNotes() {
        // A single short Latin token is not planning. Only a line that is
        // substantially non-Chinese *and* long enough to be prose qualifies, and
        // this rule is what keeps a legitimate heading from being deleted.
        String answer = """
                CT202609202219011901 这份合同已经签署生效。

                交收可以开始了。
                """;

        assertThat(AnswerCleaner.clean(answer)).isEqualTo(answer);
    }

    @Test
    @DisplayName("只有推理、没有答案时，不把草稿给用户看")
    void scratchpadOnlyIsSuppressed() {
        // Observed in practice: a contract review returned fifteen hundred
        // characters of English planning and never reached a conclusion. The
        // user saw the assistant thinking out loud, which reads as a
        // malfunction — and it contains no answer to salvage.
        String scratchpadOnly = """
                The user wants a contract review. Let me analyze the contract.

                Contract: CT202609202219011901
                - Title: 江铜电解铜 20 吨（金额 1360000 元）购销合同
                - Buyer: 浙江建工物资有限公司

                3. Quality dispute 7 days matches platform rule. OK.
                4. paymentTerms MARGIN_THEN_BALANCE — a gap to flag.
                """;

        // Null rather than an apology: deciding what to do about a model that
        // produced no answer is the agent's business, not this class's. The
        // agent retries once and only then apologises.
        assertThat(AnswerCleaner.clean(scratchpadOnly)).isNull();
    }

    @Test
    @DisplayName("整段没有中文，无论长短一律不展示")
    void nonChineseIsSuppressedWhicheverShapeItHas() {
        // Deliberate, and the narrower reading of the rule. Rule 3 of the prompt
        // requires Chinese unconditionally, so a response with no Chinese in it
        // is a malfunction whatever it looks like — a fifteen-hundred-character
        // scratchpad or one tidy English sentence. Distinguishing them would
        // mean guessing which malfunctions are acceptable to show, and the
        // answer to that is none of them: the reader asked a Chinese question
        // and would get an English reply they did not ask for.
        //
        // The cost is that a genuinely English answer is discarded and the user
        // is asked to retry. That is recoverable and visible; showing someone
        // their assistant's private monologue is neither.
        assertThat(AnswerCleaner.clean("OK, here is the answer you asked for.")).isNull();
    }

    @Test
    @DisplayName("空输入安全")
    void blanksAreSafe() {
        assertThat(AnswerCleaner.clean(null)).isNull();
        assertThat(AnswerCleaner.clean("")).isEmpty();
        // Blank in, blank out: not emptied, and not mistaken for an answer.
        assertThat(AnswerCleaner.clean("   \n  ")).isEqualTo("   \n  ");
    }
}
