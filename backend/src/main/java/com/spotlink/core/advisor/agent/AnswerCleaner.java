package com.spotlink.advisor.agent;

import lombok.extern.slf4j.Slf4j;

/**
 * 去掉模型泄漏在答案开头的草稿内容。
 *
 * <p><b>为什么需要它，而不是写一句更强的指令。</b>系统提示词已经长篇要求只输出最终答案。
 * 这对简短问题有效，对困难问题失效：被要求审查一份合同时，推理型模型会先起草一份计划 ——
 * "The caller is X, the seller. Now compose the review. Contract review structure:
 * 1. Basic info…" —— 而这份草稿会送到用户面前。没有任何措辞能可靠地解决它，因为这不是
 * 不服从指令；而是模型的草稿与它的输出，在本部署所对接的网关上共用同一条通道。
 *
 * <p>所以这道防线是确定性的代码，而且它只能是一条启发式规则。这里有效的判据是语言：
 * 提示词第 3 条保证了答案中有 {@link #CJK_RATIO} 以上的比例是中文，而计划推演是用英文写的。
 * 出现在第一行实质中文之前、且自身大多不是中文的行，就是草稿。
 *
 * <p><b>刻意保守。</b>第一个实质为中文的行之前的内容全部丢弃，其余一律不动 —— 不做答案
 * 中段的修改，不做重写。一个把手伸进正文更深处的清理器，早晚会删掉某个人需要的句子；
 * 这一个只可能删掉一段前缀，而且每次删都会记日志，所以发生频率是可见的，而不是一个
 * 悄无声息的习惯。
 */
@Slf4j
public final class AnswerCleaner {

    /**
     * 一行中中文占比达到多少，才算得上答案。
     *
     * <p>这个数值取自真实输出，而不是凭喜好定的。泄漏出来的计划行大约在 0.1 附近 ——
     * "The caller is 华东金属材料有限公司, the seller" 大部分是英文，只嵌了一个公司名 ——
     * 而答案行都在 0.4 以上，连表格行也是，因为每列都带一个列名。0.35 能把两者分开，
     * 两侧都留有余量。
     */
    private static final double CJK_RATIO = 0.35;

    /**
     * 一行至少要带有这么多中文，才会被当作答案。
     *
     * <p>用它防住比例判据在短行上失效："OK。" 是 100% 中文却毫无意义，
     * 一句无关的插入语不能被误当成回复的开头。
     */
    private static final int MIN_CJK_CHARS = 4;

    private AnswerCleaner() {
    }

    /**
     * 返回去掉开头草稿之后的答案。
     *
     * @param raw 模型产出的原始内容，开头可能带着计划推演
     * @return 答案；如果输入本身读起来就是答案，则原样返回；如果其中没有任何内容读起来
     *         像答案，则返回 <b>null</b>，好让调用方决定怎么处理 —— 那不是本类该做的决定
     */
    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String[] lines = raw.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (isAnswerLine(lines[i])) {
                start = i;
                break;
            }
        }

        if (start < 0) {
            // 通篇没有一行读起来像答案。这有两种成因，值得区分开：模型用了提示词禁止的
            // 语言作答，或者它只产出了草稿就停下了。实际见到的是第二种 —— 一次合同审查
            // 返回了一千五百个字符的英文推演，始终没有得出结论。
            //
            // 两种情况都意味着没有答案可展示，所以两者都不返回。返回空内容比一句道歉
            // 更糟，而返回原文比两者都更糟。
            log.warn("Advisor produced no answer line in {} characters; suppressed",
                    raw.length());
            // 全文记入日志，因为另一种选择是对被抑制的答案长什么样做两次猜测 ——
            // 而这个分支第一次真正触发时，发生的正是这样的事。
            log.warn("Suppressed advisor text:\n{}", raw);
            return null;
        }

        if (start == 0) {
            return raw;
        }

        String cleaned = String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length)).strip();
        log.info("Dropped {} line(s) of model working notes before the answer", start);
        log.debug("Dropped prefix began: {}", lines[0]);
        return cleaned;
    }

    /**
     * 判断一行读起来是答案的开头，还是计划推演。
     *
     * <p>空行永远不是答案，所以它永远不会成为答案的开头 —— 正是这一点让被丢弃的前缀能
     * 顺带带走草稿与回复之间的那个空行，而不需要任何特例。
     *
     * <p><b>两个条件，第二个是在第一个于真实输出上失效之后才补上的。</b>只看中文程度
     * 并不够：在规划一次合同审查时，模型把 "- 数量 20 吨，单价 68000 元/吨" 写成了一行草稿，
     * 而这一行有 47% 是中文 —— 高于任何一条真正的答案行也能达到的比例。区分二者的是形态
     * 而不是语言。开启一段回复的行是标题、表格行，或者一个把意思讲完的句子；一行草稿则是
     * 一个断片，末尾拖着一个数字或单位就断了。
     *
     * <p>所以答案行还必须额外满足其中一条：带有读者会认作某物之开头的 Markdown 结构，
     * 或者以一句写完的中文句子该有的标点结尾。这一条对照过一份真实泄漏响应的每一行，
     * 也对照过普通答案。
     */
    private static boolean isAnswerLine(String line) {
        if (line.isBlank()) {
            return false;
        }

        int cjk = 0;
        int counted = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            counted++;
            if (isCjk(c)) {
                cjk++;
            }
        }
        if (counted == 0 || cjk < MIN_CJK_CHARS || (double) cjk / counted < CJK_RATIO) {
            return false;
        }

        String trimmed = line.strip();
        return STARTS_A_BLOCK.matcher(trimmed).find()
                || ENDS_A_SENTENCE.indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0;
    }

    /** 开启某个结构的 Markdown：标题、引用、表格行。 */
    private static final java.util.regex.Pattern STARTS_A_BLOCK =
            java.util.regex.Pattern.compile("^(#{1,6}\\s|>|\\|)");

    /** 中文的句末标点。断片不会有。 */
    private static final String ENDS_A_SENTENCE = "。！？：；…";

    /**
     * 中文，以及随中文一起出现的全角标点。
     *
     * <p>标点算作中文，因为它是很强的信号：泄漏出来的英文计划即使引用了中文名称，用的也是
     * ASCII 的逗号和句点，所以把全角标点计入中文，会让这两种情况分得更开，而不是更混。
     */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 统一表意文字
                || (c >= 0x3000 && c <= 0x303F)  // CJK 标点
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角字符
    }
}
