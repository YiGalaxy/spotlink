package com.spotlink.trading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 一次订单状态迁移。只追加。
 *
 * <p>一个状态列只能说明订单在哪里；它说不出订单是怎么到那里的、是谁推动的、
 * 或者某次迁移是否发生了两次。当双方对当初约定了什么产生分歧时，这条轨迹是
 * 他们唯一拥有的证据。
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
