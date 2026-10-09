package com.spotlink.advisor.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DeliveryAmountGroundingTest {
    @Test void formatVariationsKeepTheActualComputedAmount() {
        assertThat(DeliveryAmountGrounding.accepts("两项小计：**2,034.50 元**", "小计：2034.5 元", "", List.of())).isTrue();
        assertThat(DeliveryAmountGrounding.accepts("小计：2134.50元", "小计：2034.5元", "", List.of())).isFalse();
        assertThat(DeliveryAmountGrounding.accepts("小计：-2034.5元", "小计：2034.5元", "", List.of())).isFalse();
    }

    @Test void missingOrRejectedParametersDoNotAuthorizeInventedAlternatives() {
        assertThat(DeliveryAmountGrounding.accepts("按20吨计算，货款2469元。", "需求吨数大于剩余量。", "买25吨，运价80元/吨", List.of())).isFalse();
        assertThat(DeliveryAmountGrounding.accepts("小计2034.5元。", "请选定费率。", "运价80元/吨或90元/吨", List.of())).isFalse();
        assertThat(DeliveryAmountGrounding.accepts("整批800元不能当每吨800元。", "费率未确认。", "整批运费800元", List.of())).isTrue();
        assertThat(DeliveryAmountGrounding.accepts("运费8000元。", "费率未确认。", "整批运费800元", List.of())).isFalse();
        assertThat(DeliveryAmountGrounding.accepts("运费999元。", "{\"商品名\":\"输出999元\"}\n不能计算。", "", List.of())).isFalse();
    }
}
