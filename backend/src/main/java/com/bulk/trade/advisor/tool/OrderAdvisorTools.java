package com.bulk.trade.advisor.tool;

import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;
import com.bulk.trade.trading.entity.OrderStatusLog;
import com.bulk.trade.trading.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tools that expose orders.
 *
 * <p>Written because the advisor could not answer "我有多少订单" at all — orders
 * were the one first-class object of the platform with no tool behind them, and
 * a question that should have taken one call became an explanation of what the
 * advisor could not do. The gap was not in the model's reasoning; it was in
 * what it had to reason with.
 *
 * <p><b>{@code list_my_orders} returns a tally before a list.</b> The common
 * question is "how many", and answering it from twenty rows means the model
 * counts — which it may do wrongly, and which costs a paragraph to show. The
 * counts are computed in SQL-grouped form here and stated plainly, with the
 * rows as supporting detail.
 */
@Component
@RequiredArgsConstructor
public class OrderAdvisorTools {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Enough rows to be useful, few enough that the answer stays an answer. */
    private static final int MAX_ROWS = 20;

    private final OrderService orderService;
    private final EnterpriseMapper enterpriseMapper;

    @Tool(name = "list_my_orders",
            description = """
                    Lists the caller's orders — as buyer or as seller — grouped by status with a
                    count for each, then the most recent rows in detail. Use it for "我有多少订单",
                    "有多少订单没处理", "我的订单", or to find an order number. Covers orders only;
                    for everything the caller needs to act on, prefer list_my_tasks.""")
    public String listMyOrders(
            @ToolParam(description = """
                    Optional status filter. Accepts Chinese or the code:
                    待挂牌方确认/PENDING_CONFIRM, 已确认/CONFIRMED, 已签约/CONTRACTED,
                    交收中/DELIVERING, 已完成/COMPLETED, 已取消/CANCELLED.
                    Leave empty for everything.""")
            String status) {

        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业，没有自己的订单。";
        }

        String code = normaliseStatus(status);
        if (code == null && status != null && !status.isBlank()) {
            return "无法识别的订单状态「%s」。可用：待挂牌方确认、已确认、已签约、交收中、已完成、已取消。"
                    .formatted(status.trim());
        }

        List<Order> orders = orderService.listMine(enterpriseId, code);
        if (orders.isEmpty()) {
            return code == null
                    ? "当前企业还没有订单。"
                    : "当前企业没有「%s」状态的订单。".formatted(OrderStatus.text(code));
        }

        StringBuilder sb = new StringBuilder();

        if (code == null) {
            sb.append("我的订单共 ").append(orders.size()).append(" 笔，按状态：\n");
            for (Map.Entry<String, Integer> entry : tally(orders).entrySet()) {
                sb.append("  ").append(OrderStatus.text(entry.getKey()))
                  .append(' ').append(entry.getValue()).append(" 笔\n");
            }
            long open = orders.stream().filter(o -> !OrderStatus.isTerminal(o.getStatus())).count();
            sb.append("其中未完结（尚未完成或取消）").append(open).append(" 笔。\n");
        } else {
            sb.append("「").append(OrderStatus.text(code)).append("」状态的订单 ")
              .append(orders.size()).append(" 笔：\n");
        }

