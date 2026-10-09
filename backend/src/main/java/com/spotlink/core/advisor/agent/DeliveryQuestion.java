package com.spotlink.advisor.agent;

import java.util.regex.Pattern;

/** 只在已定位单挂牌且仅询问运费/成本时预查询；混合问题保留模型的工具选择。 */
final class DeliveryQuestion {
    private static final Pattern FREIGHT = Pattern.compile("运费|运价|运输费|物流费|到货成本");
    private static final Pattern OTHER_SCOPE = Pattern.compile(
            "对比|比较|行情|规则|合同|订单|库存|待办|保证金|开票|质押|下单|联系|电话|信用|证书|推荐|找货|查货|还有哪些|其他卖家");

    private DeliveryQuestion() { }

    static boolean dedicated(String question) {
        return question != null && FREIGHT.matcher(question).find() && !OTHER_SCOPE.matcher(question).find();
    }
}
