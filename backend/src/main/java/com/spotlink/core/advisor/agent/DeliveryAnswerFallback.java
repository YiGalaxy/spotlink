package com.spotlink.advisor.agent;

/** 模型未提供有效且可核对的解释时，只展示本轮服务端查询，不冒充模型解释。 */
final class DeliveryAnswerFallback {
    private DeliveryAnswerFallback() { }

    static String fromEvidence(String evidence) {
        if (evidence == null || evidence.isBlank()) return null;
        // 商品的机器可读 JSON 已显示为服务端卡片；这里仅保留费用工具的说明及计算结果。
        String facts = evidence.lines().filter(line -> !line.stripLeading().startsWith("{"))
                .filter(line -> !line.isBlank()).map(DeliveryAnswerFallback::plainMarkdown)
                .collect(java.util.stream.Collectors.joining("\n\n"));
        if (facts.isBlank()) return null;
        return "**本轮查询结果**\n\n模型未生成有效解释，以下展示平台本轮查询依据。\n\n" + facts;
    }

    private static String plainMarkdown(String text) {
        // 仓库等来源字段是数据，不能在降级内容中变成任意 Markdown 链接、图片或 HTML。
        String value = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        for (String marker : java.util.List.of("\\", "`", "*", "_", "[", "]", "!", "#", "|")) {
            value = value.replace(marker, "\\" + marker);
        }
        return value;
    }
}
