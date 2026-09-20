package com.bulk.trade.advisor.agent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 决定这一轮是否允许读取一份合同的正文。
 *
 * <p><b>为什么用规则而不是交给模型。</b>让模型来决定，等于把决定权放进被约束的那件东西里：
 * 一个能调用工具的模型，只要觉得有用就会去调，而「觉得有用」正是那种会在没人要求审查的
 * 情况下、把合同条款送进外部服务商日志里的判断。规则跑在 Java 里，在提示词被拼出来之前，
 * 在那里它可以被读到、也可以被测试。
 *
 * <p><b>为什么在这里，粗糙的规则是可以接受的。</b>这是<em>最小化</em>，不是访问控制。合同
 * 属于调用者自己，所以判断错了在安全上一分钱都不损失——它只改变一份本就属于他的文档，是否
 * 在本不需要的时候被发了出去。两种失败都便宜、都可恢复：漏触发意味着助手请用户说明想要
 * 什么，误触发则意味着发出一份用户本来就在问的文档。<b>在一个赌注这么小的决定上，一条容易
 * 读懂的规则胜过一个难以自证的分类器。</b>
 *
 * <p><b>两半都必需。</b>只有审查动词，会在「我的库存有没有问题」上触发；只有合同名词，会在
 * 「我有哪些合同」上触发，而那是一个索引类工具本来就能回答的列表问题。要求两者同时出现，
 * 才是让窄场景保持窄的原因。
 */
public final class ContractReviewTrigger {

    /** 提到了一份合同——用词或编号。 */
    private static final Pattern MENTIONS_CONTRACT =
            Pattern.compile("合同|合约|契约|CT\\d{6,}");

    /**
     * 要求把它拿来仔细看看。
     *
     * <p>「条款」和「问题」也在这里，因为它们就是这类请求实际的措辞方式——
     * 「这份合同的条款有问题吗」里没有任何一个第一组的动词，而要求必须有其中之一，
     * 会漏掉本功能存在的理由，也就是那个问题本身。
     */
    private static final List<String> REVIEW_INTENT = List.of(
            "审查", "审核", "审阅", "帮我看看", "帮我查查", "帮我审",
            "把关", "风险", "条款", "问题", "有没有坑", "review");

    private ContractReviewTrigger() {
    }

    /**
     * 当这条消息要求仔细读一份合同时为 true。
     *
     * <p>只与用户自己的话比对——绝不与工具返回的内容比对，也绝不与检索回来的文档比对。
     * 这两者都是数据，绝不能让它们拓宽助手能读的范围。这个区别，就是「一个触发器」和
     * 「一个提示注入靶子」之间的区别。
     */
    public static boolean requested(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String message = userMessage.toLowerCase();
        if (!MENTIONS_CONTRACT.matcher(userMessage).find()) {
            return false;
        }
        for (String intent : REVIEW_INTENT) {
            if (message.contains(intent)) {
                return true;
            }
        }
        return false;
    }
}
