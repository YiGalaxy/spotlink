package com.bulk.trade.trading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One order transition. Append-only.
 *
 * <p>A status column says where an order is; it cannot say how it got there,
 * who moved it, or whether a move happened twice. When two parties disagree
 * about what was agreed, this trail is the only evidence either of them has.
 */
@Getter
@Setter
@TableName("t_order_status_log")
public class OrderStatusLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;
    private String fromStatus;
    private String toStatus;
    private Long operatorId;
    private String operator;
    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public static OrderStatusLog of(Long orderId, String from, String to,
                                    Long operatorId, String operator, String reason) {
        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(orderId);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorId(operatorId);
        log.setOperator(operator);
        log.setReason(reason);
        return log;
    }
}
