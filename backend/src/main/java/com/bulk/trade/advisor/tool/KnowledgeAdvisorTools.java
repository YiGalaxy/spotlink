package com.bulk.trade.advisor.tool;

import com.bulk.trade.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 在平台自己的规则上做检索。
 *
 * <p>像「保证金比例是多少」或「发票怎么开」这类问题，任何一张表都答不上来——答案在一份
 * 文档里。这个工具就是把这类问题变成一次检索，而不是一次猜测。
 *
 * <p>返回的片段带着文档编号和标题，系统提示词也要求把编号引出来。一条没有出处的规则
 * 回答是无法核实的：用户分不清自己听到的是平台真实的规则，还是一个听起来很像那么回事
 * 的编造。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeAdvisorTools {

    private final KnowledgeService knowledgeService;

    @Tool(name = "search_platform_rules",
            description = """
                    检索平台自己的规则文档：交易管理办法、交收管理办法、风险控制管理办法，
                    以及保证金与资金指南。用于回答平台怎么运作的问题——挂牌与摘牌规则、
                    保证金要求、磅差容差、质量异议、结算、资金存管，以及什么是电子库存单。

                    不要用它回答关于具体企业、库存或订单的问题——那些由别的工具回答。
                    引用时务必带上你取到答案的那份文档的编号和标题。""")
    public String searchPlatformRules(
            @ToolParam(description = "用户的问题，中文。照原样传进来。")
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
