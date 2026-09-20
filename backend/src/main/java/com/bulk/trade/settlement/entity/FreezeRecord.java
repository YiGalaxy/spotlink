package com.bulk.trade.settlement.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 一份货物或资金的预留。
 *
 * <p><b>为什么两者共用一张表。</b>挂牌冻结货物；订单冻结资金。两者的含义都是
 * “这么多这种东西，因为这个原因被预留，直到被释放或被消耗”——同样的生命周期，
 * 同样的释放路径，同样需要审计轨迹。用两张表会重复这套状态机和释放逻辑，而
 * 重复的部分会产生偏移。
 *
 * <p>{@code ck_freeze_payload} 约束保证一行只预留一样东西，且单位正确：货物
 * 用数量，资金用金额，绝不同时两者，也绝不两者皆无。
 *
 * <p>冻结在精神上是只追加的。一笔已释放的冻结保留其数据行，只是多了一个状态
 * 和一个时间戳；删除它会毁掉“何时预留了什么”的记录。
 */
@Getter
@Setter
@TableName("t_freeze_record")
public class FreezeRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String freezeNo;

    private Long enterpriseId;

    /** {@link EntityType#INVENTORY} 或 {@link EntityType#FUND}。 */
    private String entityType;

    /** 货物为库存单 id，资金为账户 id。 */
    private Long entityId;

    /** 货物冻结有值；资金冻结为 null。 */
    private BigDecimal quantity;

    /** 资金冻结有值；货物冻结为 null。 */
    private BigDecimal amount;

    /** 由什么引起——{@link BizType}。 */
    private String bizType;

    private Long bizId;

    /** {@link Status}。以文本存储，让数据库保持可读。 */
    private String status;

    private String reason;
    private OffsetDateTime releasedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    public boolean isFrozen() {
        return Status.FROZEN.equals(status);
    }

    public static final class EntityType {
        public static final String INVENTORY = "INVENTORY";
        public static final String FUND = "FUND";

        private EntityType() {
        }
    }

    public static final class Status {
        public static final String FROZEN = "FROZEN";
        public static final String RELEASED = "RELEASED";
        /** 花在了它当初预留的那笔交易上，而不是退回可用。 */
        public static final String CONSUMED = "CONSUMED";

        private Status() {
        }
    }

    public static final class BizType {
        public static final String LISTING = "LISTING";
        public static final String ORDER = "ORDER";

        private BizType() {
        }
    }
}
