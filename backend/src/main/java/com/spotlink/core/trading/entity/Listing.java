package com.spotlink.trading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 卖出要约（卖方挂牌）或买入请求（买方挂牌）。
 *
 * <p><b>挂牌即发出要约，摘牌即作出承诺。</b>这是本平台交易的法定形态，
 * 也正是本代码库中任何地方都不存在撮合引擎的原因。一个负责发现价格的
 * 中央引擎会让交易场所成为期货意义上的交易所；而两个具名主体每次就一份
 * 合同达成一致，则属于普通的现货交易。
 *
 * <p><b>{@code confirmMode} 决定该要约遵循两种市场惯例中的哪一种。</b>
 * 在 {@link ConfirmMode#AUTO} 下，上一段所述字面成立：挂牌<em>就是</em>
 * 要约，摘牌即构成合同，挂牌方没有二次否决权。在
 * {@link ConfirmMode#MANUAL} 下，挂牌仅仅是要约邀请——摘牌会锁定货物并
 * 请求挂牌方同意，在挂牌方同意之前什么都没有达成。两者都是真实存在的
 * 惯例；把二者混为一谈，正是让平台规则无法解释清楚的原因。
 *
 * <p>SELL 挂牌在其存续期间冻结卖方的货物——货物仍归卖方所有，但已被
 * 预留。BUY 发布时不冻结货物或模拟资金；卖方摘牌时明确选择自己的交付库存。
 * {@code freezeId} 只关联 SELL 货物预留。MANUAL 仅限 SELL，因为等待答复须有预留货物。
 */
@Getter
@Setter
@TableName("t_listing")
public class Listing {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String listingNo;
    private Long enterpriseId;

    /** {@link Side#SELL} 或 {@link Side#BUY}。 */
    private String side;

    private Long categoryId;
    private String commodityName;
    private String brand;
    private String origin;
    private String spec;

    private BigDecimal quantity;

    /** 仍可被摘牌；一份挂牌可能分多次被摘走。 */
    private BigDecimal remainingQuantity;

    private String unit;

    /** 议价挂牌为 null。 */
    private BigDecimal price;

    /** {@link PriceType#FIXED} 或 {@link PriceType#NEGOTIABLE}。 */
    private String priceType;

    /** {@link ConfirmMode#AUTO} 或 {@link ConfirmMode#MANUAL}。 */
    private String confirmMode;

    private Long warehouseId;
    private String deliveryMethod;
    private String paymentTerms;

    /** 支撑 SELL 挂牌的货物冻结；挂牌关闭时释放。 */
    private Long freezeId;

    private OffsetDateTime validUntil;

    /** {@link Status}。 */
    private String status;

    @Version
    private Integer version;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    @TableLogic
    private Integer deleted;

    public boolean isOpenForTrade() {
        return Status.OPEN.equals(status) || Status.PARTIALLY_FILLED.equals(status);
    }

    public boolean isExpired(OffsetDateTime now) {
        return validUntil != null && validUntil.isBefore(now);
    }

    public static final class Side {
        public static final String SELL = "SELL";
        public static final String BUY = "BUY";

        private Side() {
        }
    }

    public static final class PriceType {
        public static final String FIXED = "FIXED";
        public static final String NEGOTIABLE = "NEGOTIABLE";

        private PriceType() {
        }
    }

    /**
     * 仅凭摘牌即可成交，还是必须经挂牌方同意。
     *
     * <p>这不是一个装饰性的开关：它决定所有权转移的时刻。弄错一个方向，
     * 会在未经当事人同意的情况下卖掉其货物；弄错另一个方向，则会让买方
     * 持有一笔挂牌方可以无视的交易。
     */
    public static final class ConfirmMode {

        /** 摘牌即成交: 挂牌就是要约，摘牌即构成合同。 */
        public static final String AUTO = "AUTO";

        /** 摘牌待确认: 摘牌先锁定，由挂牌方的答复定夺。 */
        public static final String MANUAL = "MANUAL";

        private ConfirmMode() {
        }
    }

    /** 当摘牌必须等待该挂牌的所有者同意时为 true。 */
    public boolean awaitsListerConfirm() {
        return ConfirmMode.MANUAL.equals(confirmMode);
    }

    public static final class Status {
        public static final String OPEN = "OPEN";
        public static final String PARTIALLY_FILLED = "PARTIALLY_FILLED";
        public static final String FILLED = "FILLED";
        /** 由其所有者撤回。 */
        public static final String CLOSED = "CLOSED";
        /** 到达有效期截止时仍未被全部摘走。 */
        public static final String EXPIRED = "EXPIRED";

        private Status() {
        }
    }

    public static final class DeliveryMethod {
        public static final String SELF_PICKUP = "SELF_PICKUP";
        public static final String DELIVERED = "DELIVERED";

        private DeliveryMethod() {
        }
    }
}
