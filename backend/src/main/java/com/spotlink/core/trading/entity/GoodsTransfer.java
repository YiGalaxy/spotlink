package com.spotlink.trading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** 每笔订单的真实源/目标库存和冻结关联；逆转必须引用这一记录。 */
@Getter
@Setter
@TableName("t_goods_transfer")
public class GoodsTransfer {
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private Long orderId;
    private Long sellerId;
    private Long buyerId;
    private Long sourceNoteId;
    private Long targetNoteId;
    private Long sourceFreezeId;
    private Long targetFreezeId;
    private BigDecimal quantity;
    private String unit;
    private String status;
    @Version private Integer version;
    @TableField(fill = FieldFill.INSERT) private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private OffsetDateTime updatedAt;

    public static final String TRANSFERRED = "TRANSFERRED";
    public static final String DELIVERED = "DELIVERED";
    public static final String REVERSED = "REVERSED";
}
