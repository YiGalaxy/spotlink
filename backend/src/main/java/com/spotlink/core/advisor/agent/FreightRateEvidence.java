package com.spotlink.advisor.agent;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** 只接受用户明确标注为每吨运输费的数值；新费用声明覆盖旧声明，歧义时要求重新确认。 */
public final class FreightRateEvidence {
    private FreightRateEvidence() {}

    private static final String AMOUNT = "(\\d{1,10}(?:\\.\\d{1,6})?)";
    private static final String FREIGHT = "(?:运费|运价|运输费)";
    private static final String CONNECTOR = "\\s*(?:按|为|是|改为|改成|调整为|调整到|[:：])?\\s*";
    private static final Pattern SUFFIX_UNIT = Pattern.compile(FREIGHT + CONNECTOR + AMOUNT + "\\s*元\\s*(?:[/／]\\s*吨|每吨)");
    private static final Pattern PREFIX_UNIT = Pattern.compile("(?:每吨\\s*" + FREIGHT + "|" + FREIGHT + "\\s*每吨)" + CONNECTOR + AMOUNT + "\\s*元");
    private static final Pattern MONEY_DECLARATION = Pattern.compile(FREIGHT + "[^。；;\\n]{0,30}\\d+(?:\\.\\d+)?\\s*元");
    private static final Pattern WITHDRAWAL = Pattern.compile(FREIGHT
            + "(?:\\s*\\d+(?:\\.\\d+)?\\s*元(?:[/／]\\s*吨|每吨)?)?\\s*[，,]?\\s*"
            + "(?:的|已|已经|尚|还|暂时|目前)?(?:取消|作废|不确定|待确认|未知|不清楚|未确认|不要用)"
            + "|(?:取消|作废|不沿用|不要用|未确认|没有|不知道)\\s*(?:刚才的|此前的|之前的|旧的|旧|原来的|原|该|这个|每吨)?\\s*" + FREIGHT);
    private static final Pattern ROUTE_CHANGE = Pattern.compile("(?:改到|改送|换目的地|更换目的地|改目的地|目的地(?:换成|改为|改成)|更换起运|换起运|改起运|换成|改买)");

    public static boolean matches(String userData, BigDecimal requested) {
        if (userData == null || requested == null || requested.signum() <= 0) return false;
        Set<BigDecimal> current = Set.of();
        for (String sentence : userData.split("[。；;！？!?\\n]")) {
            boolean withdrawn = WITHDRAWAL.matcher(sentence).find();
            if (withdrawn || ROUTE_CHANGE.matcher(sentence).find()) current = Set.of();
            Set<BigDecimal> explicit = new LinkedHashSet<>();
            collect(SUFFIX_UNIT, sentence, explicit);
            collect(PREFIX_UNIT, sentence, explicit);
            if (withdrawn) continue;
            if (!explicit.isEmpty()) current = explicit;
            else if (MONEY_DECLARATION.matcher(sentence).find()) current = Set.of();
        }
        // 一句话出现多个路线或报价时不让模型随意选一个，需要用户明确选定。
        return current.size() == 1 && current.iterator().next().compareTo(requested) == 0;
    }

    private static void collect(Pattern pattern, String sentence, Set<BigDecimal> values) {
        var matcher = pattern.matcher(sentence);
        while (matcher.find()) values.add(new BigDecimal(matcher.group(1)).stripTrailingZeros());
    }
}
