package com.bulk.trade.inventory.entity;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 电子库存单——交易标的。
 *
 * <p><b>它不是仓单。</b>按《民法典》，仓单是一种物权凭证：可以质押、可以背书转让。
 * 把物权凭证当作标准化合约来交易，正是让一个现货平台被重新定性为事实上的期货交易所
 * 的原因。本实体被定义为"存放在指定仓库中的货物的数字记录"，仅此而已——这也正是它
 * 采用现在这个名字的全部理由。
 *
 * <p><b>数量是三个数，不是一个。</b>{@code totalQuantity} 是货主拥有的量；
 * {@code availableQuantity} 是还能报出去的量；{@code frozenQuantity} 是被生效中的
 * 挂牌或订单占用的量。数据库 CHECK 约束强制 {@code available + frozen = total}，
 * 于是记账错误会在写入时就被拒绝，而不是等到交收的时候才被发现。
 *
 * <p><b>并发修改走乐观锁。</b>每一次数量更新都是
 * {@code UPDATE ... WHERE id = ? AND version = ?}；两个争抢同一份可用量的请求不可能
 * 同时成功。
 */
@Getter
@Setter
@TableName("t_inventory_note")
public class InventoryNote {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String noteNo;

    /** 归属企业——即租户键。 */
    private Long enterpriseId;

    private Long categoryId;
    private Long warehouseId;

    private String commodityName;
    private String brand;
    private String origin;

    /** 规格取值，以 JSON 存储，按品类的规格 schema 组织。 */
    private String spec;

    private BigDecimal totalQuantity;
    private BigDecimal availableQuantity;
    private BigDecimal frozenQuantity;
    private String unit;

    private String qualityReportKey;
    private LocalDate productionDate;

    /** 参见 {@link Status}。 */
    private Integer status;

    /**
     * 乐观锁。MyBatis-Plus 会在更新语句后追加 {@code AND version = ?} 并递增版本号，
     * 从而把一次丢失的更新变成一个调用方能检测到的"影响行数为 0"的结果。
     */
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

    /** 当这张库存单上没有任何被占用的量时为 true。 */
    public boolean isFullyAvailable() {
        return frozenQuantity == null || frozenQuantity.signum() == 0;
    }

    public static final class Status {
        public static final int DRAFT = 0;
        public static final int PENDING_REVIEW = 1;
        public static final int IN_STOCK = 2;
        public static final int FULLY_FROZEN = 3;
        public static final int PARTIALLY_FROZEN = 4;
        public static final int DELIVERED = 5;
        public static final int CANCELLED = 6;

        private Status() {
        }

        /** 处于这些状态的库存单可以挂牌或卖出。 */
        public static boolean isTradable(Integer status) {
            return status != null
                    && (status == IN_STOCK || status == FULLY_FROZEN || status == PARTIALLY_FROZEN);
        }
    }
}
