package com.bulk.trade.trading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
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
 * 两个具名主体之间的一份待成合同。
 *
 * <p><b>双方都能读到这一行</b>，这正是租户过滤条件写成
 * {@code buyer_id = ? OR seller_id = ?} 而不是单一属主列的原因。若只用一个
 * {@code enterprise_id}，就会让其中一方沦为读自己交易的二等读者。
 *
 * <p><b>{@code amount} 是存储下来的，而不是重新计算的。</b>它是当初约定的
 * 金额。若价格或数量日后被更正，推导出来的总额会无声地改写一笔已经开过票
 * 的交易的历史。
 */
@Getter
@Setter
@TableName("t_order")
public class Order {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;

    /** 本订单所摘的那份挂牌；议价成交的订单为 null。 */
    private Long listingId;

    private Long buyerId;
    private Long sellerId;

    private Long categoryId;
    private String commodityName;
    private String spec;

    private BigDecimal quantity;
    private String unit;
    private BigDecimal price;

    /** {@code quantity * price}，在达成一致的那一刻凝固。 */
    private BigDecimal amount;

    private Long warehouseId;
    private String deliveryMethod;
    private String paymentTerms;

    /** 卖方一侧的货物冻结。 */
    private Long goodsFreezeId;

    /** 买方一侧的保证金冻结。 */
    private Long marginFreezeId;

    /** 参见 {@link OrderStatus}。 */
    private String status;

    private Long contractId;
    private OffsetDateTime confirmedAt;
    private OffsetDateTime cancelledAt;
    private String cancelReason;

    /**
     * 挂牌方答复的截止时间。
     *
     * <p>对任何从不等待的订单均为 null——AUTO 挂牌下的每一笔订单、以及
     * 每一笔已过确认环节的订单都是如此。只有在订单停留在
     * {@link OrderStatus#PENDING_CONFIRM} 期间才会被设置，这正是它算一个
     * 截止期限而非一个时间戳的原因。
     *
     * <p><b>{@code updateStrategy = ALWAYS} 是关键所在。</b>MyBatis-Plus
     * 默认会把 null 字段从生成的 UPDATE 语句中略去，这就悄悄地把“清空这一
     * 列”变成了“保持原值不动”。实体读出来是 {@code null}，API 也返回
     * {@code null}，而数据库行里还留着旧值——一个能骗过所有只检查响应、
     * 不检查数据库的测试的谎言。本实体上只有这一个字段会被清空，所以也只有
     * 它需要退出默认策略。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private OffsetDateTime confirmDeadline;

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

    /** 当给定企业是本订单的当事方之一时为 true。 */
    public boolean involves(Long enterpriseId) {
        return enterpriseId != null
                && (enterpriseId.equals(buyerId) || enterpriseId.equals(sellerId));
    }

    /** 给定企业所处的一方，用于界面呈现。 */
    public String roleOf(Long enterpriseId) {
        if (enterpriseId == null) {
            return "—";
        }
        if (enterpriseId.equals(buyerId)) {
            return "BUYER";
        }
        if (enterpriseId.equals(sellerId)) {
            return "SELLER";
        }
        return "—";
    }
}
