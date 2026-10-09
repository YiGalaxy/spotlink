package com.spotlink.advisor.agent;

import com.spotlink.advisor.dto.AdvisorProductReference;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 单挂牌专用回答只允许复述本轮计算、挂牌单价或用户明确给出的金额。 */
final class DeliveryAmountGrounding {
    private static final Pattern MONEY = Pattern.compile("(?<![0-9.])(-?[0-9]{1,12}(?:[,，][0-9]{3})*(?:\\.[0-9]{1,6})?)\\s*元");
    private DeliveryAmountGrounding() { }

    static boolean accepts(String answer, String evidence, String question, List<AdvisorProductReference> products) {
        if (answer == null) return false;
        Set<BigDecimal> permitted = new HashSet<>();
        String serverFacts = evidence.lines().filter(line -> !line.stripLeading().startsWith("{"))
                .collect(java.util.stream.Collectors.joining("\n"));
        collect(serverFacts, permitted);
        collect(question, permitted);
        products.forEach(product -> collect(product.price(), permitted));
        Set<BigDecimal> generated = new HashSet<>();
        collect(answer, generated);
        return permitted.containsAll(generated);
    }

    private static void collect(String text, Set<BigDecimal> amounts) {
        if (text == null) return;
        var matcher = MONEY.matcher(text.replace("**", "").replace("`", ""));
        while (matcher.find()) amounts.add(new BigDecimal(matcher.group(1).replace(",", "").replace("，", "")).stripTrailingZeros());
    }
}
