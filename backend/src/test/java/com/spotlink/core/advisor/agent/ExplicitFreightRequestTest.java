package com.spotlink.advisor.agent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExplicitFreightRequestTest {
    @Test void onlyUnambiguousCompleteCurrentParametersCanPrepareCalculation() {
        var request = ExplicitFreightRequest.parse("挂牌LS202610090315277405买10吨运到杭州，运价80元/吨，请算小计。");
        assertThat(request).isNotNull();
        assertThat(request.destination()).isEqualTo("杭州");
        assertThat(request.tonnes()).isEqualByComparingTo("10");
        assertThat(request.rate()).isEqualByComparingTo("80");
        assertThat(ExplicitFreightRequest.parse("买10吨运到杭州，整批运费800元")).isNull();
        assertThat(ExplicitFreightRequest.parse("买10吨，运价80元/吨")).isNull();
        assertThat(ExplicitFreightRequest.parse("买10吨或20吨运到杭州，运价80元/吨")).isNull();
        assertThat(ExplicitFreightRequest.parse("买10吨运到杭州，运价80元/吨作废")).isNull();
    }
}
