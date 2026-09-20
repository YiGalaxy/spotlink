package com.bulk.trade.advisor.tool;

import com.bulk.trade.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Retrieval over the platform's own rules.
 *
 * <p>Questions like "保证金比例是多少" or "磅差怎么算" are not answered by any
 * table — the answer is in a document. This is the tool that turns those
 * questions into a search instead of a guess.
 *
 * <p>Passages are returned with their document code and title attached, and the
 * system prompt requires the code to be quoted. A rule answer without a
 * citation is unverifiable: the user cannot tell whether they were told the
 * platform's actual rule or a plausible-sounding invention.
 */
@Component
@RequiredArgsConstructor
public class KnowledgeAdvisorTools {

    private final KnowledgeService knowledgeService;

    @Tool(name = "search_platform_rules",
            description = """
                    Searches the platform's own rule documents: 交易管理办法, 交收管理办法,
                    风险控制管理办法, and the margin/funds guide. Use it for questions about
                    how the platform works — listing and acceptance rules, margin requirements,
                    weighing tolerance, quality objections, settlement, fund custody, and what
                    an electronic inventory note is.

                    Do NOT use it for questions about specific companies, inventory or orders —
                    other tools answer those. Always cite the document code and title you got
                    the answer from.""")
    public String searchPlatformRules(
            @ToolParam(description = "The user's question, in Chinese. Pass it through as asked.")
            String question) {

        List<KnowledgeService.Passage> passages = knowledgeService.search(question, 4);
        if (passages.isEmpty()) {
            return "知识库中没有检索到相关规则。请直接告诉用户平台规则里没有查到，不要凭常识回答。";
        }

        StringBuilder sb = new StringBuilder("检索到的平台规则原文（请引用出处）：\n");
        for (int i = 0; i < passages.size(); i++) {
            KnowledgeService.Passage passage = passages.get(i);
            sb.append("\n[").append(i + 1).append("] 出处：")
              .append(passage.title()).append("（").append(passage.docCode()).append("）\n")
              .append(passage.content()).append('\n');
        }
        return sb.toString();
    }
}
