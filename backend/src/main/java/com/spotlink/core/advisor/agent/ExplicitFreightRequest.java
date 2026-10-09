package com.spotlink.advisor.agent;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** 仅提取当前消息中完整、唯一且明确的费用参数；不猜参数，不替代开放式采购意图理解。 */
public record ExplicitFreightRequest(String destination, BigDecimal tonnes, BigDecimal rate) {
    public static ExplicitFreightRequest parse(String text) {
        BigDecimal tonnes = uniqueNumber(text, Pattern.compile("(?<![0-9.])(\\d{1,10}(?:\\.\\d{1,6})?)\\s*吨(?![0-9])"));
        BigDecimal rate = uniqueNumber(text, Pattern.compile("(?:运费|运价|运输费)\\s*(?:按|为|是|改为|改成|[:：])?\\s*(\\d{1,10}(?:\\.\\d{1,6})?)\\s*元\\s*[/／]\\s*吨"));
        Set<String> destinations = new LinkedHashSet<>();
        var matcher = Pattern.compile("(?:运到|送到|目的地(?:为|是|[:：])?)\\s*([\\p{IsHan}]{2,12})(?=[，,。；;\\s]|$)").matcher(text);
        while (matcher.find()) destinations.add(matcher.group(1));
        if (tonnes == null || rate == null || destinations.size() != 1 || !FreightRateEvidence.matches(text, rate)) return null;
        return new ExplicitFreightRequest(destinations.iterator().next(), tonnes, rate);
    }

    private static BigDecimal uniqueNumber(String text, Pattern pattern) {
        Set<BigDecimal> values = new LinkedHashSet<>();
        var matcher = pattern.matcher(text);
        while (matcher.find()) values.add(new BigDecimal(matcher.group(1)).stripTrailingZeros());
        return values.size() == 1 ? values.iterator().next() : null;
    }
}
