package com.bulk.trade.trading.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 调用方需要处理的一件事。
 *
 * <p><b>为什么这是一个 record 而不是一句话。</b>同一份待办会被两类截然不同
 * 的客户端索取：AI 顾问想要散文式的描述，Web 控制台想要能据此渲染按钮的
 * 数据行。这段逻辑的第一版在顾问的工具里返回一个格式化好的字符串，结果控制
 * 台完全用不上它——而为控制台另写一份副本，又会变成第二套“下一步该谁”的
 * 规则。这些规则恰恰是最不能出现分歧的部分，所以它们住在一个 service 里，
 * 由每个客户端各自按自己的方式格式化结果。
 *
 * <p>{@code kind} 是客户端用来判断该显示哪些按钮的依据；{@code action} 是
 * 已经拟好措辞、可直接展示的指令。两者都保留，意味着按钮的文案与按钮的行为
 * 不会各走各的。
 *
 * @param kind           参见 {@link Kind}
 * @param action         调用方应该做什么，中文，可直接展示
 * @param targetType     {@link TargetType#ORDER} 或 {@link TargetType#CONTRACT}
 * @param targetId       订单或合同 id，用于发起动作调用
 * @param targetNo       其人类可读的编号
 * @param commodityName  这件事关乎什么商品
 * @param counterparty   对方企业，使任务列表便于扫读
 * @param detail         一句上下文说明——一个截止时间、一个缺失的步骤
 * @param deadline       此事失效的时间，若不会失效则为 null
 */
public record TaskView(
        String kind,
        String action,
        String targetType,
        @JsonSerialize(using = ToStringSerializer.class) Long targetId,
        String targetNo,
        String commodityName,
        String counterparty,
        BigDecimal quantity,
        String unit,
        BigDecimal amount,
        String detail,
        OffsetDateTime deadline
) {

    /**
     * 待办的类型，按应当列出的顺序排列。
     *
     * <p>按调用方沉默的代价排序。一个悬而未决的摘牌会过期并连带拖垮整笔交易；
     * 一份未签署的合同会卡住订单；起草合同是调用方自家的事务，可以等。若按
     * 创建时间排序，顺序就取决于平台碰巧以什么次序生成它们，而那是唯一毫无
     * 意义的顺序。
     */
    public static final class Kind {
        /** 有人摘了调用方的牌子；只有他能答复。 */
        public static final String ACCEPTANCE_PENDING = "ACCEPTANCE_PENDING";
        /** 有一份合同等待调用方签署。 */
        public static final String CONTRACT_TO_SIGN = "CONTRACT_TO_SIGN";
        /** 订单已确认，但还没有起草合同。 */
        public static final String CONTRACT_TO_DRAFT = "CONTRACT_TO_DRAFT";
        /** 已签署并准备就绪；交收尚未开始。 */
        public static final String DELIVERY_TO_START = "DELIVERY_TO_START";
        /** 货物在途；到货后调用方应予以确认。 */
        public static final String DELIVERY_TO_COMPLETE = "DELIVERY_TO_COMPLETE";

        private Kind() {
        }
    }

    /** 调用方要对此事采取行动需要调用的对象。 */
    public static final class TargetType {
        public static final String ORDER = "ORDER";
        public static final String CONTRACT = "CONTRACT";

        private TargetType() {
        }
    }
}