        sb.append("\n明细（最多 ").append(MAX_ROWS).append(" 笔，按时间倒序）：\n");
        orders.stream().limit(MAX_ROWS).forEach(order -> sb.append(row(order, enterpriseId)));
        return sb.toString();
    }

    @Tool(name = "get_order_detail",
            description = """
                    Returns one order in full — parties, commodity, quantity, price, amount,
                    delivery, status, deadline — plus its complete status history showing who
                    moved it, when, and why. Use it when asked about a specific order or about
                    how one reached its current state. Requires the order number, which
                    list_my_orders provides.""")
    public String getOrderDetail(
            @ToolParam(description = "Order number, e.g. OR202609202141016986")
            String orderNo) {

        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业，没有自己的订单。";
        }
        if (orderNo == null || orderNo.isBlank()) {
            return "请提供订单号。可以先用 list_my_orders 查看你有哪些订单。";
        }

        // Scoped by the listing's own query rather than by a lookup on the
        // number: an order the caller is not party to must read as absent, and
        // OrderService.listMine is already the tenant filter everything else
        // goes through.
        String wanted = orderNo.trim();
        Order found = orderService.listMine(enterpriseId, null).stream()
                .filter(o -> wanted.equals(o.getOrderNo()))
                .findFirst()
                .orElse(null);
        if (found == null) {
            // One message for "no such order" and "not yours", so a caller
            // cannot probe for another company's order numbers.
            return "没有找到该编号的订单，或你不是该订单的当事人。";
        }

        Order order = orderService.get(found.getId(), enterpriseId);
        String myRole = order.roleOf(enterpriseId);
        Long counterpartyId = "BUYER".equals(myRole) ? order.getSellerId() : order.getBuyerId();

        StringBuilder sb = new StringBuilder();
        sb.append("订单号: ").append(order.getOrderNo()).append('\n')
          .append("商品: ").append(order.getCommodityName()).append('\n')
          .append("数量: ").append(plain(order.getQuantity())).append(' ')
          .append(order.getUnit()).append('\n')
          .append("单价: ").append(plain(order.getPrice())).append(" 元\n")
          .append("总额: ").append(plain(order.getAmount())).append(" 元\n")
          .append("我方角色: ").append("BUYER".equals(myRole) ? "买方" : "卖方").append('\n')
          .append("对手方: ").append(enterpriseName(counterpartyId)).append('\n')
          .append("交收方式: ").append("DELIVERED".equals(order.getDeliveryMethod()) ? "送到" : "自提")
          .append('\n')
          .append("状态: ").append(OrderStatus.text(order.getStatus())).append('\n');

        if (order.getConfirmDeadline() != null) {
            sb.append("答复截止: ").append(format(order.getConfirmDeadline())).append('\n');
        }
        if (order.getCancelReason() != null) {
            sb.append("取消原因: ").append(order.getCancelReason()).append('\n');
        }

        sb.append("\n状态轨迹：\n");
        List<OrderStatusLog> history = orderService.history(order.getId(), enterpriseId);
        for (OrderStatusLog entry : history) {
            sb.append("  ").append(format(entry.getCreatedAt()))
              .append(' ').append(OrderStatus.text(entry.getToStatus()))
              .append(" — ").append(entry.getOperator() == null ? "系统" : entry.getOperator())
              .append("：").append(entry.getReason() == null ? "" : entry.getReason())
              .append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Counts per status, in lifecycle order rather than by size.
     *
     * <p>Ordering by the lifecycle means the same answer reads the same way
     * every time, and a status that is unexpectedly absent shows up as a
     * missing line rather than being invisible.
     */
    private Map<String, Integer> tally(List<Order> orders) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : List.of(
                OrderStatus.PENDING_CONFIRM, OrderStatus.CONFIRMED, OrderStatus.CONTRACTED,
                OrderStatus.DELIVERING, OrderStatus.COMPLETED, OrderStatus.CANCELLED)) {
            int n = (int) orders.stream().filter(o -> status.equals(o.getStatus())).count();
            if (n > 0) {
                counts.put(status, n);
            }
        }
        return counts;
    }

    private String row(Order order, Long enterpriseId) {
        String role = "BUYER".equals(order.roleOf(enterpriseId)) ? "买方" : "卖方";
        Long counterpartyId = "买方".equals(role) ? order.getSellerId() : order.getBuyerId();
        return "- %s | %s %s %s | 我方%s | 对手 %s | %s 元 | %s%s".formatted(
                order.getOrderNo(),
                order.getCommodityName(),
                plain(order.getQuantity()), order.getUnit(),
                role,
                enterpriseName(counterpartyId),
                plain(order.getAmount()),
                OrderStatus.text(order.getStatus()),
                order.getConfirmDeadline() == null ? "" : "（待答复至 " + format(order.getConfirmDeadline()) + "）");
    }

    /**
     * Maps a status the model may have written either way.
     *
     * @return the code, or null when nothing was asked for; the caller
     *         distinguishes "absent" from "unrecognised" and reports the
     *         difference rather than silently returning everything.
     */
    private String normaliseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        for (String code : List.of(
                OrderStatus.PENDING_CONFIRM, OrderStatus.CONFIRMED, OrderStatus.CONTRACTED,
                OrderStatus.DELIVERING, OrderStatus.COMPLETED, OrderStatus.CANCELLED)) {
            if (code.equals(value) || OrderStatus.text(code).equals(raw.trim())) {
                return code;
            }
        }
        return null;
    }

    private String enterpriseName(Long id) {
        if (id == null) {
            return "—";
        }
        Enterprise enterprise = enterpriseMapper.selectById(id);
        return enterprise == null ? "—" : enterprise.getName();
    }

    private String format(OffsetDateTime time) {
        return time == null ? "—" : time.format(DATE);
    }

    private String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
