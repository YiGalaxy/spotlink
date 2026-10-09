package com.spotlink.advisor.agent;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class FreightRateEvidenceTest {
    private boolean matches(String text, String amount) {
        return FreightRateEvidence.matches(text, new BigDecimal(amount));
    }
    @Test void explicitTonneUnitsOnly() {
        assertThat(matches("报价80元/吨", "80")).isFalse();
        assertThat(matches("整批运费800元", "800")).isFalse();
        assertThat(matches("运价80元/吨", "80.00")).isTrue();
        assertThat(matches("每吨运费是80元", "80")).isTrue();
        assertThat(matches("运费每吨80元", "80")).isTrue();
        assertThat(matches("运价-80元/吨", "80")).isFalse();
        assertThat(matches("运价80元/吨，请计算货款、运费和两项小计，说明未知费用", "80")).isTrue();
    }
    @Test void latestRateOverridesAndUnrelatedFollowUpKeepsIt() {
        assertThat(matches("运价80元/吨\n运价改为90元/吨\n算10吨到杭州", "80")).isFalse();
        assertThat(matches("运价80元/吨\n运价改为90元/吨\n算10吨到杭州", "90")).isTrue();
        assertThat(matches("运价80元/吨\n整批运费800元", "80")).isFalse();
    }
    @Test void withdrawalAndRouteChangesInvalidateEarlierQuote() {
        assertThat(matches("运价80元/吨\n运价不确定", "80")).isFalse();
        assertThat(matches("运价80元/吨\n取消旧运价", "80")).isFalse();
        assertThat(matches("运价80元/吨\n改到南京", "80")).isFalse();
        assertThat(matches("运价80元/吨\n目的地改为南京", "80")).isFalse();
        assertThat(matches("运价80元/吨\n改到南京，运价90元/吨", "90")).isTrue();
        assertThat(matches("不要用运价80元/吨", "80")).isFalse();
    }
    @Test void ambiguousRatesAreNotAutomaticallyChosen() {
        assertThat(matches("运价80元/吨或运价90元/吨", "80")).isFalse();
        assertThat(matches(null, "80")).isFalse();
    }
}
